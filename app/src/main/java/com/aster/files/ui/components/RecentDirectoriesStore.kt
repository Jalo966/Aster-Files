package com.aster.files.ui.components

import android.content.Context

/** 目标目录面板的最近访问记录，最多保留 6 条。 */
class RecentDirectoriesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "aster_recent_directories",
        Context.MODE_PRIVATE
    )

    fun paths(): List<String> {
        return preferences.getString(KEY_PATHS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    fun record(path: String): List<String> {
        val next = (listOf(path) + paths())
            .distinct()
            .take(MAX_ENTRIES)
        preferences.edit().putString(KEY_PATHS, next.joinToString("\n")).apply()
        return next
    }

    companion object {
        private const val KEY_PATHS = "paths"
        private const val MAX_ENTRIES = 6
    }
}
