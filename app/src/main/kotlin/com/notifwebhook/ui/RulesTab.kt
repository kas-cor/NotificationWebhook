package com.notifwebhook.ui

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifwebhook.AppPrefs
import com.notifwebhook.ExclusionRule

/** Поля правил исключения: значение в prefs + подпись. */
private val RULE_FIELDS = listOf(
    "title" to "Заголовок (title)",
    "text" to "Текст (text)",
    "app_name" to "Имя приложения (app_name)",
    "app_package" to "Пакет (app_package)"
)

private fun fieldLabel(field: String): String = when (field) {
    "title" -> "Заголовок"
    "text" -> "Текст"
    "app_name" -> "Имя приложения"
    "app_package" -> "Пакет"
    else -> field
}

@Composable
fun RulesTab(prefs: AppPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var rules by remember { mutableStateOf(prefs.getExclusionRules()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun deleteRule(ruleId: String) {
        prefs.removeExclusionRule(ruleId)
        rules = prefs.getExclusionRules()
    }

    fun addRule(field: String, pattern: String) {
        prefs.addExclusionRule(ExclusionRule(field = field, pattern = pattern))
        rules = prefs.getExclusionRules()
        context.toast("✓ Правило добавлено")
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
        item(key = "header") {
            SectionHeader("ПРАВИЛА ИСКЛЮЧЕНИЙ")
            Text(
                text = "Уведомления, где в выбранном поле найдено совпадение, будут пропущены",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (rules.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Правил пока нет — пересылаются все уведомления",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                RuleRow(
                    field = fieldLabel(rule.field),
                    pattern = rule.pattern,
                    onDelete = { deleteRule(rule.id) }
                )
            }
        }

        item(key = "add") {
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Добавить правило")
            }
        }
    }
}

/** Строка правила исключения (замена item_rule.xml + ExclusionRulesAdapter). */
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
                contentDescription = "Удалить правило",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Диалог добавления правила (замена dialog_add_rule.xml). */
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
        title = { Text("Добавить правило исключения") },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = RULE_FIELDS[fieldIndex].second,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Поле для поиска") },
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
                        RULE_FIELDS.forEachIndexed { index, (_, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
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
                    label = { Text("Текст для поиска") },
                    singleLine = true,
                    isError = patternError,
                    supportingText = if (patternError) {
                        { Text("Введите текст для поиска") }
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
            }) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
