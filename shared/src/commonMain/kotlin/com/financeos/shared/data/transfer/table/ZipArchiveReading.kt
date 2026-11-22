package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException

/**
 * 从 XLSX 压缩包读取工作簿 XML 条目，忽略媒体、附件和其他无关数据。
 * 对支持的存储和 Deflate 压缩条目校验文件边界与解压长度。
 */
object ZipArchiveReader {
    fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        val central = locateCentralDirectory(bytes) ?: return entries
        if (central.entryCount > 4_096) invalidArchive()
        var offset = central.offset
        var totalSize = 0L
        repeat(central.entryCount) {
            if (offset < 0 || offset > bytes.size - 46 || readUInt32(bytes, offset) != 0x02014B50L) invalidArchive()
            val flags = readUInt16(bytes, offset + 8).toInt()
            val method = readUInt16(bytes, offset + 10).toInt()
            val compressedSize = readUInt32(bytes, offset + 20)
            val uncompressedSize = readUInt32(bytes, offset + 24)
            val nameLength = readUInt16(bytes, offset + 28).toInt()
            val extraLength = readUInt16(bytes, offset + 30).toInt()
            val commentLength = readUInt16(bytes, offset + 32).toInt()
            val localOffset = readUInt32(bytes, offset + 42)
            val next = offset.toLong() + 46 + nameLength + extraLength + commentLength
            if (next > bytes.size) invalidArchive()
            val name = bytes.decodeToString(offset + 46, offset + 46 + nameLength).removePrefix("/")
            offset = next.toInt()
            if (!name.startsWith("xl/") || !(name.endsWith(".xml") || name.endsWith(".rels"))) return@repeat
            if (flags and 1 != 0) {
                throw DataTransferException("账单压缩包已加密，请先解压再导入。")
            }
            totalSize += uncompressedSize
            if (uncompressedSize > MAX_PART_BYTES || totalSize > MAX_ARCHIVE_BYTES) {
                throw DataTransferException("XLSX 解压后过大，请按时间范围拆分再导入。")
            }
            if (localOffset > bytes.size - 30L) invalidArchive()
            val local = localOffset.toInt()
            if (readUInt32(bytes, local) != 0x04034B50L) invalidArchive()
            val dataStart = localOffset + 30 + readUInt16(bytes, local + 26) + readUInt16(bytes, local + 28)
            if (dataStart + compressedSize > bytes.size) invalidArchive()
            val raw = bytes.copyOfRange(dataStart.toInt(), (dataStart + compressedSize).toInt())
            val content = when (method) {
                0 -> raw.takeIf { it.size.toLong() == uncompressedSize }
                8 -> inflateRaw(raw, uncompressedSize.toInt())
                else -> null
            } ?: invalidArchive()
            if (entries.put(name, content) != null) invalidArchive()
        }
        return entries
    }

    private const val MAX_PART_BYTES = 32L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024

    private fun invalidArchive(): Nothing = throw DataTransferException("XLSX 压缩包损坏或无法读取。")

    private class CentralDirectory(val offset: Int, val entryCount: Int)

    /** 从文件尾部搜索 ZIP 的 End Of Central Directory 记录。 */
    private fun locateCentralDirectory(bytes: ByteArray): CentralDirectory? {
        if (bytes.size < 22) return null
        val searchStart = maxOf(0, bytes.size - 66_000)
        var index = bytes.size - 22
        while (index >= searchStart) {
            if (readUInt32(bytes, index) == 0x06054B50L) {
                val entryCount = readUInt16(bytes, index + 10).toInt()
                val offset = readUInt32(bytes, index + 16).toInt()
                val commentLength = readUInt16(bytes, index + 20)
                if (index + 22 + commentLength == bytes.size.toLong() && offset in 0..index) {
                    return CentralDirectory(offset, entryCount)
                }
            }
            index -= 1
        }
        return null
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    // MARK: - RAW DEFLATE

    private const val MAX_BITS = 15

    /** 把 ZIP method 8 的 raw deflate 数据解压为 [expectedSize] 字节；失败返回 null。 */
    private fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray? {
        if (expectedSize < 0) return null
        if (expectedSize == 0) return ByteArray(0)
        return try {
            val output = Inflater(data, expectedSize).inflate()
            output
        } catch (_: Exception) {
            null
        }
    }

    private class Inflater(private val input: ByteArray, private val outputSize: Int) {
        private val output = ByteArray(outputSize)
        private var outputIndex = 0
        private var bitBuffer = 0L
        private var bitCount = 0
        private var inputIndex = 0
        private var blockFinal = false

        fun inflate(): ByteArray {
            do {
                blockFinal = readBits(1) == 1
                when (readBits(2)) {
                    0 -> readStoredBlock()
                    1 -> inflateHuffman(fixedLiteralTable, fixedDistanceTable)
                    2 -> {
                        val tables = readDynamicTables()
                        inflateHuffman(tables.first, tables.second)
                    }

                    else -> throw IllegalStateException("非法 deflate 块类型")
                }
            } while (!blockFinal)
            check(outputIndex == outputSize) { "解压后的数据长度不符" }
            return output
        }

        private fun readStoredBlock() {
            bitCount = 0
            bitBuffer = 0
            val length = readUInt16LE()
            val complement = readUInt16LE()
            if ((length xor 0xFFFF) != complement) throw IllegalStateException("stored 块长度校验失败")
            if (inputIndex + length > input.size || outputIndex + length > outputSize) {
                throw IllegalStateException("stored 块越界")
            }
            for (offset in 0 until length) {
                output[outputIndex + offset] = input[inputIndex + offset]
            }
            inputIndex += length
            outputIndex += length
        }

        private fun readUInt16LE(): Int {
            if (inputIndex + 2 > input.size) throw IllegalStateException("数据不足")
            val value = (input[inputIndex].toInt() and 0xFF) or
                ((input[inputIndex + 1].toInt() and 0xFF) shl 8)
            inputIndex += 2
            return value
        }

        private fun readBits(count: Int): Int {
            while (bitCount < count) {
                if (inputIndex >= input.size) throw IllegalStateException("位流越界")
                bitBuffer = bitBuffer or ((input[inputIndex].toLong() and 0xFF) shl bitCount)
                inputIndex += 1
                bitCount += 8
            }
            val value = (bitBuffer and ((1L shl count) - 1)).toInt()
            bitBuffer = bitBuffer ushr count
            bitCount -= count
            return value
        }

    /** 按规范 Huffman 表从位流解码一个符号。 */
    private fun decodeSymbol(table: HuffmanTable): Int {
        var code = 0
        for (length in 1..table.maxLength) {
            code = (code shl 1) or readBits(1)
            val count = table.counts[length]
            if (count > 0) {
                val offset = code - table.firstCode[length]
                if (offset in 0 until count) return table.symbolsByLength[length][offset]
            }
        }
        throw IllegalStateException("Huffman 码无效")
    }

        private fun inflateHuffman(literalTable: HuffmanTable, distanceTable: HuffmanTable) {
            while (true) {
                val symbol = decodeSymbol(literalTable)
                when {
                    symbol < 256 -> {
                        if (outputIndex >= outputSize) throw IllegalStateException("输出越界")
                        output[outputIndex] = symbol.toByte()
                        outputIndex += 1
                    }

                    symbol == 256 -> return

                    else -> {
                        // 长度符号 257..285，附 0..5 个额外位。
                        val lengthCode = symbol - 257
                        val lengthBase = LENGTH_BASE[lengthCode]
                        val lengthExtra = LENGTH_EXTRA[lengthCode]
                        val length = lengthBase + readBits(lengthExtra)
                        val distanceSymbol = decodeSymbol(distanceTable)
                        val distanceBase = DISTANCE_BASE[distanceSymbol]
                        val distanceExtra = DISTANCE_EXTRA[distanceSymbol]
                        val distance = distanceBase + readBits(distanceExtra)
                        if (distance > outputIndex || outputIndex + length > outputSize) {
                            throw IllegalStateException("回引越界")
                        }
                        var source = outputIndex - distance
                        repeat(length) {
                            output[outputIndex] = output[source]
                            outputIndex += 1
                            source += 1
                        }
                    }
                }
            }
        }

        private fun readDynamicTables(): Pair<HuffmanTable, HuffmanTable> {
            val literalCount = readBits(5) + 257
            val distanceCount = readBits(5) + 1
            val codeLengthCount = readBits(4) + 4
            val codeLengthOrder = intArrayOf(
                16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
            )
            val codeLengths = IntArray(19)
            for (index in 0 until codeLengthCount) {
                codeLengths[codeLengthOrder[index]] = readBits(3)
            }
            val codeLengthTable = buildTable(codeLengths)
            val allLengths = IntArray(literalCount + distanceCount)
            var index = 0
            while (index < allLengths.size) {
                val symbol = decodeSymbol(codeLengthTable)
                when {
                    symbol < 16 -> allLengths[index] = symbol

                    symbol == 16 -> {
                        if (index == 0) throw IllegalStateException("repeat 前无符号")
                        val repeat = 3 + readBits(2)
                        val previous = allLengths[index - 1]
                        repeat(repeat) {
                            if (index >= allLengths.size) throw IllegalStateException("长度越界")
                            allLengths[index] = previous
                            index += 1
                        }
                        continue
                    }

                    symbol == 17 -> {
                        val repeat = 3 + readBits(3)
                        repeat(repeat) {
                            if (index >= allLengths.size) throw IllegalStateException("长度越界")
                            allLengths[index] = 0
                            index += 1
                        }
                        continue
                    }

                    symbol == 18 -> {
                        val repeat = 11 + readBits(7)
                        repeat(repeat) {
                            if (index >= allLengths.size) throw IllegalStateException("长度越界")
                            allLengths[index] = 0
                            index += 1
                        }
                        continue
                    }

                    else -> throw IllegalStateException("code length 符号非法")
                }
                index += 1
            }
            val literalLengths = allLengths.copyOfRange(0, literalCount)
            val distanceLengths = allLengths.copyOfRange(literalCount, allLengths.size)
            return buildTable(literalLengths) to buildTable(distanceLengths)
        }
    }

    private class HuffmanTable(
        val counts: IntArray,
        /** 按码长分组的符号号，组内按符号号升序（与规范码升序一致）。 */
        val symbolsByLength: Array<IntArray>,
        /** 每个码长的最小码值：firstCode[len] = (firstCode[len-1] + counts[len-1]) << 1。 */
        val firstCode: IntArray,
        val maxLength: Int,
    )

    private fun buildTable(codeLengths: IntArray): HuffmanTable {
        val maxLength = codeLengths.maxOrNull() ?: 0
        val counts = IntArray(MAX_BITS + 1)
        codeLengths.forEach { length -> if (length > 0) counts[length] += 1 }

        @Suppress("UNCHECKED_CAST")
        val symbolsByLength = Array(MAX_BITS + 1) { IntArray(0) }
        for (length in 1..MAX_BITS) {
            val entries = ArrayList<Int>(16)
            codeLengths.forEachIndexed { index, candidate -> if (candidate == length) entries.add(index) }
            symbolsByLength[length] = entries.toIntArray()
        }

        val firstCode = IntArray(MAX_BITS + 1)
        var code = 0
        for (length in 1..MAX_BITS) {
            code = (code + counts[length - 1]) shl 1
            firstCode[length] = code
        }
        return HuffmanTable(
            counts = counts,
            symbolsByLength = symbolsByLength,
            firstCode = firstCode,
            maxLength = maxLength,
        )
    }

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )

    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )

    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193,
        12289, 16385, 24577,
    )

    private val DISTANCE_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    private val fixedLiteralTable by lazy {
        val lengths = IntArray(288)
        for (symbol in 0..143) lengths[symbol] = 8
        for (symbol in 144..255) lengths[symbol] = 9
        for (symbol in 256..279) lengths[symbol] = 7
        for (symbol in 280..287) lengths[symbol] = 8
        buildTable(lengths)
    }

    private val fixedDistanceTable by lazy { buildTable(IntArray(32) { 5 }) }
}
