package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class GetExpenseTrendUseCaseTest {
    @Test
    fun groupsExpensesByHalfOpenPeriodsAndExcludesIncome() = runTest {
        val repository = FakeTransactionRepository(
            listOf(
                transaction("first", 1_000L, TransactionType.EXPENSE, "2026-08-01T00:00:00Z"),
                transaction("income", 9_000L, TransactionType.INCOME, "2026-08-01T10:00:00Z"),
                transaction("boundary", 2_350L, TransactionType.EXPENSE, "2026-08-02T00:00:00Z"),
            ),
        )
        val periods = listOf(
            ExpenseTrendPeriod(
                key = "08-01",
                startInclusive = Instant.parse("2026-08-01T00:00:00Z"),
                endExclusive = Instant.parse("2026-08-02T00:00:00Z"),
            ),
            ExpenseTrendPeriod(
                key = "08-02",
                startInclusive = Instant.parse("2026-08-02T00:00:00Z"),
                endExclusive = Instant.parse("2026-08-03T00:00:00Z"),
            ),
        )

        assertEquals(
            listOf(
                // 净支出口径：08-01 = 支出1000 − 收入9000 = −8000（有结余）；08-02 = 2350。
                ExpenseTrendPoint("08-01", -8_000L),
                ExpenseTrendPoint("08-02", 2_350L),
            ),
            GetExpenseTrendUseCase(repository)(periods),
        )
    }

    @Test
    fun observesMultipleTrendGroupsFromOneTransactionSnapshot() = runTest {
        val repository = FakeTransactionRepository(
            listOf(
                transaction("first", 1_000L, TransactionType.EXPENSE, "2026-08-01T10:00:00Z"),
                transaction("second", 2_000L, TransactionType.EXPENSE, "2026-08-02T10:00:00Z"),
            ),
        )
        val daily = listOf(
            ExpenseTrendPeriod(
                "day-1",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"),
            ),
            ExpenseTrendPeriod(
                "day-2",
                Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"),
            ),
        )
        val monthly = listOf(
            ExpenseTrendPeriod(
                "month",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
            ),
        )

        assertEquals(
            listOf(
                listOf(ExpenseTrendPoint("month", 3_000L)),
                listOf(
                    ExpenseTrendPoint("day-1", 1_000L),
                    ExpenseTrendPoint("day-2", 2_000L),
                ),
            ),
            GetExpenseTrendUseCase(repository).observeGroups(listOf(monthly, daily)).first(),
        )
    }

    @Test
    fun rejectsTrendMoneyOverflow() = runTest {
        val repository = FakeTransactionRepository(
            listOf(
                transaction("maximum", Long.MAX_VALUE, TransactionType.EXPENSE, "2026-08-01T10:00:00Z"),
                transaction("overflow", 1L, TransactionType.EXPENSE, "2026-08-01T11:00:00Z"),
            ),
        )
        val period = ExpenseTrendPeriod(
            "day",
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-08-02T00:00:00Z"),
        )

        assertFailsWith<IllegalArgumentException> {
            GetExpenseTrendUseCase(repository)(listOf(period))
        }
    }

    private fun transaction(
        id: String,
        amount: Long,
        type: TransactionType,
        instant: String,
    ) = Transaction(
        id = id,
        amount = amount,
        type = type,
        categoryId = "system-food",
        dateTime = Instant.parse(instant),
    )
}
