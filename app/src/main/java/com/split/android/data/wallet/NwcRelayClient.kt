package com.split.android.data.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

interface NwcRelayTransport {
    suspend fun fetchWalletInfo(relayUrl: String, walletPubkey: String): NwcWalletInfoEvent

    suspend fun sendEventAndAwaitResponse(
        relayUrl: String,
        event: NwcNostrEvent,
        wallet: NwcWalletCredentials,
        clientPubkey: String
    ): JSONObject

    suspend fun subscribeNotifications(
        relayUrl: String,
        wallet: NwcWalletCredentials,
        clientPubkey: String,
        onNotification: (NwcNotificationEvent) -> Unit
    )
}

class NwcRelayClient(
    private val okHttpClient: OkHttpClient = sharedClient,
    private val timeoutMillis: Long = 12_000L
) : NwcRelayTransport {
    override suspend fun fetchWalletInfo(relayUrl: String, walletPubkey: String): NwcWalletInfoEvent {
        val subscriptionId = "split-nwc-info-${UUID.randomUUID()}"
        val result = CompletableDeferred<NwcWalletInfoEvent>()

        val webSocket = openSocket(relayUrl) { message ->
            val event = infoEventFromMessage(message, subscriptionId) ?: return@openSocket
            if (event.pubkey.equals(walletPubkey, ignoreCase = true)) {
                result.complete(event)
            }
        }

        try {
            val filter = JSONObject()
                .put("kinds", JSONArray(listOf(13194)))
                .put("authors", JSONArray(listOf(walletPubkey)))
                .put("limit", 1)
            webSocket.send(JSONArray(listOf("REQ", subscriptionId, filter)).toString())
            return withTimeout(timeoutMillis) { result.await() }
        } finally {
            webSocket.send(JSONArray(listOf("CLOSE", subscriptionId)).toString())
            webSocket.close(1000, null)
        }
    }

    override suspend fun sendEventAndAwaitResponse(
        relayUrl: String,
        event: NwcNostrEvent,
        wallet: NwcWalletCredentials,
        clientPubkey: String
    ): JSONObject {
        val subscriptionId = "split-nwc-${UUID.randomUUID()}"
        val result = CompletableDeferred<JSONObject>()

        val webSocket = openSocket(relayUrl) { message ->
            val response = responseFromMessage(
                message = message,
                subscriptionId = subscriptionId,
                requestEventId = event.id,
                wallet = wallet
            ) ?: return@openSocket
            result.complete(response)
        }

        try {
            val filter = JSONObject()
                .put("kinds", JSONArray(listOf(23195)))
                .put("authors", JSONArray(listOf(wallet.walletPubkey)))
                .put("#e", JSONArray(listOf(event.id)))
                .put("#p", JSONArray(listOf(clientPubkey)))
                .put("limit", 1)
            webSocket.send(JSONArray(listOf("REQ", subscriptionId, filter)).toString())
            webSocket.send(JSONArray(listOf("EVENT", event.toJson())).toString())
            return withTimeout(timeoutMillis) { result.await() }
        } finally {
            webSocket.send(JSONArray(listOf("CLOSE", subscriptionId)).toString())
            webSocket.close(1000, null)
        }
    }

    override suspend fun subscribeNotifications(
        relayUrl: String,
        wallet: NwcWalletCredentials,
        clientPubkey: String,
        onNotification: (NwcNotificationEvent) -> Unit
    ) {
        val subscriptionId = "split-nwc-notify-${UUID.randomUUID()}"
        val opened = CompletableDeferred<WebSocket>()
        val closed = CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    notificationFromMessage(
                        message = text,
                        subscriptionId = subscriptionId,
                        wallet = wallet,
                        clientPubkey = clientPubkey
                    )?.let(onNotification)
                }.onFailure { error ->
                    if (!closed.isCompleted) {
                        closed.completeExceptionally(error)
                    }
                    webSocket.cancel()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.isCompleted) {
                    closed.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) {
                    opened.completeExceptionally(t)
                }
                if (!closed.isCompleted) {
                    closed.completeExceptionally(t)
                }
            }
        }

        val webSocket = clientForRelay(relayUrl).newWebSocket(Request.Builder().url(relayUrl).build(), listener)
        try {
            withTimeout(timeoutMillis) { opened.await() }
            val filter = JSONObject()
                .put("kinds", JSONArray(listOf(NWC_NOTIFICATION_KIND)))
                .put("authors", JSONArray(listOf(wallet.walletPubkey)))
                .put("#p", JSONArray(listOf(clientPubkey)))
            webSocket.send(JSONArray(listOf("REQ", subscriptionId, filter)).toString())
            closed.await()
        } finally {
            webSocket.send(JSONArray(listOf("CLOSE", subscriptionId)).toString())
            webSocket.close(1000, null)
        }
    }

    private suspend fun openSocket(
        relayUrl: String,
        onMessage: (String) -> Unit
    ): WebSocket = withContext(Dispatchers.IO) {
        val opened = CompletableDeferred<WebSocket>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { onMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) {
                    opened.completeExceptionally(t)
                }
            }
        }
        val webSocket = clientForRelay(relayUrl).newWebSocket(Request.Builder().url(relayUrl).build(), listener)
        try {
            withTimeout(timeoutMillis) { opened.await() }
        } catch (error: Throwable) {
            webSocket.cancel()
            throw error
        }
    }

    private fun infoEventFromMessage(message: String, subscriptionId: String): NwcWalletInfoEvent? {
        val raw = JSONArray(message)
        val type = raw.optString(0)
        if (type != "EVENT") return null
        if (raw.length() < 3 || raw.optString(1) != subscriptionId) {
            throw NwcWalletException.InvalidRelayResponse
        }
        val event = NwcNostrEvent.fromJson(raw.getJSONObject(2))
        if (event.kind != 13194 || !NwcNostrCryptography.verifyEvent(event)) {
            throw NwcWalletException.InvalidRelayResponse
        }
        return NwcWalletInfoEvent(event)
    }

    private fun responseFromMessage(
        message: String,
        subscriptionId: String,
        requestEventId: String,
        wallet: NwcWalletCredentials
    ): JSONObject? {
        val raw = JSONArray(message)
        val type = raw.optString(0)
        if (type != "EVENT") return null
        if (raw.length() < 3 || raw.optString(1) != subscriptionId) {
            throw NwcWalletException.InvalidRelayResponse
        }
        val event = NwcNostrEvent.fromJson(raw.getJSONObject(2))
        val referencesRequest = event.tags.any { tag -> tag.size > 1 && tag[0] == "e" && tag[1] == requestEventId }
        if (event.kind != 23195 ||
            !event.pubkey.equals(wallet.walletPubkey, ignoreCase = true) ||
            !referencesRequest
        ) {
            return null
        }
        if (!NwcNostrCryptography.verifyEvent(event)) {
            throw NwcWalletException.InvalidRelayResponse
        }
        val plaintext = when (wallet.capabilities?.preferredEncryptionMode ?: NwcEncryptionMode.NIP04) {
            NwcEncryptionMode.NIP44_V2 -> NwcNostrCryptography.nip44Decrypt(
                payload = event.content,
                recipientPrivateKeyHex = wallet.secret,
                senderPublicKeyHex = event.pubkey
            )
            NwcEncryptionMode.NIP04 -> NwcNostrCryptography.nip04Decrypt(
                payload = event.content,
                recipientPrivateKeyHex = wallet.secret,
                senderPublicKeyHex = event.pubkey
            )
        }
        return JSONObject(plaintext)
    }

    private fun notificationFromMessage(
        message: String,
        subscriptionId: String,
        wallet: NwcWalletCredentials,
        clientPubkey: String
    ): NwcNotificationEvent? {
        val raw = JSONArray(message)
        val type = raw.optString(0)
        if (type != "EVENT") return null
        if (raw.length() < 3 || raw.optString(1) != subscriptionId) {
            throw NwcWalletException.InvalidRelayResponse
        }

        val event = NwcNostrEvent.fromJson(raw.getJSONObject(2))
        val referencesClient = event.tags.any { tag -> tag.size > 1 && tag[0] == "p" && tag[1].equals(clientPubkey, ignoreCase = true) }
        if (event.kind != NWC_NOTIFICATION_KIND ||
            !event.pubkey.equals(wallet.walletPubkey, ignoreCase = true) ||
            !referencesClient
        ) {
            return null
        }
        if (!NwcNostrCryptography.verifyEvent(event)) {
            throw NwcWalletException.InvalidRelayResponse
        }

        val plaintext = when (wallet.capabilities?.preferredEncryptionMode ?: NwcEncryptionMode.NIP04) {
            NwcEncryptionMode.NIP44_V2 -> NwcNostrCryptography.nip44Decrypt(
                payload = event.content,
                recipientPrivateKeyHex = wallet.secret,
                senderPublicKeyHex = event.pubkey
            )
            NwcEncryptionMode.NIP04 -> NwcNostrCryptography.nip04Decrypt(
                payload = event.content,
                recipientPrivateKeyHex = wallet.secret,
                senderPublicKeyHex = event.pubkey
            )
        }
        val decoded = JSONObject(plaintext)
        return NwcNotificationEvent(
            id = event.id,
            type = decoded.getString("notification_type"),
            notification = decoded.optJSONObject("notification") ?: JSONObject()
        )
    }

    private fun clientForRelay(relayUrl: String): OkHttpClient {
        return if (RemoteNodeTransport.preferredForUrl(relayUrl) == RemoteNodeTransport.TOR) {
            torClient
        } else {
            okHttpClient
        }
    }

    companion object {
        private const val NWC_NOTIFICATION_KIND = 23196

        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        private val torClient: OkHttpClient by lazy {
            RemoteNodeTorTransport.okHttpClient(
                connectTimeoutMillis = 20_000,
                readTimeoutMillis = 0,
                writeTimeoutMillis = 20_000,
                pingIntervalMillis = 25_000
            )
        }
    }
}
