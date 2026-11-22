package com.financeos.app.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyInputTest {
    @Test
    fun decimalAmountConvertsExactlyToMinorUnits() {
        assertEquals(2_350L, parseAmountInMinorUnits("23.50"))
        assertEquals(1L, parseAmountInMinorUnits("0.01"))
        assertEquals(2_350L, parseAmountInMinorUnits("23.5"))
    }

    @Test
    fun illegalOrZeroAmountIsRejected() {
        assertNull(normalizeAmountInput("23.501"))
        assertNull(normalizeAmountInput("12a"))
        assertNull(parseAmountInMinorUnits("0"))
        assertNull(parseAmountInMinorUnits("."))
        assertNull(parseAmountInMinorUnits(""))
    }

    @Test
    fun parserRejectsMalformedOrOverflowingAmounts() {
        assertNull(parseAmountInMinorUnits("1.002"))
        assertNull(parseAmountInMinorUnits("10.xx"))
        assertNull(parseAmountInMinorUnits("-1"))
        assertNull(parseAmountInMinorUnits("92233720368547758.08"))
        assertEquals(Long.MAX_VALUE, parseAmountInMinorUnits("92233720368547758.07"))
        assertEquals(10_250L, parseAmountInMinorUnits("102,50"))
        assertEquals(Long.MAX_VALUE, parseAmountInMinorUnits(amountInputFromMinorUnits(Long.MAX_VALUE)))
    }

    @Test
    fun budgetInputCanRepresentExplicitZeroLimit() {
        assertEquals(0L, parseAmountInMinorUnits("0", allowZero = true))
        assertEquals(0L, parseAmountInMinorUnits("0.00", allowZero = true))
    }

    @Test
    fun moneyDisplayUsesGroupingAndTwoDecimalPlaces() {
        assertEquals("¥0.00", formatMoney(0L))
        assertEquals("¥23.50", formatMoney(2_350L))
        assertEquals("¥50,000.00", formatMoney(5_000_000L))
        assertEquals("-¥92,233,720,368,547,758.08", formatMoney(Long.MIN_VALUE))
    }
}
