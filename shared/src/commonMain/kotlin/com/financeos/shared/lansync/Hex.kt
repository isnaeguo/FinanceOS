package com.financeos.shared.lansync

/** 十六进制编解码，用于请求/响应头携带 salt、nonce。 */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(DIGITS[value ushr 4])
            append(DIGITS[value and 0x0F])
        }
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val result = ByteArray(text.length / 2)
        for (index in result.indices) {
            val high = text[index * 2].digitToIntOrNull(16) ?: return null
            val low = text[index * 2 + 1].digitToIntOrNull(16) ?: return null
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
