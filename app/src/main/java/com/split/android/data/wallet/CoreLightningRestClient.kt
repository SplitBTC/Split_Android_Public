package com.split.android.data.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CoreLightningRestClient(
    private val credentials: CoreLightningNodeCredentials,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 75_000
) {
    private val pinnedCertificateBytes: ByteArray? =
        credentials.tlsCertificateDerBase64?.let { Base64.getDecoder().decode(it) }
    private val sslSocketFactory = trustLeafSocketFactory()
    private val hostnameVerifier = HostnameVerifier { _, session ->
        val pinned = pinnedCertificateBytes ?: return@HostnameVerifier true
        runCatching {
            session.peerCertificates.any { certificate ->
                certificate.encoded.contentEquals(pinned)
            }
        }.getOrDefault(false)
    }
    private val transport = credentials.transport

    var observedServerCertificateDerBase64: String? = null
        private set

    suspend fun getInfo(): CoreLightningGetInfoResponse {
        return CoreLightningGetInfoResponse.fromJson(rpc("getinfo"))
    }

    suspend fun listFunds(): CoreLightningListFundsResponse {
        return CoreLightningListFundsResponse.fromJson(rpc("listfunds"))
    }

    suspend fun createInvoice(
        amountSats: Long?,
        memo: String?,
        expirySecs: Long = 3_600L
    ): CoreLightningInvoiceResponse {
        val body = JSONObject()
            .put("amount_msat", amountSats?.takeIf { it > 0L }?.let(CoreLightningMilliSatoshi::sats) ?: "any")
            .put("label", "split-${UUID.randomUUID()}")
            .put("description", memo?.trim()?.ifBlank { null } ?: "Split payment")
            .put("expiry", expirySecs)
        return CoreLightningInvoiceResponse.fromJson(rpc("invoice", body))
    }

    suspend fun decodeInvoice(bolt11: String): CoreLightningDecodeResponse {
        return CoreLightningDecodeResponse.fromJson(
            rpc("decode", JSONObject().put("string", bolt11.trim()))
        )
    }

    suspend fun payInvoice(
        bolt11: String,
        amountSats: Long? = null
    ): CoreLightningPayResponse {
        val body = JSONObject()
            .put("bolt11", bolt11.trim())
            .put("retry_for", 60)
        amountSats?.let {
            body.put("amount_msat", CoreLightningMilliSatoshi.sats(it))
        }
        return CoreLightningPayResponse.fromJson(rpc("pay", body))
    }

    suspend fun listPays(limit: Int = 50): List<CoreLightningPay> {
        val pays = rpc("listpays")
            .optJSONArray("pays")
            .coreLightningOrEmpty()
            .coreLightningMapObjects(CoreLightningPay::fromJson)
        return pays.take(limit)
    }

    suspend fun listInvoices(limit: Int = 50): List<CoreLightningInvoice> {
        val invoices = rpc("listinvoices")
            .optJSONArray("invoices")
            .coreLightningOrEmpty()
            .coreLightningMapObjects(CoreLightningInvoice::fromJson)
        return invoices.take(limit)
    }

    private suspend fun rpc(
        method: String,
        params: JSONObject = JSONObject()
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            val connection = openConnection(method)

            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output ->
                    output.write(params.toString().toByteArray(Charsets.UTF_8))
                }

                val statusCode = connection.responseCode
                observeServerCertificate(connection)

                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val responseBody = stream?.readUtf8().orEmpty()

                if (statusCode !in 200..299) {
                    throw CoreLightningWalletException.ServerError(
                        statusCode = statusCode,
                        serverMessage = serverErrorMessage(responseBody)
                    )
                }

                runCatching { JSONObject(responseBody) }.getOrElse {
                    throw CoreLightningWalletException.ServerError(
                        statusCode = statusCode,
                        serverMessage = "Unable to decode Core Lightning response: $responseBody"
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(method: String): HttpURLConnection {
        val url = URL(makeUrlString(method))
        val rawConnection = if (transport == RemoteNodeTransport.TOR) {
            url.openConnection(RemoteNodeTorTransport.proxy)
        } else {
            url.openConnection()
        }
        val connection = (rawConnection as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doInput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("rune", credentials.rune)
        }

        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = sslSocketFactory
            connection.hostnameVerifier = hostnameVerifier
        }

        return connection
    }

    private fun makeUrlString(method: String): String {
        val scheme = credentials.scheme.trim().lowercase()
        if (scheme != "https" && scheme != "http") {
            throw CoreLightningWalletException.InvalidBaseUrl
        }
        return "$scheme://${credentials.host}:${credentials.port}/v1/$method"
    }

    private fun observeServerCertificate(connection: HttpURLConnection) {
        if (connection !is HttpsURLConnection) return
        observedServerCertificateDerBase64 = runCatching {
            val leaf = connection.serverCertificates.firstOrNull()
            leaf?.encoded?.let { Base64.getEncoder().encodeToString(it) }
        }.getOrNull()
    }

    private fun serverErrorMessage(responseBody: String): String {
        return runCatching {
            val json = JSONObject(responseBody)
            json.coreLightningOptNullableString("message")
                ?: json.coreLightningOptNullableString("error")
                ?: json.coreLightningOptNullableString("description")
                ?: responseBody.ifBlank { "Unknown Core Lightning error" }
        }.getOrDefault(responseBody.ifBlank { "Unknown Core Lightning error" })
    }
}

private fun trustLeafSocketFactory() =
    SSLContext.getInstance("TLS").apply {
        init(
            null,
            arrayOf(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            ),
            SecureRandom()
        )
    }.socketFactory

private fun InputStream.readUtf8(): String {
    return bufferedReader(Charsets.UTF_8).use { it.readText() }
}
