package org.example.memosm.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.example.memosm.data.DataStoreManager
import org.example.memosm.viewmodel.AppSettings

data class AppSettingsControls(
    val settings: AppSettings
)

@Composable
fun rememberAppSettingsState(dataStoreManager: DataStoreManager): AppSettingsControls {
    val pageSize by dataStoreManager.pageSize.collectAsState(initial = 10)
    val headerScale by dataStoreManager.headerScale.collectAsState(initial = 1.0f)

    return AppSettingsControls(
        settings = AppSettings(pageSize = pageSize, headerScale = headerScale)
    )
}
