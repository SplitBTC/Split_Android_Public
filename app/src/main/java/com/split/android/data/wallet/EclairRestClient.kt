package com.split.android.data.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Base64

class EclairRestClient(
    private val credentials: EclairNodeCredentials,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 75_000
) {
    private val transport = credentials.transport

    suspend fun getInfo(): EclairGetInfoResponse {
        return EclairGetInfoResponse.fromJson(requestObject("getinfo"))
    }

    suspend fun channelBalances(): JSONArray {
        return requestArray("channelbalances")
    }

    suspend fun onChainBalance(): JSONObject {
        return requestObject("onchainbalance")
    }

    suspend fun createInvoice(
        amountSats: Long?,
        memo: String?,
        expirySecs: Long = 3_600L
    ): EclairInvoiceResponse {
        val form = mutableMapOf(
            "description" to (memo?.trim()?.ifBlank { null } ?: "Split payment"),
            "expireIn" to expirySecs.toString()
        )
        amountSats?.takeIf { it > 0L }?.let {
            form["amountMsat"] = EclairMilliSatoshi.sats(it)
        }
        return EclairInvoiceResponse.fromJson(requestObject("createinvoice", form))
    }

    suspend fun parseInvoice(bolt11: String): EclairParseInvoiceResponse {
        return EclairInvoiceResponse.fromJson(requestObject("parseinvoice", mapOf("invoice" to bolt11.trim())))
    }

    suspend fun payInvoice(
        bolt11: String,
        amountSats: Long? = null
    ): EclairPayResponse {
        val form = mutableMapOf(
            "invoice" to bolt11.trim(),
            "blocking" to "true"
        )
        amountSats?.takeIf { it > 0L }?.let {
            form["amountMsat"] = EclairMilliSatoshi.sats(it)
        }
        val response = EclairPayResponse.fromJson(
            requestObject("payinvoice", form, readTimeoutOverrideMillis = maxOf(readTimeoutMillis, 120_000))
        )
        if (!response.didSucceed) {
            throw EclairWalletException.PaymentFailed(response.failureMessage)
        }
        return response
    }

    suspend fun listReceivedPayments(limit: Int = 50): List<EclairReceivedPayment> {
        return requestArray("listreceivedpayments", mapOf("count" to limit.toString()))
            .mapObjects(EclairReceivedPayment::fromJson)
    }

    suspend fun getSentInfo(paymentHash: String): List<EclairSentPaymentInfo> {
        return requestArray("getsentinfo", mapOf("paymentHash" to paymentHash.trim()))
            .mapObjects(EclairSentPaymentInfo::fromJson)
    }

    private suspend fun requestObject(
        path: String,
        form: Map<String, String> = emptyMap(),
        readTimeoutOverrideMillis: Int? = null
    ): JSONObject {
        val body = request(path, form, readTimeoutOverrideMillis)
        return runCatching { JSONObject(body) }.getOrElse {
            throw EclairWalletException.ServerError(200, "Unable to decode Eclair response: $body")
        }
    }

    private suspend fun requestArray(
        path: String,
        form: Map<String, String> = emptyMap()
    ): JSONArray {
        val body = request(path, form, null)
        return runCatching { JSONArray(body) }.getOrElse {
            throw EclairWalletException.ServerError(200, "Unable to decode Eclair response: $body")
        }
    }

    private suspend fun request(
        path: String,
        form: Map<String, String>,
        readTimeoutOverrideMillis: Int?
    ): String {
        return withContext(Dispatchers.IO) {
            val connection = openConnection(path, readTimeoutOverrideMillis)
            try {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(form.encodedFormBody().toByteArray(Charsets.UTF_8))
                }

                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val responseBody = stream?.readUtf8().orEmpty()

                if (statusCode !in 200..299) {
                    throw EclairWalletException.ServerError(
                        statusCode = statusCode,
                        serverMessage = serverErrorMessage(responseBody)
                    )
                }

                responseBody
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(path: String, readTimeoutOverrideMillis: Int?): HttpURLConnection {
        val url = URL(makeUrlString(path))
        val rawConnection = if (transport == RemoteNodeTransport.TOR) {
            url.openConnection(RemoteNodeTorTransport.proxy)
        } else {
            url.openConnection()
        }
        return (rawConnection as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutOverrideMillis ?: readTimeoutMillis
            doInput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Authorization", basicAuthHeader())
        }
    }

    private fun makeUrlString(path: String): String {
        val scheme = credentials.scheme.trim().lowercase()
        if (scheme != "https" && scheme != "http") {
            throw EclairWalletException.InvalidBaseUrl
        }
        return "$scheme://${credentials.host}:${credentials.port}/$path"
    }

    private fun basicAuthHeader(): String {
        val encoded = Base64.getEncoder().encodeToString(":${credentials.apiPassword}".toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }

    private fun serverErrorMessage(responseBody: String): String {
        return runCatching {
            val json = JSONObject(responseBody)
            json.eclairOptNullableString("message")
                ?: json.eclairOptNullableString("error")
                ?: json.eclairOptNullableString("details")
                ?: responseBody.ifBlank { "Unknown Eclair error" }
        }.getOrDefault(responseBody.ifBlank { "Unknown Eclair error" })
    }
}

private fun Map<String, String>.encodedFormBody(): String {
    return entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun InputStream.readUtf8(): String {
    return bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val values = mutableListOf<T>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values.add(transform(it)) }
    }
    return values
}
