package com.aster.files.security

import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 路径安全闸门的稳定错误码。
 *
 * code 用于服务层、审计日志与 UI 判断；messageZh 是面向用户的中文提示。
 * 任何新增错误都必须保持 code 稳定，不得复用旧 code 表达新语义。
 */
enum class GuardErrorCode(val code: String, val messageZh: String) {
    EMPTY_PATH("EMPTY_PATH", "路径不能为空"),
    INVALID_PATH("INVALID_PATH", "路径格式非法"),
    NOT_ABSOLUTE("NOT_ABSOLUTE", "必须使用绝对路径"),
    PATH_TRAVERSAL("PATH_TRAVERSAL", "路径不得包含 .. 片段"),
    INVALID_NAME("INVALID_NAME", "名称非法"),
    RESERVED_NAME("RESERVED_NAME", "名称为系统保留名称"),
    PATH_SEPARATOR("PATH_SEPARATOR", "名称不得包含路径分隔符"),
    OUTSIDE_ROOT("OUTSIDE_ROOT", "路径超出可访问根目录"),
    INTERNAL_PATH_FORBIDDEN("INTERNAL_PATH_FORBIDDEN", "应用内部路径不可访问"),
    SOURCE_NOT_FOUND("SOURCE_NOT_FOUND", "源路径不存在"),
    ALREADY_EXISTS("ALREADY_EXISTS", "目标已存在"),
    PARENT_NOT_FOUND("PARENT_NOT_FOUND", "父目录不存在"),
    NOT_DIRECTORY("NOT_DIRECTORY", "目标不是目录"),
    NOT_FILE("NOT_FILE", "目标不是文件"),
    SYMLINK_FORBIDDEN("SYMLINK_FORBIDDEN", "不允许对符号链接执行该操作"),
    ROOT_OPERATION_FORBIDDEN("ROOT_OPERATION_FORBIDDEN", "存储根目录不可执行该操作"),
    SELF_REFERENCE("SELF_REFERENCE", "目标不能位于源目录内部"),
    PARENT_CHANGED("PARENT_CHANGED", "父目录已发生变化，操作已中止"),
    PATH_CHANGED("PATH_CHANGED", "路径已发生变化，操作已中止"),
    UNSUPPORTED_FILE_TYPE("UNSUPPORTED_FILE_TYPE", "不支持的文件类型"),
    IO_ERROR("IO_ERROR", "文件系统访问失败"),
    ROOT_UNAVAILABLE("ROOT_UNAVAILABLE", "存储根目录不可用")
}

/**
 * FileGuard 抛出的唯一异常类型。
 *
 * @property code 稳定错误码枚举
 * @property path 触发错误的路径（可能为空）
 * @property detail 补充说明（中文）
 * @property stableCode 便于日志与 UI 直接使用的字符串错误码
 */
class FileGuardException(
    val code: GuardErrorCode,
    val path: String? = null,
    val detail: String? = null,
    cause: Throwable? = null
) : IOException(
    buildString {
        append(code.messageZh)
        if (!detail.isNullOrBlank()) {
            append("：").append(detail)
        }
        if (!path.isNullOrBlank()) {
            append("（").append(path).append("）")
        }
    },
    cause
) {
    val stableCode: String get() = code.code
}

/** 路径所属的根类型；内部根只允许服务层使用，不进入可浏览根。 */
internal enum class RootScope {
    BROWSABLE,
    INTERNAL
}

/**
 * lstat/stat 结果的不可变快照，用于第二次校验时发现路径变化。
 *
 * identity 是平台相关的文件身份对象：Android 使用 dev+ino，JVM 单元测试
 * 使用 BasicFileAttributes.fileKey。
 */
internal data class PathSnapshot(
    val identity: Any?,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val size: Long,
    val modifiedAtNanos: Long
) {
    fun sameIdentity(stat: GuardFileStat): Boolean =
        identity == stat.identity &&
            isDirectory == stat.isDirectory &&
            isRegularFile == stat.isRegularFile

    fun sameState(stat: GuardFileStat): Boolean =
        sameIdentity(stat) &&
            size == stat.size &&
            modifiedAtNanos == stat.modifiedAtNanos

    companion object {
        fun from(stat: GuardFileStat): PathSnapshot = PathSnapshot(
            identity = stat.identity,
            isDirectory = stat.isDirectory,
            isRegularFile = stat.isRegularFile,
            size = stat.size,
            modifiedAtNanos = stat.modifiedAtNanos
        )
    }
}

/**
 * 已通过 FileGuard 校验的现有路径。
 *
 * [file] 是 canonical path 对应的 File；[requestedPath] 保留用户传入的原始路径，
 * 便于在执行前再次 realpath + lstat 校验。
 */
class GuardedPath internal constructor(
    val file: File,
    val requestedPath: String,
    val canonicalPath: String,
    val isSymlink: Boolean,
    val isDirectory: Boolean,
    internal val snapshot: PathSnapshot,
    internal val scope: RootScope
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = snapshot.size
    val modifiedAt: Long get() = snapshot.modifiedAtNanos / 1_000_000L
    val isInternalPath: Boolean get() = scope == RootScope.INTERNAL

    override fun toString(): String =
        "GuardedPath(canonical=$canonicalPath, symlink=$isSymlink, directory=$isDirectory)"
}

/** 新建一个路径的计划：父目录已解析真实路径，目标尚不存在。 */
class CreationPlan internal constructor(
    val parentRequestedPath: String,
    val parent: File,
    val canonicalParentPath: String,
    val name: String,
    val target: File,
    internal val parentSnapshot: PathSnapshot,
    internal val scope: RootScope
) {
    val targetPath: String get() = target.path
    val isInternalPath: Boolean get() = scope == RootScope.INTERNAL
}

/** 重命名计划。source 与 target 在执行前都必须再次校验。 */
class RenamePlan internal constructor(
    val source: GuardedPath,
    val target: CreationPlan
)

/** 移动计划。source 与 target 在执行前都必须再次校验。 */
class MovePlan internal constructor(
    val source: GuardedPath,
    val target: CreationPlan
)

/** 复制计划。source 与 target 在执行前都必须再次校验。 */
class CopyPlan internal constructor(
    val source: GuardedPath,
    val target: CreationPlan
)

/**
 * 所有文件操作的唯一路径仲裁者。
 *
 * 安全策略（对应需求文档第 5 节）：
 * 1. 拒绝空路径、NUL、非法名称、分隔符、保留名、尾随点/空格；
 * 2. 拒绝路径中的 .. 片段；
 * 3. 只接受绝对路径；
 * 4. canonical path 必须位于根白名单内；
 * 5. 用 Os.lstat 检测最终组件是否为符号链接；
 * 6. 变更操作拒绝最终符号链接；父级链接只有在真实路径仍位于根内时才允许；
 * 7. 新建路径解析父目录真实路径并快照父目录 inode，写入前再次确认；
 * 8. 执行前再次 realpath + lstat，发现变化立即中止；
 * 9. 存储根不可删除、复制、移动或重命名；
 * 10. 目标已存在时拒绝覆盖；
 * 11. 所有错误都带中文提示与稳定错误码。
 *
 * 路径仲裁逻辑完全位于本类；[GuardFileSystem] 只负责提供 stat/lstat 结果。
 * 生产环境默认使用 Android 的 Os.stat/Os.lstat；JVM 单元测试注入基于
 * java.nio.file 的实现，因此不会把安全逻辑复制到测试里。
 *
 * 内部根（internalRoots）用于应用私有 .trash，不进入可浏览根；
 * 只有显式的 Internal API 才能访问，避免普通文件服务误触。
 *
 * 已知边界：Java/Android 文件 API 没有 openat2 的目录 fd 语义，因此这里采用
 * “canonical 校验 + lstat 快照 + 执行前二次校验”降低 TOCTOU 风险。
 * 本项目定位为单用户本地应用。
 */
class FileGuard(
    roots: List<File> = defaultRoots(),
    internalRoots: List<File> = emptyList(),
    private val fileSystem: GuardFileSystem = AndroidGuardFileSystem
) {

    private data class RootEntry(val canonical: File, val canonicalPath: String)

    private val rootEntries: List<RootEntry>
    private val internalRootEntries: List<RootEntry>

    /** 主存储根（canonical）。 */
    val root: File

    /** 主存储根的 canonical path。 */
    val rootPath: String get() = root.path

    /** 可浏览根白名单中的全部 canonical path。 */
    val allowedRootPaths: List<String> get() = rootEntries.map { it.canonicalPath }

    /** 应用私有内部根（.trash）的 canonical path，不对外浏览。 */
    val internalRootPaths: List<String> get() = internalRootEntries.map { it.canonicalPath }

    init {
        if (roots.isEmpty()) {
            throw FileGuardException(GuardErrorCode.ROOT_UNAVAILABLE, detail = "未配置可访问根目录")
        }
        rootEntries = buildRootEntries(roots, "根目录")
        internalRootEntries = buildRootEntries(internalRoots, "内部根目录")
        root = rootEntries.first().canonical
    }

    // ---------------------------------------------------------------------
    // 公共校验入口（可浏览根）
    // ---------------------------------------------------------------------

    /**
     * 校验一个用于读取/浏览的现有路径。
     *
     * 最终组件允许是符号链接，但 canonical path 仍必须位于根白名单内。
     */
    fun guardForRead(path: String): GuardedPath =
        resolvePath(validatePath(path), allowFinalSymlink = true, scope = RootScope.BROWSABLE)

    /**
     * 校验一个用于变更操作（删除、重命名、移动、复制源）的现有路径。
     *
     * 拒绝最终符号链接，拒绝存储根本身。
     */
    fun guardForWrite(path: String): GuardedPath = guardForWriteIn(path, RootScope.BROWSABLE)

    /**
     * 校验一个用于复制/移动目标的现有目录。
     *
     * 允许最终组件为符号链接（父级链接），但真实路径必须在根内。
     */
    fun guardTargetDirectory(path: String): GuardedPath =
        guardTargetDirectoryIn(path, RootScope.BROWSABLE)

    /** 校验一个用于读取的普通文件。 */
    fun guardReadableFile(path: String): GuardedPath =
        guardReadableFileIn(path, RootScope.BROWSABLE)

    // ---------------------------------------------------------------------
    // 内部根入口（.trash，不对外浏览）
    // ---------------------------------------------------------------------

    fun guardForInternalRead(path: String): GuardedPath =
        resolvePath(validatePath(path), allowFinalSymlink = true, scope = RootScope.INTERNAL)

    fun guardForInternalWrite(path: String): GuardedPath =
        guardForWriteIn(path, RootScope.INTERNAL)

    fun guardInternalTargetDirectory(path: String): GuardedPath =
        guardTargetDirectoryIn(path, RootScope.INTERNAL)

    fun guardInternalReadableFile(path: String): GuardedPath =
        guardReadableFileIn(path, RootScope.INTERNAL)

    // ---------------------------------------------------------------------
    // 名称与路径工具
    // ---------------------------------------------------------------------

    /**
     * 校验名称：不允许空、.、..、分隔符、控制字符、保留名、尾随点/空格，
     * 且 UTF-8 字节长度不得超过 255。
     */
    fun validateName(name: String): String {
        if (name.isEmpty()) {
            throw FileGuardException(GuardErrorCode.INVALID_NAME, detail = "名称不能为空")
        }
        if (name.length > MAX_NAME_BYTES || name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            throw FileGuardException(GuardErrorCode.INVALID_NAME, detail = "名称过长")
        }
        if (name == "." || name == "..") {
            throw FileGuardException(GuardErrorCode.INVALID_NAME, detail = "名称不能是 . 或 ..")
        }
        if (name.any { it == '/' || it == '\\' }) {
            throw FileGuardException(GuardErrorCode.PATH_SEPARATOR, detail = "名称不得包含路径分隔符")
        }
        if (name.any { it.code < 0x20 || it.code == 0x7F }) {
            throw FileGuardException(GuardErrorCode.INVALID_NAME, detail = "名称不得包含控制字符")
        }
        if (name.endsWith('.') || name.endsWith(' ')) {
            throw FileGuardException(GuardErrorCode.INVALID_NAME, detail = "名称不得以点或空格结尾")
        }
        if (name.isBlank()) {
            throw FileGuardException(GuardErrorCode.INVALID_NAME, detail = "名称不能只包含空白字符")
        }
        val reservedBase = name.substringBefore('.').uppercase(Locale.ROOT)
        if (reservedBase in RESERVED_NAMES) {
            throw FileGuardException(GuardErrorCode.RESERVED_NAME, detail = "名称是系统保留名称")
        }
        return name
    }

    /** 判断给定路径解析真实路径后是否位于任一可浏览根内。 */
    fun isWithinRoot(path: String): Boolean = try {
        ensureWithinRoot(canonicalFile(validatePath(path)), RootScope.BROWSABLE)
        true
    } catch (e: FileGuardException) {
        false
    }

    /** 判断给定路径解析真实路径后是否位于任一内部根内。 */
    fun isWithinInternalRoot(path: String): Boolean = try {
        ensureWithinRoot(canonicalFile(validatePath(path)), RootScope.INTERNAL)
        true
    } catch (e: FileGuardException) {
        false
    }

    // ---------------------------------------------------------------------
    // 计划创建
    // ---------------------------------------------------------------------

    /**
     * 为“在 parentPath 下新建 name”创建计划（可浏览根）。
     *
     * 会解析父目录真实路径、校验父目录、快照父目录 inode，并确认目标尚不存在。
     */
    fun prepareCreate(parentPath: String, name: String): CreationPlan =
        prepareCreateIn(parentPath, name, RootScope.BROWSABLE)

    /** 为应用私有内部根创建计划（仅服务层使用）。 */
    fun prepareInternalCreate(parentPath: String, name: String): CreationPlan =
        prepareCreateIn(parentPath, name, RootScope.INTERNAL)

    /** 创建重命名计划。新名称必须通过 validateName。 */
    fun prepareRename(sourcePath: String, newName: String): RenamePlan {
        val source = guardForWrite(sourcePath)
        if (newName == source.file.name) {
            throw FileGuardException(
                GuardErrorCode.ALREADY_EXISTS,
                source.canonicalPath,
                "新名称与当前名称相同"
            )
        }
        val parent = source.file.parentFile
            ?: throw FileGuardException(GuardErrorCode.IO_ERROR, source.canonicalPath, "无法解析父目录")
        val target = prepareCreate(parent.absolutePath, newName)
        return RenamePlan(source, target)
    }

    /** 创建可浏览根内的移动计划；未显式给出 newName 时保留源名称。 */
    fun prepareMove(
        sourcePath: String,
        targetDirectoryPath: String,
        newName: String? = null
    ): MovePlan = prepareMoveAcrossRoots(
        sourcePath = sourcePath,
        targetDirectoryPath = targetDirectoryPath,
        newName = newName,
        sourceInternal = false,
        targetInternal = false
    )

    /**
     * 创建跨根移动计划。
     *
     * TrashService 使用 sourceInternal=false / targetInternal=true 执行软删除，
     * 使用 sourceInternal=true / targetInternal=false 执行恢复。
     */
    fun prepareMoveAcrossRoots(
        sourcePath: String,
        targetDirectoryPath: String,
        newName: String? = null,
        sourceInternal: Boolean = false,
        targetInternal: Boolean = false
    ): MovePlan {
        val source = guardForWriteIn(sourcePath, scopeOf(sourceInternal))
        val target = prepareCreateIn(
            targetDirectoryPath,
            newName ?: source.file.name,
            scopeOf(targetInternal)
        )
        ensureNotInsideSource(source, target)
        return MovePlan(source, target)
    }

    /**
     * 创建复制计划。
     *
     * @param rejectNestedSymlinks 目录复制时先递归检查，发现符号链接立即拒绝
     * （需求 7.5）。该检查不跟随符号链接。
     */
    fun prepareCopy(
        sourcePath: String,
        targetDirectoryPath: String,
        newName: String? = null,
        rejectNestedSymlinks: Boolean = true
    ): CopyPlan = prepareCopyAcrossRoots(
        sourcePath = sourcePath,
        targetDirectoryPath = targetDirectoryPath,
        newName = newName,
        sourceInternal = false,
        targetInternal = false,
        rejectNestedSymlinks = rejectNestedSymlinks
    )

    /** 创建跨根复制计划（软删除跨卷回退、恢复回退等场景使用）。 */
    fun prepareCopyAcrossRoots(
        sourcePath: String,
        targetDirectoryPath: String,
        newName: String? = null,
        sourceInternal: Boolean = false,
        targetInternal: Boolean = false,
        rejectNestedSymlinks: Boolean = true
    ): CopyPlan {
        val source = guardForWriteIn(sourcePath, scopeOf(sourceInternal))
        val target = prepareCreateIn(
            targetDirectoryPath,
            newName ?: source.file.name,
            scopeOf(targetInternal)
        )
        ensureNotInsideSource(source, target)
        if (rejectNestedSymlinks && source.isDirectory) {
            assertNoSymlinksInTree(source)
        }
        return CopyPlan(source, target)
    }

    /** 删除计划。删除同样走 guardForWrite，根目录与最终符号链接都会被拒绝。 */
    fun prepareDelete(path: String): GuardedPath = guardForWrite(path)

    /** 内部根删除计划（仅服务层清理临时文件使用）。 */
    fun prepareInternalDelete(path: String): GuardedPath = guardForInternalWrite(path)

    /**
     * 递归检查目录树中是否存在符号链接或特殊文件。
     *
     * 不跟随符号链接；发现后抛出 SYMLINK_FORBIDDEN / UNSUPPORTED_FILE_TYPE。
     */
    fun assertNoSymlinksInTree(directory: GuardedPath) {
        if (!directory.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(directory.file)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            // 出队时再次 lstat，避免扫描期间目录被替换为符号链接。
            val currentStat = lstatOrNull(current)
                ?: throw FileGuardException(GuardErrorCode.PATH_CHANGED, current.path)
            if (currentStat.isSymbolicLink) {
                throw FileGuardException(
                    GuardErrorCode.SYMLINK_FORBIDDEN,
                    current.path,
                    "目录内包含符号链接，禁止复制"
                )
            }
            if (!currentStat.isDirectory) {
                throw FileGuardException(GuardErrorCode.PATH_CHANGED, current.path)
            }
            val children = current.listFiles()
                ?: throw FileGuardException(GuardErrorCode.IO_ERROR, current.path, "无法读取目录内容")
            for (child in children) {
                val childStat = lstatOrNull(child)
                    ?: throw FileGuardException(GuardErrorCode.PATH_CHANGED, child.path)
                if (childStat.isSymbolicLink) {
                    throw FileGuardException(
                        GuardErrorCode.SYMLINK_FORBIDDEN,
                        child.path,
                        "目录内包含符号链接，禁止复制"
                    )
                }
                if (childStat.isDirectory) {
                    queue.add(child)
                } else if (!childStat.isRegularFile) {
                    throw FileGuardException(GuardErrorCode.UNSUPPORTED_FILE_TYPE, child.path)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 执行前二次校验
    // ---------------------------------------------------------------------

    /** 执行前校验现有路径：realpath 不变、lstat 快照不变。 */
    fun verifyUnchanged(guarded: GuardedPath) {
        val requested = File(guarded.requestedPath)
        val canonical = canonicalForVerification(requested, GuardErrorCode.PATH_CHANGED)
        if (canonical.path != guarded.canonicalPath) {
            throw FileGuardException(GuardErrorCode.PATH_CHANGED, guarded.requestedPath)
        }
        ensureWithinRoot(canonical, guarded.scope)

        val requestedStat = lstatOrNull(requested)
            ?: throw FileGuardException(GuardErrorCode.PATH_CHANGED, guarded.requestedPath)
        if (requestedStat.isSymbolicLink != guarded.isSymlink) {
            throw FileGuardException(GuardErrorCode.PATH_CHANGED, guarded.requestedPath)
        }
        val currentStat = lstatOrNull(canonical)
            ?: throw FileGuardException(GuardErrorCode.PATH_CHANGED, guarded.canonicalPath)
        if (!guarded.snapshot.sameState(currentStat)) {
            throw FileGuardException(GuardErrorCode.PATH_CHANGED, guarded.canonicalPath)
        }
    }

    /** 删除前校验：禁止根目录，并再次确认路径未变化。 */
    fun verifyDeletable(guarded: GuardedPath) {
        ensureNotRoot(guarded)
        verifyUnchanged(guarded)
    }

    /** 新建/复制/移动前校验父目录与目标状态。 */
    fun verifyCreationTarget(plan: CreationPlan) {
        val requestedParent = File(plan.parentRequestedPath)
        val canonicalParent = canonicalForVerification(
            requestedParent,
            GuardErrorCode.PARENT_CHANGED
        )
        if (canonicalParent.path != plan.canonicalParentPath) {
            throw FileGuardException(GuardErrorCode.PARENT_CHANGED, plan.parentRequestedPath)
        }
        ensureWithinRoot(canonicalParent, plan.scope)

        val currentParentStat = lstatOrNull(canonicalParent)
            ?: throw FileGuardException(GuardErrorCode.PARENT_CHANGED, plan.parentRequestedPath)
        if (!currentParentStat.isDirectory ||
            !plan.parentSnapshot.sameIdentity(currentParentStat)
        ) {
            throw FileGuardException(GuardErrorCode.PARENT_CHANGED, plan.parentRequestedPath)
        }

        val currentTarget = File(canonicalParent, plan.name)
        if (lstatOrNull(currentTarget) != null) {
            throw FileGuardException(GuardErrorCode.ALREADY_EXISTS, currentTarget.path)
        }
    }

    fun verifyRenamePlan(plan: RenamePlan) {
        verifyUnchanged(plan.source)
        verifyCreationTarget(plan.target)
    }

    fun verifyMovePlan(plan: MovePlan) {
        verifyUnchanged(plan.source)
        verifyCreationTarget(plan.target)
        ensureNotInsideSource(plan.source, plan.target)
    }

    fun verifyCopyPlan(plan: CopyPlan) {
        verifyUnchanged(plan.source)
        verifyCreationTarget(plan.target)
        ensureNotInsideSource(plan.source, plan.target)
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private fun guardForReadIn(path: String, scope: RootScope): GuardedPath =
        resolvePath(validatePath(path), allowFinalSymlink = true, scope = scope)

    private fun guardForWriteIn(path: String, scope: RootScope): GuardedPath {
        val guarded = resolvePath(validatePath(path), allowFinalSymlink = false, scope = scope)
        ensureNotRoot(guarded)
        return guarded
    }

    private fun guardTargetDirectoryIn(path: String, scope: RootScope): GuardedPath {
        val guarded = resolvePath(validatePath(path), allowFinalSymlink = true, scope = scope)
        if (!guarded.isDirectory) {
            throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, guarded.canonicalPath)
        }
        return guarded
    }

    private fun guardReadableFileIn(path: String, scope: RootScope): GuardedPath {
        val guarded = resolvePath(validatePath(path), allowFinalSymlink = true, scope = scope)
        if (guarded.isDirectory) {
            throw FileGuardException(GuardErrorCode.NOT_FILE, guarded.canonicalPath)
        }
        return guarded
    }

    private fun prepareCreateIn(
        parentPath: String,
        name: String,
        scope: RootScope
    ): CreationPlan {
        val parent = validatePath(parentPath)
        val safeName = validateName(name)

        val requestedParentStat = statOrNull(parent)
            ?: throw FileGuardException(GuardErrorCode.PARENT_NOT_FOUND, parent.path)
        if (!requestedParentStat.isDirectory) {
            throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, parent.path)
        }

        val canonicalParent = canonicalFile(parent)
        ensureWithinRoot(canonicalParent, scope)

        val canonicalParentStat = lstatOrNull(canonicalParent)
            ?: throw FileGuardException(GuardErrorCode.PARENT_CHANGED, parent.path)
        if (!canonicalParentStat.isDirectory) {
            throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, canonicalParent.path)
        }

        val target = File(canonicalParent, safeName)
        if (lstatOrNull(target) != null) {
            throw FileGuardException(GuardErrorCode.ALREADY_EXISTS, target.path)
        }

        return CreationPlan(
            parentRequestedPath = parent.absolutePath,
            parent = canonicalParent,
            canonicalParentPath = canonicalParent.path,
            name = safeName,
            target = target,
            parentSnapshot = PathSnapshot.from(canonicalParentStat),
            scope = scope
        )
    }

    private fun resolvePath(
        file: File,
        allowFinalSymlink: Boolean,
        scope: RootScope
    ): GuardedPath {
        val requestedPath = file.absolutePath
        val requestedStat = lstatOrNull(file)
            ?: throw FileGuardException(GuardErrorCode.SOURCE_NOT_FOUND, requestedPath)
        val isSymlink = requestedStat.isSymbolicLink
        if (isSymlink && !allowFinalSymlink) {
            throw FileGuardException(
                GuardErrorCode.SYMLINK_FORBIDDEN,
                requestedPath,
                "变更操作不允许最终组件为符号链接"
            )
        }

        val canonical = canonicalFile(file)
        ensureWithinRoot(canonical, scope)

        val canonicalStat = lstatOrNull(canonical)
            ?: throw FileGuardException(
                GuardErrorCode.PATH_CHANGED,
                requestedPath,
                "解析真实路径后目标已消失"
            )
        if (canonicalStat.isSymbolicLink) {
            throw FileGuardException(GuardErrorCode.PATH_CHANGED, requestedPath)
        }
        if (!canonicalStat.isDirectory && !canonicalStat.isRegularFile) {
            throw FileGuardException(GuardErrorCode.UNSUPPORTED_FILE_TYPE, requestedPath)
        }

        return GuardedPath(
            file = canonical,
            requestedPath = requestedPath,
            canonicalPath = canonical.path,
            isSymlink = isSymlink,
            isDirectory = canonicalStat.isDirectory,
            snapshot = PathSnapshot.from(canonicalStat),
            scope = scope
        )
    }

    private fun buildRootEntries(rawRoots: List<File>, label: String): List<RootEntry> {
        val entries = LinkedHashMap<String, RootEntry>()
        rawRoots.forEach { raw ->
            val canonical = canonicalFile(raw)
            val stat = statOrNull(canonical)
            if (stat == null || !stat.isDirectory) {
                throw FileGuardException(
                    GuardErrorCode.ROOT_UNAVAILABLE,
                    raw.path,
                    "${label}不存在或不是目录"
                )
            }
            entries.putIfAbsent(canonical.path, RootEntry(canonical, canonical.path))
        }
        return entries.values.toList()
    }

    private fun validatePath(path: String?): File {
        if (path.isNullOrBlank()) {
            throw FileGuardException(GuardErrorCode.EMPTY_PATH)
        }
        if (path.length > MAX_PATH_LENGTH) {
            throw FileGuardException(GuardErrorCode.INVALID_PATH, detail = "路径过长")
        }
        if (path.indexOf('\u0000') >= 0) {
            throw FileGuardException(GuardErrorCode.INVALID_PATH, detail = "路径包含 NUL 字符")
        }
        val file = File(path)
        if (!file.isAbsolute) {
            throw FileGuardException(GuardErrorCode.NOT_ABSOLUTE, path)
        }
        // Android 的路径分隔符是 /；同时检查 \ 便于 Windows JVM 测试与 ZIP 安全。
        if (path.split('/', '\\').any { it == ".." }) {
            throw FileGuardException(GuardErrorCode.PATH_TRAVERSAL, path)
        }
        return file
    }

    private fun ensureNotRoot(guarded: GuardedPath) {
        val isRoot = rootEntries.any { it.canonicalPath == guarded.canonicalPath } ||
            internalRootEntries.any { it.canonicalPath == guarded.canonicalPath }
        if (isRoot) {
            throw FileGuardException(
                GuardErrorCode.ROOT_OPERATION_FORBIDDEN,
                guarded.canonicalPath
            )
        }
    }

    private fun ensureNotInsideSource(source: GuardedPath, target: CreationPlan) {
        if (!source.isDirectory) return
        val parentPath = target.parent.path
        if (parentPath == source.canonicalPath ||
            parentPath.startsWith(source.canonicalPath + File.separator)
        ) {
            throw FileGuardException(
                GuardErrorCode.SELF_REFERENCE,
                target.parent.path,
                "目标目录不能位于源目录内部"
            )
        }
    }

    private fun ensureWithinRoot(canonical: File, scope: RootScope) {
        val path = canonical.path
        // 内部根（.trash、.aster-data）即使嵌套在可浏览根内也必须不可见。
        if (scope == RootScope.BROWSABLE &&
            internalRootEntries.any { isUnder(path, it.canonicalPath) }
        ) {
            throw FileGuardException(
                GuardErrorCode.INTERNAL_PATH_FORBIDDEN,
                path,
                "应用内部路径不可访问"
            )
        }

        val entries = entriesFor(scope)
        if (entries.none { isUnder(path, it.canonicalPath) }) {
            val allowed = entries.joinToString("、") { it.canonicalPath }
            throw FileGuardException(
                GuardErrorCode.OUTSIDE_ROOT,
                path,
                "仅允许访问根目录 $allowed 内的路径"
            )
        }
    }

    private fun entriesFor(scope: RootScope): List<RootEntry> = when (scope) {
        RootScope.BROWSABLE -> rootEntries
        RootScope.INTERNAL -> internalRootEntries
    }

    private fun scopeOf(internal: Boolean): RootScope =
        if (internal) RootScope.INTERNAL else RootScope.BROWSABLE

    private fun isUnder(path: String, rootPath: String): Boolean {
        if (path == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return path.startsWith(prefix)
    }

    private fun canonicalFile(file: File): File = try {
        file.canonicalFile
    } catch (e: IOException) {
        throw FileGuardException(
            GuardErrorCode.IO_ERROR,
            file.path,
            "无法解析真实路径",
            e
        )
    }

    private fun canonicalForVerification(file: File, changedCode: GuardErrorCode): File = try {
        canonicalFile(file)
    } catch (e: FileGuardException) {
        throw FileGuardException(changedCode, file.path, "真实路径解析失败", e)
    }

    private fun lstatOrNull(file: File): GuardFileStat? = try {
        fileSystem.lstat(file)
    } catch (e: IOException) {
        throw FileGuardException(
            GuardErrorCode.IO_ERROR,
            file.absolutePath,
            "无法读取文件状态",
            e
        )
    }

    private fun statOrNull(file: File): GuardFileStat? = try {
        fileSystem.stat(file)
    } catch (e: IOException) {
        throw FileGuardException(
            GuardErrorCode.IO_ERROR,
            file.absolutePath,
            "无法读取文件状态",
            e
        )
    }

    companion object {
        private const val MAX_NAME_BYTES = 255
        private const val MAX_PATH_LENGTH = 4096

        /** Windows/Android 文件系统常见保留名，按扩展名之前的基名比较。 */
        private val RESERVED_NAMES: Set<String> = buildSet {
            add("CON")
            add("PRN")
            add("AUX")
            add("NUL")
            for (index in 1..9) {
                add("COM" + index)
                add("LPT" + index)
            }
        }

        /**
         * 默认根：共享存储主目录。
         *
         * Android 11+ 需要 MANAGE_EXTERNAL_STORAGE；应用私有目录、.trash、
         * cacheDir/exports 均不在此根内。
         */
        @Suppress("DEPRECATION")
        @JvmStatic
        fun defaultRoots(): List<File> = listOf(Environment.getExternalStorageDirectory())
    }
}
