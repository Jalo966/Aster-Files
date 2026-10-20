package com.aster.files.support

import com.aster.files.data.TrashEntry
import com.aster.files.data.TrashStatus
import com.aster.files.services.TrashStore

/** JVM 测试用回收站元数据存储。 */
class InMemoryTrashStore : TrashStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, TrashEntry>()

    override suspend fun insert(entry: TrashEntry) {
        synchronized(lock) {
            entries[entry.trashId] = entry
        }
    }

    override suspend fun get(trashId: String): TrashEntry? = synchronized(lock) {
        entries[trashId]
    }

    override suspend fun listActive(): List<TrashEntry> = synchronized(lock) {
        entries.values
            .filter { it.status == TrashStatus.ACTIVE }
            .sortedByDescending { it.deletedAt }
    }

    override suspend fun findActiveByOriginalPath(originalPath: String): TrashEntry? =
        synchronized(lock) {
            entries.values.firstOrNull {
                it.originalPath == originalPath && it.status == TrashStatus.ACTIVE
            }
        }

    override suspend fun markRestored(trashId: String, restoredAt: Long): Boolean =
        synchronized(lock) {
            val entry = entries[trashId] ?: return@synchronized false
            if (entry.status != TrashStatus.ACTIVE) return@synchronized false
            entries[trashId] = entry.copy(
                status = TrashStatus.RESTORED,
                restoredAt = restoredAt
            )
            true
        }

    override suspend fun delete(trashId: String): Boolean = synchronized(lock) {
        entries.remove(trashId) != null
    }

    fun snapshot(): List<TrashEntry> = synchronized(lock) { entries.values.toList() }
}
