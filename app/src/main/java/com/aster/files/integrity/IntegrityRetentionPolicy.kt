package com.aster.files.integrity

/** 完整性任务历史保留策略：自动清理 3 天前完成/取消的任务。 */
object IntegrityRetentionPolicy {
    const val AUTO_CLEAN_DAYS = 3
    const val DAY_MILLIS = 86_400_000L

    fun cutoff(nowMillis: Long): Long = nowMillis - AUTO_CLEAN_DAYS * DAY_MILLIS
}
