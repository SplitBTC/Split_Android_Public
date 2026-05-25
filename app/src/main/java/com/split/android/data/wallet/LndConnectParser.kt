package com.split.android.data.wallet

import android.net.Uri
import java.util.Base64

object LndConnectParser {
    fun normalize(rawValue: String): String? {
        val trimmed = rawValue.trim()
            .removePrefix("\uFEFF")
            .trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        if (lower.startsWith("lightning:")) {
            val withoutScheme = trimmed.drop("lightning:".length).trim()
            return normalize(withoutScheme) ?: withoutScheme
        }

        if (lower.startsWith("bitcoin:")) {
            val lightningRequest = runCatching { Uri.parse(trimmed) }.getOrNull()
                ?.let { queryParameterValue(it, "lightning") }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (!lightningRequest.isNullOrBlank()) {
                return normalize(lightningRequest) ?: lightningRequest
            }
        }

        runCatching { Uri.parse(trimmed) }.getOrNull()
            ?.let { queryParameterValue(it, "lightning") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return normalize(it) ?: it }

        lndConnectPattern.find(trimmed)?.value?.trimEnd(
            '.',
            ',',
            ';',
            ')',
            ']',
            '}',
            '"',
            '\''
        )?.let { candidate ->
            if (candidate.isNotBlank()) return candidate
        }

        return trimmed
    }

    fun parse(rawValue: String): LndNodeCredentials {
        val trimmed = normalize(rawValue)
            ?: throw LndWalletException.InvalidLndConnectUrl
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: throw LndWalletException.InvalidLndConnectUrl

        if (trimmed.isBlank() || !uri.scheme.equals("lndconnect", ignoreCase = true)) {
            throw LndWalletException.InvalidLndConnectUrl
        }

        val host = uri.host?.trim()?.ifBlank { null }
            ?: throw LndWalletException.MissingNodeHost

        LndHostAccessPolicy.validateHost(host)

        val macaroonValue = queryParameterValue(uri, "macaroon")
            ?: throw LndWalletException.MissingMacaroon
        val macaroonHex = decodeMacaroonToHex(macaroonValue)
            ?: throw LndWalletException.InvalidMacaroon

        val certificateBase64 = queryParameterValue(uri, "cert")?.let { certValue ->
            val certData = decodeBinaryValue(certValue)
                ?: throw LndWalletException.InvalidCertificate
            Base64.getEncoder().encodeToString(certData)
        }

        return LndNodeCredentials(
            host = host,
            port = if (uri.port > 0) uri.port else 8080,
            macaroonHex = macaroonHex,
            tlsCertificateDerBase64 = certificateBase64,
            label = null,
            nodePubkey = null,
            nodeAlias = null,
            connectedAtMillis = System.currentTimeMillis(),
            lastVerifiedAtMillis = null
        )
    }

    private fun decodeMacaroonToHex(value: String): String? {
        return normalizedHex(value) ?: decodeBinaryValue(value)?.toHexString()
    }

    private fun decodeBinaryValue(value: String): ByteArray? {
        val trimmed = value.trim().replace(" ", "+")
        normalizedHex(trimmed)?.let { hex -> return hexToBytes(hex) }

        return runCatching {
            Base64.getUrlDecoder().decode(paddedBase64(trimmed))
        }.getOrElse {
            runCatching {
                Base64.getDecoder().decode(paddedBase64(trimmed))
            }.getOrNull()
        }
    }

    private fun normalizedHex(value: String): String? {
        val normalized = value.trim()
            .removePrefix("0x")
            .removePrefix("0X")
        if (normalized.isBlank() || normalized.length % 2 != 0) return null
        if (!normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return normalized.lowercase()
    }

    private fun paddedBase64(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }

    private fun queryParameterValue(uri: Uri, name: String): String? {
        val encodedQuery = uri.encodedQuery ?: return null

        for (segment in encodedQuery.split('&')) {
            if (segment.isBlank()) continue
            val delimiterIndex = segment.indexOf('=')
            val rawName = if (delimiterIndex >= 0) {
                segment.substring(0, delimiterIndex)
            } else {
                segment
            }
            val rawValue = if (delimiterIndex >= 0 && delimiterIndex + 1 < segment.length) {
                segment.substring(delimiterIndex + 1)
            } else {
                ""
            }

            if (Uri.decode(rawName).trim().equals(name, ignoreCase = true)) {
                return Uri.decode(rawValue).trim().ifBlank { null }
            }
        }

        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private val lndConnectPattern = Regex("(?i)lndconnect://[^\\s]+")
}
