package com.split.android.data.wallet

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

sealed class NwcWalletException(message: String) : IllegalStateException(message) {
    data object InvalidConnectionUrl : NwcWalletException("The NWC connection string is invalid.")
    data object MissingWalletPubkey : NwcWalletException("The NWC connection string is missing the wallet pubkey.")
    data object InvalidWalletPubkey : NwcWalletException("The NWC wallet pubkey is invalid.")
    data object MissingRelay : NwcWalletException("The NWC connection string is missing a relay.")
    data object InvalidRelay : NwcWalletException("The NWC relay URL is invalid.")
    data object MissingSecret : NwcWalletException("The NWC connection string is missing the client secret.")
    data object InvalidSecret : NwcWalletException("The NWC client secret is invalid.")
    data object NoStoredConnection : NwcWalletException("No NWC wallet is stored on this device.")
    data object WalletNotConnected : NwcWalletException("NWC wallet is not connected.")
    data object RelayConnectionFailed : NwcWalletException("Split could not connect to the NWC relay.")
    data object RelayTimedOut : NwcWalletException("The NWC relay did not respond in time.")
    data object InvalidRelayResponse : NwcWalletException("The NWC relay returned an invalid response.")
    data object WalletInfoUnavailable : NwcWalletException("Split could not verify this NWC wallet's capabilities.")
    data object UnsupportedEncryption : NwcWalletException("This NWC wallet does not advertise a compatible encryption mode.")
    data object RewardsMetadataUnavailable : NwcWalletException(
        "This connected NWC wallet cannot provide the invoice metadata Split needs for rewards. Split needs the Lightning destination pubkey and payment hash before sending."
    )
    data object InsufficientCapabilities : NwcWalletException(
        "This wallet does not support the NWC features Split needs. Connect an NWC wallet or node with payments, invoices, balance, history, invoice lookup, and live payment notifications."
    )

    data class UnsupportedMethod(val method: String) : NwcWalletException("This NWC wallet does not support $method.")
    data class WalletError(val code: String?, val serverMessage: String?) : NwcWalletException(
        listOfNotNull(code, serverMessage).joinToString(": ").ifBlank { "The NWC wallet rejected the request." }
    )
}

enum class NwcEncryptionMode(val rawValue: String) {
    NIP44_V2("nip44_v2"),
    NIP04("nip04")
}

data class NwcWalletCapabilities(
    val methods: List<String>,
    val notifications: List<String>,
    val encryptionModes: List<String>,
    val walletAlias: String?
) {
    val supportsBalance: Boolean get() = supports("get_balance")
    val supportsPayments: Boolean get() = supports("pay_invoice")
    val supportsInvoices: Boolean get() = supports("make_invoice")
    val supportsInvoiceLookup: Boolean get() = supports("lookup_invoice")
    val supportsTransactions: Boolean get() = supports("list_transactions")
    val supportsPaymentReceivedNotifications: Boolean get() = supportsNotification("payment_received")
    val supportsPaymentSentNotifications: Boolean get() = supportsNotification("payment_sent")
    val supportsPaymentNotifications: Boolean
        get() = supportsPaymentReceivedNotifications && supportsPaymentSentNotifications
    val supportsSplitBaseline: Boolean
        get() = supportsPayments && supportsInvoices && supportsBalance && supportsInvoiceLookup && supportsTransactions
    val supportsSplitRequiredCapabilities: Boolean
        get() = supportsSplitBaseline && supportsPaymentNotifications
    val preferredEncryptionMode: NwcEncryptionMode
        get() = if (encryptionModes.any { it.equals(NwcEncryptionMode.NIP44_V2.rawValue, ignoreCase = true) }) {
            NwcEncryptionMode.NIP44_V2
        } else {
            NwcEncryptionMode.NIP04
        }
    val supportsCompatibleEncryption: Boolean
        get() = encryptionModes.isEmpty() ||
            encryptionModes.any { it.equals(NwcEncryptionMode.NIP44_V2.rawValue, ignoreCase = true) } ||
            encryptionModes.any { it.equals(NwcEncryptionMode.NIP04.rawValue, ignoreCase = true) }

    fun supports(method: String): Boolean {
        return methods.any { it.equals(method, ignoreCase = true) }
    }

    fun supportsNotification(notification: String): Boolean {
        return notifications.any { it.equals(notification, ignoreCase = true) }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("methods", JSONArray(methods))
            .put("notifications", JSONArray(notifications))
            .put("encryptionModes", JSONArray(encryptionModes))
            .put("walletAlias", walletAlias ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): NwcWalletCapabilities {
            return NwcWalletCapabilities(
                methods = json.optJSONArray("methods").nwcOrEmpty().toStringList(),
                notifications = json.optJSONArray("notifications").nwcOrEmpty().toStringList(),
                encryptionModes = json.optJSONArray("encryptionModes").nwcOrEmpty().toStringList(),
                walletAlias = json.nwcOptNullableString("walletAlias")
            )
        }
    }
}

data class NwcWalletCredentials(
    val walletPubkey: String,
    val relayUrls: List<String>,
    val secret: String,
    val lud16: String?,
    val label: String?,
    val connectedAtMillis: Long,
    val lastVerifiedAtMillis: Long?,
    val capabilities: NwcWalletCapabilities?
) {
    val id: String get() = walletPubkey
    val displayName: String
        get() = label?.trim()?.ifBlank { null }
            ?: capabilities?.walletAlias?.trim()?.ifBlank { null }
            ?: "NWC Wallet"
    val primaryRelayHost: String
        get() = runCatching { URI(relayUrls.first()).host }.getOrNull()?.ifBlank { null } ?: "Relay unavailable"

    fun verified(capabilities: NwcWalletCapabilities): NwcWalletCredentials {
        return copy(
            lastVerifiedAtMillis = System.currentTimeMillis(),
            capabilities = capabilities
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("walletPubkey", walletPubkey)
            .put("relayUrls", JSONArray(relayUrls))
            .put("secret", secret)
            .put("lud16", lud16 ?: JSONObject.NULL)
            .put("label", label ?: JSONObject.NULL)
            .put("connectedAtMillis", connectedAtMillis)
            .put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
            .put("capabilities", capabilities?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): NwcWalletCredentials {
            return NwcWalletCredentials(
                walletPubkey = json.getString("walletPubkey"),
                relayUrls = json.optJSONArray("relayUrls").nwcOrEmpty().toStringList(),
                secret = json.getString("secret"),
                lud16 = json.nwcOptNullableString("lud16"),
                label = json.nwcOptNullableString("label"),
                connectedAtMillis = json.optLong("connectedAtMillis", System.currentTimeMillis()),
                lastVerifiedAtMillis = json.nwcOptNullableLong("lastVerifiedAtMillis"),
                capabilities = json.optJSONObject("capabilities")?.let(NwcWalletCapabilities::fromJson)
            )
        }
    }
}

data class NwcNostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("pubkey", pubkey)
            .put("created_at", createdAt)
            .put("kind", kind)
            .put("tags", JSONArray(tags.map { JSONArray(it) }))
            .put("content", content)
            .put("sig", sig)
    }

    companion object {
        fun fromJson(json: JSONObject): NwcNostrEvent {
            val tagsJson = json.optJSONArray("tags") ?: JSONArray()
            val tags = buildList {
                for (index in 0 until tagsJson.length()) {
                    add(tagsJson.getJSONArray(index).toStringList())
                }
            }
            return NwcNostrEvent(
                id = json.getString("id"),
                pubkey = json.getString("pubkey"),
                createdAt = json.optLong("created_at"),
                kind = json.getInt("kind"),
                tags = tags,
                content = json.getString("content"),
                sig = json.getString("sig")
            )
        }
    }
}

data class NwcWalletInfoEvent(
    val event: NwcNostrEvent
) {
    val pubkey: String get() = event.pubkey
    val capabilities: NwcWalletCapabilities
        get() {
            val methods = event.content
                .split(Regex("[\\s,]+"))
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it != "notifications" }
                .distinct()
                .sorted()
            val encryptionModes = tagValues("encryption")
                .flatMap { it.split(Regex("[\\s,]+")) }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            val notifications = tagValues("notifications")
                .flatMap { it.split(Regex("[\\s,]+")) }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            return NwcWalletCapabilities(
                methods = methods,
                notifications = notifications,
                encryptionModes = encryptionModes,
                walletAlias = tagValue("name") ?: tagValue("alias")
            )
        }

    private fun tagValue(name: String): String? = tagValues(name).firstOrNull()

    private fun tagValues(name: String): List<String> {
        return event.tags.mapNotNull { tag ->
            if (tag.size > 1 && tag[0].equals(name, ignoreCase = true)) tag[1] else null
        }
    }
}

data class NwcGetBalanceResult(val balanceMsats: Long) {
    val balanceSats: Long get() = balanceMsats / 1_000L
}

data class NwcPayInvoiceResult(
    val preimage: String?,
    val feesPaidMsats: Long?
) {
    val feesPaidSats: Long? get() = feesPaidMsats?.div(1_000L)
}

data class NwcTransactionResult(
    val type: String?,
    val state: String?,
    val invoice: String?,
    val description: String?,
    val descriptionHash: String?,
    val preimage: String?,
    val paymentHash: String?,
    val amountMsats: Long?,
    val feesPaidMsats: Long?,
    val createdAt: Long?,
    val expiresAt: Long?
) {
    val amountSats: Long? get() = amountMsats?.div(1_000L)
    val feesPaidSats: Long? get() = feesPaidMsats?.div(1_000L)
}

data class NwcNotificationEvent(
    val id: String,
    val type: String,
    val notification: JSONObject
) {
    val isPaymentReceived: Boolean
        get() = type.equals("payment_received", ignoreCase = true)

    val isPaymentSent: Boolean
        get() = type.equals("payment_sent", ignoreCase = true)
}

internal fun JSONObject.nwcOptNullableString(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name).trim().ifBlank { null } else null
}

internal fun JSONObject.nwcOptNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

internal fun JSONArray?.nwcOrEmpty(): JSONArray = this ?: JSONArray()

internal fun JSONArray.toStringList(): List<String> {
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().ifBlank { null }?.let(::add)
        }
    }
}
