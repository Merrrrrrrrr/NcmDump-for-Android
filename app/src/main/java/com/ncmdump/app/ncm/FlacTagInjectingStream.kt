package com.ncmdump.app.ncm

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 一个流式 OutputStream 包装器：在 FLAC 数据流写入时，于 metadata 区插入
 * VORBIS_COMMENT 和 PICTURE block，音频帧部分原样直通（不缓存、不修改）。
 *
 * 工作方式：
 *  1. 缓冲开头字节，直到完整解析出 "fLaC" + 所有 metadata block。
 *  2. 改写 block 链（移除原有的 VORBIS_COMMENT/PICTURE，追加我们的），写出。
 *  3. 之后切换为直通模式，后续音频帧直接转发给底层流。
 *
 * metadata 区通常只有几十 KB（含封面），缓冲它不会造成内存压力。
 */
class FlacTagInjectingStream(
    private val out: OutputStream,
    private val vorbisComment: ByteArray,
    private val picture: ByteArray?
) : OutputStream() {

    private val headerBuf = ByteArrayOutputStream()
    private var passthrough = false

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (passthrough) {
            out.write(b, off, len)
            return
        }
        headerBuf.write(b, off, len)
        tryProcess()
    }

    private fun tryProcess() {
        val data = headerBuf.toByteArray()
        if (data.size < 8) return // 至少要有 "fLaC" + 一个 block header

        // 校验 FLAC magic
        if (data[0] != 'f'.code.toByte() || data[1] != 'L'.code.toByte() ||
            data[2] != 'a'.code.toByte() || data[3] != 'C'.code.toByte()
        ) {
            // 不是 FLAC（理论不会发生）——直接直通，不注入
            out.write(data)
            headerBuf.reset()
            passthrough = true
            return
        }

        // 解析 metadata block 链
        var pos = 4
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        while (true) {
            if (pos + 4 > data.size) return // 数据不够，等更多
            val flagByte = data[pos].toInt() and 0xFF
            val isLast = (flagByte and 0x80) != 0
            val type = flagByte and 0x7F
            val length = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
            if (pos + 4 + length > data.size) return // block 体还没收齐
            val body = data.copyOfRange(pos + 4, pos + 4 + length)
            blocks.add(type to body)
            pos += 4 + length
            if (isLast) break
        }

        // metadata 全部收齐，开始改写
        // 移除原有的 VORBIS_COMMENT(4) 和 PICTURE(6)，用我们自己的替换
        val kept = blocks.filter { it.first != 4 && it.first != 6 }

        // 组装新 block 列表：保留的 block（STREAMINFO 等）+ 我们的 vorbis + picture
        val newBlocks = ArrayList<Pair<Int, ByteArray>>()
        newBlocks.addAll(kept)
        newBlocks.add(4 to vorbisComment) // VORBIS_COMMENT
        if (picture != null) newBlocks.add(6 to picture) // PICTURE

        // 写出：magic + 各 block（最后一个置 isLast 标志）
        out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
        for ((index, block) in newBlocks.withIndex()) {
            val (type, body) = block
            val isLast = index == newBlocks.size - 1
            out.write(TagWriter.flacBlockHeader(type, body.size, isLast))
            out.write(body)
        }

        // 写出 metadata 之后剩余的字节（音频帧的开头部分）
        if (pos < data.size) {
            out.write(data, pos, data.size - pos)
        }

        headerBuf.reset()
        passthrough = true
    }

    /**
     * 在所有数据写完后调用。处理极端情况：文件太小、metadata 还没解析完。
     * 正常 FLAC 不会触发，但兜底防止丢数据。
     */
    fun finish() {
        if (!passthrough) {
            // metadata 未能完整解析，原样吐出缓冲数据，不注入标签
            out.write(headerBuf.toByteArray())
            headerBuf.reset()
            passthrough = true
        }
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}
