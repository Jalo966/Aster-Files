package com.aster.files.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 主题色与日/夜模式解耦。
 *
 * lightPrimary/lightContainer 用于日间 ColorScheme；darkPrimary/darkContainer 用于
 * 夜间 ColorScheme，优先保证主色与文字对比度，而不是直接复用同一个色值。
 */
enum class ThemeColor(
    val label: String,
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color
) {
    DEFAULT(
        "默认(黑白)",
        Color(0xFF1F1F1F),
        Color(0xFFE8E8E8),
        Color(0xFFF0F0F0),
        Color(0xFF3A3A3A)
    ),
    LIGHT_BLUE(
        "浅蓝",
        Color(0xFF2F6FB2),
        Color(0xFFD6E7F8),
        Color(0xFF9CC7EE),
        Color(0xFF24405A)
    ),
    DEEP_BLUE(
        "深蓝",
        Color(0xFF1C3F94),
        Color(0xFFD7E0FA),
        Color(0xFFA9BCF5),
        Color(0xFF27345C)
    ),
    PURPLE(
        "紫色",
        Color(0xFF7B3FA1),
        Color(0xFFEBDDF5),
        Color(0xFFD5B3EC),
        Color(0xFF3D294C)
    ),
    RED(
        "红色",
        Color(0xFFC62828),
        Color(0xFFF8D7D7),
        Color(0xFFFFB4AB),
        Color(0xFF5C1F1F)
    ),
    VERMILION(
        "朱红",
        Color(0xFFD8563C),
        Color(0xFFF9DCD4),
        Color(0xFFFFB59F),
        Color(0xFF5C2A1E)
    ),
    PINK(
        "粉色",
        Color(0xFFC2185B),
        Color(0xFFF8D7E3),
        Color(0xFFF6A8C5),
        Color(0xFF5A2338)
    ),
    LIGHT_GREEN(
        "浅绿",
        Color(0xFF3C8D5A),
        Color(0xFFD8EEDD),
        Color(0xFFA5D6B5),
        Color(0xFF244531)
    )
}

fun ThemeColor.swatchColor(dark: Boolean): Color =
    if (dark) darkPrimary else lightPrimary

/** 应用主题偏好；SharedPreferences 不属于业务数据库。 */
class ThemePreference(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableMode = MutableStateFlow(readMode())
    private val mutableColor = MutableStateFlow(readColor())

    val mode: StateFlow<ThemeMode> = mutableMode.asStateFlow()
    val color: StateFlow<ThemeColor> = mutableColor.asStateFlow()

    fun setMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
        mutableMode.value = mode
    }

    fun setColor(color: ThemeColor) {
        preferences.edit().putString(KEY_COLOR, color.name).apply()
        mutableColor.value = color
    }

    private fun readMode(): ThemeMode {
        val stored = preferences.getString(KEY_THEME, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    private fun readColor(): ThemeColor {
        val stored = preferences.getString(KEY_COLOR, ThemeColor.DEFAULT.name)
        return runCatching { ThemeColor.valueOf(stored ?: ThemeColor.DEFAULT.name) }
            .getOrDefault(ThemeColor.DEFAULT)
    }

    private companion object {
        const val PREFERENCES_NAME = "aster_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_COLOR = "theme_color"
    }
}
