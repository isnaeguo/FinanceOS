package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.TransactionType

/**
 * 表格导入的稳定行 ID，三端统一的去重指纹，以 Apple 端的 FNV-1a 64 位实现为权威基准。
 *
 * 无业务订单号时，指纹输入串固定为
 * `"{dateMillis}|{amountMinor}|{in|out}|{note 前 48 字符}|{counterparty 前 24 字符}"`；
 * 各段先去除首尾空白再删除全部空白字符（正则 `\s+`）。输出为 `bill-` + 64 位小写十六进制，
 * 不做前导零填充，与 Swift 的 `String(hash, radix: 16)` 逐字节一致。
 * 同一份账单在任何端导入必须得到相同 ID，合并去重才能正确识别同一条记录。
 */
object StableRowId {
    // FNV-1a 参数与 Apple 端实现保持逐字节一致；offset basis 沿用 Swift 侧常量（非标准 basis 值）。
    private const val FNV_OFFSET_BASIS = 1469598103934665603UL
    private const val FNV_PRIME = 1099511628211UL

    /** 优先使用业务订单号；无订单号时由时间/金额/方向/内容指纹派生。 */
    fun generate(
        orderId: String,
        dateMillis: Long,
        amountMinor: Long,
        type: TransactionType,
        note: String,
        counterparty: String,
    ): String {
        if (orderId.isNotBlank()) return "bill-" + clean(orderId).take(64)
        val body = listOf(
            dateMillis.toString(),
            amountMinor.toString(),
            if (type == TransactionType.INCOME) "in" else "out",
            clean(note).take(48),
            clean(counterparty).take(24),
        ).joinToString("|")
        return "bill-" + fnv1a64(body)
    }

    private fun clean(text: String): String = text.trim().replace(Regex("\\s+"), "")

    private fun fnv1a64(text: String): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            // Byte 是有符号类型，必须先按位截取低 8 位再参与异或，保证与 UTF-8 字节流逐字节一致。
            hash = (hash xor (byte.toLong() and 0xFF).toULong()) * FNV_PRIME
        }
        return hash.toString(16)
    }
}
