package com.notifwebhook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * ForegroundKeepAliveService
 *
 * Зачем нужен:
 * ─────────────────────────────────────────────────────────────
 * NotificationListenerService биндится системой и не является
 * foreground-сервисом сам по себе. На агрессивных OEM (Xiaomi MIUI,
 * Huawei EMUI, Samsung OneUI с aggressive battery) процесс может
 * быть убит, а NLS — отвязан.
 *
 * Запуск ForegroundKeepAliveService удерживает процесс приложения
 * в foreground-состоянии: система крайне неохотно убивает процессы
 * с активным foreground-сервисом.
 *
 * Android 14+ (API 34):
 * ─────────────────────────────────────────────────────────────
 * - foregroundServiceType="specialUse" в манифесте — обязателен,
 *   иначе startForeground() бросит MissingForegroundServiceTypeException.
 * - FOREGROUND_SERVICE_SPECIAL_USE permission — обязателен.
 * - В манифесте объявлено <property android:name=
 *   "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"> с описанием
 *   причины (для Play Console review).
 *
 * Как запускать:
 * ─────────────────────────────────────────────────────────────
 * Вызывай startForegroundKeepAlive(context) из:
 *   - MainActivity.onResume() (если пользователь включил пересылку)
 *   - BootReceiver.onReceive()
 */
class ForegroundKeepAliveService : Service() {

    private val tag = "KeepAliveService"

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "onCreate")
        createNotificationChannel()

        // Запрашиваем переподключение NLS — это гарантирует, что система
        // попытается забиндить NotificationListenerService, даже если он
        // был отключён после убийства процесса
        val nlsComponent = ComponentName(this, NotificationListenerService::class.java)
        android.service.notification.NotificationListenerService.requestRebind(nlsComponent)
        Log.i(tag, "requestRebind() вызван для NLS")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(tag, "Stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification()

        // API 34+: startForeground требует явного типа
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.i(tag, "Foreground started")

        // START_STICKY: если система убьёт сервис, она перезапустит его
        // (но без Intent — intent будет null, это нормально)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(tag, "onDestroy — сервис убит")
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Webhook Forwarder",
            NotificationManager.IMPORTANCE_LOW  // LOW = нет звука, минимальное место в шторке
        ).apply {
            description = "Постоянное уведомление активного слушателя уведомлений"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Тап по уведомлению открывает MainActivity
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Кнопка «Остановить»
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ForegroundKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val forwarding = AppPrefs.get(this).forwardingEnabled
        val statusText = if (forwarding) "Пересылка уведомлений активна" else "Слушатель запущен, пересылка выключена"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NotifWebhook")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .addAction(
                R.drawable.ic_stop,
                "Остановить",
                stopIntent
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "notifwebhook_keepalive"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.notifwebhook.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ForegroundKeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundKeepAliveService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
