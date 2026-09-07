package com.notifwebhook.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            HistoryRow(entry)
        }
    }
}

/** Строка записи истории. */
@Composable
private fun HistoryRow(entry: WebhookEntry) {
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
