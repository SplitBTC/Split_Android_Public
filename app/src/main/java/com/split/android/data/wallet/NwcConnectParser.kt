package com.split.android.data.wallet

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object NwcConnectParser {
    fun parse(rawValue: String): NwcWalletCredentials {
        val trimmed = rawValue.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (trimmed.isEmpty() || uri?.scheme?.lowercase() != "nostr+walletconnect") {
            throw NwcWalletException.InvalidConnectionUrl
        }

        val rawWalletPubkey = uri.host ?: uri.path.orEmpty().replace("/", "")
        val walletPubkey = normalizedHex(rawWalletPubkey)
            ?: if (rawWalletPubkey.isBlank()) {
                throw NwcWalletException.MissingWalletPubkey
            } else {
                throw NwcWalletException.InvalidWalletPubkey
            }
        if (walletPubkey.length != 64) {
            throw NwcWalletException.InvalidWalletPubkey
        }

        val query = queryParameters(uri.rawQuery)
        val relayUrls = query.values("relay")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::normalizedRelayUrl)
        if (relayUrls.isEmpty()) {
            throw NwcWalletException.MissingRelay
        }
        if (relayUrls.any { it == null }) {
            throw NwcWalletException.InvalidRelay
        }

        val secret = query.firstValue("secret")?.let(::normalizedHex)
            ?: throw NwcWalletException.MissingSecret
        if (secret.length != 64) {
            throw NwcWalletException.InvalidSecret
        }

        return NwcWalletCredentials(
            walletPubkey = walletPubkey,
            relayUrls = relayUrls.filterNotNull(),
            secret = secret,
            lud16 = query.firstValue("lud16")?.trim()?.lowercase()?.ifBlank { null },
            label = query.firstValue("name")?.trim()?.ifBlank { null },
            connectedAtMillis = System.currentTimeMillis(),
            lastVerifiedAtMillis = null,
            capabilities = null
        )
    }

    private fun normalizedRelayUrl(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return null
        if (scheme != "wss" && !isAllowedOnionRelay(uri) && !isAllowedDebugRelay(uri)) return null
        return uri.toString()
    }

    private fun isAllowedOnionRelay(uri: URI): Boolean {
        if (uri.scheme?.lowercase() != "ws") return false
        val host = uri.host?.lowercase() ?: return false
        return RemoteNodeTransport.isOnionHost(host)
    }

    private fun isAllowedDebugRelay(uri: URI): Boolean {
        if (uri.scheme?.lowercase() != "ws") return false
        val host = uri.host?.lowercase() ?: return false
        return com.split.android.BuildConfig.DEBUG &&
            (host == "localhost" || host == "127.0.0.1" || host == "::1")
    }

    internal fun normalizedHex(value: String?): String? {
        var normalized = value?.trim().orEmpty()
        if (normalized.startsWith("0x", ignoreCase = true)) {
            normalized = normalized.drop(2)
        }
        if (normalized.isEmpty() || normalized.length % 2 != 0) return null
        if (!normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return normalized.lowercase()
    }

    private fun queryParameters(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery.isNullOrBlank()) return emptyList()
        return rawQuery.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val parts = pair.split("=", limit = 2)
            val name = parts.getOrNull(0)?.urlDecoded()?.trim().orEmpty()
            val value = parts.getOrNull(1)?.urlDecoded().orEmpty()
            if (name.isEmpty()) null else name to value
        }
    }

    private fun List<Pair<String, String>>.firstValue(name: String): String? {
        return firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private fun List<Pair<String, String>>.values(name: String): List<String> {
        return filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    }

    private fun String?.urlDecoded(): String {
        return URLDecoder.decode(this.orEmpty(), StandardCharsets.UTF_8.name())
    }
}
