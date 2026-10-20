package com.aster.files.diagnostics

/** 审计日志保留策略：最多 1000 条且最多 7 天。 */
object AuditRetentionPolicy {
    const val MAX_ENTRIES = 1000
    const val MAX_AGE_DAYS = 7
    const val BACKGROUND_CLEAN_THRESHOLD_MILLIS = 60L * 60L * 1000L
    const val DAY_MILLIS = 86_400_000L

    fun cutoff(nowMillis: Long): Long = nowMillis - MAX_AGE_DAYS * DAY_MILLIS

    fun shouldCleanAfterBackground(
        nowMillis: Long,
        backgroundedAtMillis: Long
    ): Boolean {
        return backgroundedAtMillis > 0L &&
            nowMillis - backgroundedAtMillis >= BACKGROUND_CLEAN_THRESHOLD_MILLIS
    }
}
