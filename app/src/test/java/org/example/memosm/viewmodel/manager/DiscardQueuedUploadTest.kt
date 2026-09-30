package org.example.memosm.viewmodel.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardQueuedUploadTest {

    @Test
    fun discardsOnlyWhenRowExistsAndNothingReferencesIt() {
        assertTrue(AttachmentManager.shouldDiscardQueuedUpload(rowExists = true, referencedElsewhere = false))
    }

    @Test
    fun keepsUploadWhenRowIsAlreadyGone() {
        // Already uploaded or discarded: nothing to cancel.
        assertFalse(AttachmentManager.shouldDiscardQueuedUpload(rowExists = false, referencedElsewhere = false))
    }

    @Test
    fun keepsUploadWhenStillReferenced() {
        // A persisted draft or queued outbox op still carries the clientId:
        // an orphan on the server beats losing bytes that are still referenced.
        assertFalse(AttachmentManager.shouldDiscardQueuedUpload(rowExists = true, referencedElsewhere = true))
        assertFalse(AttachmentManager.shouldDiscardQueuedUpload(rowExists = false, referencedElsewhere = true))
    }
}
