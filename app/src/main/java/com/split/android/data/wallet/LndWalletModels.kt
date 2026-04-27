package com.split.android.data.wallet

import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

sealed class LndWalletException(message: String) : IllegalStateException(message) {
    data object InvalidLndConnectUrl : LndWalletException("The LND connection string is invalid.")
    data object MissingNodeHost : LndWalletException("The LND connection string is missing a node host.")
    data object MissingMacaroon : LndWalletException("The LND connection string is missing a macaroon.")
    data object InvalidMacaroon : LndWalletException("The LND macaroon is invalid.")
    data object InvalidCertificate : LndWalletException("The LND TLS certificate is invalid.")
    data object InvalidBaseUrl : LndWalletException("The LND node URL is invalid.")
    data object TorOnionNotSupported : LndWalletException(
        "This LND Connect QR uses a Tor .onion address. Split can connect over local network or VPN right now; Tor node connections are not supported yet."
    )
    data object NoStoredNode : LndWalletException("No LND node is stored on this device.")
    data object NodeNotConnected : LndWalletException("LND node is not connected.")
    data object InvalidResponse : LndWalletException("LND returned an invalid response.")
    data class ServerError(
        val statusCode: Int,
        val serverMessage: String
    ) : LndWalletException("LND server error $statusCode: $serverMessage")
}

data class LndNodeCredentials(
    val host: String,
    val port: Int,
    val macaroonHex: String,
    val tlsCertificateDerBase64: String?,
    val label: String?,
    val nodePubkey: String?,
    val nodeAlias: String?,
    val connectedAtMillis: Long,
    val lastVerifiedAtMillis: Long?
) {
    val id: String
        get() = nodePubkey?.trim()?.lowercase()?.ifBlank { null } ?: "${host.lowercase()}:$port"

    val displayName: String
        get() = label?.trim()?.ifBlank { null }
            ?: nodeAlias?.trim()?.ifBlank { null }
            ?: "$host:$port"

    fun verified(info: LndGetInfoResponse): LndNodeCredentials {
        return copy(
            nodePubkey = info.identityPubkey.trim().ifBlank { nodePubkey },
            nodeAlias = info.alias?.trim()?.ifBlank { nodeAlias },
            lastVerifiedAtMillis = System.currentTimeMillis()
        )
    }

    fun withHostAndPort(host: String, port: Int): LndNodeCredentials {
        return copy(host = host, port = port)
    }

    val restConnectionCandidates: List<LndNodeCredentials>
        get() {
            val candidates = mutableListOf<LndNodeCredentials>()
            val seen = mutableSetOf<String>()

            for (portCandidate in restPortCandidates()) {
                for (hostCandidate in restHostCandidates()) {
                    val candidate = withHostAndPort(hostCandidate, portCandidate)
                    val key = "${candidate.host.lowercase()}:${candidate.port}"
                    if (seen.add(key)) {
                        candidates.add(candidate)
                    }
                }
            }

            return candidates
        }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("host", host)
            .put("port", port)
            .put("macaroonHex", macaroonHex)
            .put("tlsCertificateDerBase64", tlsCertificateDerBase64 ?: JSONObject.NULL)
            .put("label", label ?: JSONObject.NULL)
            .put("nodePubkey", nodePubkey ?: JSONObject.NULL)
            .put("nodeAlias", nodeAlias ?: JSONObject.NULL)
            .put("connectedAtMillis", connectedAtMillis)
            .put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
    }

    private fun restHostCandidates(): List<String> {
        val trimmedHost = host.trim()
        val candidates = mutableListOf(trimmedHost)
        if (trimmedHost.lowercase().endsWith(".local")) {
            val fallback = trimmedHost.dropLast(".local".length)
            if (fallback.isNotBlank()) {
                candidates.add(fallback)
            }
        }
        return candidates
    }

    private fun restPortCandidates(): List<Int> {
        return if (port == 10009) listOf(10009, 8080, 8081) else listOf(port)
    }

    companion object {
        fun fromJson(json: JSONObject): LndNodeCredentials {
            return LndNodeCredentials(
                host = json.getString("host"),
                port = json.optInt("port", 8080),
                macaroonHex = json.getString("macaroonHex"),
                tlsCertificateDerBase64 = json.lndOptNullableString("tlsCertificateDerBase64"),
                label = json.lndOptNullableString("label"),
                nodePubkey = json.lndOptNullableString("nodePubkey"),
                nodeAlias = json.lndOptNullableString("nodeAlias"),
                connectedAtMillis = json.optLong("connectedAtMillis", System.currentTimeMillis()),
                lastVerifiedAtMillis = json.lndOptNullableLong("lastVerifiedAtMillis")
            )
        }
    }
}

data class LndGetInfoResponse(
    val identityPubkey: String,
    val alias: String?
)

data class LndBalanceSummary(
    val channelBalanceSats: Long,
    val onChainBalanceSats: Long
) {
    val spendableSats: Long
        get() = max(channelBalanceSats, 0L)
}

data class LndAddInvoiceResponse(
    val rHash: String?,
    val paymentRequest: String,
    val addIndex: Long?,
    val paymentAddr: String?
)

data class LndPayInvoiceResponse(
    val paymentError: String?,
    val paymentPreimage: String?,
    val paymentHash: String?
) {
    val didSucceed: Boolean
        get() = paymentError.isNullOrBlank()
}

data class LndDecodePayReqResponse(
    val destination: String?,
    val paymentHash: String?,
    val amountSats: Long?,
    val timestamp: Long?,
    val expiry: Long?,
    val description: String?
)

data class LndSignMessageResponse(
    val signature: String
)

data class LndPayment(
    val paymentHash: String?,
    val paymentPreimage: String?,
    val paymentRequest: String?,
    val valueSat: Long?,
    val feeSat: Long?,
    val creationDate: Long?,
    val status: String?,
    val failureReason: String?,
    val description: String?,
    val destination: String?
) {
    val id: String
        get() = paymentHash ?: paymentPreimage ?: paymentRequest ?: "${creationDate ?: 0L}"
}

data class LndInvoice(
    val memo: String?,
    val rHash: String?,
    val value: Long?,
    val settled: Boolean?,
    val creationDate: Long?,
    val settleDate: Long?,
    val addIndex: Long?,
    val settleIndex: Long?,
    val paymentRequest: String?,
    val state: String?,
    val amtPaidSat: Long?
) {
    val id: String
        get() = rHash ?: paymentRequest ?: "${creationDate ?: 0L}"

    val isSettledInvoice: Boolean
        get() = settled == true || state.equals("SETTLED", ignoreCase = true)
}

internal fun LndPayment.toWalletTransactionRow(): WalletTransactionRow {
    val amountSats = max(valueSat ?: 0L, 0L)
    val feeSats = max(feeSat ?: 0L, 0L)
    val date = dateFromUnixSeconds(creationDate)

    return WalletTransactionRow(
        id = "lnd-payment-$id",
        transactionTimestampMillis = date.time,
        direction = "sent",
        btcAmount = amountSats.toLndBtcString(),
        feeBtcAmount = feeSats.toLndBtcString(),
        network = "lightning",
        status = lndPaymentStatus(status, failureReason),
        dateString = date.displayString(),
        note = description?.trim().orEmpty(),
        amountSats = amountSats,
        feeSats = feeSats,
        method = "LND",
        destinationPubkey = destination,
        invoice = paymentRequest,
        lnAddress = null,
        lnurlDomain = null,
        lnurlComment = null,
        senderComment = null,
        paymentHash = paymentHash,
        preimage = paymentPreimage,
        expiryDateString = null,
        txReferenceLabel = "Payment Hash",
        txReference = paymentHash,
        hasConversion = false
    )
}

internal fun LndInvoice.toWalletTransactionRowOrNull(): WalletTransactionRow? {
    if (!isSettledInvoice) return null

    val amountSats = max(amtPaidSat ?: value ?: 0L, 0L)
    if (amountSats <= 0L) return null

    val date = dateFromUnixSeconds(settleDate ?: creationDate)
    return WalletTransactionRow(
        id = "lnd-invoice-$id",
        transactionTimestampMillis = date.time,
        direction = "received",
        btcAmount = amountSats.toLndBtcString(),
        feeBtcAmount = 0L.toLndBtcString(),
        network = "lightning",
        status = "Completed",
        dateString = date.displayString(),
        note = memo?.trim().orEmpty(),
        amountSats = amountSats,
        feeSats = 0L,
        method = "LND",
        destinationPubkey = null,
        invoice = paymentRequest,
        lnAddress = null,
        lnurlDomain = null,
        lnurlComment = null,
        senderComment = null,
        paymentHash = rHash,
        preimage = null,
        expiryDateString = null,
        txReferenceLabel = "Invoice Hash",
        txReference = rHash,
        hasConversion = false
    )
}

internal fun JSONObject.lndOptFlexibleLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = get(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        else -> null
    }
}

internal fun JSONObject.lndOptNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifBlank { null }
}

internal fun JSONObject.lndOptNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

internal fun Long.toLndBtcString(): String {
    val btc = toDouble() / 100_000_000.0
    return String.format(Locale.US, "%.8f", btc)
}

private fun lndPaymentStatus(status: String?, failureReason: String?): String {
    if (!failureReason.isNullOrBlank() &&
        !failureReason.equals("FAILURE_REASON_NONE", ignoreCase = true)
    ) {
        return "Failed"
    }

    val normalized = status?.trim().orEmpty()
    if (normalized.isBlank()) return "Completed"
    if (normalized.contains("fail", ignoreCase = true)) return "Failed"
    if (normalized.contains("in_flight", ignoreCase = true)) return "Pending"
    if (normalized.contains("initiated", ignoreCase = true)) return "Pending"
    if (normalized.contains("pending", ignoreCase = true)) return "Pending"
    if (normalized.contains("succeed", ignoreCase = true)) return "Completed"
    if (normalized.contains("complete", ignoreCase = true)) return "Completed"
    return normalized
}

private fun dateFromUnixSeconds(seconds: Long?): Date {
    val normalized = seconds?.takeIf { it > 0L } ?: return Date()
    return Date(normalized * 1_000L)
}

private fun Date.displayString(): String {
    return DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.US
    ).format(this)
}

internal fun msatsToSatsCeiling(msats: Long): Long {
    if (msats <= 0L) return 0L
    return ceil(msats.toDouble() / 1_000.0).toLong()
}
