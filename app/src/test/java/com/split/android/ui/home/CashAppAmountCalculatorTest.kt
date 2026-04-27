package com.split.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CashAppAmountCalculatorTest {

    @Test
    fun parsesSatsInputIntoSats() {
        val result = CashAppAmountCalculator.parseAmountSats(
            unit = CashAppAmountUnit.SATS,
            inputText = "100000",
            btcUsdRate = null
        )

        assertEquals(100000L, result)
    }

    @Test
    fun parsesUsdInputIntoSatsUsingBtcPrice() {
        val result = CashAppAmountCalculator.parseAmountSats(
            unit = CashAppAmountUnit.USD,
            inputText = "50.00",
            btcUsdRate = 100000.0
        )

        assertEquals(50000L, result)
    }

    @Test
    fun returnsNullForUsdInputWhenBtcPriceIsMissing() {
        val result = CashAppAmountCalculator.parseAmountSats(
            unit = CashAppAmountUnit.USD,
            inputText = "50.00",
            btcUsdRate = null
        )

        assertNull(result)
    }

    @Test
    fun sanitizesUsdInputToTwoDecimalPlaces() {
        val result = CashAppAmountCalculator.sanitizeInput(
            unit = CashAppAmountUnit.USD,
            value = "$25.999"
        )

        assertEquals("25.99", result)
    }

    @Test
    fun buildsUsdHelperTextWhenAmountIsAvailable() {
        val summary = CashAppAmountCalculator.helperText(
            unit = CashAppAmountUnit.USD,
            inputText = "50.00",
            amountSats = 50000L,
            btcUsdRate = 100000.0
        )

        assertEquals(
            "Approx. 50,000 sats",
            summary
        )
    }

    @Test
    fun buildsMissingPricePromptForUsdMode() {
        val summary = CashAppAmountCalculator.helperText(
            unit = CashAppAmountUnit.USD,
            inputText = "50.00",
            amountSats = null,
            btcUsdRate = null
        )

        assertEquals(
            "Waiting for Bitcoin price data.",
            summary
        )
    }
}
