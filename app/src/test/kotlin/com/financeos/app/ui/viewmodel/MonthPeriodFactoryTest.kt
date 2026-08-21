package com.financeos.app.ui.viewmodel

import com.financeos.shared.domain.model.BudgetMonth
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MonthPeriodFactoryTest {
    @Test
    fun monthBoundaryUsesLocalTimezoneAcrossDaylightSavingChange() {
        val period = YearMonth.of(2026, 3).toMonthPeriod(ZoneId.of("America/New_York"))

        assertEquals(BudgetMonth(2026, 3), period.month)
        assertEquals(Instant.parse("2026-03-01T05:00:00Z"), period.startInclusive)
        assertEquals(Instant.parse("2026-04-01T04:00:00Z"), period.endExclusive)
    }

    @Test
    fun decemberBoundaryRollsIntoNextYear() {
        val period = YearMonth.of(2026, 12).toMonthPeriod(ZoneId.of("Asia/Shanghai"))

        assertEquals(BudgetMonth(2026, 12), period.month)
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), period.startInclusive)
        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), period.endExclusive)
    }

    @Test
    fun budgetMonthSelectionOnlyResolvesCurrentAndNextMonth() {
        val december = YearMonth.of(2026, 12)

        assertEquals(
            listOf(BudgetMonthSelection.CURRENT, BudgetMonthSelection.NEXT),
            BudgetMonthSelection.entries,
        )
        assertEquals(december, BudgetMonthSelection.CURRENT.resolve(december))
        assertEquals(YearMonth.of(2027, 1), BudgetMonthSelection.NEXT.resolve(december))
    }

    @Test
    fun transactionMonthNavigationStopsAtCurrentMonth() {
        val currentMonth = YearMonth.of(2026, 8)

        assertEquals(
            currentMonth,
            nextTransactionMonthOrNull(YearMonth.of(2026, 7), currentMonth),
        )
        assertNull(nextTransactionMonthOrNull(currentMonth, currentMonth))
    }

    @Test
    fun transactionDateAllowsTodayAndPastButRejectsFuture() {
        val today = LocalDate.of(2026, 8, 21)

        assertTrue(isTransactionDateAllowed(today.minusDays(1), today))
        assertTrue(isTransactionDateAllowed(today, today))
        assertFalse(isTransactionDateAllowed(today.plusDays(1), today))
    }
}
