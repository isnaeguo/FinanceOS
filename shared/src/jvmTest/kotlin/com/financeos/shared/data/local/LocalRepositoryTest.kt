package com.financeos.shared.data.local

import androidx.room3.Room
import com.financeos.shared.data.local.repository.LocalBudgetRepository
import com.financeos.shared.data.local.repository.LocalCategoryRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.usecase.AddTransactionCommand
import com.financeos.shared.domain.usecase.AddTransactionUseCase
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocalRepositoryTest {
    @Test
    fun addTransactionUseCasePersistsLunchExpenseAfterDatabaseReopen() = runTest {
        val directory = Files.createTempDirectory("financeos-room-test").toFile()
        val databaseFile = directory.resolve("financeos.db")
        val transaction = expense(
            id = "transaction-persisted",
            amount = 2_350L,
            dateTime = Instant.parse("2026-08-10T08:30:00Z"),
            note = "午饭",
        )
        val originalBudget = Budget(
            id = "budget-2026-08",
            month = BudgetMonth(2026, 8),
            amountLimit = 300_000L,
        )
        val updatedBudget = originalBudget.copy(amountLimit = 350_000L)

        try {
            val firstDatabase = openFileDatabase(databaseFile.absolutePath)
            try {
                val transactionRepository = LocalTransactionRepository(firstDatabase.transactionDao())
                val categoryRepository = LocalCategoryRepository(firstDatabase.categoryDao())
                AddTransactionUseCase(transactionRepository, categoryRepository)(
                    AddTransactionCommand(
                        id = transaction.id,
                        amount = transaction.amount,
                        type = transaction.type,
                        categoryId = transaction.categoryId,
                        accountId = transaction.accountId,
                        dateTime = transaction.dateTime,
                        note = transaction.note,
                    ),
                )
                val budgetRepository = LocalBudgetRepository(firstDatabase.budgetDao())
                budgetRepository.save(originalBudget)
                budgetRepository.save(updatedBudget)

                assertEquals(
                    DefaultCategories.all.map { it.id }.sorted(),
                    LocalCategoryRepository(firstDatabase.categoryDao()).getAll().map { it.id }.sorted(),
                )
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = openFileDatabase(databaseFile.absolutePath)
            try {
                assertEquals(
                    transaction,
                    LocalTransactionRepository(reopenedDatabase.transactionDao()).get(transaction.id),
                )
                assertEquals(
                    updatedBudget,
                    LocalBudgetRepository(reopenedDatabase.budgetDao()).get(BudgetMonth(2026, 8)),
                )
                assertEquals(
                    DefaultCategories.all.map { it.id }.sorted(),
                    LocalCategoryRepository(reopenedDatabase.categoryDao()).getAll().map { it.id }.sorted(),
                )
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun monthQueryAndDeleteReflectCurrentTransactions() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalTransactionRepository(database.transactionDao())
            val august = expense(
                id = "transaction-august",
                dateTime = Instant.parse("2026-08-31T23:59:59Z"),
            )
            val september = expense(
                id = "transaction-september",
                dateTime = Instant.parse("2026-09-01T00:00:00Z"),
            )
            repository.add(august)
            repository.add(september)

            assertEquals(
                listOf(august),
                repository.getByMonth(
                    startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
                    endExclusive = Instant.parse("2026-09-01T00:00:00Z"),
                ),
            )
            assertTrue(repository.delete(august.id))
            assertNull(repository.get(august.id))
            assertEquals(listOf(september), repository.getAll())
            assertFalse(repository.delete(august.id))
        } finally {
            database.close()
        }
    }

    private fun openMemoryDatabase(): FinanceOsDatabase = buildFinanceOsDatabase(
        Room.inMemoryDatabaseBuilder {
            FinanceOsDatabaseConstructor.initialize()
        },
    )

    private fun openFileDatabase(path: String): FinanceOsDatabase = buildFinanceOsDatabase(
        Room.databaseBuilder(
            name = path,
            factory = { FinanceOsDatabaseConstructor.initialize() },
        ),
    )

    private fun expense(
        id: String,
        amount: Long = 1_299L,
        dateTime: Instant,
        note: String = "数据库测试",
    ): Transaction = Transaction(
        id = id,
        amount = amount,
        type = TransactionType.EXPENSE,
        categoryId = "system-food",
        accountId = null,
        dateTime = dateTime,
        note = note,
    )
}
