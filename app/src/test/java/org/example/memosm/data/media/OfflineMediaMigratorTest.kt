package org.example.memosm.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineMediaMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun movesFilePreservingRelativePath() {
        val legacy = tmp.newFolder("a-legacy")
        val root = tmp.newFolder("a-root")
        val source = File(legacy, "acc1/attachments_1/img.png").apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }
        val target = OfflineMediaMigrator.movePreservingPath(legacy, root, source)
        assertEquals(File(root, "acc1/attachments_1/img.png").absolutePath, target?.absolutePath)
        assertEquals("bytes", target?.readText())
        assertFalse(source.exists())
    }

    @Test
    fun rerunAfterCompletedMoveAdoptsTarget() {
        // Crash after the file moved but before the DB row was updated:
        // source is gone, target exists -> the target is returned so the row
        // can be pointed at it.
        val legacy = tmp.newFolder("b-legacy")
        val root = tmp.newFolder("b-root")
        val target = File(root, "acc1/attachments_1/img.png").apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }
        val moved = OfflineMediaMigrator.movePreservingPath(
            legacy, root, File(legacy, "acc1/attachments_1/img.png")
        )
        assertEquals(target.absolutePath, moved?.absolutePath)
    }

    @Test
    fun bothPresentWithSameSizeDropsSource() {
        // Crash after copying but before deleting the source.
        val legacy = tmp.newFolder("c-legacy")
        val root = tmp.newFolder("c-root")
        val source = File(legacy, "acc1/f.bin").apply {
            parentFile?.mkdirs()
            writeText("same")
        }
        File(root, "acc1/f.bin").apply {
            parentFile?.mkdirs()
            writeText("same")
        }
        val moved = OfflineMediaMigrator.movePreservingPath(legacy, root, source)
        assertEquals(File(root, "acc1/f.bin").absolutePath, moved?.absolutePath)
        assertFalse(source.exists())
    }

    @Test
    fun bothPresentWithDifferentSizeReplacesTarget() {
        val legacy = tmp.newFolder("d-legacy")
        val root = tmp.newFolder("d-root")
        val source = File(legacy, "acc1/f.bin").apply {
            parentFile?.mkdirs()
            writeText("new-content")
        }
        File(root, "acc1/f.bin").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        val moved = OfflineMediaMigrator.movePreservingPath(legacy, root, source)
        assertEquals("new-content", moved?.readText())
        assertFalse(source.exists())
    }

    @Test
    fun sourceOutsideLegacyRootIsRejected() {
        val legacy = tmp.newFolder("e-legacy")
        val root = tmp.newFolder("e-root")
        val outside = tmp.newFile("elsewhere.bin")
        assertNull(OfflineMediaMigrator.movePreservingPath(legacy, root, outside))
        assertTrue(outside.exists())
    }

    @Test
    fun missingSourceAndTargetReturnsNull() {
        val legacy = tmp.newFolder("f-legacy")
        val root = tmp.newFolder("f-root")
        assertNull(
            OfflineMediaMigrator.movePreservingPath(
                legacy, root, File(legacy, "acc1/gone.bin")
            )
        )
    }
}
