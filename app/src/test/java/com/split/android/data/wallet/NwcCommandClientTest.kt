package com.split.android.data.wallet

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NwcCommandClientTest {
    @Test
    fun returnsFirstSuccessfulRelayResponseByCompletionOrder() = runBlocking {
        val client = NwcCommandClient(
            wallet = testWalletCredentials(
                relayUrls = listOf("wss://slow.example.com", "wss://fast.example.com")
            ),
            relayClient = FakeNwcRelayTransport { relayUrl ->
                when (relayUrl) {
                    "wss://slow.example.com" -> {
                        delay(2_000)
                        JSONObject("""{"result":{"balance":111000}}""")
                    }
                    "wss://fast.example.com" -> JSONObject("""{"result":{"balance":222000}}""")
                    else -> error("Unexpected relay $relayUrl")
                }
            }
        )

        val result = withTimeout(500) {
            client.getBalance()
        }

        assertEquals(222L, result.balanceSats)
    }

    @Test
    fun fallsBackAfterFastRelayFailure() = runBlocking {
        val client = NwcCommandClient(
            wallet = testWalletCredentials(
                relayUrls = listOf("wss://fail.example.com", "wss://ok.example.com")
            ),
            relayClient = FakeNwcRelayTransport { relayUrl ->
                when (relayUrl) {
                    "wss://fail.example.com" -> error("relay unavailable")
                    "wss://ok.example.com" -> JSONObject("""{"result":{"balance":123000}}""")
                    else -> error("Unexpected relay $relayUrl")
                }
            }
        )

        assertEquals(123L, client.getBalance().balanceSats)
    }
}

private class FakeNwcRelayTransport(
    private val response: suspend (String) -> JSONObject
) : NwcRelayTransport {
    override suspend fun fetchWalletInfo(relayUrl: String, walletPubkey: String): NwcWalletInfoEvent {
        throw UnsupportedOperationException("Not needed for this test.")
    }

    override suspend fun sendEventAndAwaitResponse(
        relayUrl: String,
        event: NwcNostrEvent,
        wallet: NwcWalletCredentials,
        clientPubkey: String
    ): JSONObject {
        return response(relayUrl)
    }

    override suspend fun subscribeNotifications(
        relayUrl: String,
        wallet: NwcWalletCredentials,
        clientPubkey: String,
        onNotification: (NwcNotificationEvent) -> Unit
    ) {
        throw UnsupportedOperationException("Not needed for this test.")
    }
}

