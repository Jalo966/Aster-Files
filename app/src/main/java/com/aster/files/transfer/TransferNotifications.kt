package com.aster.files.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object TransferNotifications {
    private const val CHANNEL_ID = "aster_transfers"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "网络传输",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "网络文件上传与下载完成提醒" }
            )
        }
    }

    fun completed(task: TransferTask) {
        val context = appContext ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(task.direction + "完成")
            .setContentText(task.name)
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(task.id.hashCode(), notification)
        }
    }
}
