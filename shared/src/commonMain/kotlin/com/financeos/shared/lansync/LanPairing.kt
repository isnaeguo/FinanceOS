package com.financeos.shared.lansync

/**
 * 配对码：10 字符 Base32（RFC 4648 无填充、去易混 0/O/1/I），如 `K7M2QX4T9A`。
 *
 * 说明：去混后字母表 28 字符，10 位实际熵约 48 bit；仍远超 6 位数字码（约 20 bit）的离线
 * 枚举可行边界，且仅在本次“接收会话”内有效。协议文档中如实记录该熵值。
 */
object LanPairing {
    /** 28 字符字母表：RFC 4648 Base32 去掉 0/O/1/I。 */
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
