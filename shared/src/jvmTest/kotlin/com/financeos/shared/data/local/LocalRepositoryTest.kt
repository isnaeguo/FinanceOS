package com.financeos.shared.data.local

import androidx.room3.Room
import com.financeos.shared.data.local.repository.LocalBudgetRepository
import com.financeos.shared.data.local.repository.LocalCategoryRepository
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.time.EpochClock
import com.financeos.shared.domain.usecase.AddTransactionCommand
import com.financeos.shared.domain.usecase.AddTransactionUseCase
import com.financeos.shared.domain.usecase.ExpenseTrendPeriod
import com.financeos.shared.domain.usecase.ExpenseTrendPoint
import com.financeos.shared.domain.usecase.GetExpenseTrendUseCase
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocalRepositoryTest {
    /** 固定时钟使同步元数据可精确断言。 */
    private val fixedClock = EpochClock { FIXED_NOW_MILLIS }

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
            updatedAt = FIXED_NOW_MILLIS,
        )
        val updatedBudget = originalBudget.copy(amountLimit = 350_000L, updatedAt = FIXED_NOW_MILLIS + 1)
        val categoryBudget = Budget(
            id = "budget-food-2026-08",
            month = BudgetMonth(2026, 8),
            amountLimit = 80_000L,
            categoryId = "system-food",
            updatedAt = FIXED_NOW_MILLIS,
        )

        try {
            val firstDatabase = openFileDatabase(databaseFile.absolutePath)
            try {
                val transactionRepository = LocalTransactionRepository(
                    firstDatabase.transactionDao(),
                    fixedClock,
                )
                val categoryRepository = LocalCategoryRepository(firstDatabase.categoryDao())
                AddTransactionUseCase(transactionRepository, categoryRepository, fixedClock)(
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
                val budgetRepository = LocalBudgetRepository(firstDatabase.budgetDao(), fixedClock)
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
                val transactionRepository = LocalTransactionRepository(
                    reopenedDatabase.transactionDao(),
                    fixedClock,
                )
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
            val repository = LocalTransactionRepository(database.transactionDao(), fixedClock)
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
            val repository = LocalTransactionRepository(database.transactionDao(), fixedClock)
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
            val repository = LocalBudgetRepository(database.budgetDao(), fixedClock)
            val original = Budget(
                id = "budget-observed",
                month = BudgetMonth(2026, 8),
                amountLimit = 100_000L,
                updatedAt = FIXED_NOW_MILLIS,
            )
            val updated = original.copy(amountLimit = 120_000L, updatedAt = FIXED_NOW_MILLIS + 1)
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
            val transactionRepository = LocalTransactionRepository(database.transactionDao(), fixedClock)
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
            val transactionRepository = LocalTransactionRepository(database.transactionDao(), fixedClock)
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
            assertFailsWith<IllegalArgumentException> {
                dataRepository.replaceAll(invalidSnapshot)
            }

            assertEquals(existing, transactionRepository.get(existing.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun repeatedStatementImportKeepsEditsAndDeletedIds() = runTest {
        val database = openMemoryDatabase()
        try {
            val data = LocalFinanceDataRepository(database)
            val ledger = LocalTransactionRepository(database.transactionDao(), fixedClock)
            val time = Instant.parse("2026-08-10T08:30:00Z")
            val edited = expense("bank-edited", dateTime = time)
            val deleted = expense("bank-deleted", dateTime = time)
            val input = listOf(edited, deleted, edited)
            assertEquals(2, data.importTransactions(input).transactionCount)
            ledger.add(edited.copy(note = "人工修改"))
            assertTrue(ledger.delete(deleted.id))

            val newerFile = input.map { it.copy(updatedAt = FIXED_NOW_MILLIS + 100) }
            assertEquals(0, data.importTransactions(newerFile).transactionCount)
            assertEquals("人工修改", ledger.get(edited.id)?.note)
            assertNull(ledger.get(deleted.id))
            assertEquals(2, data.snapshot().transactions.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun editChecksRevisionAndCategoryInSameWrite() = runTest {
        val database = openMemoryDatabase()
        try {
            val ledger = LocalTransactionRepository(database.transactionDao(), fixedClock)
            val baseline = expense("editable", dateTime = Instant.parse("2026-08-10T08:30:00Z"))
            ledger.add(baseline)
            assertFalse(ledger.updateCategory(baseline, "system-income"))
            assertTrue(ledger.updateCategory(baseline, "system-transport"))
            assertFalse(ledger.update(baseline, baseline.copy(note = "已过期的编辑")))
            val current = checkNotNull(ledger.get(baseline.id))
            assertEquals("system-transport", current.categoryId)
            assertEquals(FIXED_NOW_MILLIS + 1, current.updatedAt)
            assertTrue(ledger.update(current, current.copy(amount = 5_000L, note = "车票")))
            assertEquals("车票", ledger.get(baseline.id)?.note)
            assertTrue(ledger.delete(baseline.id))
            assertFalse(ledger.updateCategory(current, "system-food"))
            val tombstone = LocalFinanceDataRepository(database).snapshot().transactions.single()
            assertEquals(FIXED_NOW_MILLIS + 3, tombstone.updatedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun creatingCategoryAssignsOnlyTheUnchangedRowAndKeepsExistingCategories() = runTest {
        val database = openMemoryDatabase()
        try {
            val ledger = LocalTransactionRepository(database.transactionDao(), fixedClock)
            val categories = LocalCategoryRepository(database.categoryDao())
            val date = Instant.parse("2026-08-10T08:30:00Z")
            val original = expense("original", dateTime = date)
            val other = expense("other", dateTime = date).copy(
                type = TransactionType.INCOME, categoryId = "system-income",
            )
            val createdId = "user-9d052e00-e71a-4434-aa79-3aaf25ab22db"
            val otherId = "user-fc485a64-e99a-4e30-9102-bd55760aba5b"
            ledger.add(original)
            ledger.add(other)
            ledger.add(original.copy(note = "changed during review"))

            assertFalse(ledger.updateCategoryWithNewCategory(original, createdId, "Commute"))
            assertNull(categories.get(createdId))
            val latest = checkNotNull(ledger.get(original.id))
            assertFailsWith<IllegalArgumentException> {
                ledger.updateCategoryWithNewCategory(latest, createdId, " ")
            }
            assertFailsWith<IllegalArgumentException> {
                ledger.updateCategoryWithNewCategory(latest, createdId, "a".repeat(41))
            }
            assertTrue(ledger.updateCategoryWithNewCategory(latest, createdId, "  Commute  "))
            val assigned = checkNotNull(ledger.get(original.id))
            assertEquals(latest.copy(categoryId = createdId, updatedAt = latest.updatedAt + 1), assigned)
            val category = checkNotNull(categories.get(createdId))
            assertEquals("Commute", category.name)
            assertEquals(CategoryType.EXPENSE, category.type)
            assertEquals("other", category.iconKey)
            assertFalse(category.isSystem)

            val duplicate = assertFailsWith<IllegalArgumentException> {
                ledger.updateCategoryWithNewCategory(other, otherId, "commute")
            }
            assertTrue(duplicate.message.orEmpty().contains("同名"))
            assertFailsWith<IllegalArgumentException> {
                ledger.updateCategoryWithNewCategory(other, createdId, "Program income")
            }
            assertEquals(category, categories.get(createdId))
            assertNull(categories.get(otherId))
            assertEquals(other, ledger.get(other.id))
            assertTrue(ledger.updateCategoryWithNewCategory(other, otherId, "Program income"))
            assertEquals(CategoryType.INCOME, categories.get(otherId)?.type)
        } finally {
            database.close()
        }
    }

    @Test
    fun subMillisecondBoundsAreComparedAgainstStoredMillis() = runTest {
        val database = openMemoryDatabase()
        try {
            val ledger = LocalTransactionRepository(database.transactionDao(), fixedClock)
            val start = Instant.parse("2026-08-10T08:30:00Z")
            val next = Instant.parse("2026-08-10T08:30:00.001Z")
            ledger.add(expense("on-boundary", dateTime = start))
            ledger.add(expense("next", dateTime = next))
            val between = Instant.parse("2026-08-10T08:30:00.0005Z")
            assertEquals(listOf("on-boundary"), ledger.getByPeriod(start, between).map { it.id })
            assertEquals(emptyList(), ledger.getByPeriod(between, next))
            val period = ExpenseTrendPeriod("sub-millisecond", start, between)
            val trend = GetExpenseTrendUseCase(ledger)
            val expected = listOf(ExpenseTrendPoint(period.key, 1_299L))
            assertEquals(expected, trend(listOf(period)))
            assertEquals(listOf(expected), trend.observeGroups(listOf(listOf(period))).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun widgetRemainingIncludesTodayWhileDailyAllocationUsesEarlierExpenses() = runTest {
        val database = openMemoryDatabase()
        try {
            val ledger = LocalTransactionRepository(database.transactionDao(), fixedClock)
            val data = LocalFinanceDataRepository(database)
            val month = BudgetMonth(2026, 8)
            val startOfToday = Instant.parse("2026-08-15T00:00:00Z")
            val period = MonthPeriod(
                month = month,
                startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
                endExclusive = Instant.parse("2026-09-01T00:00:00Z"),
            )
            LocalBudgetRepository(database.budgetDao(), fixedClock)
                .save(Budget("widget-budget", month, 100_000L))
            ledger.add(expense("previous-month", 50_000L, Instant.parse("2026-07-31T23:59:59Z")))
            ledger.add(expense("earlier", 30_000L, Instant.parse("2026-08-14T23:59:59Z")))
            ledger.add(expense("today", 10_000L, startOfToday))
            ledger.add(expense("removed", 50_000L, startOfToday))
            ledger.delete("removed")
            ledger.add(expense("next-month", 50_000L, period.endExclusive))
            // 小组件展示支出金额。即使收入总额超过 Long 范围也不影响结果。
            ledger.add(expense("income-1", Long.MAX_VALUE, startOfToday)
                .copy(type = TransactionType.INCOME, categoryId = "system-income"))
            ledger.add(expense("income-2", 1L, startOfToday)
                .copy(type = TransactionType.INCOME, categoryId = "system-income"))

            val overview = data.monthlyBudgetOverview(period, dayOfMonth = 15, startOfToday = startOfToday)
            assertEquals(40_000L, overview.amountUsed)
            assertEquals(60_000L, overview.amountRemaining)
            assertEquals(4_117L, overview.dailyAvailable)
            assertFalse(overview.isOverBudget)
        } finally {
            database.close()
        }
    }

    @Test
    fun widgetSpendingSaturatesEachTotalIndependentlyAtLongMax() = runTest {
        val database = openMemoryDatabase()
        try {
            val ledger = LocalTransactionRepository(database.transactionDao(), fixedClock)
            val data = LocalFinanceDataRepository(database)
            val month = BudgetMonth(2026, 8)
            val period = MonthPeriod(
                month,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
            )
            val today = Instant.parse("2026-08-15T00:00:00Z")
            val yesterday = Instant.parse("2026-08-14T23:59:59Z")
            val limit = 100_000L
            LocalBudgetRepository(database.budgetDao(), fixedClock).save(Budget("widget-budget", month, limit))
            ledger.add(expense("previous", 17_000L, yesterday))
            ledger.add(expense("today", Long.MAX_VALUE - 17_000L, today))
            val exact = data.monthlyBudgetOverview(period, 15, today)
            assertEquals(Long.MAX_VALUE, exact.amountUsed)
            assertEquals(4_882L, exact.dailyAvailable)

            ledger.add(expense("today-overflow", 1L, today))
            assertEquals(exact, data.monthlyBudgetOverview(period, 15, today))

            ledger.add(expense("yesterday-overflow", Long.MAX_VALUE, yesterday))
            val overflow = data.monthlyBudgetOverview(period, 15, today)
            assertEquals(Long.MAX_VALUE, overflow.amountUsed)
            assertEquals(limit - Long.MAX_VALUE, overflow.amountRemaining)
            assertEquals(0L, overflow.dailyAvailable)
            assertTrue(overflow.isOverBudget)
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
        updatedAt = FIXED_NOW_MILLIS,
    )

    private companion object {
        const val FIXED_NOW_MILLIS = 1_756_896_000_000L
    }
}
