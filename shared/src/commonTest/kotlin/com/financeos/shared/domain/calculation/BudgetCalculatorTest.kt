package com.financeos.shared.domain.calculation

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetCalculatorTest {
    @Test
    fun calculatesAvailableBudget() {
        val usage = BudgetCalculator.calculate(
            budget = budget(amountLimit = 100_000L),
            amountUsed = 25_000L,
        )

        assertEquals(25_000L, usage.amountUsed)
        assertEquals(75_000L, usage.amountRemaining)
        assertEquals(0.25, usage.usageRatio)
        assertFalse(usage.isOverBudget)
        assertTrue(usage.hasBudget)
    }

    @Test
    fun calculatesOverspentBudgetWithoutCappingRatio() {
        val usage = BudgetCalculator.calculate(
            budget = budget(amountLimit = 100_000L),
            amountUsed = 120_000L,
        )

        assertEquals(-20_000L, usage.amountRemaining)
        assertEquals(1.2, usage.usageRatio!!, absoluteTolerance = 0.000_001)
        assertTrue(usage.isOverBudget)
    }

    @Test
    fun handlesZeroLimitWithoutDividingByZero() {
        val unused = BudgetCalculator.calculate(budget(amountLimit = 0L), amountUsed = 0L)
        val spent = BudgetCalculator.calculate(budget(amountLimit = 0L), amountUsed = 500L)

        assertEquals(0L, unused.amountRemaining)
        assertNull(unused.usageRatio)
        assertFalse(unused.isOverBudget)
        assertEquals(-500L, spent.amountRemaining)
        assertNull(spent.usageRatio)
        assertTrue(spent.isOverBudget)
    }

    @Test
    fun representsMissingBudgetExplicitly() {
        val usage = BudgetCalculator.calculate(budget = null, amountUsed = 500L)

        assertEquals(500L, usage.amountUsed)
        assertNull(usage.amountRemaining)
        assertNull(usage.usageRatio)
        assertFalse(usage.isOverBudget)
        assertFalse(usage.hasBudget)
    }

    @Test
    fun rejectsNegativeMoneyValues() {
        assertFailsWith<IllegalArgumentException> {
            budget(amountLimit = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            BudgetCalculator.calculate(budget(), amountUsed = -1L)
        }
    }

    @Test
    fun validatesMonthAndOptionalCategory() {
        assertFailsWith<IllegalArgumentException> {
            BudgetMonth(year = 2026, month = 13)
        }
        assertFailsWith<IllegalArgumentException> {
            budget(categoryId = " ")
        }

        assertNull(budget().categoryId)
        assertEquals("food", budget(categoryId = "food").categoryId)
    }

    private fun budget(
        amountLimit: Long = 100_000L,
        categoryId: String? = null,
    ) = Budget(
        id = "budget-id",
        month = BudgetMonth(year = 2026, month = 8),
        amountLimit = amountLimit,
        categoryId = categoryId,
    )
}
