package com.split.android.data.wallet

import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Locale
import kotlin.math.max

sealed class CoreLightningWalletException(message: String) : IllegalStateException(message) {
    data object InvalidConnectionUrl : CoreLightningWalletException("The Core Lightning connection string is invalid.")
    data object MissingNodeHost : CoreLightningWalletException("The Core Lightning connection string is missing a node host.")
    data object MissingRune : CoreLightningWalletException("The Core Lightning connection string is missing a rune.")
    data object InvalidRune : CoreLightningWalletException("The Core Lightning rune is invalid.")
    data object InvalidCertificate : CoreLightningWalletException("The Core Lightning TLS certificate is invalid.")
    data object InvalidBaseUrl : CoreLightningWalletException("The Core Lightning node URL is invalid.")
    data object PublicInternetHostNotAllowed : CoreLightningWalletException(
        "This Core Lightning REST connection uses a public host. Split supports private-network, .local, Tailscale, or Tor .onion Core Lightning REST connections."
    )
    data object NoStoredNode : CoreLightningWalletException("No Core Lightning node is stored on this device.")
    data object NodeNotConnected : CoreLightningWalletException("Core Lightning node is not connected.")
    data object InvalidResponse : CoreLightningWalletException("Core Lightning returned an invalid response.")
    data object PaymentFailed : CoreLightningWalletException("Core Lightning payment failed.")
    data class ServerError(
        val statusCode: Int,
        val serverMessage: String
    ) : CoreLightningWalletException("Core Lightning server error $statusCode: $serverMessage")
}

sealed interface CoreLightningResolvedAddress {
    data class Ipv4(val address: Inet4Address) : CoreLightningResolvedAddress
    data class Ipv6(val address: Inet6Address) : CoreLightningResolvedAddress
}

object CoreLightningHostAccessPolicy {
    fun validateHost(host: String) {
        val normalizedHost = host.trim().lowercase(Locale.US)
        if (normalizedHost.isBlank()) {
            throw CoreLightningWalletException.MissingNodeHost
        }

        // Onion hostnames are resolved by Tor, not device DNS.
    }

    fun validateResolvedAddresses(addresses: List<CoreLightningResolvedAddress>) {
        if (addresses.isEmpty()) {
            throw CoreLightningWalletException.PublicInternetHostNotAllowed
        }

        if (!addresses.all(::isAllowedResolvedAddress)) {
            throw CoreLightningWalletException.PublicInternetHostNotAllowed
        }
    }

    private fun isAllowedResolvedAddress(address: CoreLightningResolvedAddress): Boolean {
        return when (address) {
            is CoreLightningResolvedAddress.Ipv4 -> {
                val octets = address.address.address
                val firstOctet = octets[0].toInt() and 0xff
                val secondOctet = octets[1].toInt() and 0xff

                when (firstOctet) {
                    10 -> true
                    172 -> secondOctet in 16..31
                    192 -> secondOctet == 168
                    100 -> secondOctet in 64..127
                    else -> false
                }
            }
            is CoreLightningResolvedAddress.Ipv6 -> {
                val bytes = address.address.address
                val firstByte = bytes[0].toInt() and 0xff
                val secondByte = bytes[1].toInt() and 0xff

                if ((firstByte and 0xfe) == 0xfc) {
                    true
                } else {
                    firstByte == 0xfe && (secondByte and 0xc0) == 0x80
                }
            }
        }
    }
}

data class CoreLightningMilliSatoshi(
    val msats: Long
) {
    val satsRoundedDown: Long
        get() = max(msats, 0L) / 1_000L

    val satsRoundedUp: Long
        get() {
            if (msats <= 0L) return 0L
            val sats = msats / 1_000L
            return if (msats % 1_000L == 0L) sats else sats + 1L
        }

    companion object {
        fun sats(sats: Long): String = "${max(sats, 0L) * 1_000L}msat"

        fun parse(value: Any?): CoreLightningMilliSatoshi? {
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
                else -> raw.toLongOrNull()
            } ?: return null

            return CoreLightningMilliSatoshi(msats)
        }
    }
}

data class CoreLightningFlexibleLong(
    val value: Long
) {
    companion object {
        fun parse(value: Any?): CoreLightningFlexibleLong? {
            val raw = when (value) {
                null -> return null
                JSONObject.NULL -> return null
                is Number -> value.toLong()
                is String -> value.trim().toDoubleOrNull()?.toLong()
                else -> value.toString().trim().toDoubleOrNull()?.toLong()
            } ?: return null
            return CoreLightningFlexibleLong(raw)
        }
    }
}

data class CoreLightningNodeCredentials(
    val scheme: String,
    val host: String,
    val port: Int,
    val rune: String,
    val tlsCertificateDerBase64: String?,
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

    fun withLabel(label: String?): CoreLightningNodeCredentials {
        return copy(label = label?.trim()?.ifBlank { null })
    }

    fun withHost(host: String): CoreLightningNodeCredentials {
        return copy(host = host)
    }

    fun withTlsCertificateDerBase64(certificate: String?): CoreLightningNodeCredentials {
        return copy(tlsCertificateDerBase64 = certificate?.trim()?.ifBlank { null } ?: tlsCertificateDerBase64)
    }

    fun verified(
        nodeId: String?,
        nodeAlias: String?
    ): CoreLightningNodeCredentials {
        return copy(
            nodeId = nodeId?.trim()?.ifBlank { null } ?: this.nodeId,
            nodeAlias = nodeAlias?.trim()?.ifBlank { null } ?: this.nodeAlias,
            lastVerifiedAtMillis = System.currentTimeMillis()
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("scheme", scheme)
            .put("host", host)
            .put("port", port)
            .put("rune", rune)
            .put("tlsCertificateDerBase64", tlsCertificateDerBase64 ?: JSONObject.NULL)
            .put("label", label ?: JSONObject.NULL)
            .put("nodeId", nodeId ?: JSONObject.NULL)
            .put("nodeAlias", nodeAlias ?: JSONObject.NULL)
            .put("connectedAtMillis", connectedAtMillis)
            .put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): CoreLightningNodeCredentials {
            return CoreLightningNodeCredentials(
                scheme = json.optString("scheme", "https").trim().ifBlank { "https" },
                host = json.getString("host"),
                port = json.optInt("port", 3010),
                rune = json.getString("rune"),
                tlsCertificateDerBase64 = json.coreLightningOptNullableString("tlsCertificateDerBase64")
                    ?: json.coreLightningOptNullableString("tlsCertificateDERBase64"),
                label = json.coreLightningOptNullableString("label"),
                nodeId = json.coreLightningOptNullableString("nodeId"),
                nodeAlias = json.coreLightningOptNullableString("nodeAlias"),
                connectedAtMillis = json.optLong("connectedAtMillis", System.currentTimeMillis()),
                lastVerifiedAtMillis = json.coreLightningOptNullableLong("lastVerifiedAtMillis")
            )
        }
    }
}

data class CoreLightningGetInfoResponse(
    val id: String,
    val alias: String?,
    val color: String?,
    val numPeers: Int?,
    val numPendingChannels: Int?,
    val numActiveChannels: Int?,
    val numInactiveChannels: Int?,
    val blockHeight: Int?,
    val network: String?,
    val version: String?
) {
    companion object {
        fun fromJson(json: JSONObject): CoreLightningGetInfoResponse {
            return CoreLightningGetInfoResponse(
                id = json.optString("id"),
                alias = json.coreLightningOptNullableString("alias"),
                color = json.coreLightningOptNullableString("color"),
                numPeers = json.coreLightningOptNullableInt("num_peers"),
                numPendingChannels = json.coreLightningOptNullableInt("num_pending_channels"),
                numActiveChannels = json.coreLightningOptNullableInt("num_active_channels"),
                numInactiveChannels = json.coreLightningOptNullableInt("num_inactive_channels"),
                blockHeight = json.coreLightningOptNullableInt("blockheight"),
                network = json.coreLightningOptNullableString("network"),
                version = json.coreLightningOptNullableString("version")
            )
        }
    }
}

data class CoreLightningBalanceSummary(
    val channelBalanceSats: Long,
    val onChainBalanceSats: Long
) {
    val spendableSats: Long
        get() = max(channelBalanceSats, 0L)
}

data class CoreLightningListFundsResponse(
    val outputs: List<CoreLightningOutput>,
    val channels: List<CoreLightningChannel>
) {
    val spendableChannelBalanceSats: Long
        get() = channels
            .filter { it.state.equals("CHANNELD_NORMAL", ignoreCase = true) }
            .sumOf { it.ourAmountMsat?.satsRoundedDown ?: 0L }

    val onChainBalanceSats: Long
        get() = outputs
            .filter { it.status.equals("confirmed", ignoreCase = true) }
            .filter { it.reserved != true }
            .sumOf { it.amountMsat?.satsRoundedDown ?: 0L }

    companion object {
        fun fromJson(json: JSONObject): CoreLightningListFundsResponse {
            return CoreLightningListFundsResponse(
                outputs = json.optJSONArray("outputs").coreLightningOrEmpty().coreLightningMapObjects { output ->
                    CoreLightningOutput(
                        amountMsat = CoreLightningMilliSatoshi.parse(output.opt("amount_msat")),
                        status = output.coreLightningOptNullableString("status"),
                        reserved = output.coreLightningOptNullableBoolean("reserved")
                    )
                },
                channels = json.optJSONArray("channels").coreLightningOrEmpty().coreLightningMapObjects { channel ->
                    CoreLightningChannel(
                        ourAmountMsat = CoreLightningMilliSatoshi.parse(channel.opt("our_amount_msat")),
                        amountMsat = CoreLightningMilliSatoshi.parse(channel.opt("amount_msat")),
                        state = channel.coreLightningOptNullableString("state")
                    )
                }
            )
        }
    }
}

data class CoreLightningOutput(
    val amountMsat: CoreLightningMilliSatoshi?,
    val status: String?,
    val reserved: Boolean?
)

data class CoreLightningChannel(
    val ourAmountMsat: CoreLightningMilliSatoshi?,
    val amountMsat: CoreLightningMilliSatoshi?,
    val state: String?
)

data class CoreLightningInvoiceResponse(
    val bolt11: String,
    val paymentHash: String?,
    val expiresAt: CoreLightningFlexibleLong?
) {
    companion object {
        fun fromJson(json: JSONObject): CoreLightningInvoiceResponse {
            return CoreLightningInvoiceResponse(
                bolt11 = json.getString("bolt11"),
                paymentHash = json.coreLightningOptNullableString("payment_hash"),
                expiresAt = CoreLightningFlexibleLong.parse(json.opt("expires_at"))
            )
        }
    }
}

data class CoreLightningPayResponse(
    val paymentPreimage: String?,
    val paymentHash: String?,
    val createdAt: CoreLightningFlexibleLong?,
    val parts: Int?,
    val amountMsat: CoreLightningMilliSatoshi?,
    val amountSentMsat: CoreLightningMilliSatoshi?,
    val status: String?
) {
    val didSucceed: Boolean
        get() = status.equals("complete", ignoreCase = true) ||
            status.equals("completed", ignoreCase = true)

    companion object {
        fun fromJson(json: JSONObject): CoreLightningPayResponse {
            return CoreLightningPayResponse(
                paymentPreimage = json.coreLightningOptNullableString("payment_preimage")
                    ?: json.coreLightningOptNullableString("preimage"),
                paymentHash = json.coreLightningOptNullableString("payment_hash"),
                createdAt = CoreLightningFlexibleLong.parse(json.opt("created_at")),
                parts = json.coreLightningOptNullableInt("parts"),
                amountMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_msat")),
                amountSentMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_sent_msat")),
                status = json.coreLightningOptNullableString("status")
            )
        }
    }
}

data class CoreLightningDecodeResponse(
    val type: String?,
    val valid: Boolean?,
    val payee: String?,
    val paymentHash: String?,
    val amountMsat: CoreLightningMilliSatoshi?,
    val description: String?,
    val createdAt: CoreLightningFlexibleLong?,
    val expiry: CoreLightningFlexibleLong?
) {
    val amountSats: Long?
        get() = amountMsat?.satsRoundedDown

    companion object {
        fun fromJson(json: JSONObject): CoreLightningDecodeResponse {
            return CoreLightningDecodeResponse(
                type = json.coreLightningOptNullableString("type"),
                valid = json.coreLightningOptNullableBoolean("valid"),
                payee = json.coreLightningOptNullableString("payee"),
                paymentHash = json.coreLightningOptNullableString("payment_hash"),
                amountMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_msat")),
                description = json.coreLightningOptNullableString("description"),
                createdAt = CoreLightningFlexibleLong.parse(json.opt("created_at")),
                expiry = CoreLightningFlexibleLong.parse(json.opt("expiry"))
            )
        }
    }
}

data class CoreLightningPay(
    val paymentHash: String?,
    val paymentPreimage: String?,
    val bolt11: String?,
    val description: String?,
    val destination: String?,
    val status: String?,
    val amountMsat: CoreLightningMilliSatoshi?,
    val amountSentMsat: CoreLightningMilliSatoshi?,
    val createdAt: CoreLightningFlexibleLong?,
    val completedAt: CoreLightningFlexibleLong?
) {
    val id: String
        get() = paymentHash ?: bolt11 ?: "${createdAt?.value ?: 0L}"

    companion object {
        fun fromJson(json: JSONObject): CoreLightningPay {
            return CoreLightningPay(
                paymentHash = json.coreLightningOptNullableString("payment_hash"),
                paymentPreimage = json.coreLightningOptNullableString("payment_preimage")
                    ?: json.coreLightningOptNullableString("preimage"),
                bolt11 = json.coreLightningOptNullableString("bolt11"),
                description = json.coreLightningOptNullableString("description"),
                destination = json.coreLightningOptNullableString("destination"),
                status = json.coreLightningOptNullableString("status"),
                amountMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_msat")),
                amountSentMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_sent_msat")),
                createdAt = CoreLightningFlexibleLong.parse(json.opt("created_at")),
                completedAt = CoreLightningFlexibleLong.parse(json.opt("completed_at"))
            )
        }
    }
}

data class CoreLightningInvoice(
    val label: String?,
    val paymentHash: String?,
    val status: String?,
    val expiresAt: CoreLightningFlexibleLong?,
    val createdIndex: CoreLightningFlexibleLong?,
    val updatedIndex: CoreLightningFlexibleLong?,
    val description: String?,
    val amountMsat: CoreLightningMilliSatoshi?,
    val amountReceivedMsat: CoreLightningMilliSatoshi?,
    val paidAt: CoreLightningFlexibleLong?,
    val bolt11: String?,
    val paymentPreimage: String?
) {
    val id: String
        get() = paymentHash ?: bolt11 ?: label ?: "${createdIndex?.value ?: 0L}"

    val isPaid: Boolean
        get() = status.equals("paid", ignoreCase = true)

    companion object {
        fun fromJson(json: JSONObject): CoreLightningInvoice {
            return CoreLightningInvoice(
                label = json.coreLightningOptNullableString("label"),
                paymentHash = json.coreLightningOptNullableString("payment_hash"),
                status = json.coreLightningOptNullableString("status"),
                expiresAt = CoreLightningFlexibleLong.parse(json.opt("expires_at")),
                createdIndex = CoreLightningFlexibleLong.parse(json.opt("created_index")),
                updatedIndex = CoreLightningFlexibleLong.parse(json.opt("updated_index")),
                description = json.coreLightningOptNullableString("description"),
                amountMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_msat")),
                amountReceivedMsat = CoreLightningMilliSatoshi.parse(json.opt("amount_received_msat")),
                paidAt = CoreLightningFlexibleLong.parse(json.opt("paid_at")),
                bolt11 = json.coreLightningOptNullableString("bolt11"),
                paymentPreimage = json.coreLightningOptNullableString("payment_preimage")
                    ?: json.coreLightningOptNullableString("preimage")
            )
        }
    }
}

internal fun JSONObject.coreLightningOptNullableString(name: String): String? {
    return if (has(name) && !isNull(name)) {
        optString(name).trim().ifBlank { null }
    } else {
        null
    }
}

internal fun JSONObject.coreLightningOptNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

internal fun JSONObject.coreLightningOptNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

internal fun JSONObject.coreLightningOptNullableBoolean(name: String): Boolean? {
    return if (has(name) && !isNull(name)) optBoolean(name) else null
}

internal fun org.json.JSONArray?.coreLightningOrEmpty(): org.json.JSONArray {
    return this ?: org.json.JSONArray()
}

internal fun <T> org.json.JSONArray.coreLightningMapObjects(transform: (JSONObject) -> T): List<T> {
    val values = mutableListOf<T>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values.add(transform(it)) }
    }
    return values
}
