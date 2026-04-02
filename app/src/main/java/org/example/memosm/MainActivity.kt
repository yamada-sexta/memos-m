package org.example.memosm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.ShareIntentData
import org.example.memosm.ui.MainScreen
import org.example.memosm.ui.component.LoginScreen
import org.example.memosm.ui.theme.MemosMTheme
import org.example.memosm.widget.DraftWidget

import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApiFactory

class MainActivity : ComponentActivity() {

    // StateFlow to hold pending share data, observable by Compose
    private val pendingShareDataFlow = MutableStateFlow<ShareIntentData?>(null)

    // StateFlow to trigger composer opening from widget
    private val shouldOpenComposerFlow = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Parse share intent data from initial launch
        pendingShareDataFlow.value = parseShareIntent(intent)

        // Check if launched from widget
        if (intent.action == DraftWidget.ACTION_OPEN_COMPOSER) {
            shouldOpenComposerFlow.value = true
        }

        setContent {
            MemosMTheme {
                LocalContext.current
                val scope = rememberCoroutineScope()

                val application = applicationContext as MemosApplication
                val dataStoreManager = application.dataStoreManager
                val draftManager = application.draftManager
                val memoCacheRepository = application.memoCacheRepository
                val okHttpClient = application.okHttpClient

                // Observe accounts instead of single credentials
                val accounts by dataStoreManager.accounts.collectAsState(initial = null)

                // Wait for DataStore to emit initial values
                var isCheckingSession by remember { mutableStateOf(true) }

                // Collect pending share data from the flow
                val pendingShareData by pendingShareDataFlow.collectAsState()
                val shouldOpenComposer by shouldOpenComposerFlow.collectAsState()

                LaunchedEffect(accounts) {
                    if (accounts != null) {
                        // Once we have a non-null list (even if empty), we've finished the initial load
                        isCheckingSession = false
                    }
                }

                if (isCheckingSession) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val activeAccount = accounts?.find { it.isActive }

                    var api by remember(activeAccount) { mutableStateOf<org.example.memosm.api.MemosApi?>(null) }

                    LaunchedEffect(activeAccount) {
                        if (activeAccount != null) {
                            val client = okHttpClient.newBuilder()
                                .addInterceptor(AuthInterceptor(activeAccount.accessToken))
                                .build()
                            api = MemosApiFactory.create(activeAccount.hostUrl, client)
                        } else {
                            api = null
                        }
                    }

                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        if (activeAccount != null) {
                            MainScreen(
                                onLogout = {
                                    scope.launch {
                                        dataStoreManager.deleteAccount(activeAccount.id)
                                    }
                                },
                                shareIntentData = pendingShareData,
                                onShareIntentConsumed = { pendingShareDataFlow.value = null },
                                shouldOpenComposer = shouldOpenComposer,
                                onComposerOpened = { shouldOpenComposerFlow.value = false },
                                api = api,
                                accounts = accounts ?: emptyList(),
                                dataStoreManager = dataStoreManager,
                                draftManager = draftManager,
                                memoCacheRepository = memoCacheRepository
                            )
                        } else {
                            // If no active account, show login
                            LoginScreen(
                                modifier = Modifier.padding(innerPadding),
                                onLoginSuccess = { baseUrl, token ->
                                    scope.launch {
                                        dataStoreManager.addAccount(baseUrl, token)
                                    }
                                })
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the activity is already running and receives a new intent (e.g., share).
     * With launchMode="singleTask", share intents will come here instead of onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the current intent

        // Parse and emit the new share data
        val shareData = parseShareIntent(intent)
        if (shareData != null) {
            pendingShareDataFlow.value = shareData
        }

        if (intent.action == DraftWidget.ACTION_OPEN_COMPOSER) {
            shouldOpenComposerFlow.value = true
        }
    }

    /**
     * Parses a share intent (ACTION_SEND or ACTION_SEND_MULTIPLE) and extracts
     * text content and file URIs.
     */
    private fun parseShareIntent(intent: Intent?): ShareIntentData? {
        if (intent == null) return null

        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }

        Log.d("MainActivity", "parseShareIntent: action=$action")

        // Extract text content
        val text =
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        Log.d("MainActivity", "parseShareIntent: text=${text?.take(100)}")

        // Extract URIs
        val uris = mutableListOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                // Single file/image share
                val singleUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                Log.d("MainActivity", "parseShareIntent: SEND singleUri=$singleUri")
                singleUri?.let { uri ->
                    val localUri = copyUriToCache(uri)
                    Log.d("MainActivity", "parseShareIntent: copied to localUri=$localUri")
                    if (localUri != null) uris.add(localUri)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple files/images share
                val multipleUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                Log.d("MainActivity", "parseShareIntent: SEND_MULTIPLE count=${multipleUris?.size}")
                multipleUris?.forEach { uri ->
                    val localUri = copyUriToCache(uri)
                    Log.d("MainActivity", "parseShareIntent: copied $uri -> $localUri")
                    if (localUri != null) uris.add(localUri)
                }
            }
        }

        val shareData = ShareIntentData(text = text, uris = uris)
        Log.d(
            "MainActivity",
            "parseShareIntent: result isEmpty=${shareData.isEmpty}, uriCount=${uris.size}"
        )
        return if (shareData.isEmpty) null else shareData
    }

    /**
     * Copies a shared URI's content to a local cache file so it remains
     * accessible after the temporary share-intent permission expires.
     * Preserves the original filename/extension for proper MIME type detection.
     * Returns the local file Uri, or null if the copy fails.
     */
    private fun copyUriToCache(uri: Uri): Uri? {
        return try {
            // Try to get the original display name from ContentResolver
            var displayName: String? = null
            if (uri.scheme == "content") {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index =
                            cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) displayName = cursor.getString(index)
                    }
                }
            }

            // Fallback: derive filename from URI path or MIME type
            if (displayName.isNullOrBlank()) {
                val baseName = uri.lastPathSegment ?: "file"
                val mimeType = contentResolver.getType(uri)
                val ext = if (mimeType != null) {
                    android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mimeType)
                } else null
                displayName = if (ext != null && !baseName.contains('.')) {
                    "$baseName.$ext"
                } else {
                    baseName
                }
            }

            val fileName = "share_${System.currentTimeMillis()}_$displayName"
            val cacheFile = java.io.File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.w("MainActivity", "Could not open input stream for shared URI: $uri")
                return null
            }
            Uri.fromFile(cacheFile)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to copy shared URI to cache: $uri", e)
            null
        }
    }
}
