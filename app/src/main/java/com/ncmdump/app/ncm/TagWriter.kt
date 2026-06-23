package com.ncmdump.app.ncm

import com.ncmdump.app.crypto.AesEcb
import com.ncmdump.app.crypto.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 给解密后的音频文件写入元数据（标题/艺术家/专辑）和封面图。
 *
 * - MP3: 在文件最前面插入一个 ID3v2.3 标签头
 * - FLAC: 在 STREAMINFO 之后插入 VORBIS_COMMENT 和 PICTURE metadata block
 *
 * 注意：这些操作只是在音频流外面"包一层"容器元数据，不触碰任何音频帧，
 * 因此完全无损，解码出的波形与不加标签时逐字节一致。
 */
object TagWriter {

    /**
     * 为 MP3 生成一个 ID3v2.3 标签字节块，应写在音频数据**之前**。
     */
    fun buildMp3Id3Tag(meta: NcmMetadata?, image: ByteArray?, imageMime: String): ByteArray {
        val frames = ByteArrayOutputStream()

        meta?.musicName?.let { frames.write(textFrame("TIT2", it)) }
        meta?.artist?.let { frames.write(textFrame("TPE1", it)) }
        meta?.album?.let { frames.write(textFrame("TALB", it)) }

        if (image != null && image.isNotEmpty()) {
            frames.write(apicFrame(image, imageMime))
        }

        val frameData = frames.toByteArray()
        if (frameData.isEmpty()) return ByteArray(0)

        val out = ByteArrayOutputStream()
        // ID3v2 header: "ID3" + version(2.3.0) + flags + size(synchsafe)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
        out.write(0x03) // version major 3
        out.write(0x00) // version revision
        out.write(0x00) // flags
        out.write(synchsafe(frameData.size))
        out.write(frameData)
        return out.toByteArray()
    }

    /** ID3v2.3 文本帧，使用 UTF-16 编码以支持中日韩文字 */
    private fun textFrame(id: String, text: String): ByteArray {
        // encoding 0x01 = UTF-16 with BOM
        val textBytes = text.toByteArray(Charsets.UTF_16) // 带 BOM
        val content = ByteArrayOutputStream()
        content.write(0x01) // encoding byte
        content.write(textBytes)
        // UTF-16 字符串以 0x00 0x00 结尾
        content.write(byteArrayOf(0x00, 0x00))
        return frameWrapper(id, content.toByteArray())
    }

    /** ID3v2.3 APIC（封面图）帧 */
    private fun apicFrame(image: ByteArray, mime: String): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(0x00) // encoding: ISO-8859-1（用于描述文本）
        content.write(mime.toByteArray(Charsets.ISO_8859_1))
        content.write(0x00) // mime 字符串结尾
        content.write(0x03) // picture type: 3 = front cover
        content.write(0x00) // description（空）+ 结尾
        content.write(image)
        return frameWrapper("APIC", content.toByteArray())
    }

    /** 给帧内容加上 4 字节帧 ID + 4 字节大小 + 2 字节标志 */
    private fun frameWrapper(id: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1)) // 4 字节帧 ID
        // ID3v2.3 帧大小是普通的 big-endian 32 位（非 synchsafe）
        out.write(beInt(content.size))
        out.write(byteArrayOf(0x00, 0x00)) // flags
        out.write(content)
        return out.toByteArray()
    }

    /** ID3v2 header 的 synchsafe 整数（每字节只用低 7 位） */
    private fun synchsafe(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte()
        )
    }

    private fun beInt(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    // ===================== FLAC =====================

    /**
     * 给一个已解密的 FLAC 数据流插入 VORBIS_COMMENT 和 PICTURE block。
     * 由于 FLAC 的 metadata block 必须紧跟在 "fLaC" 标记后，
     * 我们需要先把原始 FLAC 读入、解析 metadata block 链，再插入新 block。
     *
     * 这里采用流式重写：读 header → 写 header + 新 block → 拷贝剩余数据。
     */
    fun buildFlacBlocks(meta: NcmMetadata?, image: ByteArray?, imageMime: String): FlacInsertBlocks {
        val vorbis = buildVorbisComment(meta)
        val picture = if (image != null && image.isNotEmpty())
            buildFlacPicture(image, imageMime) else null
        return FlacInsertBlocks(vorbis, picture)
    }

    data class FlacInsertBlocks(val vorbisComment: ByteArray, val picture: ByteArray?)

    /** 构建 VORBIS_COMMENT block 的内容（不含 4 字节 block header） */
    private fun buildVorbisComment(meta: NcmMetadata?): ByteArray {
        val vendor = "ncmdump-android".toByteArray(Charsets.UTF_8)
        val comments = mutableListOf<String>()
        meta?.musicName?.let { comments.add("TITLE=$it") }
        meta?.artist?.let { comments.add("ARTIST=$it") }
        meta?.album?.let { comments.add("ALBUM=$it") }

        val out = ByteArrayOutputStream()
        out.write(leInt(vendor.size))
        out.write(vendor)
        out.write(leInt(comments.size))
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            out.write(leInt(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** 构建 PICTURE block 的内容（不含 4 字节 block header） */
    private fun buildFlacPicture(image: ByteArray, mime: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(beInt(3)) // picture type: 3 = front cover
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        out.write(beInt(mimeBytes.size))
        out.write(mimeBytes)
        out.write(beInt(0)) // description length = 0
        out.write(beInt(0)) // width（未知填 0）
        out.write(beInt(0)) // height
        out.write(beInt(0)) // color depth
        out.write(beInt(0)) // number of colors
        out.write(beInt(image.size))
        out.write(image)
        return out.toByteArray()
    }

    /** FLAC metadata block header：1 字节(last-flag<<7 | type) + 3 字节长度 */
    fun flacBlockHeader(type: Int, length: Int, isLast: Boolean): ByteArray {
        val first = ((if (isLast) 0x80 else 0x00) or (type and 0x7F))
        return byteArrayOf(
            first.toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
    }

    private fun leInt(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
