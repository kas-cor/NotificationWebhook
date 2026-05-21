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
import android.util.Log
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

    companion object {
        private const val TAG = "MainActivity"
    }

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

    // Exclusion rules views
    private lateinit var rulesRecycler: RecyclerView
    private lateinit var btnAddRule: Button
    private lateinit var rulesAdapter: ExclusionRulesAdapter

    // History views
    private lateinit var tvHistorySummary: TextView
    private lateinit var btnViewHistory: Button

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
        refreshExclusionRules()
        refreshHistorySummary()
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
        refreshHistorySummary()
        refreshExclusionRules()

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

        // Exclusion rules
        rulesRecycler = findViewById(R.id.rv_exclusion_rules)
        rulesRecycler.layoutManager = LinearLayoutManager(this)
        btnAddRule = findViewById(R.id.btn_add_rule)

        // History
        tvHistorySummary = findViewById(R.id.tv_history_summary)
        btnViewHistory = findViewById(R.id.btn_view_history)
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

        // Кнопка добавления правила исключения
        btnAddRule.setOnClickListener { showAddRuleDialog() }

        // Кнопка просмотра истории
        btnViewHistory.setOnClickListener { showHistoryDialog() }
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
    // Exclusion Rules UI
    // -------------------------------------------------------------------------

    private fun refreshExclusionRules() {
        val rules = prefs.getExclusionRules()
        if (!::rulesAdapter.isInitialized) {
            rulesAdapter = ExclusionRulesAdapter(rules) { ruleId ->
                prefs.removeExclusionRule(ruleId)
                refreshExclusionRules()
            }
            rulesRecycler.adapter = rulesAdapter
        } else {
            rulesAdapter.updateRules(rules)
        }
    }

    private fun showAddRuleDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_rule, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_rule_field)
        val patternInput = dialogView.findViewById<TextInputEditText>(R.id.et_rule_pattern)
        val patternLayout = dialogView.findViewById<TextInputLayout>(R.id.til_rule_pattern)

        val fields = listOf("title", "text", "app_name", "app_package")
        val fieldLabels = listOf(
            "Заголовок (title)",
            "Текст (text)",
            "Имя приложения (app_name)",
            "Пакет (app_package)"
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fieldLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Добавить правило исключения")
            .setMessage("Укажите поле и текст, при вхождении которого уведомление будет пропущено")
            .setView(dialogView)
            .setPositiveButton("Добавить") { dialog, _ ->
                val pattern = patternInput.text?.toString()?.trim().orEmpty()
                if (pattern.isBlank()) {
                    patternLayout.error = "Введите текст для поиска"
                    return@setPositiveButton
                }
                val selectedField = fields[spinner.selectedItemPosition]
                prefs.addExclusionRule(ExclusionRule(field = selectedField, pattern = pattern))
                refreshExclusionRules()
                Toast.makeText(this, "✓ Правило добавлено", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // History UI
    // -------------------------------------------------------------------------

    private fun refreshHistorySummary() {
        val history = prefs.getHistory()
        if (history.isEmpty()) {
            tvHistorySummary.text = "Нет записей"
            btnViewHistory.isEnabled = false
            btnViewHistory.alpha = 0.5f
        } else {
            val successCount = history.count { it.success }
            val lastEntry = history.last()
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val time = timeFormat.format(Date(lastEntry.timestamp))
            tvHistorySummary.text = "Всего: ${history.size} | Успешно: $successCount | Последняя: ${lastEntry.appName} в $time"
            btnViewHistory.isEnabled = true
            btnViewHistory.alpha = 1f
        }
    }

    private fun showHistoryDialog() {
        val history = prefs.getHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "История пуста", Toast.LENGTH_SHORT).show()
            return
        }

        val reversed = history.reversed()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("История отправки (${history.size})")
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Очистить") { _, _ ->
                prefs.clearHistory()
                refreshHistorySummary()
                Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
            }
            .create()

        val recyclerView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_history, null) as RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = HistoryAdapter(reversed)

        dialog.setView(recyclerView)
        dialog.show()
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

        Log.d(TAG, "→ Тест POST: $url")
        Log.d(TAG, "  Payload: ${payload.take(200)}")

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
                        Log.d(TAG, "  Auth: Bearer token")
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
                    val success = code in 200..299
                    Log.d(TAG, "← Тест POST результат: HTTP $code (${if (success) "OK" else "ERR"})")
                    prefs.addHistoryEntry(
                        WebhookEntry(
                            timestamp = System.currentTimeMillis(),
                            appPackage = "com.notifwebhook.test",
                            appName = "NotifWebhook",
                            title = "Тестовое уведомление",
                            text = if (success) "✓ HTTP $code" else "✗ HTTP $code",
                            success = success,
                            httpCode = code
                        )
                    )
                    refreshHistorySummary()

                    if (success) {
                        Toast.makeText(this@MainActivity, "✓ Успешно! HTTP $code", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "✗ HTTP ошибка: $code", Toast.LENGTH_LONG).show()
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "← Тест POST ошибка: ${e.message}")
                    prefs.addHistoryEntry(
                        WebhookEntry(
                            timestamp = System.currentTimeMillis(),
                            appPackage = "com.notifwebhook.test",
                            appName = "NotifWebhook",
                            title = "Тестовое уведомление",
                            text = "Ошибка: ${e.message}",
                            success = false,
                            httpCode = 0
                        )
                    )
                    refreshHistorySummary()
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

// =============================================================================
// ExclusionRulesAdapter
// =============================================================================

class ExclusionRulesAdapter(
    private var rules: List<ExclusionRule>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<ExclusionRulesAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val fieldView: TextView = view.findViewById(R.id.rule_field)
        val patternView: TextView = view.findViewById(R.id.rule_pattern)
        val deleteBtn: ImageButton = view.findViewById(R.id.btn_delete_rule)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false))

    override fun getItemCount() = rules.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rule = rules[position]
        val fieldLabel = when (rule.field) {
            "title" -> "Заголовок"
            "text" -> "Текст"
            "app_name" -> "Имя приложения"
            "app_package" -> "Пакет"
            else -> rule.field
        }
        holder.fieldView.text = fieldLabel
        holder.patternView.text = rule.pattern
        holder.deleteBtn.setOnClickListener { onDelete(rule.id) }
    }

    fun updateRules(newRules: List<ExclusionRule>) {
        rules = newRules
        notifyDataSetChanged()
    }
}

// =============================================================================
// HistoryAdapter
// =============================================================================

class HistoryAdapter(
    private val entries: List<WebhookEntry>
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val statusDot: View = view.findViewById(R.id.history_status_dot)
        val appName: TextView = view.findViewById(R.id.history_app_name)
        val time: TextView = view.findViewById(R.id.history_time)
        val title: TextView = view.findViewById(R.id.history_title)
        val text: TextView = view.findViewById(R.id.history_text)
        val httpCode: TextView = view.findViewById(R.id.history_http)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        val context = holder.itemView.context

        holder.statusDot.setBackgroundResource(
            if (entry.success) R.drawable.circle_green else R.drawable.circle_red
        )
        holder.appName.text = entry.appName

        val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        holder.time.text = timeFormat.format(Date(entry.timestamp))

        holder.title.text = entry.title
        holder.text.text = entry.text

        val httpText = if (entry.httpCode > 0) "HTTP ${entry.httpCode}" else "Ошибка соединения"
        holder.httpCode.text = httpText
    }
}
