package com.financeos.shared.data.local

import androidx.room3.Room
import com.financeos.shared.data.local.repository.LocalBudgetRepository
import com.financeos.shared.data.local.repository.LocalCategoryRepository
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.usecase.AddTransactionCommand
import com.financeos.shared.domain.usecase.AddTransactionUseCase
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocalRepositoryTest {
    @Test
    fun addAndDeleteTransactionPersistAcrossDatabaseReopen() = runTest {
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
        val categoryBudget = Budget(
            id = "budget-food-2026-08",
            month = BudgetMonth(2026, 8),
            amountLimit = 80_000L,
            categoryId = "system-food",
        )

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
                budgetRepository.save(categoryBudget)

                assertEquals(
                    DefaultCategories.all.map { it.id }.sorted(),
                    LocalCategoryRepository(firstDatabase.categoryDao()).getAll().map { it.id }.sorted(),
                )
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = openFileDatabase(databaseFile.absolutePath)
            try {
                val transactionRepository = LocalTransactionRepository(reopenedDatabase.transactionDao())
                assertEquals(
                    transaction,
                    transactionRepository.get(transaction.id),
                )
                assertEquals(
                    updatedBudget,
                    LocalBudgetRepository(reopenedDatabase.budgetDao()).get(BudgetMonth(2026, 8)),
                )
                assertEquals(
                    categoryBudget,
                    LocalBudgetRepository(reopenedDatabase.budgetDao()).get(
                        month = BudgetMonth(2026, 8),
                        categoryId = "system-food",
                    ),
                )
                assertEquals(
                    DefaultCategories.all.map { it.id }.sorted(),
                    LocalCategoryRepository(reopenedDatabase.categoryDao()).getAll().map { it.id }.sorted(),
                )
                assertTrue(transactionRepository.delete(transaction.id))
            } finally {
                reopenedDatabase.close()
            }

            val databaseAfterDelete = openFileDatabase(databaseFile.absolutePath)
            try {
                val budgetRepository = LocalBudgetRepository(databaseAfterDelete.budgetDao())
                assertNull(
                    LocalTransactionRepository(databaseAfterDelete.transactionDao()).get(transaction.id),
                )
                assertEquals(updatedBudget, budgetRepository.get(BudgetMonth(2026, 8)))
                assertEquals(
                    categoryBudget,
                    budgetRepository.get(BudgetMonth(2026, 8), "system-food"),
                )
            } finally {
                databaseAfterDelete.close()
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

    @Test
    fun monthObserverEmitsAfterAddAndDelete() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalTransactionRepository(database.transactionDao())
            val transaction = expense(
                id = "transaction-observed",
                dateTime = Instant.parse("2026-08-21T05:00:00Z"),
            )
            val emissions = mutableListOf<List<Transaction>>()
            val emissionSignal = Channel<Unit>(capacity = Channel.UNLIMITED)
            val observationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeByMonth(
                    startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
                    endExclusive = Instant.parse("2026-09-01T00:00:00Z"),
                ).collect { transactions ->
                    emissions += transactions
                    emissionSignal.send(Unit)
                }
            }

            // 每次等待 Room 发出结果后再写下一次，避免快速写入被数据库失效通知合并。
            emissionSignal.receive()
            repository.add(transaction)
            emissionSignal.receive()
            repository.delete(transaction.id)
            emissionSignal.receive()
            observationJob.cancel()

            assertEquals(
                listOf(emptyList(), listOf(transaction), emptyList()),
                emissions,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun budgetObserverEmitsAfterCreateAndUpdate() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalBudgetRepository(database.budgetDao())
            val original = Budget(
                id = "budget-observed",
                month = BudgetMonth(2026, 8),
                amountLimit = 100_000L,
            )
            val updated = original.copy(amountLimit = 120_000L)
            val emissions = mutableListOf<List<Budget>>()
            val emissionSignal = Channel<Unit>(capacity = Channel.UNLIMITED)
            val observationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeByMonth(BudgetMonth(2026, 8)).collect { budgets ->
                    emissions += budgets
                    emissionSignal.send(Unit)
                }
            }

            emissionSignal.receive()
            repository.save(original)
            emissionSignal.receive()
            repository.save(updated)
            emissionSignal.receive()
            observationJob.cancel()

            assertEquals(
                listOf(emptyList(), listOf(original), listOf(updated)),
                emissions,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun mergeAndReplaceUseCompleteDomainSnapshots() = runTest {
        val database = openMemoryDatabase()
        try {
            val dataRepository = LocalFinanceDataRepository(database)
            val transactionRepository = LocalTransactionRepository(database.transactionDao())
            val original = expense(
                id = "transaction-original",
                amount = 1_000L,
                dateTime = Instant.parse("2026-08-01T02:00:00Z"),
            )
            transactionRepository.add(original)
            val imported = expense(
                id = "transaction-imported",
                amount = 2_350L,
                dateTime = Instant.parse("2026-08-10T08:30:00Z"),
                note = "午饭",
            )

            dataRepository.merge(
                FinanceDataSnapshot(
                    transactions = listOf(imported),
                    categories = emptyList(),
                    budgets = emptyList(),
                ),
            )
            assertEquals(setOf(original, imported), dataRepository.snapshot().transactions.toSet())

            val replacement = FinanceDataSnapshot(
                transactions = listOf(imported.copy(note = "恢复后的午饭")),
                categories = DefaultCategories.all,
                budgets = emptyList(),
            )
            dataRepository.replaceAll(replacement)

            assertEquals(
                replacement.copy(categories = replacement.categories.sortedBy { it.id }),
                dataRepository.snapshot(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidRestoreDoesNotClearExistingData() = runTest {
        val database = openMemoryDatabase()
        try {
            val dataRepository = LocalFinanceDataRepository(database)
            val transactionRepository = LocalTransactionRepository(database.transactionDao())
            val existing = expense(
                id = "transaction-safe",
                dateTime = Instant.parse("2026-08-10T08:30:00Z"),
            )
            transactionRepository.add(existing)

            val invalidSnapshot = FinanceDataSnapshot(
                transactions = listOf(existing.copy(categoryId = "missing-category")),
                categories = DefaultCategories.all,
                budgets = emptyList(),
            )
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                dataRepository.replaceAll(invalidSnapshot)
            }

            assertEquals(existing, transactionRepository.get(existing.id))
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
