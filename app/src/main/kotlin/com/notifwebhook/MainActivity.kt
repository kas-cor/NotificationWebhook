package com.notifwebhook

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notifwebhook.ui.MainScreen
import com.notifwebhook.ui.NotifWebhookTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs.get(this)
        // Применяем сохранённую локаль (если выбрана не системная)
        prefs.locale.takeIf { it.isNotEmpty() }?.let { applyLocale(it) }

        var themeMode by mutableStateOf(prefs.themeMode)
        var locale by mutableStateOf(prefs.locale)

        setContent {
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemDark()
            }
            NotifWebhookTheme(darkTheme = darkTheme) {
                MainScreen(
                    prefs = prefs,
                    themeMode = themeMode,
                    locale = locale,
                    onThemeChange = {
                        themeMode = it
                        prefs.themeMode = it
                    },
                    onLocaleChange = {
                        locale = it
                        prefs.locale = it
                        recreate()
                    }
                )
            }
        }
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyLocale(lang: String) {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
