package com.split.android.data.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingPrivacyV4Test {
    @Test
    fun lightningAddressHashMatchesBackendContract() {
        val hash = MessagingPrivacyV4.lightningAddressClientHash(" Donate@example.com ")

        assertEquals(
            "5b302903b0b357301b2b5441b794e19bd26a1e6232e12b1b1f5764e9b5ff41ab",
            hash
        )
    }

    @Test
    fun normalizeLightningAddressLowercasesAndTrims() {
        assertEquals(
            "donate@example.com",
            MessagingPrivacyV4.normalizeLightningAddress(" Donate@example.com ")
        )
    }
}
