package org.example.memosm.ui.backup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.example.memosm.data.DataStoreManager
import org.example.memosm.ui.component.setting.BackupTransferScreen
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.koin.android.ext.android.inject

/** Only the Import/Export form lives here; Backup & Restore remains a Settings section. */
class BackupTransferActivity : ComponentActivity() {
    private val settings: DataStoreManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SavedMemosMTheme(settings) {
                BackupTransferScreen(intent.getBooleanExtra(EXPORT, false), intent.getStringExtra(SOURCE), ::finish)
            }
        }
    }

    companion object {
        private const val EXPORT = "export"
        private const val SOURCE = "source"
        fun exportIntent(context: Context) = Intent(context, BackupTransferActivity::class.java).putExtra(EXPORT, true)
        fun importIntent(context: Context, sourcePath: String? = null) = Intent(context, BackupTransferActivity::class.java).putExtra(SOURCE, sourcePath)
    }
}
