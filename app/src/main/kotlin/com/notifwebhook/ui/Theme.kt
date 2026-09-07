package com.notifwebhook.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.notifwebhook.R

/**
 * Compose-тема, повторяющая цвета Material3 из res/values/colors.xml
 * (светлая + тёмная схемы Theme Builder).
 */
@Composable
fun NotifWebhookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors() else LightColors()
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun LightColors() = lightColorScheme(
    primary = colorResource(R.color.md_theme_primary),
    onPrimary = colorResource(R.color.md_theme_on_primary),
    primaryContainer = colorResource(R.color.md_theme_primary_container),
    onPrimaryContainer = colorResource(R.color.md_theme_on_primary_container),
    secondary = colorResource(R.color.md_theme_secondary),
    onSecondary = colorResource(R.color.md_theme_on_secondary),
    secondaryContainer = colorResource(R.color.md_theme_secondary_container),
    onSecondaryContainer = colorResource(R.color.md_theme_on_secondary_container),
    tertiary = colorResource(R.color.md_theme_tertiary),
    onTertiary = colorResource(R.color.md_theme_on_tertiary),
    tertiaryContainer = colorResource(R.color.md_theme_tertiary_container),
    onTertiaryContainer = colorResource(R.color.md_theme_on_tertiary_container),
    error = colorResource(R.color.md_theme_error),
    onError = colorResource(R.color.md_theme_on_error),
    errorContainer = colorResource(R.color.md_theme_error_container),
    onErrorContainer = colorResource(R.color.md_theme_on_error_container),
    background = colorResource(R.color.md_theme_background),
    onBackground = colorResource(R.color.md_theme_on_background),
    surface = colorResource(R.color.md_theme_surface),
    onSurface = colorResource(R.color.md_theme_on_surface),
    surfaceVariant = colorResource(R.color.md_theme_surface_variant),
    onSurfaceVariant = colorResource(R.color.md_theme_on_surface_variant),
    outline = colorResource(R.color.md_theme_outline),
    outlineVariant = colorResource(R.color.md_theme_outline_variant)
)

@Composable
private fun DarkColors() = darkColorScheme(
    primary = colorResource(R.color.md_theme_primary_dark),
    onPrimary = colorResource(R.color.md_theme_on_primary_dark),
    primaryContainer = colorResource(R.color.md_theme_primary_container_dark),
    onPrimaryContainer = colorResource(R.color.md_theme_on_primary_container_dark),
    secondary = colorResource(R.color.md_theme_secondary_dark),
    onSecondary = colorResource(R.color.md_theme_on_secondary_dark),
    secondaryContainer = colorResource(R.color.md_theme_secondary_container_dark),
    onSecondaryContainer = colorResource(R.color.md_theme_on_secondary_container_dark),
    tertiary = colorResource(R.color.md_theme_tertiary_dark),
    onTertiary = colorResource(R.color.md_theme_on_tertiary_dark),
    tertiaryContainer = colorResource(R.color.md_theme_tertiary_container_dark),
    onTertiaryContainer = colorResource(R.color.md_theme_on_tertiary_container_dark),
    error = colorResource(R.color.md_theme_error_dark),
    onError = colorResource(R.color.md_theme_on_error_dark),
    errorContainer = colorResource(R.color.md_theme_error_container_dark),
    onErrorContainer = colorResource(R.color.md_theme_on_error_container_dark),
    background = colorResource(R.color.md_theme_background_dark),
    onBackground = colorResource(R.color.md_theme_on_background_dark),
    surface = colorResource(R.color.md_theme_surface_dark),
    onSurface = colorResource(R.color.md_theme_on_surface_dark),
    surfaceVariant = colorResource(R.color.md_theme_surface_variant_dark),
    onSurfaceVariant = colorResource(R.color.md_theme_on_surface_variant_dark),
    outline = colorResource(R.color.md_theme_outline_dark),
    outlineVariant = colorResource(R.color.md_theme_outline_variant_dark)
)
