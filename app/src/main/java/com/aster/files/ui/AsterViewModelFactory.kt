package com.aster.files.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aster.files.AppContainer
import com.aster.files.network.NetworkStorageStore
import com.aster.files.ui.viewmodel.AnalysisViewModel
import com.aster.files.ui.viewmodel.AppsViewModel
import com.aster.files.ui.viewmodel.DirectoryAnalysisViewModel
import com.aster.files.ui.viewmodel.CategoryViewModel
import com.aster.files.ui.viewmodel.FilesViewModel
import com.aster.files.ui.viewmodel.FrameExtractViewModel
import com.aster.files.ui.viewmodel.IntegrityViewModel
import com.aster.files.ui.viewmodel.MainViewModel
import com.aster.files.ui.viewmodel.NetworkBrowserViewModel
import com.aster.files.ui.viewmodel.NetworkStorageViewModel
import com.aster.files.ui.viewmodel.NetworkTransferViewModel
import com.aster.files.ui.viewmodel.SettingsViewModel
import com.aster.files.ui.viewmodel.WifiTransferViewModel
import com.aster.files.ui.viewmodel.TrashViewModel

class AsterViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(WifiTransferViewModel::class.java) -> WifiTransferViewModel(
                context = container.applicationContext,
                guard = container.fileGuard,
                rootPath = container.rootPath
            )
            modelClass.isAssignableFrom(NetworkStorageViewModel::class.java) -> NetworkStorageViewModel(
                store = NetworkStorageStore(container.applicationContext)
            )
            modelClass.isAssignableFrom(NetworkBrowserViewModel::class.java) -> NetworkBrowserViewModel(
                store = NetworkStorageStore(container.applicationContext)
            )
            modelClass.isAssignableFrom(NetworkTransferViewModel::class.java) -> NetworkTransferViewModel(
                store = NetworkStorageStore(container.applicationContext),
                guard = container.fileGuard
            )
            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
                rootPath = container.rootPath,
                defaultSort = container.fileSortPreferences.sort.value
            )
            modelClass.isAssignableFrom(CategoryViewModel::class.java) -> CategoryViewModel(
                categoryService = container.categoryService,
                fileService = container.fileService,
                trashService = container.trashService,
                archiveService = container.archiveService,
                rootPath = container.rootPath,
                sortPreferences = container.fileSortPreferences
            )
            modelClass.isAssignableFrom(AppsViewModel::class.java) -> AppsViewModel(
                appService = container.appService
            )
            modelClass.isAssignableFrom(FilesViewModel::class.java) -> FilesViewModel(
                fileService = container.fileService,
                trashService = container.trashService,
                archiveService = container.archiveService,
                rootPath = container.rootPath,
                sortPreferences = container.fileSortPreferences
            )
            modelClass.isAssignableFrom(AnalysisViewModel::class.java) -> AnalysisViewModel(
                container.analysisService
            )
            modelClass.isAssignableFrom(DirectoryAnalysisViewModel::class.java) -> DirectoryAnalysisViewModel(
                service = container.directoryAnalysisService,
                fileService = container.fileService,
                trashService = container.trashService
            )
            modelClass.isAssignableFrom(FrameExtractViewModel::class.java) -> FrameExtractViewModel(
                service = container.frameExtractionService
            )
            modelClass.isAssignableFrom(IntegrityViewModel::class.java) -> IntegrityViewModel(
                service = container.manualIntegrityService,
                store = container.integrityStore,
                actionHandler = container.integrityActionHandler
            )
            modelClass.isAssignableFrom(TrashViewModel::class.java) -> TrashViewModel(
                container.trashService
            )
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                context = container.applicationContext,
                repository = container.repository,
                rootPath = container.rootPath,
                themePreference = container.themePreference,
                deleteConfirmationPreferences = container.deleteConfirmationPreferences,
                trashRetentionPreferences = container.trashRetentionPreferences
            )
            else -> error("Unknown ViewModel: " + modelClass.name)
        }
        return viewModel as T
    }
}
