package com.financeos.shared.lansync

/**
 * 配对码：由 10 个字符组成，如 `K7M2QX4T9A`。
 *
 * 使用 32 个易区分的大写字母和数字，共有 50 位熵。同一配对码仅在本次接收会话中有效。
 */
object LanPairing {
    /** A–Z 和 0–9 去掉 0/O/1/I 后的 32 个字符。 */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 配对码字符数。 */
    const val CODE_LENGTH = 10

    /** 生成新的随机配对码。 */
    fun generate(): String {
        val bytes = LanSyncCrypto.randomBytes(CODE_LENGTH)
        return bytes.joinToString("") { byte -> ALPHABET[(byte.toInt() and 0xFF) % ALPHABET.length].toString() }
    }

    /** 校验用户输入的配对码格式（长度与字母表）。 */
    fun isValid(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { character -> character in ALPHABET }
}
