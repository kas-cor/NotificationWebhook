package com.notifwebhook

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Запись истории отправки webhook.
 */
data class WebhookEntry(
    val timestamp: Long,
    val appPackage: String,
    val appName: String,
    val title: String,
    val text: String,
    val success: Boolean,
    val httpCode: Int,
    val classifyStatus: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("app_package", appPackage)
        put("app_name", appName)
        put("title", title)
        put("text", text)
        put("success", success)
        put("http_code", httpCode)
        if (classifyStatus != null) {
            put("classify_status", classifyStatus)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): WebhookEntry = WebhookEntry(
            timestamp = obj.optLong("timestamp", 0L),
            appPackage = obj.optString("app_package", ""),
            appName = obj.optString("app_name", ""),
            title = obj.optString("title", ""),
            text = obj.optString("text", ""),
            success = obj.optBoolean("success", false),
            httpCode = obj.optInt("http_code", 0),
            classifyStatus = if (obj.has("classify_status")) obj.optString("classify_status", "").ifEmpty { null } else null
        )
    }
}

/**
 * Правило исключения: если в указанном поле найдена подстрока — уведомление пропускается.
 */
data class ExclusionRule(
    val id: String = UUID.randomUUID().toString(),
    val field: String,  // "title", "text", "app_name", "app_package"
    val pattern: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("field", field)
        put("pattern", pattern)
    }

    companion object {
        fun fromJson(obj: JSONObject): ExclusionRule = ExclusionRule(
            id = obj.optString("id", UUID.randomUUID().toString()),
            field = obj.optString("field", "title"),
            pattern = obj.optString("pattern", "")
        )
    }
}

/**
 * Единая точка доступа к SharedPreferences.
 * Потокобезопасно: SharedPreferences.getXxx() безопасен для чтения из любого потока.
 */
class AppPrefs internal constructor(private val sp: SharedPreferences) {

    private val writeLock = Any()

    var webhookUrl: String
        get() = sp.getString(KEY_WEBHOOK_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBHOOK_URL, v).apply()

    var forwardingEnabled: Boolean
        get() = sp.getBoolean(KEY_FORWARDING_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_FORWARDING_ENABLED, v).apply()

    var skipOngoing: Boolean
        get() = sp.getBoolean(KEY_SKIP_ONGOING, true)
        set(v) = sp.edit().putBoolean(KEY_SKIP_ONGOING, v).apply()

    /**
     * Включает/выключает классификацию промо (второй запрос к серверу и автосмахивание).
     * По умолчанию включено.
     */
    var classificationEnabled: Boolean
        get() = sp.getBoolean(KEY_CLASSIFICATION_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_CLASSIFICATION_ENABLED, v).apply()

    /**
     * Режим темы: "system" | "light" | "dark". По умолчанию — системный.
     */
    var themeMode: String
        get() = sp.getString(KEY_THEME_MODE, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_THEME_MODE, v).apply()

    /**
     * Язык интерфейса: "" (системный) | "ru" | "en". По умолчанию — системный.
     */
    var locale: String
        get() = sp.getString(KEY_LOCALE, "") ?: ""
        set(v) = sp.edit().putString(KEY_LOCALE, v).apply()

    var bearerToken: String
        get() = sp.getString(KEY_BEARER_TOKEN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_BEARER_TOKEN, v).apply()

    var allowedApps: Set<String>
        get() = sp.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_ALLOWED_APPS, v).apply()

    // -------------------------------------------------------------------------
    // Webhook History (last 50 entries)
    // -------------------------------------------------------------------------

    /**
     * Возвращает копию списка истории (последние 50).
     */
    fun getHistory(): List<WebhookEntry> {
        val raw = sp.getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            WebhookEntry.fromJson(arr.getJSONObject(i))
        }
    }

    /**
     * Добавляет запись в историю. Если записей больше 50 — удаляет самую старую.
     */
    fun addHistoryEntry(entry: WebhookEntry) = synchronized(writeLock) {
        val list = getHistory().toMutableList()
        list.add(entry)
        // Оставляем только последние 50
        while (list.size > 50) {
            list.removeAt(0)
        }
        saveHistory(list)
    }

    /**
     * Очищает всю историю.
     */
    fun clearHistory() = synchronized(writeLock) {
        sp.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(list: List<WebhookEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // -------------------------------------------------------------------------
    // Exclusion Rules
    // -------------------------------------------------------------------------

    fun getExclusionRules(): List<ExclusionRule> {
        val raw = sp.getString(KEY_EXCLUSION_RULES, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            ExclusionRule.fromJson(arr.getJSONObject(i))
        }
    }

    fun addExclusionRule(rule: ExclusionRule) = synchronized(writeLock) {
        val list = getExclusionRules().toMutableList()
        list.add(rule)
        saveExclusionRules(list)
    }

    fun removeExclusionRule(ruleId: String) = synchronized(writeLock) {
        val list = getExclusionRules().toMutableList()
        list.removeAll { it.id == ruleId }
        saveExclusionRules(list)
    }

    private fun saveExclusionRules(list: List<ExclusionRule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_EXCLUSION_RULES, arr.toString()).apply()
    }

    // -------------------------------------------------------------------------

    companion object {
        private const val PREFS_NAME = "notif_webhook_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_BEARER_TOKEN = "bearer_token"
        private const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
        private const val KEY_SKIP_ONGOING = "skip_ongoing"
        private const val KEY_CLASSIFICATION_ENABLED = "classification_enabled"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOCALE = "locale"
        private const val KEY_ALLOWED_APPS = "allowed_apps"
        private const val KEY_HISTORY = "webhook_history"
        private const val KEY_EXCLUSION_RULES = "exclusion_rules"

        @Volatile private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(
                    runCatching {
                        val masterKey = MasterKey.Builder(context.applicationContext)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        EncryptedSharedPreferences.create(
                            context.applicationContext,
                            PREFS_NAME,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                    }.getOrElse {
                        context.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    }
                ).also { instance = it }
            }
    }
}
