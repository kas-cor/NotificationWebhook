package com.notifwebhook

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import android.widget.CheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
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

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs

    // Views
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var permissionBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var webhookInput: EditText
    private lateinit var bearerTokenInput: EditText
    private lateinit var saveBtn: Button
    private lateinit var testBtn: Button
    private lateinit var forwardingSwitch: SwitchMaterial
    private lateinit var skipOngoingSwitch: SwitchMaterial
    private lateinit var appsRecycler: RecyclerView

    // Разрешение на POST_NOTIFICATIONS (API 33+)
    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат обрабатывается в onResume */ }

    // BroadcastReceiver для статуса NLS
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(NotificationListenerService.EXTRA_CONNECTED, false)
            updateStatusUI(connected)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs.get(this)
        bindViews()
        restoreUI()
        setupListeners()
        loadAppsAsync()
    }

    override fun onResume() {
        super.onResume()

        // Регистрируем receiver для статуса NLS
        val filter = IntentFilter(NotificationListenerService.ACTION_SERVICE_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        // Обновляем статус сразу при открытии экрана
        updateStatusUI(isNlsEnabled())
        updatePermissionButton()
        updateBatteryButton()

        // Запрашиваем POST_NOTIFICATIONS на API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        permissionBtn = findViewById(R.id.btn_permission)
        batteryBtn = findViewById(R.id.btn_battery)
        webhookInput = findViewById(R.id.et_webhook_url)
        bearerTokenInput = findViewById(R.id.et_bearer_token)
        saveBtn = findViewById(R.id.btn_save_webhook)
        testBtn = findViewById(R.id.btn_test_webhook)
        forwardingSwitch = findViewById(R.id.switch_forwarding)
        skipOngoingSwitch = findViewById(R.id.switch_skip_ongoing)
        appsRecycler = findViewById(R.id.rv_apps)
        appsRecycler.layoutManager = LinearLayoutManager(this)
    }

    private fun restoreUI() {
        webhookInput.setText(prefs.webhookUrl)
        bearerTokenInput.setText(prefs.bearerToken)
        forwardingSwitch.isChecked = prefs.forwardingEnabled
        skipOngoingSwitch.isChecked = prefs.skipOngoing
    }

    private fun setupListeners() {
        // Кнопка «Предоставить доступ к уведомлениям»
        permissionBtn.setOnClickListener {
            if (!isNlsEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("Доступ к уведомлениям")
                    .setMessage(
                        "Откроются настройки системы.\n\n" +
                        "Найдите «NotifWebhook» и включите переключатель.\n\n" +
                        "После этого вернитесь в приложение."
                    )
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }

        // Кнопка «Исключить из оптимизации батареи»
        batteryBtn.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Сохранить webhook URL и Bearer token
        saveBtn.setOnClickListener {
            val url = webhookInput.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "URL должен начинаться с http:// или https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.webhookUrl = url
            prefs.bearerToken = bearerTokenInput.text.toString().trim()
            Toast.makeText(this, "✓ URL и токен сохранены", Toast.LENGTH_SHORT).show()
        }

        // Тест webhook
        testBtn.setOnClickListener { sendTestRequest() }

        // Переключатель пересылки
        forwardingSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !isNlsEnabled()) {
                forwardingSwitch.isChecked = false
                Toast.makeText(this, "Сначала предоставьте доступ к уведомлениям", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            prefs.forwardingEnabled = checked
            if (checked) {
                ForegroundKeepAliveService.start(this)
            } else {
                ForegroundKeepAliveService.stop(this)
            }
        }

        // Пропускать ongoing
        skipOngoingSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.skipOngoing = checked
        }
    }

    // -------------------------------------------------------------------------
    // Status helpers
    // -------------------------------------------------------------------------

    private fun updateStatusUI(connected: Boolean) {
        val active = connected && isNlsEnabled()
        statusDot.setBackgroundResource(
            if (active) R.drawable.circle_green else R.drawable.circle_red
        )
        statusText.text = if (active) "Слушатель активен ✓" else "Слушатель неактивен"
    }

    private fun updatePermissionButton() {
        if (isNlsEnabled()) {
            permissionBtn.text = "✓ Доступ к уведомлениям есть"
            permissionBtn.alpha = 0.5f
            permissionBtn.isEnabled = false
        } else {
            permissionBtn.text = "⚠ Предоставить доступ к уведомлениям"
            permissionBtn.alpha = 1f
            permissionBtn.isEnabled = true
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        if (exempt) {
            batteryBtn.text = "✓ Оптимизация батареи отключена"
            batteryBtn.alpha = 0.5f
        } else {
            batteryBtn.text = "⚠ Отключить оптимизацию батареи"
            batteryBtn.alpha = 1f
        }
    }

    /**
     * Проверяем, включён ли NLS через Settings.Secure.
     * Это единственный надёжный способ проверки на Android 14+.
     */
    private fun isNlsEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == packageName
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже исключено из оптимизации", Toast.LENGTH_SHORT).show()
            return
        }
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — прямой запрос без лишних шагов
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    // -------------------------------------------------------------------------
    // Apps list
    // -------------------------------------------------------------------------

    private fun loadAppsAsync() {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            }
            val selected = prefs.allowedApps.toMutableSet()
            appsRecycler.adapter = AppListAdapter(packageManager, apps, selected) { newSet ->
                prefs.allowedApps = newSet
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test webhook
    // -------------------------------------------------------------------------

    private fun sendTestRequest() {
        val url = prefs.webhookUrl
        if (url.isBlank()) {
            Toast.makeText(this, "Сначала введите и сохраните webhook URL", Toast.LENGTH_SHORT).show()
            return
        }
        testBtn.isEnabled = false
        testBtn.text = "Отправка..."

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
            put("timestamp_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
            put("timestamp_ms", System.currentTimeMillis())
        }.toString()

        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
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

            testBtn.isEnabled = true
            testBtn.text = "Отправить тест"

            result.fold(
                onSuccess = { code ->
                    if (code in 200..299) {
                        Toast.makeText(this@MainActivity, "✓ Успешно! HTTP $code", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "✗ HTTP ошибка: $code", Toast.LENGTH_LONG).show()
                    }
                },
                onFailure = { e ->
                    Toast.makeText(this@MainActivity, "✗ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
}

// =============================================================================
// AppListAdapter
// =============================================================================

class AppListAdapter(
    private val pm: PackageManager,
    private val apps: List<ApplicationInfo>,
    private val selected: MutableSet<String>,
    private val onChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val pkg: TextView = view.findViewById(R.id.app_package)
        val checkbox: CheckBox = view.findViewById(R.id.app_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(pm.getApplicationIcon(app))
        holder.name.text = pm.getApplicationLabel(app).toString()
        holder.pkg.text = app.packageName
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = app.packageName in selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
            onChanged(selected.toSet())
        }
        holder.itemView.setOnClickListener { holder.checkbox.toggle() }
    }
}
