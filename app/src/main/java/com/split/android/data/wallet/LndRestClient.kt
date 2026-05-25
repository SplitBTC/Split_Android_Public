package com.split.android.data.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory

class LndRestClient(
    private val credentials: LndNodeCredentials,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 45_000
) {
    private val pinnedCertificateBytes: ByteArray? =
        credentials.tlsCertificateDerBase64?.let { Base64.getDecoder().decode(it) }
    private val sslSocketFactory = pinnedCertificateBytes?.let(::sslSocketFactoryForPinnedCertificate)
    private val hostnameVerifier = pinnedCertificateBytes?.let { pinnedBytes ->
        HostnameVerifier { _, session -> session.containsPinnedCertificate(pinnedBytes) }
    }
    private val transport = credentials.transport

    suspend fun getInfo(): LndGetInfoResponse {
        val json = requestJson(path = "/v1/getinfo")
        return LndGetInfoResponse(
            identityPubkey = json.optString("identity_pubkey"),
            alias = json.lndOptNullableString("alias")
        )
    }

    suspend fun walletBalance(): Long {
        val json = requestJson(path = "/v1/balance/blockchain")
        return json.lndOptFlexibleLong("total_balance") ?: 0L
    }

    suspend fun channelBalance(): Long {
        val json = requestJson(path = "/v1/balance/channels")
        val localBalance = json.optJSONObject("local_balance")
            ?.lndOptFlexibleLong("sat")
        return localBalance ?: json.lndOptFlexibleLong("balance") ?: 0L
    }

    suspend fun addInvoice(
        amountSats: Long?,
        memo: String?,
        expirySecs: Long = 3_600L
    ): LndAddInvoiceResponse {
        val body = JSONObject()
            .put("memo", memo?.trim()?.ifBlank { null } ?: JSONObject.NULL)
            .put("value", amountSats?.toString() ?: JSONObject.NULL)
            .put("expiry", expirySecs.toString())

        val json = requestJson(
            path = "/v1/invoices",
            method = "POST",
            body = body
        )

        return LndAddInvoiceResponse(
            rHash = json.lndOptNullableString("r_hash"),
            paymentRequest = json.getString("payment_request"),
            addIndex = json.lndOptFlexibleLong("add_index"),
            paymentAddr = json.lndOptNullableString("payment_addr")
        )
    }

    suspend fun decodePayReq(bolt11: String): LndDecodePayReqResponse {
        val json = requestJson(path = "/v1/payreq/${urlPathEncode(bolt11.trim())}")
        return LndDecodePayReqResponse(
            destination = json.lndOptNullableString("destination"),
            paymentHash = json.lndOptNullableString("payment_hash"),
            amountSats = json.lndOptFlexibleLong("num_satoshis"),
            timestamp = json.lndOptFlexibleLong("timestamp"),
            expiry = json.lndOptFlexibleLong("expiry"),
            description = json.lndOptNullableString("description")
        )
    }

    suspend fun payInvoice(
        bolt11: String,
        amountSats: Long?
    ): LndPayInvoiceResponse {
        val body = JSONObject()
            .put("payment_request", bolt11.trim())
            .put("amt", amountSats?.toString() ?: JSONObject.NULL)

        val json = requestJson(
            path = "/v1/channels/transactions",
            method = "POST",
            body = body
        )

        return LndPayInvoiceResponse(
            paymentError = json.lndOptNullableString("payment_error"),
            paymentPreimage = json.lndOptNullableString("payment_preimage"),
            paymentHash = json.lndOptNullableString("payment_hash")
        )
    }

    suspend fun estimateRouteFee(
        destinationPubkey: String,
        amountSats: Long
    ): Long {
        require(amountSats > 0L) { "Enter an amount in sats." }

        return runCatching {
            estimateRouteFeeWithRouter(destinationPubkey, amountSats)
        }.getOrElse {
            estimateRouteFeeWithQueryRoutes(destinationPubkey, amountSats)
        }
    }

    suspend fun signMessage(message: String): LndSignMessageResponse {
        val body = JSONObject()
            .put("msg", Base64.getEncoder().encodeToString(message.toByteArray(Charsets.UTF_8)))
            .put("single_hash", false)
        val json = requestJson(
            path = "/v1/signmessage",
            method = "POST",
            body = body
        )
        return LndSignMessageResponse(signature = json.getString("signature"))
    }

    suspend fun listPayments(maxPayments: Int = 50): List<LndPayment> {
        val json = requestJson(
            path = "/v1/payments",
            queryItems = listOf("max_payments" to maxPayments.toString())
        )
        return json.optJSONArray("payments").orEmpty().mapObjects(::paymentFromJson)
    }

    suspend fun listInvoices(maxInvoices: Int = 50): List<LndInvoice> {
        val json = requestJson(
            path = "/v1/invoices",
            queryItems = listOf("num_max_invoices" to maxInvoices.toString())
        )
        return json.optJSONArray("invoices").orEmpty().mapObjects(::invoiceFromJson)
    }

    suspend fun subscribeInvoices(
        settleIndex: Long?,
        onInvoice: suspend (LndInvoice) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val queryItems = buildList {
                if (settleIndex != null) {
                    add("settle_index" to settleIndex.toString())
                }
            }
            val connection = openConnection(
                path = "/v1/invoices/subscribe",
                queryItems = queryItems,
                readTimeout = 86_400_000
            )

            try {
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    throw LndWalletException.ServerError(
                        statusCode = statusCode,
                        serverMessage = connection.errorStream?.readUtf8().orEmpty()
                            .ifBlank { "Invoice listener connection failed." }
                    )
                }

                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        decodeStreamingInvoiceLine(line)?.let { invoice ->
                            onInvoice(invoice)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun estimateRouteFeeWithRouter(
        destinationPubkey: String,
        amountSats: Long
    ): Long {
        val destinationData = hexToBytes(destinationPubkey.trim().lowercase())
            ?: throw LndWalletException.InvalidResponse
        val body = JSONObject()
            .put("dest", Base64.getEncoder().encodeToString(destinationData))
            .put("amt_sat", amountSats.toString())
        val json = requestJson(
            path = "/v2/router/route/estimatefee",
            method = "POST",
            body = body
        )
        val msats = json.lndOptFlexibleLong("routing_fee_msat")
            ?: throw LndWalletException.InvalidResponse
        return msatsToSatsCeiling(msats)
    }

    private suspend fun estimateRouteFeeWithQueryRoutes(
        destinationPubkey: String,
        amountSats: Long
    ): Long {
        val json = requestJson(path = "/v1/graph/routes/${destinationPubkey.trim()}/$amountSats")
        val routes = json.optJSONArray("routes").orEmpty()
        val feeOptions = buildList {
            for (index in 0 until routes.length()) {
                val route = routes.optJSONObject(index) ?: continue
                route.lndOptFlexibleLong("total_fees_msat")?.let { add(msatsToSatsCeiling(it)) }
                route.lndOptFlexibleLong("total_fees")?.let { add(it) }
            }
        }
        return feeOptions.minOrNull() ?: throw LndWalletException.InvalidResponse
    }

    private suspend fun requestJson(
        path: String,
        method: String = "GET",
        queryItems: List<Pair<String, String>> = emptyList(),
        body: JSONObject? = null
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            val connection = openConnection(path = path, method = method, queryItems = queryItems)

            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { output ->
                        output.write(body.toString().toByteArray(Charsets.UTF_8))
                    }
                }

                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val responseBody = stream?.readUtf8().orEmpty()

                if (statusCode !in 200..299) {
                    throw LndWalletException.ServerError(
                        statusCode = statusCode,
                        serverMessage = serverErrorMessage(responseBody)
                    )
                }

                runCatching { JSONObject(responseBody) }.getOrElse {
                    throw LndWalletException.ServerError(
                        statusCode = statusCode,
                        serverMessage = "Unable to decode LND response: $responseBody"
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(
        path: String,
        method: String = "GET",
        queryItems: List<Pair<String, String>> = emptyList(),
        readTimeout: Int = readTimeoutMillis
    ): HttpsURLConnection {
        val url = URL(makeUrlString(path = path, queryItems = queryItems))
        val rawConnection = if (transport == RemoteNodeTransport.TOR) {
            url.openConnection(RemoteNodeTorTransport.proxy)
        } else {
            url.openConnection()
        }
        val connection = (rawConnection as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            this.readTimeout = readTimeout
            doInput = true
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Grpc-Metadata-macaroon", credentials.macaroonHex)
        }

        sslSocketFactory?.let { connection.sslSocketFactory = it }
        hostnameVerifier?.let { connection.hostnameVerifier = it }
        return connection
    }

    private fun makeUrlString(
        path: String,
        queryItems: List<Pair<String, String>>
    ): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val query = queryItems.joinToString("&") { (key, value) ->
            "${urlQueryEncode(key)}=${urlQueryEncode(value)}"
        }
        return buildString {
            append("https://")
            append(credentials.host)
            append(":")
            append(credentials.port)
            append(normalizedPath)
            if (query.isNotBlank()) {
                append("?")
                append(query)
            }
        }
    }

    private fun decodeStreamingInvoiceLine(line: String): LndInvoice? {
        var trimmed = line.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            trimmed = trimmed.drop("data:".length).trim()
        }

        val json = JSONObject(trimmed)
        json.optJSONObject("error")?.let { errorJson ->
            throw LndWalletException.ServerError(
                statusCode = 200,
                serverMessage = errorJson.lndOptNullableString("message")
                    ?: errorJson.lndOptNullableString("error")
                    ?: "LND stream returned an error."
            )
        }
        json.lndOptNullableString("error")?.let { error ->
            throw LndWalletException.ServerError(statusCode = 200, serverMessage = error)
        }

        return invoiceFromJson(json.optJSONObject("result") ?: json)
    }

    private fun serverErrorMessage(responseBody: String): String {
        return runCatching {
            val json = JSONObject(responseBody)
            json.lndOptNullableString("message")
                ?: json.lndOptNullableString("error")
                ?: responseBody.ifBlank { "Unknown LND error" }
        }.getOrDefault(responseBody.ifBlank { "Unknown LND error" })
    }
}

private fun paymentFromJson(json: JSONObject): LndPayment {
    return LndPayment(
        paymentHash = json.lndOptNullableString("payment_hash"),
        paymentPreimage = json.lndOptNullableString("payment_preimage"),
        paymentRequest = json.lndOptNullableString("payment_request"),
        valueSat = json.lndOptFlexibleLong("value_sat"),
        feeSat = json.lndOptFlexibleLong("fee_sat"),
        creationDate = json.lndOptFlexibleLong("creation_date"),
        status = json.lndOptNullableString("status"),
        failureReason = json.lndOptNullableString("failure_reason"),
        description = json.lndOptNullableString("description"),
        destination = json.lndOptNullableString("destination")
    )
}

private fun invoiceFromJson(json: JSONObject): LndInvoice {
    return LndInvoice(
        memo = json.lndOptNullableString("memo"),
        rHash = json.lndOptNullableString("r_hash"),
        value = json.lndOptFlexibleLong("value"),
        settled = if (json.has("settled") && !json.isNull("settled")) json.optBoolean("settled") else null,
        creationDate = json.lndOptFlexibleLong("creation_date"),
        settleDate = json.lndOptFlexibleLong("settle_date"),
        addIndex = json.lndOptFlexibleLong("add_index"),
        settleIndex = json.lndOptFlexibleLong("settle_index"),
        paymentRequest = json.lndOptNullableString("payment_request"),
        state = json.lndOptNullableString("state"),
        amtPaidSat = json.lndOptFlexibleLong("amt_paid_sat")
    )
}

private fun JSONArray?.orEmpty(): JSONArray {
    return this ?: JSONArray()
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val values = mutableListOf<T>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values.add(transform(it)) }
    }
    return values
}

private fun InputStream.readUtf8(): String {
    return bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private fun urlQueryEncode(value: String): String {
    return URLEncoder.encode(value, Charsets.UTF_8.name())
}

private fun urlPathEncode(value: String): String {
    return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

private fun hexToBytes(value: String): ByteArray? {
    if (value.length % 2 != 0) return null
    if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        bytes[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

private fun sslSocketFactoryForPinnedCertificate(certificateBytes: ByteArray) =
    runCatching {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificate = certificateFactory.generateCertificate(certificateBytes.inputStream()) as X509Certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("lnd", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, null)
        }.socketFactory
    }.getOrNull()

private fun SSLSession.containsPinnedCertificate(pinnedBytes: ByteArray): Boolean {
    return runCatching {
        peerCertificates.any { certificate ->
            certificate.encoded.contentEquals(pinnedBytes)
        }
    }.getOrDefault(false)
}
