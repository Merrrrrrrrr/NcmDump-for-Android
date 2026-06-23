package com.ncmdump.app.crypto

/**
 * Minimal Base64 decoder matching the C++ implementation.
 */
object Base64 {

    private const val BAD = 64.toByte()

    private val DECODING_TABLE = byteArrayOf(
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 62, 64, 64, 64, 63,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 64, 64, 64, 64, 64, 64,
        64,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 64, 64, 64, 64, 64,
        64, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64
    )

    fun decode(input: String): ByteArray {
        if (input.length % 4 != 0) {
            throw IllegalArgumentException("Input data size is not a multiple of 4")
        }

        var outLen = input.length / 4 * 3
        if (input[input.length - 1] == '=') outLen--
        if (input[input.length - 2] == '=') outLen--

        val out = ByteArray(outLen)
        var i = 0
        var j = 0

        while (i < input.length) {
            val a = if (input[i] == '=') 0.also { i++ } else DECODING_TABLE[input[i++].code].toInt()
            val b = if (input[i] == '=') 0.also { i++ } else DECODING_TABLE[input[i++].code].toInt()
            val c = if (input[i] == '=') 0.also { i++ } else DECODING_TABLE[input[i++].code].toInt()
            val d = if (input[i] == '=') 0.also { i++ } else DECODING_TABLE[input[i++].code].toInt()

            val triple = (a shl 18) + (b shl 12) + (c shl 6) + d

            if (j < outLen) out[j++] = (triple shr 16 and 0xFF).toByte()
            if (j < outLen) out[j++] = (triple shr 8 and 0xFF).toByte()
            if (j < outLen) out[j++] = (triple and 0xFF).toByte()
        }

        return out
    }
}
