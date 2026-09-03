package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 固定测试向量锁定三端去重指纹：任何实现（Kotlin/Swift）对同一输入必须产生逐字节一致的 ID。
 * 期望值以 Apple 端 FNV-1a-64 实现为准，basis 与 Swift 侧常量一致。
 */
class StableRowIdTest {
    @Test
    fun generatesStableFingerprintForChineseNote() {
        assertEquals(
            "bill-f902a7e5f744dfa1",
            StableRowId.generate(
                orderId = "",
                dateMillis = 1_786_350_600_000L,
                amountMinor = 2_350L,
                type = TransactionType.EXPENSE,
                note = "午饭",
                counterparty = "肯德基",
            ),
        )
    }

    @Test
    fun generatesStableFingerprintForNoteWithCommaAndQuotes() {
        assertEquals(
            "bill-4785b5ec9bdc215c",
            StableRowId.generate(
                orderId = "",
                dateMillis = 1_786_350_600_001L,
                amountMinor = 12_800L,
                type = TransactionType.EXPENSE,
                note = "午饭, \"加饮料\"\n汤",
                counterparty = "美团",
            ),
        )
    }

    @Test
    fun generatesStableFingerprintForEmptyCounterpartyAtAmountBoundary45() {
        assertEquals(
            "bill-bd9ec6dbfc75aaee",
            StableRowId.generate(
                orderId = "",
                dateMillis = 1_786_350_600_002L,
                amountMinor = 45L,
                type = TransactionType.EXPENSE,
                note = "",
                counterparty = "",
            ),
        )
    }

    @Test
    fun generatesStableFingerprintForIncome() {
        assertEquals(
            "bill-bdeb97198f8454b2",
            StableRowId.generate(
                orderId = "",
                dateMillis = 1_786_350_600_003L,
                amountMinor = 100_000L,
                type = TransactionType.INCOME,
                note = "工资",
                counterparty = "公司",
            ),
        )
    }

    @Test
    fun prefersOrderIdAfterRemovingWhitespace() {
        assertEquals("bill-WX1234AB", StableRowId.generate(
            orderId = "  WX 1234\tAB ",
            dateMillis = 1_786_350_600_000L,
            amountMinor = 2_350L,
            type = TransactionType.EXPENSE,
            note = "午饭",
            counterparty = "肯德基",
        ))
    }

    @Test
    fun collapsesAllWhitespaceBeforeHashing() {
        // 空白折叠先于指纹：同一账单中的多余空格、换行不产生新 ID。
        assertEquals(
            "bill-f902a7e5f744dfa1",
            StableRowId.generate(
                orderId = "",
                dateMillis = 1_786_350_600_000L,
                amountMinor = 2_350L,
                type = TransactionType.EXPENSE,
                note = "午 饭\n",
                counterparty = " 肯 德 基 ",
            ),
        )
    }
}
