package com.aster.files.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 回收站保留天数；只允许 30/60/90，默认 30。 */
class TrashRetentionPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableDays = MutableStateFlow(read())
    val days: StateFlow<Int> = mutableDays.asStateFlow()

    fun setDays(days: Int) {
        require(days in OPTIONS) { "不支持的回收站保留天数" }
        preferences.edit().putInt(KEY_DAYS, days).apply()
        mutableDays.value = days
    }

    private fun read(): Int {
        val value = preferences.getInt(KEY_DAYS, DEFAULT_DAYS)
        return if (value in OPTIONS) value else DEFAULT_DAYS
    }

    companion object {
        const val DEFAULT_DAYS = 30
        val OPTIONS = listOf(30, 60, 90)
        private const val PREFERENCES_NAME = "aster_trash_retention"
        private const val KEY_DAYS = "retention_days"
    }
}
