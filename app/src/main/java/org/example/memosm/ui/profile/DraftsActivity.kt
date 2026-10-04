package org.example.memosm.ui.profile

import androidx.compose.runtime.Composable
import org.example.memosm.ui.nav.DraftsScreen
import org.example.memosm.viewmodel.MemosViewModel

class DraftsActivity : ProfileActivity() {
    @Composable
    override fun Destination(viewModel: MemosViewModel, onBack: () -> Unit) {
        DraftsScreen(viewModel = viewModel, onBack = onBack)
    }
}
