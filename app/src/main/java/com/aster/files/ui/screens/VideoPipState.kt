package com.aster.files.ui.screens

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object VideoPipState {
    @Volatile var screenOpen = false
    @Volatile var playing = false
    @Volatile var aspectRatio = 16f / 9f
    var inPip by mutableStateOf(false)

    fun params(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val ratio = if (aspectRatio > 0f) aspectRatio else 16f / 9f
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .build()
    }
}
