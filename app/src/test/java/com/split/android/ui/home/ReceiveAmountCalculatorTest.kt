package com.split.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiveAmountCalculatorTest {

    @Test
    fun convertsUsdInputIntoBtcText() {
        val result = ReceiveAmountCalculator.btcTextFromUsdInput(
            usdInput = "100.00",
            btcUsdRate = 100000.0
        )

        assertEquals("0.00100000", result)
    }

    @Test
    fun convertsBtcInputIntoUsdText() {
        val result = ReceiveAmountCalculator.usdTextFromBtcInput(
            btcInput = "0.00100000",
            btcUsdRate = 100000.0
        )

        assertEquals("100.00", result)
    }

    @Test
    fun convertsUsdInputIntoSatsText() {
        val result = ReceiveAmountCalculator.satsTextFromUsdInput(
            usdInput = "100.00",
            btcUsdRate = 100000.0
        )

        assertEquals("100000", result)
    }

    @Test
    fun convertsSatsInputIntoUsdText() {
        val result = ReceiveAmountCalculator.usdTextFromSatsInput(
            satsInput = "100000",
            btcUsdRate = 100000.0
        )

        assertEquals("100.00", result)
    }

    @Test
    fun convertsBtcInputIntoSats() {
        val result = ReceiveAmountCalculator.satsFromBtcInput("0.00123456")

        assertEquals(123456L, result)
    }

    @Test
    fun returnsNullWhenBtcPriceIsMissing() {
        val result = ReceiveAmountCalculator.btcTextFromUsdInput(
            usdInput = "100.00",
            btcUsdRate = null
        )

        assertNull(result)
    }

    @Test
    fun sanitizesSatsInputToDigitsOnly() {
        val result = ReceiveAmountCalculator.sanitizeSatsInput("0012abc3.4")

        assertEquals("1234", result)
    }

    @Test
    fun sanitizesBtcInputToEightDecimals() {
        val result = ReceiveAmountCalculator.sanitizeBtcInput("0.123456789")

        assertEquals("0.12345678", result)
    }
}
