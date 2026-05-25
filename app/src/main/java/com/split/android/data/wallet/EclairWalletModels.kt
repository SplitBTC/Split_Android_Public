package com.split.android.data.wallet

import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Locale
import kotlin.math.max

sealed class EclairWalletException(message: String) : IllegalStateException(message) {
    data object InvalidConnection : EclairWalletException("The Eclair connection details are invalid.")
    data object MissingNodeHost : EclairWalletException("The Eclair connection is missing a node host.")
    data object MissingPassword : EclairWalletException("The Eclair API password is missing.")
    data object InvalidBaseUrl : EclairWalletException("The Eclair node URL is invalid.")
    data object PublicInternetHostNotAllowed : EclairWalletException(
        "This Eclair connection uses a public host. Split supports private-network, .local, Tailscale, or Tor .onion Eclair connections."
    )
    data object NoStoredNode : EclairWalletException("No Eclair node is stored on this device.")
    data object NodeNotConnected : EclairWalletException("Eclair node is not connected.")
    data object InvalidResponse : EclairWalletException("Eclair returned an invalid response.")
    data class PaymentFailed(val reason: String?) : EclairWalletException(
        reason?.trim()?.ifBlank { null } ?: "Eclair payment failed."
    )
    data class ServerError(
        val statusCode: Int,
        val serverMessage: String
    ) : EclairWalletException("Eclair server error $statusCode: $serverMessage")
}

sealed interface EclairResolvedAddress {
    data class Ipv4(val address: Inet4Address) : EclairResolvedAddress
    data class Ipv6(val address: Inet6Address) : EclairResolvedAddress
}

object EclairHostAccessPolicy {
    fun validateHost(host: String) {
        if (host.trim().isBlank()) {
            throw EclairWalletException.MissingNodeHost
        }
    }

    fun validateResolvedAddresses(addresses: List<EclairResolvedAddress>) {
        if (addresses.isEmpty() || !addresses.all(::isAllowedResolvedAddress)) {
            throw EclairWalletException.PublicInternetHostNotAllowed
        }
    }

    private fun isAllowedResolvedAddress(address: EclairResolvedAddress): Boolean {
        return when (address) {
            is EclairResolvedAddress.Ipv4 -> {
                val octets = address.address.address
                val first = octets[0].toInt() and 0xff
                val second = octets[1].toInt() and 0xff
                when (first) {
                    10 -> true
                    172 -> second in 16..31
                    192 -> second == 168
                    100 -> second in 64..127
                    else -> false
                }
            }
            is EclairResolvedAddress.Ipv6 -> {
                val bytes = address.address.address
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                (first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80)
            }
        }
    }
}

data class EclairMilliSatoshi(val msats: Long) {
    val satsRoundedDown: Long
        get() = max(msats, 0L) / 1_000L

    val satsRoundedUp: Long
        get() {
            if (msats <= 0L) return 0L
            val sats = msats / 1_000L
            return if (msats % 1_000L == 0L) sats else sats + 1L
        }

    companion object {
        fun sats(sats: Long): String = "${max(sats, 0L) * 1_000L}"

        fun parse(value: Any?): EclairMilliSatoshi? {
            val raw = when (value) {
                null -> return null
                JSONObject.NULL -> return null
                is Number -> value.toLong().toString()
                is String -> value.trim()
                else -> value.toString().trim()
            }.lowercase(Locale.US)
            if (raw.isBlank()) return null

            val msats = when {
                raw.endsWith("msat") -> raw.dropLast(4).toLongOrNull()
                raw.endsWith("sat") -> raw.dropLast(3).toDoubleOrNull()?.let { (it * 1_000.0).toLong() }
                raw.endsWith("btc") -> raw.dropLast(3).toDoubleOrNull()?.let { (it * 100_000_000_000.0).toLong() }
                else -> raw.toDoubleOrNull()?.toLong()
            } ?: return null

            return EclairMilliSatoshi(msats)
        }
    }
}

data class EclairFlexibleLong(val value: Long) {
    companion object {
        fun parse(value: Any?): EclairFlexibleLong? {
            val parsed = when (value) {
                null -> return null
                JSONObject.NULL -> return null
                is Number -> value.toLong()
                is String -> value.trim().toDoubleOrNull()?.toLong()
                else -> value.toString().trim().toDoubleOrNull()?.toLong()
            } ?: return null
            return EclairFlexibleLong(parsed)
        }
    }
}

data class EclairNodeCredentials(
    val scheme: String,
    val host: String,
    val port: Int,
    val apiPassword: String,
    val label: String?,
    val nodeId: String?,
    val nodeAlias: String?,
    val connectedAtMillis: Long,
    val lastVerifiedAtMillis: Long?
) {
    val id: String
        get() = nodeId?.trim()?.lowercase(Locale.US)?.ifBlank { null }
            ?: "${scheme.lowercase(Locale.US)}://${host.lowercase(Locale.US)}:$port"

    val displayName: String
        get() = label?.trim()?.ifBlank { null }
            ?: nodeAlias?.trim()?.ifBlank { null }
            ?: "$host:$port"

    fun withLabel(label: String?): EclairNodeCredentials {
        return copy(label = label?.trim()?.ifBlank { null })
    }

    fun withHost(host: String): EclairNodeCredentials = copy(host = host)

    fun verified(info: EclairGetInfoResponse): EclairNodeCredentials {
        return copy(
            nodeId = info.nodeId?.trim()?.ifBlank { null } ?: nodeId,
            nodeAlias = info.alias?.trim()?.ifBlank { null } ?: nodeAlias,
            lastVerifiedAtMillis = System.currentTimeMillis()
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("scheme", scheme)
            .put("host", host)
            .put("port", port)
            .put("apiPassword", apiPassword)
            .put("label", label ?: JSONObject.NULL)
            .put("nodeId", nodeId ?: JSONObject.NULL)
            .put("nodeAlias", nodeAlias ?: JSONObject.NULL)
            .put("connectedAtMillis", connectedAtMillis)
            .put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): EclairNodeCredentials {
            return EclairNodeCredentials(
                scheme = json.optString("scheme", "http").trim().ifBlank { "http" },
                host = json.getString("host"),
                port = json.optInt("port", 8080),
                apiPassword = json.getString("apiPassword"),
                label = json.eclairOptNullableString("label"),
                nodeId = json.eclairOptNullableString("nodeId"),
                nodeAlias = json.eclairOptNullableString("nodeAlias"),
                connectedAtMillis = json.optLong("connectedAtMillis", System.currentTimeMillis()),
                lastVerifiedAtMillis = json.eclairOptNullableLong("lastVerifiedAtMillis")
            )
        }
    }
}

data class EclairGetInfoResponse(
    val nodeId: String?,
    val alias: String?,
    val version: String?,
    val blockHeight: Int?
) {
    companion object {
        fun fromJson(json: JSONObject): EclairGetInfoResponse {
            return EclairGetInfoResponse(
                nodeId = json.eclairOptNullableString("nodeId") ?: json.eclairOptNullableString("node_id"),
                alias = json.eclairOptNullableString("alias"),
                version = json.eclairOptNullableString("version"),
                blockHeight = json.eclairOptNullableInt("blockHeight") ?: json.eclairOptNullableInt("block_height")
            )
        }
    }
}

data class EclairBalanceSummary(
    val channelBalanceSats: Long,
    val onChainBalanceSats: Long
) {
    val spendableSats: Long
        get() = max(channelBalanceSats, 0L)
}

data class EclairInvoiceResponse(
    val serialized: String,
    val nodeId: String?,
    val description: String?,
    val paymentHash: String?,
    val expiry: Long?,
    val amountMsat: EclairMilliSatoshi?,
    val timestamp: Long?
) {
    val amountSats: Long?
        get() = amountMsat?.satsRoundedDown

    companion object {
        fun fromJson(json: JSONObject): EclairInvoiceResponse {
            return EclairInvoiceResponse(
                serialized = json.eclairOptNullableString("serialized")
                    ?: json.eclairOptNullableString("invoice")
                    ?: json.eclairOptNullableString("bolt11")
                    ?: throw EclairWalletException.InvalidResponse,
                nodeId = json.eclairOptNullableString("nodeId") ?: json.eclairOptNullableString("node_id"),
                description = json.eclairOptNullableString("description"),
                paymentHash = json.eclairOptNullableString("paymentHash") ?: json.eclairOptNullableString("payment_hash"),
                expiry = json.eclairOptNullableLong("expiry"),
                amountMsat = EclairMilliSatoshi.parse(json.opt("amount")),
                timestamp = json.eclairOptNullableLong("timestamp")
            )
        }
    }
}

typealias EclairParseInvoiceResponse = EclairInvoiceResponse

data class EclairPayResponse(
    val paymentHash: String?,
    val paymentPreimage: String?,
    val statusType: String?,
    val recipientAmount: EclairMilliSatoshi?,
    val amount: EclairMilliSatoshi?,
    val feesPaid: EclairMilliSatoshi?,
    val paymentId: String?,
    val failureMessage: String?
) {
    val didSucceed: Boolean
        get() = statusType.equals("sent", ignoreCase = true) ||
            statusType.equals("succeeded", ignoreCase = true) ||
            statusType.equals("success", ignoreCase = true) ||
            paymentPreimage?.isNotBlank() == true

    companion object {
        fun fromJson(json: JSONObject): EclairPayResponse {
            val status = json.optJSONObject("status")
            val failures = status?.optJSONArray("failures")
            val firstFailure = failures?.optJSONObject(0)
            return EclairPayResponse(
                paymentHash = json.eclairOptNullableString("paymentHash") ?: json.eclairOptNullableString("payment_hash"),
                paymentPreimage = json.eclairOptNullableString("paymentPreimage") ?: status?.eclairOptNullableString("paymentPreimage"),
                statusType = status?.eclairOptNullableString("type") ?: json.eclairOptNullableString("status"),
                recipientAmount = EclairMilliSatoshi.parse(json.opt("recipientAmount")),
                amount = EclairMilliSatoshi.parse(json.opt("amount")),
                feesPaid = EclairMilliSatoshi.parse(json.opt("feesPaid") ?: status?.opt("feesPaid")),
                paymentId = json.eclairOptNullableString("id"),
                failureMessage = firstFailure?.eclairOptNullableString("failureMessage")
                    ?: firstFailure?.eclairOptNullableString("reason")
                    ?: status?.eclairOptNullableString("failureMessage")
            )
        }
    }
}

data class EclairReceivedPayment(
    val paymentHash: String?,
    val invoice: EclairInvoiceResponse?,
    val amount: EclairMilliSatoshi?,
    val receivedAmount: EclairMilliSatoshi?,
    val statusType: String?,
    val receivedAt: EclairFlexibleLong?,
    val createdAt: EclairFlexibleLong?
) {
    val id: String
        get() = paymentHash ?: invoice?.paymentHash ?: invoice?.serialized ?: "${createdAt?.value ?: 0L}"

    companion object {
        fun fromJson(json: JSONObject): EclairReceivedPayment {
            val invoice = json.optJSONObject("invoice")?.let(EclairInvoiceResponse::fromJson)
            val status = json.optJSONObject("status")
            return EclairReceivedPayment(
                paymentHash = json.eclairOptNullableString("paymentHash")
                    ?: json.eclairOptNullableString("payment_hash")
                    ?: invoice?.paymentHash,
                invoice = invoice,
                amount = EclairMilliSatoshi.parse(json.opt("amount")),
                receivedAmount = EclairMilliSatoshi.parse(json.opt("receivedAmount") ?: json.opt("received_amount")),
                statusType = status?.eclairOptNullableString("type") ?: json.eclairOptNullableString("status"),
                receivedAt = EclairFlexibleLong.parse(json.opt("receivedAt") ?: json.opt("received_at")),
                createdAt = EclairFlexibleLong.parse(json.opt("createdAt") ?: json.opt("created_at"))
            )
        }
    }
}

data class EclairSentPayment(
    val row: WalletTransactionRow
)

data class EclairSentPaymentInfo(
    val paymentHash: String?,
    val payment: EclairInvoiceResponse?
) {
    companion object {
        fun fromJson(json: JSONObject): EclairSentPaymentInfo {
            val payment = json.optJSONObject("payment")?.let(EclairInvoiceResponse::fromJson)
            return EclairSentPaymentInfo(
                paymentHash = json.eclairOptNullableString("paymentHash")
                    ?: json.eclairOptNullableString("payment_hash")
                    ?: payment?.paymentHash,
                payment = payment
            )
        }
    }
}

internal fun JSONObject.eclairOptNullableString(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name).trim().ifBlank { null } else null
}

internal fun JSONObject.eclairOptNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

internal fun JSONObject.eclairOptNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
