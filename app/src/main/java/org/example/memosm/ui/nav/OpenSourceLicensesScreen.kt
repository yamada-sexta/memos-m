package org.example.memosm.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import org.example.memosm.R
import org.example.memosm.ui.component.ProfileBackButton

@Composable
internal fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_about_licenses)) },
                navigationIcon = { ProfileBackButton(onClick = onBack) }
            )
        }
    ) { padding ->
        val loaded = libraries
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LibrariesContainer(libraries = loaded, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}
