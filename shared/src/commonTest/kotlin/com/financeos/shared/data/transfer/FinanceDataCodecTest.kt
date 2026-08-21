package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class FinanceDataCodecTest {
    @Test
    fun jsonRoundTripKeepsAllDomainFields() {
        val snapshot = sampleSnapshot()

        val encoded = FinanceDataJsonCodec().encode(snapshot)
        val decoded = FinanceDataJsonCodec().decode(encoded)

        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("\"schema_version\": 1"))
        assertTrue(encoded.contains("\"amount_minor\": 2350"))
    }

    @Test
    fun jsonRejectsUnsupportedSchemaVersion() {
        val content = FinanceDataJsonCodec()
            .encode(sampleSnapshot())
            .replace("\"schema_version\": 1", "\"schema_version\": 99")

        val error = assertFailsWith<DataTransferException> {
            FinanceDataJsonCodec().decode(content)
        }

        assertTrue(error.message.orEmpty().contains("99"))
    }

    @Test
    fun csvRoundTripKeepsExactMoneyAndEscapedNote() {
        val transaction = sampleSnapshot().transactions.single().copy(
            note = "午饭, 加饮料\n备注里有\"引号\"",
        )

        val encoded = TransactionCsvCodec().encode(listOf(transaction))
        val decoded = TransactionCsvCodec().decode(encoded)

        assertEquals(listOf(transaction), decoded)
        assertTrue(encoded.startsWith("\uFEFF"))
        assertTrue(encoded.contains("23.50"))
    }

    @Test
    fun csvRejectsInvalidMoneyInsteadOfRounding() {
        val content = """
            id,amount_minor,amount,type,category_id,account_id,date_time_epoch_millis,note
            tx-1,23.50,23.50,EXPENSE,category-food,,1786350600000,午饭
        """.trimIndent()

        assertFailsWith<DataTransferException> {
            TransactionCsvCodec().decode(content)
        }
    }

    private fun sampleSnapshot() = FinanceDataSnapshot(
        transactions = listOf(
            Transaction(
                id = "transaction-lunch",
                amount = 2_350L,
                type = TransactionType.EXPENSE,
                categoryId = "category-food",
                accountId = "cash",
                dateTime = Instant.parse("2026-08-10T08:30:00Z"),
                note = "午饭",
            ),
        ),
        categories = listOf(
            Category(
                id = "category-food",
                name = "餐饮",
                type = CategoryType.EXPENSE,
                iconKey = "food",
                isSystem = true,
            ),
        ),
        budgets = listOf(
            Budget(
                id = "budget-2026-08",
                month = BudgetMonth(2026, 8),
                amountLimit = 300_000L,
            ),
        ),
    )
}
