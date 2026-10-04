package org.example.memosm.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.example.memosm.R
import org.example.memosm.ui.component.setting.AuditLogContent

@Composable
internal fun SyncLogScreen(onBack: () -> Unit) {
    SettingsPageScaffold(
        title = stringResource(R.string.audit_log_title),
        onBack = onBack,
        collapsingHeader = true
    ) { padding ->
        AuditLogContent(modifier = Modifier.fillMaxSize().padding(padding))
    }
}
