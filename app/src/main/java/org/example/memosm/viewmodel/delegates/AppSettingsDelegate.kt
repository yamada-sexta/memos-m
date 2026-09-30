package org.example.memosm.viewmodel.delegates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.data.DataStoreManager
import org.example.memosm.viewmodel.MemosUiState

interface AppSettingsDelegate {
    fun loadPageSize()
    fun loadHeaderScale()
    fun updatePageSize(size: Int)
    fun updateHeaderScale(scale: Float)

    // Offline / pre-download settings
    fun loadOfflineSettings()
    fun updatePreDownloadText(enabled: Boolean)
    fun updatePreDownloadAttachments(enabled: Boolean)
    fun updatePreDownloadWifiOnly(enabled: Boolean)
    fun updatePreDownloadExplore(enabled: Boolean)
    fun updateAttachmentCacheMaxMb(mb: Int)
    fun updateTextCacheMaxMb(mb: Int)
    fun updateThemeCacheMaxMb(mb: Int)
}

class AppSettingsDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val dataStoreManager: DataStoreManager,
    private val onPageSizeChanged: () -> Unit,
    private val onOfflineSettingsChanged: () -> Unit = {}
) : AppSettingsDelegate {

    override fun loadPageSize() {
        scope.launch {
            dataStoreManager.pageSize.collect { size ->
                uiState.update { it.copy(appSettings = it.appSettings.copy(pageSize = size)) }
            }
        }
    }

    override fun loadHeaderScale() {
        scope.launch {
            dataStoreManager.headerScale.collect { scale ->
                uiState.update { it.copy(appSettings = it.appSettings.copy(headerScale = scale)) }
            }
        }
    }

    override fun updatePageSize(size: Int) {
        scope.launch {
            dataStoreManager.savePageSize(size)
            uiState.update { it.copy(appSettings = it.appSettings.copy(pageSize = size)) }
            // Refresh all lists with new page size
            onPageSizeChanged()
        }
    }

    override fun updateHeaderScale(scale: Float) {
        scope.launch {
            dataStoreManager.saveHeaderScale(scale)
            uiState.update { it.copy(appSettings = it.appSettings.copy(headerScale = scale)) }
        }
    }

    override fun loadOfflineSettings() {
        scope.launch {
            dataStoreManager.preDownloadText.collect { enabled ->
                uiState.update { it.copy(appSettings = it.appSettings.copy(preDownloadText = enabled)) }
            }
        }
        scope.launch {
            dataStoreManager.preDownloadAttachments.collect { enabled ->
                uiState.update {
                    it.copy(appSettings = it.appSettings.copy(preDownloadAttachments = enabled))
                }
            }
        }
        scope.launch {
            dataStoreManager.preDownloadWifiOnly.collect { enabled ->
                uiState.update {
                    it.copy(appSettings = it.appSettings.copy(preDownloadWifiOnly = enabled))
                }
            }
        }
        scope.launch {
            dataStoreManager.preDownloadExplore.collect { enabled ->
                uiState.update {
                    it.copy(appSettings = it.appSettings.copy(preDownloadExplore = enabled))
                }
            }
        }
        scope.launch {
            dataStoreManager.attachmentCacheMaxMb.collect { mb ->
                uiState.update {
                    it.copy(appSettings = it.appSettings.copy(attachmentCacheMaxMb = mb))
                }
            }
        }
        scope.launch {
            dataStoreManager.textCacheMaxMb.collect { mb ->
                uiState.update {
                    it.copy(appSettings = it.appSettings.copy(textCacheMaxMb = mb))
                }
            }
        }
        scope.launch {
            dataStoreManager.themeCacheMaxMb.collect { mb ->
                uiState.update {
                    it.copy(appSettings = it.appSettings.copy(themeCacheMaxMb = mb))
                }
                // Feed the coil media cache evictor limit (non-blocking; the
                // cache rebuilds lazily on next access).
                org.example.memosm.ui.component.item.media.MediaCache.updateCacheLimit(mb)
            }
        }
    }

    override fun updatePreDownloadText(enabled: Boolean) {
        scope.launch {
            dataStoreManager.savePreDownloadText(enabled)
            uiState.update { it.copy(appSettings = it.appSettings.copy(preDownloadText = enabled)) }
            onOfflineSettingsChanged()
        }
    }

    override fun updatePreDownloadAttachments(enabled: Boolean) {
        scope.launch {
            dataStoreManager.savePreDownloadAttachments(enabled)
            uiState.update {
                it.copy(appSettings = it.appSettings.copy(preDownloadAttachments = enabled))
            }
            onOfflineSettingsChanged()
        }
    }

    override fun updatePreDownloadWifiOnly(enabled: Boolean) {
        scope.launch {
            dataStoreManager.savePreDownloadWifiOnly(enabled)
            uiState.update {
                it.copy(appSettings = it.appSettings.copy(preDownloadWifiOnly = enabled))
            }
        }
    }

    override fun updatePreDownloadExplore(enabled: Boolean) {
        scope.launch {
            dataStoreManager.savePreDownloadExplore(enabled)
            uiState.update {
                it.copy(appSettings = it.appSettings.copy(preDownloadExplore = enabled))
            }
            onOfflineSettingsChanged()
        }
    }

    override fun updateAttachmentCacheMaxMb(mb: Int) {
        scope.launch {
            dataStoreManager.saveAttachmentCacheMaxMb(mb)
            uiState.update {
                it.copy(appSettings = it.appSettings.copy(attachmentCacheMaxMb = mb))
            }
        }
    }

    override fun updateTextCacheMaxMb(mb: Int) {
        scope.launch {
            dataStoreManager.saveTextCacheMaxMb(mb)
            uiState.update {
                it.copy(appSettings = it.appSettings.copy(textCacheMaxMb = mb))
            }
        }
    }

    override fun updateThemeCacheMaxMb(mb: Int) {
        scope.launch {
            dataStoreManager.saveThemeCacheMaxMb(mb)
            uiState.update {
                it.copy(appSettings = it.appSettings.copy(themeCacheMaxMb = mb))
            }
            org.example.memosm.ui.component.item.media.MediaCache.updateCacheLimit(mb)
        }
    }
}
