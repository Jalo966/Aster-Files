package com.aster.files.support

import com.aster.files.security.GuardFileStat
import com.aster.files.security.GuardFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * JVM 单元测试使用的 GuardFileSystem。
 *
 * 只提供 stat/lstat 数据，路径仲裁仍由生产代码 FileGuard 完成。
 * 生产 Android 构建不会包含本类（位于 src/test）。
 */
class JvmGuardFileSystem : GuardFileSystem {

    override fun stat(file: File): GuardFileStat? = read(file, follow = true)

    override fun lstat(file: File): GuardFileStat? = read(file, follow = false)

    private fun read(file: File, follow: Boolean): GuardFileStat? {
        val path = file.toPath()
        return try {
            val attributes = if (follow) {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } else {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS
                )
            }
            GuardFileStat(
                identity = attributes.fileKey()
                    ?: path.toAbsolutePath().normalize().toString(),
                isDirectory = attributes.isDirectory,
                isRegularFile = attributes.isRegularFile,
                isSymbolicLink = attributes.isSymbolicLink,
                size = attributes.size(),
                modifiedAtNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
            )
        } catch (e: NoSuchFileException) {
            null
        } catch (e: NotDirectoryException) {
            null
        }
    }
}
