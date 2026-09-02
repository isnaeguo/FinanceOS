package com.financeos.app.ui.viewmodel

import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionSortTest {
    @Test
    fun defaultSortKeepsTimeDescendingInputUntouched() {
        val items = listOf(
            item("newest", 3_000L),
            item("middle", 8_000L),
            item("oldest", 5_000L),
        )

        val result = sortTransactionItems(items, AmountSort.TIME_NEWEST)

        assertEquals(listOf("newest", "middle", "oldest"), result.map { it.id })
    }

    @Test
    fun amountLargestSortsByAmountDescending() {
        val items = listOf(
            item("newest", 3_000L),
            item("middle", 8_000L),
            item("oldest", 5_000L),
        )

        val result = sortTransactionItems(items, AmountSort.AMOUNT_LARGEST)

        assertEquals(listOf("middle", "oldest", "newest"), result.map { it.id })
    }

    @Test
    fun amountSmallestSortsByAmountAscending() {
        val items = listOf(
            item("newest", 3_000L),
            item("middle", 8_000L),
            item("oldest", 5_000L),
        )

        val result = sortTransactionItems(items, AmountSort.AMOUNT_SMALLEST)

        assertEquals(listOf("newest", "oldest", "middle"), result.map { it.id })
    }

    @Test
    fun equalAmountsKeepOriginalRelativeOrderForStableTimeOrdering() {
        // 输入本身是按时间倒序（date desc）给出的，金额相同时稳定排序保留该相对次序。
        val items = listOf(
            item("newer", 5_000L),
            item("older", 5_000L),
            item("other", 9_000L),
        )

        val result = sortTransactionItems(items, AmountSort.AMOUNT_LARGEST)

        assertEquals(listOf("other", "newer", "older"), result.map { it.id })
    }

    private fun item(id: String, amountMinor: Long) = TransactionItemUiState(
        id = id,
        categoryId = "system-food",
        categoryName = "餐饮",
        categoryIconKey = "food",
        accountId = null,
        note = null,
        amountText = "¥1.00",
        dateTimeText = "8月1日 12:00",
        typeLabel = "支出",
        isExpense = true,
        type = TransactionType.EXPENSE,
        amountMinor = amountMinor,
    )
}
