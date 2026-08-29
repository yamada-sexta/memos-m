package org.example.memosm.viewmodel.manager

import org.example.memosm.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentManagerTest {
    @Test
    fun `thumbnail URL uses server attachment route`() {
        val attachment = Attachment(
            name = "attachments/image-id",
            filename = "photo.jpg",
            type = "image/jpeg"
        )

        assertEquals(
            "https://memos.example/file/attachments/image-id/photo.jpg?thumbnail=true",
            AttachmentManager.getAttachmentThumbnailUrl("https://memos.example/", attachment)
        )
    }

    @Test
    fun `thumbnail URL uses server route instead of regular external link`() {
        val attachment = Attachment(
            name = "attachments/image-id",
            filename = "photo.jpg",
            externalLink = "https://storage.example/photo.jpg",
            type = "image/jpeg"
        )

        assertEquals(
            "https://memos.example/file/attachments/image-id/photo.jpg?thumbnail=true",
            AttachmentManager.getAttachmentThumbnailUrl("https://memos.example", attachment)
        )
    }

    @Test
    fun `thumbnail URL preserves share token and fragment`() {
        val attachment = Attachment(
            name = "attachments/image-id",
            filename = "photo.jpg",
            externalLink = "/file/attachments/image-id/photo.jpg?share_token=secret#image",
            type = "image/jpeg"
        )

        assertEquals(
            "https://memos.example/file/attachments/image-id/photo.jpg?share_token=secret&thumbnail=true#image",
            AttachmentManager.getAttachmentThumbnailUrl("https://memos.example", attachment)
        )
    }

    @Test
    fun `thumbnail URL replaces existing thumbnail parameter`() {
        val attachment = Attachment(
            filename = "photo.jpg",
            externalLink = "https://memos.example/file/attachments/id/photo.jpg?thumbnail=false&share_token=secret",
            type = "image/jpeg"
        )

        assertEquals(
            "https://memos.example/file/attachments/id/photo.jpg?thumbnail=true&share_token=secret",
            AttachmentManager.getAttachmentThumbnailUrl("https://memos.example", attachment)
        )
    }
}
