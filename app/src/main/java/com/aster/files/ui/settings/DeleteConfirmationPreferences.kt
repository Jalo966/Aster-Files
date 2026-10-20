package com.aster.files.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 普通删除是否先弹确认；回收站彻底删除/清空不受此开关影响。 */
class DeleteConfirmationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "aster_delete_settings",
        Context.MODE_PRIVATE
    )
    private val mutableEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_CONFIRM, true)
    )
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CONFIRM, enabled).apply()
        mutableEnabled.value = enabled
    }

    private companion object {
        const val KEY_CONFIRM = "confirm_before_delete"
    }
}
