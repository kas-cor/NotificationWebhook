package com.notifwebhook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Автозапуск после перезагрузки устройства.
 *
 * Android 14+: BOOT_COMPLETED по-прежнему работает при условии,
 * что пользователь хотя бы раз запустил приложение вручную.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i("BootReceiver", "Boot completed / package replaced — запускаем KeepAlive")

        val prefs = AppPrefs.get(context)
        // Запускаем foreground-сервис только если пользователь включал пересылку
        if (prefs.forwardingEnabled) {
            ForegroundKeepAliveService.start(context)

            // Принудительно запрашиваем переподключение NLS — после перезагрузки
            // система не всегда автоматически биндит NotificationListenerService
            val nlsComponent = ComponentName(context, NotificationListenerService::class.java)
            android.service.notification.NotificationListenerService.requestRebind(nlsComponent)
            Log.i("BootReceiver", "requestRebind() вызван для NLS")
        }
    }
}
