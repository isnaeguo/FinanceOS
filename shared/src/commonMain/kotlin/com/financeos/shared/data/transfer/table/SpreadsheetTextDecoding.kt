package com.financeos.shared.data.transfer.table

/**
 * 把表格文件原始字节解码为文本。
 *
 * 账单导出常见 GB18030/GBK 编码：先按 UTF-8 解释，出现替换字符时按 GB18030 重试，
 * 与 Android 端导入器的判定规则保持一致。两端实现不得改变字符语义。
 */
expect fun decodeSpreadsheetText(bytes: ByteArray): String
