package com.financeos.app.ui.viewmodel

import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionFilterTest {
    @Test
    fun combinesNoteCategoryAccountAndTypeFilters() {
        val items = listOf(
            item("food-cash", "food", "cash", "周末午饭", TransactionType.EXPENSE),
            item("food-none", "food", null, "工作日午饭", TransactionType.EXPENSE),
            item("salary", "income", "bank", "八月工资", TransactionType.INCOME),
        )

        val result = filterTransactionItems(
            items = items,
            searchQuery = "午饭",
            selectedType = TransactionType.EXPENSE,
            selectedCategoryId = "food",
            selectedAccount = AccountFilter.Specific("cash"),
        )

        assertEquals(listOf("food-cash"), result.map { it.id })
    }

    @Test
    fun unspecifiedAccountAndEmptySearchAreHandledExplicitly() {
        val items = listOf(
            item("none", "food", null, null, TransactionType.EXPENSE),
            item("cash", "food", "cash", "午饭", TransactionType.EXPENSE),
        )

        val result = filterTransactionItems(
            items = items,
            searchQuery = " ",
            selectedType = null,
            selectedCategoryId = null,
            selectedAccount = AccountFilter.Unspecified,
        )

        assertEquals(listOf("none"), result.map { it.id })
    }

    private fun item(
        id: String,
        categoryId: String,
        accountId: String?,
        note: String?,
        type: TransactionType,
    ) = TransactionItemUiState(
        id = id,
        categoryId = categoryId,
        categoryName = categoryId,
        categoryIconKey = "other",
        accountId = accountId,
        note = note,
        amountText = "¥1.00",
        dateTimeText = "8月1日 12:00",
        typeLabel = if (type == TransactionType.EXPENSE) "支出" else "收入",
        isExpense = type == TransactionType.EXPENSE,
        type = type,
    )
}
