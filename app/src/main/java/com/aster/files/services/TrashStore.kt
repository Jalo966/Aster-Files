package com.aster.files.services

import com.aster.files.data.Repository
import com.aster.files.data.TrashEntry
import com.aster.files.data.TrashStatus

/** 回收站元数据存储抽象；生产实现走 Repository，JVM 测试使用内存实现。 */
interface TrashStore {
    suspend fun insert(entry: TrashEntry)
    suspend fun get(trashId: String): TrashEntry?
    suspend fun listActive(): List<TrashEntry>
    suspend fun findActiveByOriginalPath(originalPath: String): TrashEntry?
    suspend fun markRestored(trashId: String, restoredAt: Long): Boolean
    suspend fun delete(trashId: String): Boolean
}

/** 生产环境：Repository + trash_entries 表。 */
class RepositoryTrashStore(private val repository: Repository) : TrashStore {
    override suspend fun insert(entry: TrashEntry) {
        repository.insertTrashEntry(entry)
    }

    override suspend fun get(trashId: String): TrashEntry? =
        repository.getTrashEntry(trashId)

    override suspend fun listActive(): List<TrashEntry> =
        repository.listTrashEntries(TrashStatus.ACTIVE)

    override suspend fun findActiveByOriginalPath(originalPath: String): TrashEntry? =
        repository.findActiveTrashByOriginalPath(originalPath)

    override suspend fun markRestored(trashId: String, restoredAt: Long): Boolean =
        repository.restoreTrashEntry(trashId, restoredAt)

    override suspend fun delete(trashId: String): Boolean =
        repository.deleteTrashEntry(trashId)
}
