package com.notifwebhook.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
                icon = { Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(22.dp)) },
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
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_ongoing),
                subtitle = stringResource(R.string.settings_ongoing_sub),
                checked = skipOngoing,
                onCheckedChange = {
                    skipOngoing = it
                    prefs.skipOngoing = it
                }
            )

            SettingSwitchRow(
                icon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(22.dp)) },
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
            locales.forEach { (value, label) ->
                RadioSelectRow(
                    label = label,
                    selected = locale == value,
                    onClick = { onLocaleChange(value) }
                )
            }
        }

        // ===================== ТЕМА =====================
        SectionCard {
            SectionHeader(stringResource(R.string.section_theme))

            val themes = listOf(
                "system" to stringResource(R.string.theme_system),
                "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark)
            )
            themes.forEach { (value, label) ->
                RadioSelectRow(
                    label = label,
                    selected = themeMode == value,
                    onClick = { onThemeChange(value) }
                )
            }
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

            // --- Проверка обновлений ---
            Button(
                onClick = { startUpdateCheck() },
                enabled = updateState.phase != UpdatePhase.CHECKING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                if (updateState.phase == UpdatePhase.CHECKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.checking_updates))
                } else {
                    Text(stringResource(R.string.check_updates))
                }
            }

            when (updateState.phase) {
                UpdatePhase.AVAILABLE -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.update_available, updateState.version.orEmpty()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) { Text(stringResource(R.string.download)) }
                }
                UpdatePhase.UP_TO_DATE -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.up_to_date),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                UpdatePhase.ERROR -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.update_check_error),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                UpdatePhase.IDLE, UpdatePhase.CHECKING -> Unit
            }

            Spacer(Modifier.height(8.dp))

            // --- Репозиторий GitHub ---
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) { Text(stringResource(R.string.github_repo)) }
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

/** Строка выбора с RadioButton справа, кликабельная по всей ширине. */
@Composable
private fun RadioSelectRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        RadioButton(selected = selected, onClick = null)
    }
}
