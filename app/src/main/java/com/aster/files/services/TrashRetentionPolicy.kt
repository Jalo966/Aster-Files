package com.aster.files.services

/** 回收站保留期计算；独立纯函数便于 JVM 测试与启动清理复用。 */
object TrashRetentionPolicy {
    const val DEFAULT_DAYS = 30
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    fun cutoff(nowMillis: Long, retentionDays: Int): Long {
        require(retentionDays > 0) { "retentionDays 必须大于 0" }
        return nowMillis - retentionDays.toLong() * DAY_MILLIS
    }

    fun isExpired(deletedAtMillis: Long, nowMillis: Long, retentionDays: Int): Boolean =
        deletedAtMillis <= cutoff(nowMillis, retentionDays)
}
