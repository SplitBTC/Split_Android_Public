package com.split.android.data.wallet

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class NwcCommandClient(
    private val wallet: NwcWalletCredentials,
    private val relayClient: NwcRelayTransport = NwcRelayClient()
) {
    suspend fun getBalance(): NwcGetBalanceResult {
        val result = send("get_balance", JSONObject())
        return NwcGetBalanceResult(balanceMsats = result.getJSONObject("result").flexLong("balance"))
    }

    suspend fun makeInvoice(
        amountSats: Long?,
        description: String?,
        expirySecs: Long = 3_600L
    ): NwcTransactionResult {
        val params = JSONObject()
            .put("description", description ?: JSONObject.NULL)
            .put("expiry", expirySecs)

        amountSats
            ?.takeIf { it > 0L }
            ?.let { params.put("amount", it * 1_000L) }

        return resultTransaction(send("make_invoice", params).getJSONObject("result"))
    }

    suspend fun payInvoice(invoice: String, amountSats: Long? = null): NwcPayInvoiceResult {
        val params = JSONObject()
            .put("invoice", invoice)
            .put("amount", amountSats?.times(1_000L) ?: JSONObject.NULL)
        val result = send("pay_invoice", params).getJSONObject("result")
        return NwcPayInvoiceResult(
            preimage = result.nwcOptNullableString("preimage"),
            feesPaidMsats = result.nwcOptFlexibleLong("fees_paid")
        )
    }

    suspend fun lookupInvoice(invoice: String): NwcTransactionResult {
        val params = JSONObject()
            .put("invoice", invoice)
            .put("payment_hash", JSONObject.NULL)
        return resultTransaction(send("lookup_invoice", params).getJSONObject("result"))
    }

    suspend fun lookupInvoiceByPaymentHash(paymentHash: String): NwcTransactionResult {
        val params = JSONObject()
            .put("invoice", JSONObject.NULL)
            .put("payment_hash", paymentHash)
        return resultTransaction(send("lookup_invoice", params).getJSONObject("result"))
    }

    suspend fun listTransactions(limit: Int = 50): List<NwcTransactionResult> {
        val params = JSONObject().put("limit", limit)
        val transactions = send("list_transactions", params)
            .getJSONObject("result")
            .optJSONArray("transactions")
            .nwcOrEmpty()
        return buildList {
            for (index in 0 until transactions.length()) {
                add(resultTransaction(transactions.getJSONObject(index)))
            }
        }
    }

    private suspend fun send(method: String, params: JSONObject): JSONObject {
        requireSupported(method)
        val request = JSONObject()
            .put("method", method)
            .put("params", params)
        val requestJson = request.toString()
        val encryptionMode = wallet.capabilities?.preferredEncryptionMode ?: NwcEncryptionMode.NIP04
        val encryptedContent = when (encryptionMode) {
            NwcEncryptionMode.NIP44_V2 -> NwcNostrCryptography.nip44Encrypt(
                plaintext = requestJson,
                senderPrivateKeyHex = wallet.secret,
                recipientPublicKeyHex = wallet.walletPubkey
            )
            NwcEncryptionMode.NIP04 -> NwcNostrCryptography.nip04Encrypt(
                plaintext = requestJson,
                senderPrivateKeyHex = wallet.secret,
                recipientPublicKeyHex = wallet.walletPubkey
            )
        }
        val clientPubkey = NwcNostrCryptography.publicKeyHex(wallet.secret)
        val createdAt = System.currentTimeMillis() / 1_000L
        val tags = mutableListOf(
            listOf("p", wallet.walletPubkey),
            listOf("expiration", (createdAt + 60L).toString())
        )
        if (encryptionMode == NwcEncryptionMode.NIP44_V2) {
            tags.add(listOf("encryption", NwcEncryptionMode.NIP44_V2.rawValue))
        }
        val event = NwcNostrCryptography.signEvent(
            privateKeyHex = wallet.secret,
            createdAt = createdAt,
            kind = 23194,
            tags = tags,
            content = encryptedContent
        )
        val response = sendToFirstRelay(event, clientPubkey)
        response.optJSONObject("error")?.let { error ->
            if (error.length() > 0 && (!error.isNull("code") || !error.isNull("message"))) {
                throw NwcWalletException.WalletError(
                    code = error.nwcOptNullableString("code"),
                    serverMessage = error.nwcOptNullableString("message")
                )
            }
        }
        if (!response.has("result") || response.isNull("result")) {
            throw NwcWalletException.InvalidRelayResponse
        }
        return response
    }

    private suspend fun sendToFirstRelay(event: NwcNostrEvent, clientPubkey: String): JSONObject = coroutineScope {
        val results = Channel<Result<JSONObject>>(Channel.UNLIMITED)
        val jobs = wallet.relayUrls.map { relayUrl ->
            launch {
                results.send(
                    runCatching {
                        relayClient.sendEventAndAwaitResponse(
                            relayUrl = relayUrl,
                            event = event,
                            wallet = wallet,
                            clientPubkey = clientPubkey
                        )
                    }
                )
            }
        }

        var lastError: Throwable? = null
        try {
            repeat(jobs.size) {
                val result = results.receive()
                result.onSuccess { response ->
                    jobs.forEach { it.cancel() }
                    results.close()
                    return@coroutineScope response
                }.onFailure { error ->
                    lastError = error
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }

        throw lastError ?: NwcWalletException.RelayConnectionFailed
    }

    private fun requireSupported(method: String) {
        if (wallet.capabilities?.supports(method) == false) {
            throw NwcWalletException.UnsupportedMethod(method)
        }
    }

    private fun resultTransaction(json: JSONObject): NwcTransactionResult {
        return NwcTransactionResult(
            type = json.nwcOptNullableString("type"),
            state = json.nwcOptNullableString("state"),
            invoice = json.nwcOptNullableString("invoice"),
            description = json.nwcOptNullableString("description"),
            descriptionHash = json.nwcOptNullableString("description_hash"),
            preimage = json.nwcOptNullableString("preimage"),
            paymentHash = json.nwcOptNullableString("payment_hash"),
            amountMsats = json.nwcOptFlexibleLong("amount"),
            feesPaidMsats = json.nwcOptFlexibleLong("fees_paid"),
            createdAt = json.nwcOptFlexibleLong("created_at"),
            expiresAt = json.nwcOptFlexibleLong("expires_at")
        )
    }
}

internal fun JSONObject.nwcOptFlexibleLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val raw = opt(name)
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}

internal fun JSONObject.flexLong(name: String): Long {
    return nwcOptFlexibleLong(name) ?: throw NwcWalletException.InvalidRelayResponse
}
