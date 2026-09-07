package com.notifwebhook.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notifwebhook.AppPrefs
import com.notifwebhook.R

/** Вкладки нижней навигации. */
enum class MainTab(val labelRes: Int, val icon: ImageVector) {
    Home(R.string.tab_home, Icons.Filled.Home),
    Exclusions(R.string.tab_exclusions, Icons.Filled.Apps),
    History(R.string.tab_history, Icons.Filled.History),
    Settings(R.string.tab_settings, Icons.Filled.Settings)
}

@Composable
fun MainScreen(
    prefs: AppPrefs,
    themeMode: String,
    locale: String,
    onThemeChange: (String) -> Unit,
    onLocaleChange: (String) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                MainTab.entries.forEachIndexed { index, tab ->
                    val selected = index == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (MainTab.entries[selectedTab]) {
            MainTab.Home -> HomeTab(prefs, contentModifier)
            MainTab.Exclusions -> ExclusionsTab(prefs, contentModifier)
            MainTab.History -> HistoryTab(prefs, contentModifier)
            MainTab.Settings -> SettingsTab(
                prefs = prefs,
                modifier = contentModifier,
                themeMode = themeMode,
                locale = locale,
                onThemeChange = onThemeChange,
                onLocaleChange = onLocaleChange
            )
        }
    }
}
