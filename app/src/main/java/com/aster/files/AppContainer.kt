package com.aster.files

import android.content.Context
import com.aster.files.data.AsterDatabase
import com.aster.files.data.Repository
import com.aster.files.integrity.AndroidMediaDecodeVerifier
import com.aster.files.integrity.DecodeVerifier
import com.aster.files.integrity.IntegrityActionHandler
import com.aster.files.integrity.IntegrityActionStore
import com.aster.files.integrity.IntegrityStore
import com.aster.files.integrity.ManualIntegrityService
import com.aster.files.integrity.RepositoryIntegrityActionStore
import com.aster.files.integrity.RepositoryIntegrityStore
import com.aster.files.security.FileGuard
import com.aster.files.services.AnalysisService
import com.aster.files.services.AppService
import com.aster.files.services.DirectoryAnalysisService
import com.aster.files.services.FrameExtractionService
import com.aster.files.services.ArchiveService
import com.aster.files.services.CategoryService
import com.aster.files.services.AuditLogger
import com.aster.files.services.AuditTrail
import com.aster.files.services.FileService
import com.aster.files.services.RepositoryAuditLogger
import com.aster.files.services.RepositoryTrashStore
import com.aster.files.services.TrashService
import com.aster.files.services.TrashStore
import com.aster.files.services.ZipPasswordStore
import com.aster.files.services.EncryptedZipPasswordStore
import com.aster.files.ui.settings.DeleteConfirmationPreferences
import com.aster.files.ui.settings.TrashRetentionPreferences
import com.aster.files.ui.sort.FileSortPreferences
import com.aster.files.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files

/**
 * 应用级依赖容器。
 *
 * .trash 与 .aster-data 都是应用私有内部根，注入 FileGuard 后对可浏览文件服务
 * 完全不可见。两者的首次创建同样通过 FileGuard 计划完成。
 *
 * 注意：FileGuard 初始化会做 stat/canonical 磁盘访问，服务属性应在 IO 协程中
 * 首次访问（AsterApplication 已在 IO 作用域预热恢复路径）。
 */
class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext

    private val mutableReady = MutableStateFlow(false)

    /** warmUp 完成后才允许构建 UI/ViewModel，保证 FileGuard 初始化在 IO 上完成。 */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    val database: AsterDatabase by lazy { AsterDatabase(applicationContext) }
    val repository: Repository by lazy { Repository(database) }

    private val privateRoots: Pair<File, File> by lazy {
        val filesDir = applicationContext.filesDir
        val bootstrapGuard = FileGuard(
            roots = FileGuard.defaultRoots(),
            internalRoots = listOf(filesDir)
        )
        val trash = ensurePrivateDirectory(bootstrapGuard, filesDir, ".trash")
        val asterData = ensurePrivateDirectory(bootstrapGuard, filesDir, ".aster-data")
        trash to asterData
    }

    /** 应用私有回收站根目录（物理文件在其下 files/ 子目录）。 */
    val trashRoot: File get() = privateRoots.first

    /** 应用私有内部数据目录；对 FileService 严格不可见。 */
    val asterDataRoot: File get() = privateRoots.second

    /** 共享存储主根路径；仅路径字符串，不触发磁盘 stat。 */
    val rootPath: String by lazy { FileGuard.defaultRoots().first().absolutePath }

    val fileGuard: FileGuard by lazy {
        FileGuard(internalRoots = listOf(trashRoot, asterDataRoot))
    }

    val spaceCleaner: com.aster.files.services.SpaceCleaner by lazy {
        com.aster.files.services.SpaceCleaner(fileGuard)
    }

    val mediaConversionService: com.aster.files.services.MediaConversionService by lazy {
        com.aster.files.services.MediaConversionService(fileGuard, fileService, auditTrail)
    }

    val vaultManager: com.aster.files.vault.VaultManager by lazy {
        com.aster.files.vault.VaultManager(applicationContext.filesDir, fileGuard)
    }

    val themePreference: ThemePreference by lazy { ThemePreference(applicationContext) }
    val fileSortPreferences: FileSortPreferences by lazy {
        FileSortPreferences(applicationContext)
    }
    val deleteConfirmationPreferences: DeleteConfirmationPreferences by lazy {
        DeleteConfirmationPreferences(applicationContext)
    }
    val trashRetentionPreferences: TrashRetentionPreferences by lazy {
        TrashRetentionPreferences(applicationContext)
    }
    val zipPasswordStore: ZipPasswordStore by lazy {
        EncryptedZipPasswordStore(applicationContext)
    }

    val auditLogger: AuditLogger by lazy { RepositoryAuditLogger(repository) }
    val auditTrail: AuditTrail by lazy { AuditTrail(auditLogger) }
    val trashStore: TrashStore by lazy { RepositoryTrashStore(repository) }
    val integrityStore: IntegrityStore by lazy { RepositoryIntegrityStore(repository) }
    val integrityActionStore: IntegrityActionStore by lazy {
        RepositoryIntegrityActionStore(repository)
    }
    val decodeVerifier: DecodeVerifier by lazy { AndroidMediaDecodeVerifier() }

    val fileService: FileService by lazy { FileService(fileGuard, auditTrail) }
    val trashService: TrashService by lazy {
        TrashService(fileGuard, trashRoot, trashStore, auditTrail)
    }
    val archiveService: ArchiveService by lazy {
        ArchiveService(
            guard = fileGuard,
            audit = auditTrail,
            passwordStore = zipPasswordStore
        )
    }
    val analysisService: AnalysisService by lazy { AnalysisService(fileGuard) }
    val directoryAnalysisService: DirectoryAnalysisService by lazy {
        DirectoryAnalysisService(fileGuard)
    }
    val frameExtractionService: FrameExtractionService by lazy {
        FrameExtractionService(fileGuard, auditTrail)
    }
    val categoryService: CategoryService by lazy { CategoryService(fileGuard) }
    val appService: AppService by lazy {
        AppService(applicationContext, fileGuard, auditTrail)
    }
    val manualIntegrityService: ManualIntegrityService by lazy {
        ManualIntegrityService(
            guard = fileGuard,
            store = integrityStore,
            decodeVerifier = decodeVerifier,
            audit = auditTrail
        )
    }
    val integrityActionHandler: IntegrityActionHandler by lazy {
        IntegrityActionHandler(
            guard = fileGuard,
            integrityStore = integrityStore,
            actionStore = integrityActionStore,
            trashService = trashService,
            audit = auditTrail
        )
    }

    /**
     * 在 IO 上预热所有会触碰 FileGuard、内部根或磁盘的服务。
     * 之后 MainActivity 才构建 AsterRoot 与 ViewModel。
     */
    fun warmUp() {
        fileGuard
        trashRoot
        asterDataRoot
        repository
        auditTrail
        trashStore
        integrityStore
        integrityActionStore
        decodeVerifier
        fileService
        trashService
        archiveService
        analysisService
        directoryAnalysisService
        categoryService
        appService
        manualIntegrityService
        integrityActionHandler
        themePreference
    }

    fun markReady() {
        mutableReady.value = true
    }

    private fun ensurePrivateDirectory(
        guard: FileGuard,
        parent: File,
        name: String
    ): File {
        val targetPath = File(parent, name).absolutePath
        val existing = runCatching {
            guard.guardInternalTargetDirectory(targetPath)
        }.getOrNull()
        if (existing != null) {
            check(!existing.isSymlink) { name + " 不能是符号链接" }
            return existing.file
        }

        val plan = guard.prepareInternalCreate(parent.absolutePath, name)
        guard.verifyCreationTarget(plan)
        Files.createDirectory(plan.target.toPath())
        return plan.target
    }
}
