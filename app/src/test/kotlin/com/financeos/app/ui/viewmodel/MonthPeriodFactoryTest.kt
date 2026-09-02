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
    fun budgetMonthNavigationReachesAnyPastMonthButStopsAtNextMonth() {
        val current = YearMonth.of(2026, 8)
        val latest = current.plusMonths(1)
        val earliest = YearMonth.of(2000, 1)

        assertEquals(YearMonth.of(2026, 7), previousBudgetMonthOrNull(current, earliest))
        assertEquals(YearMonth.of(2020, 1), previousBudgetMonthOrNull(YearMonth.of(2020, 2), earliest))
        // 到达最远回溯下界后不再前进，同时未来不会越过“当前月+1”。
        assertNull(previousBudgetMonthOrNull(earliest, earliest))
        assertEquals(latest, nextBudgetMonthOrNull(current, latest))
        assertNull(nextBudgetMonthOrNull(latest, latest))
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
