package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BulkExpenseDatasetTest {
    @Test
    fun thirtyGeneratedExpenseDatasetsPassAllMonthlyUseCases() = runTest {
        repeat(DATASET_COUNT) { datasetIndex ->
            val transactionCount = transactionCount(datasetIndex)
            val expectedTotal = expenseTotal(datasetIndex)
            val expectedByCategory = linkedMapOf<String, Long>()
            val transactionRepository = FakeTransactionRepository()
            val addTransaction = AddTransactionUseCase(
                transactionRepository = transactionRepository,
                categoryRepository = FakeCategoryRepository(categories),
            )

            // 确定性拆分确保每组总额精确命中目标，同时每笔金额始终大于零。
            val baseAmount = expectedTotal / transactionCount
            val remainder = (expectedTotal % transactionCount).toInt()
            repeat(transactionCount) { transactionIndex ->
                val categoryId = categoryIds[(datasetIndex + transactionIndex) % categoryIds.size]
                val amount = baseAmount + if (transactionIndex < remainder) 1L else 0L
                addTransaction(
                    AddTransactionCommand(
                        id = "dataset-$datasetIndex-transaction-$transactionIndex",
                        amount = amount,
                        type = TransactionType.EXPENSE,
                        categoryId = categoryId,
                        dateTime = transactionTime(datasetIndex, transactionIndex),
                    ),
                )
                expectedByCategory[categoryId] =
                    (expectedByCategory[categoryId] ?: 0L) + amount
            }

            val monthlyTransactions = GetMonthlyTransactionsUseCase(transactionRepository)
            val monthlySummary = GetMonthlySummaryUseCase(monthlyTransactions)
            val budgets = buildList {
                add(
                    Budget(
                        id = "dataset-$datasetIndex-total-budget",
                        month = month,
                        amountLimit = expectedTotal + TOTAL_BUDGET_BUFFER,
                    ),
                )
                expectedByCategory.forEach { (categoryId, amountUsed) ->
                    add(
                        Budget(
                            id = "dataset-$datasetIndex-$categoryId-budget",
                            month = month,
                            amountLimit = amountUsed + CATEGORY_BUDGET_BUFFER,
                            categoryId = categoryId,
                        ),
                    )
                }
            }
            val budgetStatus = GetBudgetStatusUseCase(
                getMonthlySummary = monthlySummary,
                budgetRepository = FakeBudgetRepository(budgets),
            )
            val dailyAvailableBudget = CalculateDailyAvailableBudgetUseCase(budgetStatus)

            assertTrue(
                transactionCount in MIN_TRANSACTION_COUNT..MAX_TRANSACTION_COUNT,
                "数据集 $datasetIndex 的流水数量超出范围",
            )
            assertTrue(
                expectedTotal in MIN_EXPENSE_TOTAL..MAX_EXPENSE_TOTAL,
                "数据集 $datasetIndex 的目标总额超出范围",
            )
            assertEquals(
                transactionCount,
                monthlyTransactions(period).size,
                "数据集 $datasetIndex 的月份流水数量不一致",
            )

            val summary = monthlySummary(period)
            assertEquals(0L, summary.totalIncome, "数据集 $datasetIndex 不应包含收入")
            assertEquals(expectedTotal, summary.totalExpense, "数据集 $datasetIndex 的支出总额不一致")
            assertEquals(-expectedTotal, summary.netChange, "数据集 $datasetIndex 的净变化不一致")
            assertEquals(expectedByCategory, summary.expensesByCategory, "数据集 $datasetIndex 的分类汇总不一致")
            assertEquals(expectedTotal, summary.expensesByCategory.values.sum(), "数据集 $datasetIndex 的分类合计不一致")

            val status = budgetStatus(period)
            assertEquals(expectedTotal, status.total.amountUsed, "数据集 $datasetIndex 的总预算使用额不一致")
            assertEquals(TOTAL_BUDGET_BUFFER, status.total.amountRemaining, "数据集 $datasetIndex 的总预算余额不一致")
            assertEquals(categoryIds.size, status.categories.size, "数据集 $datasetIndex 的分类预算数量不一致")
            expectedByCategory.forEach { (categoryId, amountUsed) ->
                val categoryUsage = status.categories.getValue(categoryId)
                assertEquals(amountUsed, categoryUsage.amountUsed, "数据集 $datasetIndex 的 $categoryId 使用额不一致")
                assertEquals(
                    CATEGORY_BUDGET_BUFFER,
                    categoryUsage.amountRemaining,
                    "数据集 $datasetIndex 的 $categoryId 预算余额不一致",
                )
            }

            val currentDay = datasetIndex % DAYS_IN_AUGUST + 1
            val daily = checkNotNull(dailyAvailableBudget(period, currentDay))
            val expectedRemainingDays = DAYS_IN_AUGUST - currentDay + 1
            assertEquals(expectedRemainingDays, daily.remainingDays, "数据集 $datasetIndex 的剩余天数不一致")
            assertEquals(
                TOTAL_BUDGET_BUFFER / expectedRemainingDays,
                daily.dailyAmount,
                "数据集 $datasetIndex 的日均可用预算不一致",
            )
        }
    }

    private fun transactionCount(datasetIndex: Int): Int =
        MIN_TRANSACTION_COUNT +
            datasetIndex * (MAX_TRANSACTION_COUNT - MIN_TRANSACTION_COUNT) / (DATASET_COUNT - 1)

    private fun expenseTotal(datasetIndex: Int): Long =
        MIN_EXPENSE_TOTAL +
            datasetIndex * (MAX_EXPENSE_TOTAL - MIN_EXPENSE_TOTAL) / (DATASET_COUNT - 1)

    private fun transactionTime(
        datasetIndex: Int,
        transactionIndex: Int,
    ): Instant {
        val day = transactionIndex % DAYS_IN_AUGUST + 1
        val hour = transactionIndex / DAYS_IN_AUGUST % 24
        val minute = transactionIndex * 7 % 60
        val second = (datasetIndex + transactionIndex) % 60
        val timestamp = buildString {
            append("2026-08-")
            append(day.toString().padStart(2, '0'))
            append('T')
            append(hour.toString().padStart(2, '0'))
            append(':')
            append(minute.toString().padStart(2, '0'))
            append(':')
            append(second.toString().padStart(2, '0'))
            append('Z')
        }
        return Instant.parse(timestamp)
    }

    private companion object {
        const val DATASET_COUNT = 30
        const val MIN_TRANSACTION_COUNT = 200
        const val MAX_TRANSACTION_COUNT = 500
        const val MIN_EXPENSE_TOTAL = 200_000L
        const val MAX_EXPENSE_TOTAL = 5_000_000L
        const val TOTAL_BUDGET_BUFFER = 100_000L
        const val CATEGORY_BUDGET_BUFFER = 10_000L
        const val DAYS_IN_AUGUST = 31

        val month = BudgetMonth(2026, 8)
        val period = MonthPeriod(
            month = month,
            startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
            endExclusive = Instant.parse("2026-09-01T00:00:00Z"),
        )
        val categoryIds = listOf(
            "food",
            "transport",
            "shopping",
            "entertainment",
            "digital",
            "learning",
            "travel",
            "daily-needs",
        )
        val categories = categoryIds.map { categoryId ->
            Category(
                id = categoryId,
                name = categoryId,
                type = CategoryType.EXPENSE,
                iconKey = categoryId,
                isSystem = true,
            )
        }
    }
}
