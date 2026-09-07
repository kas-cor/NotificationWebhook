package com.notifwebhook.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifwebhook.AppPrefs
import com.notifwebhook.ExclusionRule
import com.notifwebhook.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Вкладка «Исключения» — объединяет выбор приложений для пересылки
 * (пусто = все) и правила исключений (title/text/app/package по подстроке).
 */
@Composable
fun ExclusionsTab(prefs: AppPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // --- Приложения ---
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedApps by remember { mutableStateOf(prefs.allowedApps) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    // --- Правила исключений ---
    var rules by remember { mutableStateOf(prefs.getExclusionRules()) }
    var showAddDialog by remember { mutableStateOf(false) }
    val ruleAddedMsg = stringResource(R.string.rule_added)

    fun deleteRule(ruleId: String) {
        prefs.removeExclusionRule(ruleId)
        rules = prefs.getExclusionRules()
    }

    fun addRule(field: String, pattern: String) {
        prefs.addExclusionRule(ExclusionRule(field = field, pattern = pattern))
        rules = prefs.getExclusionRules()
        context.toast(ruleAddedMsg)
    }

    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { field, pattern ->
                addRule(field, pattern)
                showAddDialog = false
            }
        )
    }

    ListTabCard(modifier = modifier.padding(16.dp)) {

        // ===== ПРИЛОЖЕНИЯ =====
        item(key = "apps_header") {
            SectionHeader(stringResource(R.string.section_apps))
            Text(
                text = stringResource(R.string.apps_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (apps.isEmpty()) {
            item(key = "apps_loading") {
                Text(
                    text = stringResource(R.string.loading),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(apps, key = { "app_${it.packageName}" }) { app ->
                val checked = app.packageName in selectedApps
                AppRow(
                    app = app,
                    checked = checked,
                    onToggle = {
                        val newSelected = if (it) selectedApps + app.packageName
                        else selectedApps - app.packageName
                        selectedApps = newSelected
                        prefs.allowedApps = newSelected
                    }
                )
            }
        }

        item(key = "apps_divider") {
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // ===== ПРАВИЛА ИСКЛЮЧЕНИЙ =====
        item(key = "rules_header") {
            SectionHeader(stringResource(R.string.section_rules))
            Text(
                text = stringResource(R.string.rules_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (rules.isEmpty()) {
            item(key = "rules_empty") {
                Text(
                    text = stringResource(R.string.no_rules),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(rules, key = { "rule_${it.id}" }) { rule ->
                RuleRow(
                    field = fieldLabel(rule.field),
                    pattern = rule.pattern,
                    onDelete = { deleteRule(rule.id) }
                )
            }
        }

        item(key = "rules_add") {
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_rule))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Приложения
// ---------------------------------------------------------------------------

/** Установленное приложение с заранее загруженной иконкой. */
private data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap
)

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle
        )
    }
}

/** Загружает список несистемных приложений с иконками на фоновом потоке. */
private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .mapNotNull { app ->
            runCatching {
                InstalledApp(
                    label = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(app).toImageBitmap()
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/** Конвертация Drawable в ImageBitmap (для app-иконок). */
private fun Drawable.toImageBitmap(): ImageBitmap {
    val bmp = when (this) {
        is BitmapDrawable -> bitmap
        else -> null
    }
    if (bmp != null && !bmp.isRecycled) return bmp.asImageBitmap()

    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    setBounds(0, 0, w, h)
    draw(canvas)
    return out.asImageBitmap()
}

// ---------------------------------------------------------------------------
// Правила исключений
// ---------------------------------------------------------------------------

/** Поля правил исключения: значение в prefs + ресурс подписи для выпадающего меню. */
private val RULE_FIELDS = listOf(
    "title" to R.string.field_title_opt,
    "text" to R.string.field_text_opt,
    "app_name" to R.string.field_appname_opt,
    "app_package" to R.string.field_pkg_opt
)

/** Короткая подпись поля правила (для строки правила). */
@Composable
private fun fieldLabel(field: String): String = when (field) {
    "title" -> stringResource(R.string.field_title)
    "text" -> stringResource(R.string.field_text)
    "app_name" -> stringResource(R.string.field_appname)
    "app_package" -> stringResource(R.string.field_pkg)
    else -> field
}

/** Строка правила исключения. */
@Composable
private fun RuleRow(
    field: String,
    pattern: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = field,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = pattern,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete_rule),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Диалог добавления правила исключения. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (field: String, pattern: String) -> Unit
) {
    var fieldIndex by rememberSaveable { mutableStateOf(0) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var patternError by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_rule_dialog)) },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = stringResource(RULE_FIELDS[fieldIndex].second),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_search)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        RULE_FIELDS.forEachIndexed { index, (_, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
                                onClick = {
                                    fieldIndex = index
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        patternError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pattern_search)) },
                    singleLine = true,
                    isError = patternError,
                    supportingText = if (patternError) {
                        { Text(stringResource(R.string.enter_pattern)) }
                    } else null
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pattern.isBlank()) {
                    patternError = true
                } else {
                    onAdd(RULE_FIELDS[fieldIndex].first, pattern.trim())
                }
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
