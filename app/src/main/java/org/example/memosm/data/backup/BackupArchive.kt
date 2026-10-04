package org.example.memosm.data.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType

/** Streaming, portable container. SHA-256 covers the header and compressed/encrypted payload. */
object BackupArchive {
    const val EXTENSION = "mmbackup"
    const val MIME_TYPE = "application/octet-stream"
    const val FORMAT_VERSION = 1
    const val SCHEMA_VERSION = 1
    const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_NODES = 2_000_000
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024
    private const val ITERATIONS = 1_300_000
    private val magic = byteArrayOf(77, 77, 66, 65, 67, 75, 85, 80) // MMBACKUP
    const val HEADER_BYTES = 8 + 4 + 4 + 1 + 4 + 16 + 12
    private const val CHECKSUM_BYTES = 32

    data class Decoded(val metadata: JsonObject, val blobs: Map<String, File>, val directory: File) : Closeable {
        override fun close() { directory.deleteRecursively() }
    }

    fun isEncrypted(source: File): Boolean = source.inputStream().use { readHeader(it).encrypted }

    fun write(target: File, metadata: JsonObject, blobs: Map<String, File>, password: CharArray? = null) {
        require(password == null || password.isNotEmpty()) { "Password must not be empty" }
        validateMetadata(metadata)
        require(blobs.size <= 100_000) { "Too many attachment files" }
        target.parentFile!!.mkdirs()
        val payload = File.createTempFile(".payload-", ".tmp", target.parentFile)
        val temp = File.createTempFile(".archive-", ".tmp", target.parentFile)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.write(magic); it.writeInt(FORMAT_VERSION); it.writeInt(SCHEMA_VERSION); it.writeBoolean(password != null)
                it.writeInt(if (password != null) ITERATIONS else 0); it.write(salt); it.write(nonce)
            }
        }.toByteArray()
        try {
            val output = payload.outputStream().buffered()
            val protected = if (password == null) output else EncryptedOutput(output, password, salt, nonce, header)
            MessagePack.newDefaultPacker(GZIPOutputStream(protected)).use { packer ->
                writeValue(packer, metadata)
                packer.packMapHeader(blobs.size)
                val buffer = ByteArray(64 * 1024)
                var expanded = 0L
                blobs.forEach { (id, file) ->
                    require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid attachment id" }
                    val size = file.length()
                    require(size in 0..Int.MAX_VALUE.toLong()) { "Attachment is too large" }
                    expanded += size
                    require(expanded <= MAX_EXPANDED_BYTES) { "Backup is too large" }
                    packer.packString(id); packer.packBinaryHeader(size.toInt())
                    file.inputStream().use { input ->
                        var remaining = size
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            require(n > 0) { "Attachment changed during backup" }
                            packer.writePayload(buffer, 0, n); remaining -= n
                        }
                        require(input.read() == -1) { "Attachment changed during backup" }
                    }
                }
            }
            require(payload.length() + HEADER_BYTES + CHECKSUM_BYTES <= MAX_ARCHIVE_BYTES) { "Backup is too large" }
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { file ->
                val outputDigest = DigestOutputStream(file, digest)
                outputDigest.write(header)
                payload.inputStream().use { it.copyTo(outputDigest) }
                outputDigest.on(false)
                outputDigest.write(digest.digest())
                outputDigest.flush(); file.fd.sync()
            }
            check(temp.renameTo(target)) { "Could not finalize backup" }
        } finally { payload.delete(); temp.delete() }
    }

    /** Verifies the entire archive before returning anything that may be applied to storage. */
    fun read(source: File, stagingRoot: File, password: CharArray? = null): Decoded {
        require(source.isFile && source.length() in (HEADER_BYTES + CHECKSUM_BYTES + 1L)..MAX_ARCHIVE_BYTES) { "Invalid backup size" }
        source.inputStream().use { readHeader(it) }
        verifyChecksum(source)
        val directory = File(stagingRoot, "decode-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs()) { "Could not prepare backup storage" }
        try {
            source.inputStream().buffered().use { raw ->
                val header = readHeader(raw)
                if (header.encrypted && password == null) throw PasswordRequiredException()
                val bounded = BoundedInputStream(raw, source.length() - HEADER_BYTES - CHECKSUM_BYTES)
                val protected = if (!header.encrypted) bounded else EncryptedInput(bounded, password!!, header.salt, header.nonce, header.bytes)
                val gzip = LimitedInputStream(GZIPInputStream(protected), MAX_EXPANDED_BYTES)
                MessagePack.newDefaultUnpacker(gzip).use { unpacker ->
                    val metadata = readValue(unpacker, Budget()).asJsonObject
                    val count = unpacker.unpackMapHeader()
                    require(count in 0..100_000) { "Too many attachment files" }
                    val blobs = linkedMapOf<String, File>()
                    val buffer = ByteArray(64 * 1024)
                    repeat(count) {
                        val id = readString(unpacker)
                        require(id.matches(Regex("[a-f0-9]{64}")) && id !in blobs) { "Invalid attachment id" }
                        val size = unpacker.unpackBinaryHeader()
                        require(size >= 0 && directory.usableSpace > size.toLong() + 1024 * 1024) { "Insufficient backup storage" }
                        val file = File(directory, id)
                        val digest = MessageDigest.getInstance("SHA-256")
                        file.outputStream().use { output ->
                            var remaining = size
                            while (remaining > 0) {
                                val n = minOf(buffer.size, remaining)
                                unpacker.readPayload(buffer, 0, n); output.write(buffer, 0, n)
                                digest.update(buffer, 0, n); remaining -= n
                            }
                            output.fd.sync()
                        }
                        require(hex(digest.digest()) == id) { "Attachment checksum mismatch" }
                        blobs[id] = file
                    }
                    require(!unpacker.hasNext()) { "Unexpected backup content" }
                    // Drain the underlying cipher as well: GCM authentication must finish before restore.
                    while (protected.read(buffer) != -1) { /* authenticate trailing cipher blocks */ }
                    return Decoded(metadata, blobs, directory)
                }
            }
        } catch (e: Exception) {
            directory.deleteRecursively()
            if (e is PasswordRequiredException) throw e
            if (generateSequence<Throwable>(e) { it.cause }.any { it is AEADBadTagException }) throw BackupPasswordException()
            throw e
        }
    }

    fun fileId(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        hex(digest.digest())
    }

    internal fun writeValue(packer: MessagePacker, value: JsonElement) {
        when {
            value.isJsonNull -> packer.packNil()
            value.isJsonObject -> {
                packer.packMapHeader(value.asJsonObject.size())
                value.asJsonObject.entrySet().forEach { (key, item) -> packer.packString(key); writeValue(packer, item) }
            }
            value.isJsonArray -> { packer.packArrayHeader(value.asJsonArray.size()); value.asJsonArray.forEach { writeValue(packer, it) } }
            value.asJsonPrimitive.isBoolean -> packer.packBoolean(value.asBoolean)
            value.asJsonPrimitive.isString -> packer.packString(value.asString)
            else -> {
                val n = value.asBigDecimal
                if (n.stripTrailingZeros().scale() <= 0) packer.packBigInteger(n.toBigIntegerExact()) else packer.packDouble(n.toDouble())
            }
        }
    }

    private fun validateMetadata(value: JsonElement) {
        var nodes = 0
        var stringBytes = 0L
        fun visit(item: JsonElement, depth: Int) {
            require(depth <= 64 && ++nodes <= MAX_NODES) { "Backup metadata is too complex" }
            when {
                item.isJsonObject -> item.asJsonObject.entrySet().forEach { (key, child) ->
                    stringBytes += key.toByteArray(Charsets.UTF_8).size
                    visit(child, depth + 1)
                }
                item.isJsonArray -> item.asJsonArray.forEach { visit(it, depth + 1) }
                item.isJsonPrimitive && item.asJsonPrimitive.isString -> {
                    val bytes = item.asString.toByteArray(Charsets.UTF_8).size
                    require(bytes <= MAX_STRING_BYTES) { "Backup string is too large" }
                    stringBytes += bytes
                }
            }
            require(stringBytes <= 64L * 1024 * 1024 && nodes * 48L + stringBytes * 2 <= 128L * 1024 * 1024) { "Backup metadata is too large" }
        }
        visit(value, 0)
    }

    private class Budget(var nodes: Int = 0, var stringBytes: Long = 0)
    private fun readValue(unpacker: MessageUnpacker, budget: Budget, depth: Int = 0): JsonElement {
        require(depth <= 64 && ++budget.nodes <= MAX_NODES && budget.nodes * 48L + budget.stringBytes * 2 <= 128L * 1024 * 1024) { "Backup metadata is too complex" }
        return when (unpacker.nextFormat.valueType) {
            ValueType.NIL -> { unpacker.unpackNil(); JsonNull.INSTANCE }
            ValueType.BOOLEAN -> JsonPrimitive(unpacker.unpackBoolean())
            ValueType.INTEGER -> JsonPrimitive(unpacker.unpackBigInteger())
            ValueType.FLOAT -> JsonPrimitive(unpacker.unpackDouble().also { require(it.isFinite()) })
            ValueType.STRING -> JsonPrimitive(readString(unpacker, budget))
            ValueType.ARRAY -> JsonArray().also { array ->
                val count = unpacker.unpackArrayHeader(); require(count in 0..MAX_NODES)
                repeat(count) { array.add(readValue(unpacker, budget, depth + 1)) }
            }
            ValueType.MAP -> JsonObject().also { obj ->
                val count = unpacker.unpackMapHeader(); require(count in 0..MAX_NODES)
                repeat(count) {
                    val key = readString(unpacker, budget)
                    require(!obj.has(key)) { "Duplicate backup field" }
                    obj.add(key, readValue(unpacker, budget, depth + 1))
                }
            }
            else -> error("Unsupported backup metadata value")
        }
    }

    private fun readString(unpacker: MessageUnpacker, budget: Budget = Budget()): String {
        val size = unpacker.unpackRawStringHeader()
        require(size in 0..MAX_STRING_BYTES) { "Backup string is too large" }
        budget.stringBytes += size
        require(budget.stringBytes <= 64L * 1024 * 1024 && budget.nodes * 48L + budget.stringBytes * 2 <= 128L * 1024 * 1024) { "Backup metadata is too large" }
        return String(unpacker.readPayload(size), Charsets.UTF_8)
    }

    private data class Header(val encrypted: Boolean, val salt: ByteArray, val nonce: ByteArray, val bytes: ByteArray)
    private fun readHeader(input: InputStream): Header {
        val bytes = ByteArray(HEADER_BYTES)
        DataInputStream(input).readFully(bytes)
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val found = ByteArray(8).also { data.readFully(it) }
        require(found.contentEquals(magic)) { "Not a .mmbackup file (invalid magic signature)" }
        require(data.readInt() == FORMAT_VERSION) { "Unsupported backup format version" }
        require(data.readInt() == SCHEMA_VERSION) { "Unsupported backup schema version" }
        val flag = data.readUnsignedByte(); require(flag in 0..1) { "Unsupported backup flags" }
        val encrypted = flag == 1
        require(data.readInt() == if (encrypted) ITERATIONS else 0) { "Unsupported password derivation" }
        return Header(encrypted, ByteArray(16).also { data.readFully(it) }, ByteArray(12).also { data.readFully(it) }, bytes)
    }

    /** Encrypt bounded frames so providers never buffer a whole GCM message in memory. */
    private class FrameCipher(password: CharArray, salt: ByteArray, private val baseNonce: ByteArray, private val header: ByteArray) : Closeable {
        private val key: ByteArray
        init {
            val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
            key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }
        fun process(mode: Int, frame: Long, bytes: ByteArray, size: Int): ByteArray {
            val nonce = baseNonce.copyOf()
            repeat(8) { offset -> nonce[nonce.lastIndex - offset] = (nonce[nonce.lastIndex - offset].toInt() xor (frame ushr (offset * 8)).toInt()).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.updateAAD(java.nio.ByteBuffer.allocate(8).putLong(frame).array())
            return cipher.doFinal(bytes, 0, size)
        }
        override fun close() { key.fill(0) }
    }
    private class EncryptedOutput(output: OutputStream, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray) : OutputStream() {
        private val output = DataOutputStream(output)
        private val cipher = FrameCipher(password, salt, nonce, header)
        private val buffer = ByteArray(64 * 1024)
        private var used = 0
        private var frame = 0L
        override fun write(b: Int) { buffer[used++] = b.toByte(); if (used == buffer.size) emit() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off; var remaining = len
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size - used)
                b.copyInto(buffer, used, offset, offset + count)
                used += count; offset += count; remaining -= count
                if (used == buffer.size) emit()
            }
        }
        private fun emit() {
            val encrypted = cipher.process(Cipher.ENCRYPT_MODE, frame++, buffer, used)
            output.writeInt(encrypted.size); output.write(encrypted); used = 0
        }
        override fun close() {
            try { if (used > 0) emit(); emit(); output.flush() } // authenticated empty final frame
            finally { cipher.close(); output.close() }
        }
    }
    private class EncryptedInput(input: InputStream, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray) : InputStream() {
        private val input = DataInputStream(input)
        private val cipher = FrameCipher(password, salt, nonce, header)
        private var buffer = byteArrayOf()
        private var offset = 0
        private var frame = 0L
        private var finished = false
        private fun next(): Boolean {
            if (finished) return false
            val size = input.readInt()
            require(size in 16..(64 * 1024 + 16)) { "Invalid encrypted backup frame" }
            val encrypted = ByteArray(size).also { input.readFully(it) }
            buffer = cipher.process(Cipher.DECRYPT_MODE, frame++, encrypted, size); offset = 0
            if (buffer.isEmpty()) {
                finished = true
                require(input.read() == -1) { "Unexpected encrypted backup content" }
                return false
            }
            return true
        }
        override fun read(): Int { if (offset == buffer.size && !next()) return -1; return buffer[offset++].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (offset == buffer.size && !next()) return -1
            val count = minOf(len, buffer.size - offset)
            buffer.copyInto(b, off, offset, offset + count); offset += count
            return count
        }
        override fun close() { cipher.close(); input.close() }
    }

    private fun verifyChecksum(source: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = source.length() - CHECKSUM_BYTES
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(n > 0) { "Truncated backup" }; digest.update(buffer, 0, n); remaining -= n
            }
            val expected = ByteArray(CHECKSUM_BYTES).also { DataInputStream(input).readFully(it) }
            require(MessageDigest.isEqual(expected, digest.digest())) { "Backup checksum mismatch" }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int { if (remaining == 0L) return -1; return super.read().also { if (it >= 0) remaining-- } }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) return -1
            return `in`.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
        }
    }
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        private fun account(n: Int): Int { if (n > 0) count += n; require(count <= limit) { "Expanded backup is too large" }; return n }
        override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = account(`in`.read(b, off, len))
    }
}
