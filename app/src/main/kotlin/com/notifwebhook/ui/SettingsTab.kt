package com.notifwebhook.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifwebhook.AppPrefs
import com.notifwebhook.BuildConfig
import com.notifwebhook.ForegroundKeepAliveService
import com.notifwebhook.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/kas-cor/NotificationWebhook/releases/latest"
private const val GITHUB_RELEASES_URL =
    "https://github.com/kas-cor/NotificationWebhook/releases/latest"
private const val GITHUB_REPO_URL =
    "https://github.com/kas-cor/NotificationWebhook"

/** Проверка, включён ли NLS через Settings.Secure (аналог приватной копии из HomeTab). */
private fun hasNotificationAccess(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ) ?: return false
    return flat.split(":").any { entry ->
        ComponentName.unflattenFromString(entry)?.packageName == context.packageName
    }
}

/** Состояния проверки обновлений. */
private enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, ERROR }

private data class UpdateUiState(val phase: UpdatePhase = UpdatePhase.IDLE, val version: String? = null)

/** GET-запрос к GitHub API и парсинг tag_name на фоновом потоке. */
private fun fetchLatestReleaseTag(): String? {
    val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).optString("tag_name", "").trim().ifEmpty { null }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/** Убирает ведущую "v" у тэга релиза. */
private fun cleanVersion(tag: String): String = tag.removePrefix("v").trim()

@Composable
fun SettingsTab(
    prefs: AppPrefs,
    modifier: Modifier = Modifier,
    themeMode: String,
    locale: String,
    onThemeChange: (String) -> Unit,
    onLocaleChange: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessRequiredToast = stringResource(R.string.access_required_first)

    // --- Состояния переключателей (перечитываем при рекомпозиции/возврате) ---
    var forwardingEnabled by remember { mutableStateOf(prefs.forwardingEnabled) }
    var skipOngoing by remember { mutableStateOf(prefs.skipOngoing) }
    var classificationEnabled by remember { mutableStateOf(prefs.classificationEnabled) }

    // --- Состояние проверки обновлений ---
    var updateState by remember { mutableStateOf(UpdateUiState()) }

    fun startUpdateCheck() {
        updateState = UpdateUiState(UpdatePhase.CHECKING)
        scope.launch {
            val latest = withContext(Dispatchers.IO) { fetchLatestReleaseTag() }
            updateState = if (latest == null) {
                UpdateUiState(UpdatePhase.ERROR)
            } else if (cleanVersion(latest) == cleanVersion(BuildConfig.VERSION_NAME)) {
                UpdateUiState(UpdatePhase.UP_TO_DATE)
            } else {
                UpdateUiState(UpdatePhase.AVAILABLE, cleanVersion(latest))
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ===================== НАСТРОЙКИ =====================
        SectionCard {
            SectionHeader(stringResource(R.string.section_settings))

            SettingSwitchRow(
                icon = { Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_forward),
                subtitle = null,
                checked = forwardingEnabled,
                onCheckedChange = { enable ->
                    if (enable && !hasNotificationAccess(context)) {
                        context.toast(accessRequiredToast)
                    } else {
                        forwardingEnabled = enable
                        prefs.forwardingEnabled = enable
                        if (enable) {
                            ForegroundKeepAliveService.start(context)
                        } else {
                            ForegroundKeepAliveService.stop(context)
                        }
                    }
                }
            )

            SettingSwitchRow(
                icon = { Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_ongoing),
                subtitle = stringResource(R.string.settings_ongoing_sub),
                checked = skipOngoing,
                onCheckedChange = {
                    skipOngoing = it
                    prefs.skipOngoing = it
                }
            )

            SettingSwitchRow(
                icon = { Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_promo),
                subtitle = stringResource(R.string.settings_promo_sub),
                checked = classificationEnabled,
                onCheckedChange = {
                    classificationEnabled = it
                    prefs.classificationEnabled = it
                }
            )
        }

        // ===================== ЛОКАЛИЗАЦИЯ =====================
        SectionCard {
            SectionHeader(stringResource(R.string.section_language))

            val locales = listOf(
                "" to stringResource(R.string.lang_system),
                "ru" to stringResource(R.string.lang_ru),
                "en" to stringResource(R.string.lang_en)
            )
            val currentLocale = locales.firstOrNull { it.first == locale }?.second
                ?: stringResource(R.string.lang_system)
            DropdownSettingRow(
                label = stringResource(R.string.lang_system_label),
                selectedLabel = currentLocale,
                icon = { Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(22.dp)) },
                options = locales
            ) { onLocaleChange(it) }
        }

        // ===================== ТЕМА =====================
        SectionCard {
            SectionHeader(stringResource(R.string.section_theme))

            val themes = listOf(
                "system" to stringResource(R.string.theme_system),
                "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark)
            )
            val currentTheme = themes.firstOrNull { it.first == themeMode }?.second
                ?: stringResource(R.string.theme_system)
            DropdownSettingRow(
                label = stringResource(R.string.theme_label),
                selectedLabel = currentTheme,
                icon = { Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(22.dp)) },
                options = themes
            ) { onThemeChange(it) }
        }

        // ===================== О ПРИЛОЖЕНИИ =====================
        SectionCard {
            SectionHeader(stringResource(R.string.section_about))

            Text(
                text = stringResource(R.string.app_name),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // --- Проверка обновлений (текст-строка вместо кнопки) ---
            TextActionRow(
                icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp)) },
                title = when (updateState.phase) {
                    UpdatePhase.CHECKING -> stringResource(R.string.checking_updates)
                    else -> stringResource(R.string.check_updates)
                },
                enabled = updateState.phase != UpdatePhase.CHECKING,
                onClick = { startUpdateCheck() }
            )

            when (updateState.phase) {
                UpdatePhase.AVAILABLE -> {
                    Text(
                        text = stringResource(R.string.update_available, updateState.version.orEmpty()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 2.dp)
                    )
                    // Скачать — текстовая строка
                    TextActionRow(
                        icon = { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        title = stringResource(R.string.download),
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL)))
                        }
                    )
                }
                UpdatePhase.UP_TO_DATE -> {
                    Text(
                        text = stringResource(R.string.up_to_date),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 2.dp)
                    )
                }
                UpdatePhase.ERROR -> {
                    Text(
                        text = stringResource(R.string.update_check_error),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 2.dp)
                    )
                }
                UpdatePhase.IDLE, UpdatePhase.CHECKING -> Unit
            }

            Spacer(Modifier.height(4.dp))

            // --- Репозиторий GitHub (текст-строка вместо кнопки) ---
            TextActionRow(
                icon = { Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(20.dp)) },
                title = stringResource(R.string.github_repo),
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
                }
            )
        }
    }
}

/** Кликабельная текстовая строка-действие с иконкой (вместо полноценной кнопки). */
@Composable
private fun TextActionRow(
    icon: @Composable () -> Unit,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                enabled = enabled,
                onClick = onClick,
                role = Role.Button
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = title,
            fontSize = 15.sp,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Строка выпадающего списка настройки: label + текущее значение + стрелка. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSettingRow(
    label: String,
    selectedLabel: String,
    icon: @Composable () -> Unit,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) { icon() }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedLabel,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, optLabel) ->
                DropdownMenuItem(
                    text = { Text(optLabel) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Строка-переключатель с иконкой слева. */
@Composable
private fun SettingSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.width(36.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                icon()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
