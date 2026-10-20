package com.aster.files

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import com.aster.files.diagnostics.AuditRetentionPolicy
import com.aster.files.transfer.TransferNotifications
import com.aster.files.diagnostics.CrashLogManager
import com.aster.files.diagnostics.LifecyclePreferences
import com.aster.files.image.ApngDecoderFactory
import com.aster.files.integrity.IntegrityRetentionPolicy
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 容器在进程启动时创建；恢复中断任务与容器首次初始化在 IO 作用域执行，
 * 避免主线程磁盘访问。恢复失败不会阻止应用启动。
 */
class AsterApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var lifecyclePreferences: LifecyclePreferences
    private var startedActivities = 0

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { OkHttpClient() }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024L * 1024L)
                    .build()
            }
            .components {
                add(ApngDecoderFactory())
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        TransferNotifications.init(this)
        com.aster.files.transfer.TransferCenter.init(this)
        if (BuildConfig.ENABLE_CRASH_LOG) {
            CrashLogManager.install(this)
        }
        container = AppContainer(this)
        lifecyclePreferences = LifecyclePreferences(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities == 0) {
                    val now = System.currentTimeMillis()
                    if (AuditRetentionPolicy.shouldCleanAfterBackground(
                            now,
                            lifecyclePreferences.backgroundedAt()
                        )
                    ) {
                        applicationScope.launch {
                            runCatching { container.repository.cleanupAuditEntries(now) }
                        }
                    }
                    lifecyclePreferences.clearBackgroundedAt()
                }
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    lifecyclePreferences.markBackgrounded(System.currentTimeMillis())
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        applicationScope.launch {
            try {
                container.warmUp()
                container.manualIntegrityService.recoverInterruptedTasks()
                container.integrityActionHandler.recoverInterruptedActions()
                container.integrityStore.cleanupFinishedTasksBefore(
                    IntegrityRetentionPolicy.cutoff(System.currentTimeMillis())
                )
                container.trashService.cleanupExpired(
                    container.trashRetentionPreferences.days.value
                )
                container.repository.cleanupAuditEntries(System.currentTimeMillis())
                lifecyclePreferences.clearBackgroundedAt()
            } finally {
                container.markReady()
            }
        }
    }
}
