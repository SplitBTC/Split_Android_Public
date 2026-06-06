package com.split.android.data.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingPrivacyV4Test {
    @Test
    fun lightningAddressHashMatchesBackendContract() {
        val hash = MessagingPrivacyV4.lightningAddressClientHash(" Donate@Example.com ")

        assertEquals(
            "6d9cb0138b1363847559a75ef691dcca6e60cfe41e18b867337e1623b346735c",
            hash
        )
    }

    @Test
    fun normalizeLightningAddressLowercasesAndTrims() {
        assertEquals(
            "donate@example.com",
            MessagingPrivacyV4.normalizeLightningAddress(" Donate@Example.com ")
        )
    }
}
