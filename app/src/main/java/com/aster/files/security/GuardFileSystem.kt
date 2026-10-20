package com.aster.files.security

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.IOException

/**
 * FileGuard 使用的文件状态后端。
 *
 * 只提供 stat/lstat 结果，不包含任何路径仲裁逻辑；路径安全判断全部在
 * FileGuard 中完成。生产环境使用 [AndroidGuardFileSystem]，JVM 单元测试
 * 使用 java.nio.file 的实现。
 */
interface GuardFileSystem {
    /** 跟随最终符号链接读取状态；不存在返回 null。 */
    @Throws(IOException::class)
    fun stat(file: File): GuardFileStat?

    /** 不跟随最终符号链接读取状态；不存在返回 null。 */
    @Throws(IOException::class)
    fun lstat(file: File): GuardFileStat?
}

/** 平台无关的文件状态快照。 */
data class GuardFileStat(
    val identity: Any?,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val isSymbolicLink: Boolean,
    val size: Long,
    val modifiedAtNanos: Long
)

/** 生产环境实现：Android Os.stat / Os.lstat。 */
object AndroidGuardFileSystem : GuardFileSystem {

    override fun stat(file: File): GuardFileStat? = read(file, follow = true)

    override fun lstat(file: File): GuardFileStat? = read(file, follow = false)

    private fun read(file: File, follow: Boolean): GuardFileStat? = try {
        val stat = if (follow) Os.stat(file.absolutePath) else Os.lstat(file.absolutePath)
        stat.toGuardFileStat()
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) {
            null
        } else {
            throw IOException("无法读取文件状态：${file.absolutePath}", e)
        }
    }

    private fun StructStat.toGuardFileStat(): GuardFileStat = GuardFileStat(
        identity = AndroidFileIdentity(st_dev, st_ino),
        isDirectory = OsConstants.S_ISDIR(st_mode),
        isRegularFile = OsConstants.S_ISREG(st_mode),
        isSymbolicLink = OsConstants.S_ISLNK(st_mode),
        size = st_size,
        modifiedAtNanos = st_mtim.tv_sec * 1_000_000_000L + st_mtim.tv_nsec
    )
}

/** dev + inode 是 Android/Linux 上稳定的文件身份。 */
private data class AndroidFileIdentity(val device: Long, val inode: Long)
