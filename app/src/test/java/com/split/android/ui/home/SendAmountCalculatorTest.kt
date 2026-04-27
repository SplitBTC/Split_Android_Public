package com.split.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendAmountCalculatorTest {

    @Test
    fun parsesSatsInputIntoSats() {
        val result = SendAmountCalculator.parseEnteredAmountSats(
            amountUnit = SendAmountUnit.SATS,
            amountText = "100000",
            btcUsdRate = null
        )

        assertEquals(100000L, result)
    }

    @Test
    fun parsesUsdInputIntoSats() {
        val result = SendAmountCalculator.parseEnteredAmountSats(
            amountUnit = SendAmountUnit.USD,
            amountText = "50.00",
            btcUsdRate = 100000.0
        )

        assertEquals(50000L, result)
    }

    @Test
    fun returnsNullForUsdWithoutPrice() {
        val result = SendAmountCalculator.parseEnteredAmountSats(
            amountUnit = SendAmountUnit.USD,
            amountText = "50.00",
            btcUsdRate = null
        )

        assertNull(result)
    }

    @Test
    fun formatsLockedUsdAmountWhenPriceAvailable() {
        val result = SendAmountCalculator.lockedAmountDisplayText(
            amountUnit = SendAmountUnit.USD,
            lockedAmountSats = 50000L,
            btcUsdRate = 100000.0
        )

        assertEquals("50.00", result)
    }

    @Test
    fun formatsSendMaxUsdAmountWhenPriceAvailable() {
        val result = SendAmountCalculator.sendMaxAmountDisplayText(
            amountUnit = SendAmountUnit.USD,
            balanceSats = 125000L,
            btcUsdRate = 100000.0
        )

        assertEquals("125.00", result)
    }
}
