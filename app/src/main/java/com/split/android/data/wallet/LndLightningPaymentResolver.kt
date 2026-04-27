package com.split.android.data.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LndResolvedInvoice(
    val invoice: String
)

sealed class LndLightningPaymentResolverException(message: String) : IllegalStateException(message) {
    data object UnsupportedRequest : LndLightningPaymentResolverException(
        "Node wallet sends support Lightning invoices, LNURL, and Lightning addresses."
    )
    data object InvalidLightningAddress : LndLightningPaymentResolverException("The Lightning address is invalid.")
    data object InvalidLnurl : LndLightningPaymentResolverException("The LNURL is invalid.")
    data object InvalidResponse : LndLightningPaymentResolverException("The LNURL server returned an invalid response.")
    data class AmountOutOfRange(
        val minSats: Long,
        val maxSats: Long
    ) : LndLightningPaymentResolverException("Amount must be between $minSats and $maxSats sats.")
    data class ServerError(val reason: String) : LndLightningPaymentResolverException(reason)
}

object LndLightningPaymentResolver {
    suspend fun resolveInvoice(
        paymentRequest: String,
        amountSats: Long,
        comment: String?
    ): LndResolvedInvoice {
        val trimmed = paymentRequest.trim()
        val lower = trimmed.lowercase()

        if (isBolt11(trimmed)) {
            return LndResolvedInvoice(invoice = trimmed)
        }

        if (lower.startsWith("lnurl")) {
            val url = decodeLnurl(trimmed)
            return resolveLnurlPay(url = url, amountSats = amountSats, comment = comment)
        }

        if (trimmed.contains("@") && !trimmed.contains(" ")) {
            val url = lightningAddressUrl(trimmed)
            return resolveLnurlPay(url = url, amountSats = amountSats, comment = comment)
        }

        throw LndLightningPaymentResolverException.UnsupportedRequest
    }

    fun isBolt11(paymentRequest: String): Boolean {
        val lower = paymentRequest.trim().lowercase()
        return lower.startsWith("lnbc") ||
            lower.startsWith("lntb") ||
            lower.startsWith("lnbcrt")
    }

    private suspend fun resolveLnurlPay(
        url: URL,
        amountSats: Long,
        comment: String?
    ): LndResolvedInvoice {
        val payRequest = fetchJson(url)

        if (payRequest.lndOptNullableString("status").equals("ERROR", ignoreCase = true)) {
            throw LndLightningPaymentResolverException.ServerError(
                payRequest.lndOptNullableString("reason") ?: "LNURL server returned an error."
            )
        }

        val callback = payRequest.lndOptNullableString("callback")?.let(::URL)
            ?: throw LndLightningPaymentResolverException.InvalidResponse
        if (!payRequest.lndOptNullableString("tag").equals("payRequest", ignoreCase = true)) {
            throw LndLightningPaymentResolverException.InvalidResponse
        }

        if (amountSats <= 0L || amountSats > Long.MAX_VALUE / 1_000L) {
            throw LndLightningPaymentResolverException.AmountOutOfRange(
                minSats = 1L,
                maxSats = Long.MAX_VALUE / 1_000L
            )
        }

        val amountMsats = amountSats * 1_000L
        val minSendable = payRequest.lndOptFlexibleLong("minSendable")
        val maxSendable = payRequest.lndOptFlexibleLong("maxSendable")
        if (minSendable != null &&
            maxSendable != null &&
            (amountMsats < minSendable || amountMsats > maxSendable)
        ) {
            throw LndLightningPaymentResolverException.AmountOutOfRange(
                minSats = maxOf(1L, minSendable / 1_000L),
                maxSats = maxOf(1L, maxSendable / 1_000L)
            )
        }

        val query = buildList {
            add("amount" to amountMsats.toString())
            normalizedComment(comment, payRequest.optInt("commentAllowed", 0))?.let {
                add("comment" to it)
            }
        }

        val callbackUrl = appendQueryItems(callback, query)
        val callbackResponse = fetchJson(callbackUrl)

        if (callbackResponse.lndOptNullableString("status").equals("ERROR", ignoreCase = true)) {
            throw LndLightningPaymentResolverException.ServerError(
                callbackResponse.lndOptNullableString("reason") ?: "LNURL payment request failed."
            )
        }

        val invoice = callbackResponse.lndOptNullableString("pr")
            ?: throw LndLightningPaymentResolverException.InvalidResponse
        return LndResolvedInvoice(invoice = invoice)
    }

    private suspend fun fetchJson(url: URL): JSONObject {
        return withContext(Dispatchers.IO) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                doInput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }

            try {
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (statusCode !in 200..299) {
                    throw LndLightningPaymentResolverException.InvalidResponse
                }
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun lightningAddressUrl(value: String): URL {
        val parts = value.trim().split("@", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw LndLightningPaymentResolverException.InvalidLightningAddress
        }

        val encodedUsername = urlEncode(parts[0])
        return URL("https://${parts[1]}/.well-known/lnurlp/$encodedUsername")
    }

    private fun normalizedComment(value: String?, allowedLength: Int): String? {
        if (allowedLength <= 0) return null
        val normalized = value?.trim()?.ifBlank { null } ?: return null
        return normalized.take(allowedLength)
    }

    private fun appendQueryItems(
        url: URL,
        queryItems: List<Pair<String, String>>
    ): URL {
        val existingQuery = url.query?.takeIf { it.isNotBlank() }
        val appendedQuery = queryItems.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val separator = if (existingQuery == null) "?" else "&"
        return URL(url.toString() + separator + appendedQuery)
    }

    private fun decodeLnurl(value: String): URL {
        val decoded = Bech32.decode(value.lowercase())
            ?: throw LndLightningPaymentResolverException.InvalidLnurl
        if (decoded.hrp != "lnurl") {
            throw LndLightningPaymentResolverException.InvalidLnurl
        }

        val bytes = convertBits(decoded.data, fromBits = 5, toBits = 8, pad = false)
        val urlString = bytes.toByteArray().toString(Charsets.UTF_8)
        return runCatching { URL(urlString) }
            .getOrElse { throw LndLightningPaymentResolverException.InvalidLnurl }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

private data class Bech32Data(
    val hrp: String,
    val data: List<Int>
)

private object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(value: String): Bech32Data? {
        val lower = value.lowercase()
        val separatorIndex = lower.lastIndexOf('1')
        if (separatorIndex <= 0 || separatorIndex + 7 > lower.length) return null

        val hrp = lower.substring(0, separatorIndex)
        val dataPart = lower.substring(separatorIndex + 1)
        val values = dataPart.map { char ->
            CHARSET.indexOf(char).takeIf { it >= 0 } ?: return null
        }

        if (!verifyChecksum(hrp, values)) return null
        return Bech32Data(hrp = hrp, data = values.dropLast(6))
    }

    private fun verifyChecksum(hrp: String, values: List<Int>): Boolean {
        return polymod(hrpExpand(hrp) + values) == 1
    }

    private fun hrpExpand(hrp: String): List<Int> {
        return hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
    }

    private fun polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        for (value in values) {
            val top = checksum shr 25
            checksum = (checksum and 0x1ffffff) shl 5 xor value
            for (index in generators.indices) {
                if (((top shr index) and 1) == 1) {
                    checksum = checksum xor generators[index]
                }
            }
        }
        return checksum
    }
}

private fun convertBits(
    data: List<Int>,
    fromBits: Int,
    toBits: Int,
    pad: Boolean
): List<Byte> {
    var acc = 0
    var bits = 0
    val result = mutableListOf<Byte>()
    val maxv = (1 shl toBits) - 1
    val maxAcc = (1 shl (fromBits + toBits - 1)) - 1

    for (value in data) {
        acc = ((acc shl fromBits) or value) and maxAcc
        bits += fromBits

        while (bits >= toBits) {
            bits -= toBits
            result.add(((acc shr bits) and maxv).toByte())
        }
    }

    if (pad && bits > 0) {
        result.add(((acc shl (toBits - bits)) and maxv).toByte())
    }

    return result
}
