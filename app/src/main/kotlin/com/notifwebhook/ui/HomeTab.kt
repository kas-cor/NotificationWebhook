package com.notifwebhook.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.notifwebhook.AppPrefs
import com.notifwebhook.NotificationListenerService
import com.notifwebhook.R
import com.notifwebhook.WebhookEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Проверка, включён ли NLS через Settings.Secure (надёжный способ на Android 14+). */
private fun isNotificationAccessEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ) ?: return false
    return flat.split(":").any { entry ->
        ComponentName.unflattenFromString(entry)?.packageName == context.packageName
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
fun HomeTab(prefs: AppPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nlsEnabled by remember { mutableStateOf(isNotificationAccessEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }

    var webhookUrl by rememberSaveable { mutableStateOf(prefs.webhookUrl) }
    var bearerToken by rememberSaveable { mutableStateOf(prefs.bearerToken) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var showAccessDialog by remember { mutableStateOf(false) }

    // --- Статус NLS: live-обновление + перечитывание при возврате на экран ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent) {
                nlsEnabled = intent.getBooleanExtra(
                    NotificationListenerService.EXTRA_CONNECTED,
                    isNotificationAccessEnabled(context)
                )
            }
        }
        val filter = IntentFilter(NotificationListenerService.ACTION_SERVICE_STATUS)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nlsEnabled = isNotificationAccessEnabled(context)
                batteryExempt = isBatteryOptimizationIgnored(context)
            }
        }
        lifecycle.addObserver(observer)

        onDispose {
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(observer)
        }
    }

    // --- POST_NOTIFICATIONS (API 33+, minSdk 34 => всегда) ---
    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не требуется */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showAccessDialog) {
        AlertDialog(
            onDismissRequest = { showAccessDialog = false },
            title = { Text(stringResource(R.string.grant_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.grant_dialog_msg1) +
                        stringResource(R.string.grant_dialog_msg2) +
                        stringResource(R.string.grant_dialog_msg3)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessDialog = false
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ===== СТАТУС =====
        SectionCard {
            SectionHeader(stringResource(R.string.section_status))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (nlsEnabled) colorResource(R.color.status_green)
                            else colorResource(R.color.status_red)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (nlsEnabled) stringResource(R.string.listener_active)
                    else stringResource(R.string.listener_inactive),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showAccessDialog = true },
                enabled = !nlsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (nlsEnabled) stringResource(R.string.grant_access_done)
                    else stringResource(R.string.grant_access)
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (isBatteryOptimizationIgnored(context)) {
                        context.toast(context.getString(R.string.battery_already))
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (batteryExempt) stringResource(R.string.battery_exempt_done)
                    else stringResource(R.string.battery_exempt)
                )
            }
        }

        // ===== WEBHOOK URL =====
        SectionCard {
            SectionHeader(stringResource(R.string.section_webhook))

            OutlinedTextField(
                value = webhookUrl,
                onValueChange = { webhookUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.hint_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bearerToken,
                onValueChange = { bearerToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.hint_token)) },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisible) stringResource(R.string.hide_token)
                            else stringResource(R.string.show_token)
                        )
                    }
                }
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val url = webhookUrl.trim()
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            context.toast(context.getString(R.string.url_invalid))
                            return@Button
                        }
                        prefs.webhookUrl = url
                        prefs.bearerToken = bearerToken.trim()
                        context.toast(context.getString(R.string.url_saved))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save))
                }

                Button(
                    onClick = {
                        val url = prefs.webhookUrl
                        if (url.isBlank()) {
                            context.toast(context.getString(R.string.url_required))
                            return@Button
                        }
                        testing = true
                        scope.launch {
                            val result = runTestPost(prefs)
                            testing = false
                            result.fold(
                                onSuccess = { code ->
                                    val success = code in 200..299
                                    prefs.addHistoryEntry(testEntry(success, code, null))
                                    if (success) context.toast(
                                        context.getString(R.string.test_success, code), long = true
                                    ) else context.toast(
                                        context.getString(R.string.test_http_error, code), long = true
                                    )
                                },
                                onFailure = { e ->
                                    prefs.addHistoryEntry(testEntry(false, 0, e.message))
                                    context.toast(
                                        context.getString(R.string.test_error, e.message ?: ""), long = true
                                    )
                                }
                            )
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (testing) stringResource(R.string.sending) else stringResource(R.string.test_post))
                }
            }
        }

        // ===== НАСТРОЙКИ переехали на отдельную вкладку «Настройки» =====

        Spacer(Modifier.height(8.dp))
    }
}

/** Выполняет тестовый POST в сохранённый webhook на фоновом потоке. */
private suspend fun runTestPost(prefs: AppPrefs): Result<Int> {
    val url = prefs.webhookUrl
    val payload = JSONObject().apply {
        put("app_package", "com.notifwebhook.test")
        put("app_name", "NotifWebhook")
        put("title", "Тестовое уведомление")
        put("text", "Соединение с webhook работает корректно")
        put("sub_text", "")
        put("category", "test")
        put("priority", 0)
        put("notification_id", -1)
        put("channel_id", "test")
        put(
            "timestamp_iso",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        )
        put("timestamp_ms", System.currentTimeMillis())
    }.toString()

    return withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val token = prefs.bearerToken
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
            }
            val code = conn.responseCode
            conn.disconnect()
            code
        }
    }
}

/** Создаёт запись истории для тестового POST. */
private fun testEntry(success: Boolean, code: Int, errorMessage: String?): WebhookEntry =
    WebhookEntry(
        timestamp = System.currentTimeMillis(),
        appPackage = "com.notifwebhook.test",
        appName = "NotifWebhook",
        title = "Тестовое уведомление",
        text = when {
            errorMessage != null -> "Ошибка: $errorMessage"
            success -> "✓ HTTP $code"
            else -> "✗ HTTP $code"
        },
        success = success,
        httpCode = code
    )
