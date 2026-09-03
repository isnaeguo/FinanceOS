package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.time.EpochClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FinanceDataCodecTest {
    @Test
    fun jsonRoundTripKeepsAllDomainFields() {
        val snapshot = sampleSnapshot()

        val encoded = FinanceDataJsonCodec().encode(snapshot)
        val decoded = FinanceDataJsonCodec().decode(encoded)

        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("\"schema_version\": 2"))
        assertTrue(encoded.contains("\"amount_minor\": 2350"))
        assertTrue(encoded.contains("\"updated_at_epoch_millis\": 1786350600000"))
        assertTrue(encoded.contains("\"deleted_at_epoch_millis\": 1786400000000"))
    }

    @Test
    fun jsonReadsLegacyV1DocumentsAsOldestRecords() {
        val decoded = FinanceDataJsonCodec().decode(V1_DOCUMENT)

        val transaction = decoded.transactions.single()
        assertEquals("transaction-lunch", transaction.id)
        assertEquals(2_350L, transaction.amount)
        assertEquals(0L, transaction.updatedAt)
        assertNull(transaction.deletedAt)
        assertEquals(0L, decoded.categories.single().updatedAt)
        assertEquals(0L, decoded.budgets.single().updatedAt)
    }

    @Test
    fun jsonRejectsUnsupportedSchemaVersion() {
        val content = FinanceDataJsonCodec()
            .encode(sampleSnapshot())
            .replace("\"schema_version\": 2", "\"schema_version\": 99")

        val error = assertFailsWith<DataTransferException> {
            FinanceDataJsonCodec().decode(content)
        }

        assertTrue(error.message.orEmpty().contains("99"))
    }

    @Test
    fun jsonRejectsNegativeUpdatedAt() {
        val content = FinanceDataJsonCodec()
            .encode(sampleSnapshot())
            .replace(
                "\"updated_at_epoch_millis\": 1786350600000",
                "\"updated_at_epoch_millis\": -1",
            )

        assertFailsWith<DataTransferException> {
            FinanceDataJsonCodec().decode(content)
        }
    }

    @Test
    fun csvRoundTripKeepsExactMoneyAndEscapedNote() {
        val clock = EpochClock { 1_756_896_000_000L }
        val transaction = sampleSnapshot().transactions.single().copy(
            note = "午饭, 加饮料\n备注里有\"引号\"",
        )

        val encoded = TransactionCsvCodec(clock).encode(listOf(transaction))
        val decoded = TransactionCsvCodec(clock).decode(encoded)

        // 流水交换格式不携带元数据：解码记录以导入时刻为最后修改时间。
        assertEquals(
            listOf(transaction.copy(updatedAt = 1_756_896_000_000L, deletedAt = null)),
            decoded,
        )
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
                updatedAt = 1_786_350_600_000L,
                deletedAt = 1_786_400_000_000L,
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

    private companion object {
        /** 手写的 schema_version=1 历史文档：不含同步元数据字段。 */
        val V1_DOCUMENT = """
            {
              "format": "financeos-backup",
              "schema_version": 1,
              "transactions": [
                {
                  "id": "transaction-lunch",
                  "amount_minor": 2350,
                  "type": "EXPENSE",
                  "category_id": "category-food",
                  "account_id": "cash",
                  "date_time_epoch_millis": 1786350600000,
                  "note": "午饭"
                }
              ],
              "categories": [
                {
                  "id": "category-food",
                  "name": "餐饮",
                  "type": "EXPENSE",
                  "icon_key": "food",
                  "is_system": true
                }
              ],
              "budgets": [
                {
                  "id": "budget-2026-08",
                  "year": 2026,
                  "month": 8,
                  "amount_limit_minor": 300000
                }
              ]
            }
        """.trimIndent()
    }
}
