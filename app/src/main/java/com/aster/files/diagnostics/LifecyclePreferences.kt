package com.aster.files.diagnostics

import android.content.Context

/** 仅保存退到后台的时间戳，用于 >1 小时后触发审计清理。 */
class LifecyclePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "aster_lifecycle",
        Context.MODE_PRIVATE
    )

    fun backgroundedAt(): Long = preferences.getLong(KEY_BACKGROUNDED_AT, 0L)

    fun markBackgrounded(atMillis: Long) {
        preferences.edit().putLong(KEY_BACKGROUNDED_AT, atMillis).apply()
    }

    fun clearBackgroundedAt() {
        preferences.edit().remove(KEY_BACKGROUNDED_AT).apply()
    }

    private companion object {
        const val KEY_BACKGROUNDED_AT = "backgrounded_at"
    }
}
