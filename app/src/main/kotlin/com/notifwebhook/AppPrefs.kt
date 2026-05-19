package com.notifwebhook

import android.content.Context
import android.content.SharedPreferences

/**
 * Единая точка доступа к SharedPreferences.
 * Потокобезопасно: SharedPreferences.getXxx() безопасен для чтения из любого потока.
 */
class AppPrefs private constructor(private val sp: SharedPreferences) {

    var webhookUrl: String
        get() = sp.getString(KEY_WEBHOOK_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBHOOK_URL, v).apply()

    var forwardingEnabled: Boolean
        get() = sp.getBoolean(KEY_FORWARDING_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_FORWARDING_ENABLED, v).apply()

    var skipOngoing: Boolean
        get() = sp.getBoolean(KEY_SKIP_ONGOING, true)
        set(v) = sp.edit().putBoolean(KEY_SKIP_ONGOING, v).apply()

    var allowedApps: Set<String>
        get() = sp.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_ALLOWED_APPS, v).apply()

    companion object {
        private const val PREFS_NAME = "notif_webhook_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
        private const val KEY_SKIP_ONGOING = "skip_ongoing"
        private const val KEY_ALLOWED_APPS = "allowed_apps"

        @Volatile private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(
                    context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
