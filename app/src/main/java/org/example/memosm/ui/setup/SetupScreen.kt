package org.example.memosm.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R

@Composable
fun SetupScreen(
    onLogin: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.align(Alignment.TopCenter)
                    .widthIn(max = 480.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp)
                    .padding(top = maxHeight * 0.25f, bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.setup_get_started), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.login_button))
                    }
                    FilledTonalButton(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.recovery_import))
                    }
                }
            }
            onClose?.let { close ->
                IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        }
    }
}
