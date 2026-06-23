package com.ncmdump.app.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the ncmdump C++ AES implementation.
 * We use Java's built-in Cipher for AES-128-ECB since it's
 * functionally identical and hardware-accelerated.
 */
object AesEcb {

    private const val ALGORITHM = "AES/ECB/NoPadding"

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        // Remove PKCS5Padding manually since we use NoPadding
        val decrypted = cipher.doFinal(data)
        // Manual PKCS5 unpad (same logic as the C++ code)
        return unpad(decrypted)
    }

    private fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xFF
        if (pad > 16 || pad == 0) return data
        // Verify padding
        for (i in data.size - pad until data.size) {
            if ((data[i].toInt() and 0xFF) != pad) return data
        }
        return data.copyOf(data.size - pad)
    }
}
