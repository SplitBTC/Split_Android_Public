package com.split.android.data.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NwcConnectParserTest {
    @Test
    fun parsesNostrWalletConnectConnectionString() {
        val relay = "wss://relay.getalby.com/v1"
        val connectionString = "nostr+walletconnect://$testWalletPubkey" +
            "?relay=${relay.urlEncoded()}" +
            "&secret=$testSecret" +
            "&lud16=ALICE@EXAMPLE.COM" +
            "&name=Alby%20Hub"

        val credentials = NwcConnectParser.parse(connectionString)

        assertEquals(testWalletPubkey, credentials.walletPubkey)
        assertEquals(listOf(relay), credentials.relayUrls)
        assertEquals(testSecret, credentials.secret)
        assertEquals("alice@example.com", credentials.lud16)
        assertEquals("Alby Hub", credentials.label)
    }

    @Test
    fun rejectsConnectionStringWithoutSecureRelay() {
        val connectionString = "nostr+walletconnect://$testWalletPubkey" +
            "?relay=${"http://relay.example.com".urlEncoded()}" +
            "&secret=$testSecret"

        val error = assertThrows(NwcWalletException.InvalidRelay::class.java) {
            NwcConnectParser.parse(connectionString)
        }

        assertEquals("The NWC relay URL is invalid.", error.message)
    }

    private fun String.urlEncoded(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }
}

