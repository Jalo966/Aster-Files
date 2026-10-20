package com.aster.files.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightBackground = Color(0xFFF7F4EC)
private val LightSurface = Color(0xFFFFFDF7)
private val LightSurfaceVariant = Color(0xFFEDE7D8)
private val LightOnBackground = Color(0xFF1B1C18)
private val LightOnSurfaceVariant = Color(0xFF3D5E54)
private val LightOutline = Color(0xFFD9D2C1)

private val DarkBackground = Color(0xFF171B1A)
private val DarkSurface = Color(0xFF202624)
private val DarkSurfaceVariant = Color(0xFF2B3430)
private val DarkOnBackground = Color(0xFFE1E5E0)
private val DarkOnSurfaceVariant = Color(0xFFBCC9C2)
private val DarkOutline = Color(0xFF6F7E77)

@Composable
fun AsterTheme(
    mode: ThemeMode,
    themeColor: ThemeColor = ThemeColor.DEFAULT,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = remember(dark, themeColor) {
        buildColorScheme(dark, themeColor)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

private fun buildColorScheme(dark: Boolean, color: ThemeColor) =
    if (dark) {
        darkColorScheme(
            primary = color.darkPrimary,
            onPrimary = DarkBackground,
            primaryContainer = color.darkContainer,
            onPrimaryContainer = Color(0xFFF0F4F1),
            secondary = color.darkPrimary,
            onSecondary = DarkBackground,
            secondaryContainer = color.darkContainer,
            onSecondaryContainer = Color(0xFFF0F4F1),
            tertiary = color.darkPrimary,
            onTertiary = DarkBackground,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnBackground,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
    } else {
        lightColorScheme(
            primary = color.lightPrimary,
            onPrimary = Color.White,
            primaryContainer = color.lightContainer,
            onPrimaryContainer = Color(0xFF15201C),
            secondary = color.lightPrimary,
            onSecondary = Color.White,
            secondaryContainer = color.lightContainer,
            onSecondaryContainer = Color(0xFF15201C),
            tertiary = color.lightPrimary,
            onTertiary = Color.White,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnBackground,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = LightOutline,
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }
