package com.notifwebhook

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NotificationListenerService — ядро приложения.
 *
 * Android 14+ (API 34) особенности:
 * - Сервис биндится системой через BIND_NOTIFICATION_LISTENER_SERVICE.
 *   startService() вручную — НЕ работает и НЕ нужен.
 * - meta-data filter_types в манифесте задаёт фильтрацию на уровне системы.
 * - requestRebind() — единственный способ попросить систему переподключить сервис
 *   после onListenerDisconnected().
 * - На некоторых OEM (Xiaomi, Huawei) сервис может отключаться агрессивно —
 *   ForegroundKeepAliveService помогает удержать процесс.
 */
class NotificationListenerService : NotificationListenerService() {

    private val tag = "NLS_Webhook"

    // Coroutine scope живёт вместе с сервисом
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Дедупликация: храним последние N пар (packageName+title+text) чтобы не слать дубли
    private val recentNotifications: LinkedHashMap<String, Long> = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 50 // Максимум 50 записей в кэше
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Service onCreate")
        // Запрашиваем переподключение при создании — это помогает системе
        // быстрее забиндить сервис, особенно после перезагрузки / обновления
        requestRebind(ComponentName(this, NotificationListenerService::class.java))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.i(tag, "Service onDestroy — will attempt rebind")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(tag, "onListenerConnected — система подключила сервис")
        broadcastStatus(connected = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(tag, "onListenerDisconnected — запрашиваем переподключение")
        broadcastStatus(connected = false)
        // Единственный официальный способ попросить систему переподключить нас
        requestRebind(ComponentName(this, NotificationListenerService::class.java))

        // Повторный запрос через 5 секунд на случай если первый не сработал
        serviceScope.launch {
            kotlinx.coroutines.delay(5_000)
            Log.i(tag, "Повторный requestRebind() через 5 сек")
            requestRebind(ComponentName(this@NotificationListenerService, NotificationListenerService::class.java))
        }
    }

    // -------------------------------------------------------------------------
    // Основной обработчик уведомлений
    // -------------------------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = AppPrefs.get(this)

        if (!prefs.forwardingEnabled) return

        val webhookUrl = prefs.webhookUrl
        if (webhookUrl.isBlank()) return

        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return

        // Фильтр по выбранным приложениям (пустой = все)
        val allowedApps = prefs.allowedApps
        if (allowedApps.isNotEmpty() && packageName !in allowedApps) return

        // Пропускаем ongoing (системные постоянные уведомления)
        if (prefs.skipOngoing && notification.isOngoing) return

        val extras: Bundle = notification.extras

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName
        }

        var title = resolveTitle(notification, extras)
        var text = resolveText(notification, extras)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // Если заголовок пустой, используем название приложения
        if (title.isBlank()) {
            title = appName
        }

        // Если текст пустой, пробуем subText или берём хотя бы заголовок
        if (text.isBlank()) {
            text = subText.ifBlank { title }
        }

        // Логируем когда title или text пустые — для отладки
        if (title == appName && text == appName) {
            Log.w(tag, "Пустой title и text для $packageName. Extras keys: ${extras.keySet().joinToString(",")}")
        }

        // Дедупликация: одинаковое уведомление в течение 3 секунд — пропускаем
        val dedupeKey = "$packageName|$title|$text"
        val now = System.currentTimeMillis()
        synchronized(recentNotifications) {
            val last = recentNotifications[dedupeKey]
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                Log.d(tag, "Дубликат пропущен: $packageName")
                return
            }
            recentNotifications[dedupeKey] = now
        }

        val payload = buildPayload(sbn, notification, packageName, appName, title, text, subText)

        Log.d(tag, "→ Webhook: $packageName | $title | ${text.take(80)}")

        serviceScope.launch {
            val success = sendToWebhook(webhookUrl, payload)
            Log.d(tag, "Webhook result: $success for $packageName")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Опционально: можно слать событие об удалении уведомления
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Извлекаем текст уведомления с приоритетом:
     * MessagingStyle.messages → bigText → textLines → text → infoText → summaryText → tickerText
     */
    fun resolveText(notification: Notification, extras: Bundle): String {
        // 1. MessagingStyle (чат/мессенджеры: Telegram, WhatsApp и т.д.)
        // В extras EXTRA_MESSAGES хранится как ArrayList<Bundle>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rawMessages = extras.getParcelableArrayList<Parcelable>(Notification.EXTRA_MESSAGES)
            if (!rawMessages.isNullOrEmpty()) {
                val texts = rawMessages.mapNotNull { p ->
                    (p as? Bundle)?.getCharSequence("android.text")?.toString()
                }
                if (texts.isNotEmpty()) {
                    return texts.joinToString("\n")
                }
            }
        }

        // 2. Big text (развёрнутые уведомления)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (!bigText.isNullOrBlank()) return bigText

        // 3. Многострочный текст (групповые чаты)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) return lines.joinToString("\n") { it.toString() }

        // 4. Основной текст
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) return text

        // 5. Информационный текст (часто содержит дополнительную инфу)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            if (!infoText.isNullOrBlank()) return infoText
        }

        // 6. Текст сводки (групповые уведомления)
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        if (!summaryText.isNullOrBlank()) return summaryText

        // 7. tickerText — текст, который бегущей строкой показывается в статус-баре
        if (notification.tickerText != null) {
            return notification.tickerText.toString()
        }

        return ""
    }

    /**
     * Извлекаем заголовок уведомления с приоритетом:
     * titleBig → conversationTitle → title → tickerText
     */
    fun resolveTitle(notification: Notification, extras: Bundle): String {
        // 1. Заголовок в развёрнутом виде (некоторые приложения разделяют title и bigTitle)
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        if (!titleBig.isNullOrBlank()) return titleBig

        // 2. Заголовок разговора (MessagingStyle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            if (!conversationTitle.isNullOrBlank()) return conversationTitle
        }

        // 3. Основной заголовок
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (!title.isNullOrBlank()) return title

        // 4. tickerText как последнее средство
        if (notification.tickerText != null) {
            return notification.tickerText.toString()
        }

        return ""
    }

    val Notification.isOngoing: Boolean
        get() = (flags and Notification.FLAG_ONGOING_EVENT) != 0

    fun buildPayload(
        sbn: StatusBarNotification,
        notification: Notification,
        packageName: String,
        appName: String,
        title: String,
        text: String,
        subText: String
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .format(Date(sbn.postTime))

        return JSONObject().apply {
            put("app_package", packageName)
            put("app_name", appName)
            put("title", title)
            put("text", text)
            put("sub_text", subText)
            put("category", notification.category.orEmpty())
            put("priority", notification.priority)
            put("notification_id", sbn.id)
            put("timestamp_iso", ts)
            put("timestamp_ms", sbn.postTime)
            // API 26+ channel id
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                put("channel_id", notification.channelId.orEmpty())
            }
        }.toString()
    }

    /**
     * HTTP POST на webhook.
     * Выполняется в IO-диспетчере корутины — блокирующий вызов безопасен.
     */
    private fun sendToWebhook(webhookUrl: String, jsonPayload: String): Boolean {
        return try {
            val conn = URL(webhookUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "NotifWebhook-Android/1.0")
            val token = AppPrefs.get(this).bearerToken
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                    w.write(jsonPayload)
                }
            }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(tag, "sendToWebhook error: ${e.message}")
            false
        }
    }

    private fun broadcastStatus(connected: Boolean) {
        sendBroadcast(
            Intent(ACTION_SERVICE_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, connected)
        )
    }

    companion object {
        const val ACTION_SERVICE_STATUS = "com.notifwebhook.SERVICE_STATUS"
        const val EXTRA_CONNECTED = "connected"
        private const val DEDUPE_WINDOW_MS = 3_000L
    }
}
