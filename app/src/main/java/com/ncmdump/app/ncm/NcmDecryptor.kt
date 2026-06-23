package com.ncmdump.app.ncm

import com.ncmdump.app.crypto.AesEcb
import com.ncmdump.app.crypto.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Pure Kotlin port of the ncmdump NCM decryption engine.
 *
 * Usage:
 *   val stream = contentResolver.openInputStream(uri)!!
 *   stream.use {
 *       val dec = NcmDecryptor()
 *       dec.parseHeader(it)
 *       val format = dec.probeFormat(it)   // reads+decrypts first 16 bytes, buffers them
 *       val outStream = ...
 *       dec.decryptRemaining(it, outStream) // writes buffered bytes + rest
 *   }
 */
class NcmDecryptor {

    companion object {
        private val CORE_KEY = byteArrayOf(
            0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
            0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
        )
        private val MODIFY_KEY = byteArrayOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C.toByte(), 0x5D.toByte(), 0x26, 0x30, 0x55, 0x3C.toByte(), 0x27, 0x28
        )
        private val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }

    var metadataJson: String? = null
        private set
    var imageData: ByteArray? = null
        private set

    private val keyBox = IntArray(256)

    // Bytes peeked by probeFormat (already decrypted), to be written first by decryptRemaining
    private var pendingDecrypted: ByteArray = ByteArray(0)
    private var pendingCount: Int = 0

    /**
     * Parse the NCM header from [stream].
     * After this returns, [stream] is positioned at the start of the encrypted audio data.
     */
    fun parseHeader(stream: InputStream) {
        val magic = ByteArray(8)
        readFully(stream, magic)
        if (String(magic, Charsets.US_ASCII) != "CTENFDAM") {
            throw NcmException("不是有效的 NCM 文件（文件头错误）")
        }
        skipFully(stream, 2L) // gap

        // --- RC4 seed key ---
        val keyLen = readUint32Le(stream)
        if (keyLen <= 0) throw NcmException("NCM 文件损坏（密钥长度为 0）")
        val keyData = ByteArray(keyLen)
        readFully(stream, keyData)
        for (i in keyData.indices) keyData[i] = (keyData[i].toInt() xor 0x64).toByte()
        val decryptedKey = AesEcb.decrypt(CORE_KEY, keyData)
        // First 17 bytes = "neteasecloudmusic", rest is the RC4 seed
        buildKeyBox(decryptedKey, 17, decryptedKey.size - 17)

        // --- Metadata ---
        val metaLen = readUint32Le(stream)
        if (metaLen > 0) {
            val metaData = ByteArray(metaLen)
            readFully(stream, metaData)
            for (i in metaData.indices) metaData[i] = (metaData[i].toInt() xor 0x63).toByte()
            // Skip "163 key(Don't modify):" (22 bytes)
            val b64 = String(metaData, 22, metaData.size - 22, Charsets.US_ASCII)
            val decoded = Base64.decode(b64)
            val decrypted = AesEcb.decrypt(MODIFY_KEY, decoded)
            // Skip "music:" (6 bytes)
            metadataJson = String(decrypted, 6, decrypted.size - 6, Charsets.UTF_8)
        }

        // --- Skip CRC32 (4 bytes) + image version (1 byte) ---
        skipFully(stream, 5L)

        // --- Cover image ---
        val coverFrameLen = readUint32Le(stream)
        val imageLen = readUint32Le(stream)
        if (imageLen > 0) {
            imageData = ByteArray(imageLen)
            readFully(stream, imageData!!)
            val remaining = coverFrameLen - imageLen
            if (remaining > 0) skipFully(stream, remaining.toLong())
        } else {
            if (coverFrameLen > 0) skipFully(stream, coverFrameLen.toLong())
        }
    }

    /**
     * Read and decrypt the first up to 16 bytes of audio data to detect the format.
     * The decrypted bytes are buffered internally and will be written first by [decryptRemaining].
     * Returns "mp3" or "flac".
     */
    fun probeFormat(stream: InputStream): String {
        val probe = ByteArray(16)
        val n = stream.read(probe).coerceAtLeast(0)

        for (i in 0 until n) {
            val j = (i + 1) and 0xFF
            val ki = keyBox[j]
            val kj = keyBox[(ki + j) and 0xFF]
            probe[i] = (probe[i].toInt() xor keyBox[(ki + kj) and 0xFF]).toByte()
        }

        pendingDecrypted = probe
        pendingCount = n

        return if (n >= 3 &&
            probe[0] == 0x49.toByte() &&
            probe[1] == 0x44.toByte() &&
            probe[2] == 0x33.toByte()
        ) "mp3" else "flac"
    }

    /**
     * Decrypt all remaining audio data from [stream] and write to [output].
     * Must be called after [probeFormat].
     */
    fun decryptRemaining(stream: InputStream, output: OutputStream) {
        // Write the bytes already decrypted by probeFormat
        if (pendingCount > 0) {
            output.write(pendingDecrypted, 0, pendingCount)
        }

        // Continue decryption; XOR index continues from pendingCount
        val buffer = ByteArray(0x8000)
        var offset = pendingCount

        while (true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            for (i in 0 until n) {
                val absI = offset + i
                val j = (absI + 1) and 0xFF
                val ki = keyBox[j]
                val kj = keyBox[(ki + j) and 0xFF]
                buffer[i] = (buffer[i].toInt() xor keyBox[(ki + kj) and 0xFF]).toByte()
            }
            output.write(buffer, 0, n)
            offset += n
        }
        output.flush()
    }

    fun imageMimeType(): String {
        val img = imageData ?: return "image/jpeg"
        return if (img.size >= 8 && img.copyOf(8).contentEquals(PNG_HEADER)) "image/png"
        else "image/jpeg"
    }

    fun parseMetadata(): NcmMetadata? {
        val json = metadataJson ?: return null
        return try {
            val obj = JSONObject(json)
            NcmMetadata(
                musicName = obj.optString("musicName").takeIf { it.isNotEmpty() },
                album = obj.optString("album").takeIf { it.isNotEmpty() },
                artist = buildArtistString(obj),
                bitrate = obj.optInt("bitrate", 0),
                duration = obj.optInt("duration", 0),
                format = obj.optString("format").takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) { null }
    }

    private fun buildArtistString(obj: JSONObject): String? {
        val arr = obj.optJSONArray("artist") ?: return null
        return (0 until arr.length())
            .mapNotNull { arr.optJSONArray(it)?.optString(0) }
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Build the RC4-like key box from [key] starting at [keyOffset] for [keyLen] bytes.
     */
    private fun buildKeyBox(key: ByteArray, keyOffset: Int, keyLen: Int) {
        for (i in 0 until 256) keyBox[i] = i
        var c: Int
        var lastByte = 0
        var kOff = 0
        for (i in 0 until 256) {
            val swap = keyBox[i]
            c = (swap + lastByte + (key[keyOffset + kOff].toInt() and 0xFF)) and 0xFF
            kOff = (kOff + 1) % keyLen
            keyBox[i] = keyBox[c]
            keyBox[c] = swap
            lastByte = c
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) throw NcmException("文件意外结束")
            off += n
        }
    }

    private fun readUint32Le(stream: InputStream): Int {
        val b = ByteArray(4)
        readFully(stream, b)
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun skipFully(stream: InputStream, n: Long) {
        var rem = n
        while (rem > 0) {
            val skipped = stream.skip(rem)
            if (skipped <= 0) throw NcmException("跳过数据时文件意外结束")
            rem -= skipped
        }
    }
}

data class NcmMetadata(
    val musicName: String?,
    val album: String?,
    val artist: String?,
    val bitrate: Int,
    val duration: Int,
    val format: String?
)

class NcmException(message: String) : Exception(message)
