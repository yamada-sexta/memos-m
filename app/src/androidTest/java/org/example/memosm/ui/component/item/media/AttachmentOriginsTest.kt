package org.example.memosm.ui.component.item.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.example.memosm.model.Attachment
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AttachmentOriginsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sourceLookupUsesAttachmentIdentityAndFallsBackAfterThumbnailLeavesComposition() {
        val origins = AttachmentOrigins()
        val attachment = Attachment(name = "attachments/source", filename = "file.pdf", type = "application/pdf")
        val showThumbnail = mutableStateOf(true)
        compose.setContent {
            if (showThumbnail.value) {
                Box(Modifier.size(160.dp).attachmentOrigin(origins, attachment))
            }
        }
        compose.runOnIdle {
            assertNotNull(origins.boundsFor(attachment.copy(size = "1234")))
            assertNull(origins.boundsFor(attachment.copy(name = "attachments/other")))
            showThumbnail.value = false
        }
        compose.runOnIdle { assertNull(origins.boundsFor(attachment)) }
    }
}
