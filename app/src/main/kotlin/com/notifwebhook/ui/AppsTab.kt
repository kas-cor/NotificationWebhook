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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifwebhook.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Установленное приложение с заранее загруженной иконкой. */
private data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap
)

@Composable
fun AppsTab(prefs: AppPrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selected by remember { mutableStateOf(prefs.allowedApps) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    ListTabCard(modifier = modifier.padding(16.dp)) {
        item(key = "header") {
            SectionHeader("ПРИЛОЖЕНИЯ")
            Text(
                text = "Выберите приложения для пересылки. Пусто = все приложения",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (apps.isEmpty()) {
            item(key = "loading") {
                Text(
                    text = "Загрузка...",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                val checked = app.packageName in selected
                AppRow(
                    app = app,
                    checked = checked,
                    onToggle = {
                        val newSelected = if (it) selected + app.packageName
                        else selected - app.packageName
                        selected = newSelected
                        prefs.allowedApps = newSelected
                    }
                )
            }
        }
    }
}

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
        Column(
            modifier = Modifier.weight(1f)
        ) {
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
            // Иконка может отсутствовать (или загрузка падать) — такие приложения пропускаем
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
