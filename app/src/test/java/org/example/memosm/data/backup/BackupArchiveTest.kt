package org.example.memosm.data.backup

import com.google.gson.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun metadata() = JsonObject().apply {
        addProperty("token", "private-token")
        addProperty("text", "日本語 · café · 📝")
        addProperty("integer", Long.MAX_VALUE)
        addProperty("fraction", 1.25)
        addProperty("enabled", true)
        add("nested", JsonObject().apply { addProperty("name", "memos/1") })
    }

    @Test fun `binary compressed archive preserves nested values and attachment bytes`() {
        val blob = temp.newFile("image").apply { writeBytes(ByteArray(150_000) { (it % 251).toByte() }) }
        val id = BackupArchive.fileId(blob)
        val archive = temp.newFile("roundtrip.mmbackup")
        BackupArchive.write(archive, metadata(), mapOf(id to blob))
        assertFalse(BackupArchive.isEncrypted(archive))
        assertTrue(archive.length() < blob.length())
        BackupArchive.read(archive, temp.root).use { decoded ->
            assertEquals(metadata(), decoded.metadata)
            assertArrayEquals(blob.readBytes(), decoded.blobs.getValue(id).readBytes())
            assertEquals(temp.root.canonicalFile, decoded.directory.parentFile.canonicalFile)
        }
    }

    @Test fun `encrypted archive requires password and authenticates frames and header`() {
        val blob = temp.newFile("random").apply { writeBytes(ByteArray(180_000).also { java.security.SecureRandom().nextBytes(it) }) }
        val id = BackupArchive.fileId(blob)
        val archive = temp.newFile("encrypted.mmbackup")
        val password = "pässword 日本語".toCharArray()
        BackupArchive.write(archive, metadata(), mapOf(id to blob), password)
        assertTrue(BackupArchive.isEncrypted(archive))
        assertThrows(PasswordRequiredException::class.java) { BackupArchive.read(archive, temp.root) }
        assertThrows(BackupPasswordException::class.java) { BackupArchive.read(archive, temp.root, "wrong".toCharArray()) }
        BackupArchive.read(archive, temp.root, password).use { decoded ->
            assertEquals(metadata(), decoded.metadata)
            assertArrayEquals(blob.readBytes(), decoded.blobs.getValue(id).readBytes())
        }
        // An attacker recomputing the public checksum still cannot forge encrypted content.
        val bytes = archive.readBytes()
        bytes[70] = (bytes[70].toInt() xor 1).toByte()
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(bytes.size - 32))
        checksum.copyInto(bytes, bytes.size - 32)
        archive.writeBytes(bytes)
        assertThrows(BackupPasswordException::class.java) { BackupArchive.read(archive, temp.root, password) }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("decode-") })
    }

    @Test fun `magic and separate format and schema versions precede all binary content`() {
        val archive = temp.newFile("header.mmbackup")
        BackupArchive.write(archive, metadata(), emptyMap())
        java.io.DataInputStream(archive.inputStream()).use { input ->
            val magic = ByteArray(8).also { input.readFully(it) }
            assertEquals("MMBACKUP", magic.toString(Charsets.US_ASCII))
            assertEquals(BackupArchive.FORMAT_VERSION, input.readInt())
            assertEquals(BackupArchive.SCHEMA_VERSION, input.readInt())
        }
        val original = archive.readBytes()
        for (offset in listOf(8, 12)) {
            val changed = original.copyOf()
            java.nio.ByteBuffer.wrap(changed).putInt(offset, 999)
            archive.writeBytes(changed)
            val failure = assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(archive, temp.root) }
            assertTrue(failure.message!!.contains(if (offset == 8) "format version" else "schema version"))
        }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("decode-") })
    }

    @Test fun `checksum failure and truncation do not leave staged files`() {
        val archive = temp.newFile("damaged.mmbackup")
        BackupArchive.write(archive, metadata(), emptyMap())
        RandomAccessFile(archive, "rw").use { it.seek(55); val byte = it.read(); it.seek(55); it.write(byte xor 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(archive, temp.root) }
        archive.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(archive, temp.root) }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("decode-") })
    }

    @Test fun `files beyond the old 25 MB limit stream through the archive`() {
        val blob = temp.newFile("large")
        val buffer = ByteArray(64 * 1024)
        java.security.SecureRandom().nextBytes(buffer)
        blob.outputStream().use { out -> repeat(416) { out.write(buffer) } }
        val id = BackupArchive.fileId(blob)
        val archive = temp.newFile("large.mmbackup")
        BackupArchive.write(archive, metadata(), mapOf(id to blob))
        assertTrue(archive.length() > 25L * 1024 * 1024)
        BackupArchive.read(archive, temp.root).use { decoded ->
            assertEquals(blob.length(), decoded.blobs.getValue(id).length())
            assertEquals(id, BackupArchive.fileId(decoded.blobs.getValue(id)))
        }
    }

    @Test fun `unsafe ids and excessive nesting are rejected before export`() {
        val file = temp.newFile("source").apply { writeText("file") }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.write(File(temp.root, "unsafe.mmbackup"), metadata(), mapOf("../../escape" to file))
        }
        val root = JsonObject()
        var node = root
        repeat(70) { val child = JsonObject(); node.add("child", child); node = child }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(File(temp.root, "deep.mmbackup"), root, emptyMap()) }
    }

    @Test fun `queued edits are excluded by default and optimistic cache rows are never exported`() {
        assertFalse(BackupCategory.QUEUED_EDITS in BackupSelection(setOf("account")).categories)
        assertTrue(CacheBackupPolicy.canExport("memos/1", setOf("memos/2")))
        assertFalse(CacheBackupPolicy.canExport("memos/2", setOf("memos/2")))
        assertFalse(CacheBackupPolicy.canExport("offline-123", emptySet()))
        assertFalse(CacheBackupPolicy.canExport("", emptySet()))
    }
}
