package com.aster.files.support

import com.aster.files.data.AuditEntry
import com.aster.files.services.AuditLogger
import java.util.concurrent.CopyOnWriteArrayList

/** JVM 测试用审计记录器。 */
class RecordingAuditLogger : AuditLogger {
    private val storage = CopyOnWriteArrayList<AuditEntry>()

    val entries: List<AuditEntry> get() = storage.toList()

    val lastOrNull: AuditEntry? get() = storage.lastOrNull()

    override suspend fun log(entry: AuditEntry) {
        storage.add(entry)
    }

    fun clear() {
        storage.clear()
    }
}
