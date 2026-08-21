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
}
