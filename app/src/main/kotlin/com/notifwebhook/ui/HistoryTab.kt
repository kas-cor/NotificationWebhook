package com.notifwebhook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifwebhook.AppPrefs
import com.notifwebhook.ExclusionRule
import com.notifwebhook.R
import com.notifwebhook.WebhookEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryTab(prefs: AppPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var history by remember { mutableStateOf(prefs.getHistory()) }

    val successCount = history.count { it.success }

    // Запись истории, для которой открыт диалог «Исключить из пересылки»
    var excludeEntry by remember { mutableStateOf<WebhookEntry?>(null) }
    val ruleAddedMsg = stringResource(R.string.rule_added)

    excludeEntry?.let { entry ->
        ExcludeFromHistoryDialog(
            entry = entry,
            onDismiss = { excludeEntry = null },
            onCreate = { field, pattern ->
                prefs.addExclusionRule(ExclusionRule(field = field, pattern = pattern))
                context.toast(ruleAddedMsg)
                excludeEntry = null
            }
        )
    }

    ListTabCard(modifier = modifier.padding(16.dp)) {
        item(key = "header") {
            SectionHeader(stringResource(R.string.section_history))

            if (history.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_records),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                val lastEntry = history.last()
                Text(
                    text = stringResource(
                        R.string.history_summary,
                        history.size,
                        successCount,
                        lastEntry.appName,
                        formatTime(lastEntry.timestamp, "HH:mm")
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val clearedMsg = stringResource(R.string.history_cleared)
                Button(
                    onClick = {
                        prefs.clearHistory()
                        history = emptyList()
                        context.toast(clearedMsg)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(bottom = 4.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_history))
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        items(history.reversed()) { entry ->
            HistoryRow(entry, onExclude = { excludeEntry = entry })
        }
    }
}

/** Диалог «Исключить из пересылки»: выбираем поле из записи → создаём правило. */
@Composable
private fun ExcludeFromHistoryDialog(
    entry: WebhookEntry,
    onDismiss: () -> Unit,
    onCreate: (field: String, pattern: String) -> Unit
) {
    val fieldLabels = listOf(
        R.string.field_title_opt,
        R.string.field_text_opt,
        R.string.field_appname_opt,
        R.string.field_pkg_opt
    )
    val fieldKeys = listOf("title", "text", "app_name", "app_package")
    val fieldValues = listOf(entry.title, entry.text, entry.appName, entry.appPackage)

    var selectedIndex by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val fieldEmptyMsg = stringResource(R.string.exclude_field_empty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exclude_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.exclude_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                fieldLabels.forEachIndexed { i, labelRes ->
                    val value = fieldValues[i].take(40)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIndex = i }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedIndex == i,
                            onClick = { selectedIndex = i }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(labelRes) + " → $value",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pattern = fieldValues[selectedIndex]
                if (pattern.isBlank()) {
                    context.toast(fieldEmptyMsg)
                } else {
                    onCreate(fieldKeys[selectedIndex], pattern)
                }
            }) { Text(stringResource(R.string.exclude_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Строка записи истории. */
@Composable
private fun HistoryRow(entry: WebhookEntry, onExclude: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.success) colorResource(R.color.status_green)
                        else colorResource(R.color.status_red)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.appName,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatTime(entry.timestamp, "dd.MM HH:mm"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onExclude,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.exclude_title),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        Column(modifier = Modifier.padding(start = 20.dp)) {
            if (entry.title.isNotBlank()) {
                Text(
                    text = entry.title,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (entry.text.isNotBlank()) {
                Text(
                    text = entry.text,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (entry.httpCode > 0) "HTTP ${entry.httpCode}"
                else stringResource(R.string.http_error_conn),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 1.dp)
            )

            // Статус классификации (второй запрос)
            val classifyStatus = entry.classifyStatus
            if (classifyStatus != null) {
                Text(
                    text = classifyStatusLabel(classifyStatus),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun classifyStatusLabel(status: String): String = when (status) {
    "dismiss" -> stringResource(R.string.promo_dismiss)
    "keep" -> stringResource(R.string.promo_keep)
    "pending" -> stringResource(R.string.promo_pending)
    "error" -> stringResource(R.string.promo_error)
    "disabled" -> stringResource(R.string.promo_disabled)
    else -> stringResource(R.string.promo_other, status)
}

private fun formatTime(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
