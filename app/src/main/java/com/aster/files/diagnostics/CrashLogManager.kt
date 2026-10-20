package com.aster.files.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import kotlin.system.exitProcess

/** 全局未捕获异常入口；仅崩溃时写文件，不记录普通日志。 */
object CrashLogManager {
    @Volatile
    private var store: CrashLogStore? = null

    @Synchronized
    fun install(context: Context) {
        if (store != null) return
        val appContext = context.applicationContext
        val crashStore = CrashLogStore(
            directory = File(appContext.filesDir, "debug_logs"),
            deviceInfo = Build.MANUFACTURER + " " + Build.MODEL + " / API " + Build.VERSION.SDK_INT
        )
        store = crashStore
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashStore.writeCrash(thread.name, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun store(context: Context): CrashLogStore {
        install(context)
        return store!!
    }
}
