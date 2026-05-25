package com.split.android.data.wallet

import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.Locale

object CoreLightningConnectParser {
    fun parse(rawValue: String): CoreLightningNodeCredentials {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) {
            throw CoreLightningWalletException.InvalidConnectionUrl
        }

        parseJson(trimmed)?.let { return it }

        if (trimmed.startsWith("clnrest://", ignoreCase = true)) {
            val embedded = trimmed.drop("clnrest://".length)
            if (embedded.startsWith("https://", ignoreCase = true) ||
                embedded.startsWith("http://", ignoreCase = true)
            ) {
                return parseUri(URI(embedded), schemeOverride = URI(embedded).scheme?.lowercase(Locale.US))
            }
        }

        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw CoreLightningWalletException.InvalidConnectionUrl
        }
        val rawScheme = uri.scheme?.lowercase(Locale.US)
            ?: throw CoreLightningWalletException.InvalidConnectionUrl

        return when {
            rawScheme == "clnrest+https" -> parseUri(uri, schemeOverride = "https")
            rawScheme == "clnrest+http" -> parseUri(uri, schemeOverride = "http")
            rawScheme == "clnrest" -> {
                val query = parseQuery(uri.rawQuery)
                val scheme = query.firstValue("scheme")
                    ?: query.firstValue("protocol")
                    ?: "https"
                parseUri(uri, schemeOverride = scheme.lowercase(Locale.US))
            }
            rawScheme == "https" || rawScheme == "http" -> parseUri(uri, schemeOverride = rawScheme)
            else -> throw CoreLightningWalletException.InvalidConnectionUrl
        }
    }

    private fun parseUri(
        uri: URI,
        schemeOverride: String?,
        extraQuery: Map<String, List<String>> = emptyMap()
    ): CoreLightningNodeCredentials {
        val scheme = schemeOverride?.lowercase(Locale.US)
            ?: throw CoreLightningWalletException.InvalidConnectionUrl
        if (scheme != "https" && scheme != "http") {
            throw CoreLightningWalletException.InvalidConnectionUrl
        }

        val host = uri.host?.trim()?.ifBlank { null }
            ?: throw CoreLightningWalletException.MissingNodeHost
        CoreLightningHostAccessPolicy.validateHost(host)

        val query = parseQuery(uri.rawQuery).mergedWith(extraQuery)
        val rune = normalizedRune(
            query.firstValue("rune")
                ?: query.firstValue("token")
                ?: query.firstValue("auth")
                ?: uri.userInfo
        )
        val cert = certificateValue(query)?.let(::decodeCertificateToBase64)

        return CoreLightningNodeCredentials(
            scheme = scheme,
            host = host,
            port = if (uri.port > 0) uri.port else 3010,
            rune = rune,
            tlsCertificateDerBase64 = cert,
            label = query.firstValue("name")?.trim()?.ifBlank { null },
            nodeId = null,
            nodeAlias = null,
            connectedAtMillis = System.currentTimeMillis(),
            lastVerifiedAtMillis = null
        )
    }

    private fun parseJson(value: String): CoreLightningNodeCredentials? {
        if (!value.startsWith("{") || !value.endsWith("}")) {
            return null
        }

        val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val urlString = json.firstString("url", "baseUrl", "base_url", "restUrl", "rest_url")
        val scheme = json.firstString("scheme", "protocol")?.lowercase(Locale.US)
        val host = json.firstString("host", "hostname")
        val port = json.firstInt("port")
        val rune = json.firstString("rune", "token", "auth")
        val cert = json.firstString(*certificateFieldNames)
        val label = json.firstString("name", "label")

        if (!urlString.isNullOrBlank()) {
            val uri = runCatching { URI(urlString.trim()) }.getOrElse {
                throw CoreLightningWalletException.InvalidConnectionUrl
            }
            val resolvedScheme = uri.scheme
                ?.lowercase(Locale.US)
                ?.removePrefix("clnrest+")
                ?: scheme
                ?: "https"
            val existingQuery = parseQuery(uri.rawQuery)
            val query = linkedMapOf<String, MutableList<String>>()
            if (!rune.isNullOrBlank() && !existingQuery.containsAny("rune")) {
                query["rune"] = mutableListOf(rune)
            }
            if (!cert.isNullOrBlank() && !existingQuery.containsAny("cert")) {
                query["cert"] = mutableListOf(cert)
            }
            if (!label.isNullOrBlank() && !existingQuery.containsAny("name")) {
                query["name"] = mutableListOf(label)
            }
            return parseUri(uri, resolvedScheme, extraQuery = query)
        }

        val resolvedHost = host?.trim()?.ifBlank { null }
            ?: throw CoreLightningWalletException.MissingNodeHost
        val resolvedScheme = scheme ?: "https"
        if (resolvedScheme != "https" && resolvedScheme != "http") {
            throw CoreLightningWalletException.InvalidConnectionUrl
        }
        CoreLightningHostAccessPolicy.validateHost(resolvedHost)

        return CoreLightningNodeCredentials(
            scheme = resolvedScheme,
            host = resolvedHost,
            port = port ?: 3010,
            rune = normalizedRune(rune),
            tlsCertificateDerBase64 = cert?.let(::decodeCertificateToBase64),
            label = label?.trim()?.ifBlank { null },
            nodeId = null,
            nodeAlias = null,
            connectedAtMillis = System.currentTimeMillis(),
            lastVerifiedAtMillis = null
        )
    }

    private fun normalizedRune(value: String?): String {
        val trimmed = value?.trim()?.ifBlank { null }
            ?: throw CoreLightningWalletException.MissingRune
        if (trimmed.any { it.isWhitespace() }) {
            throw CoreLightningWalletException.InvalidRune
        }
        return trimmed
    }

    private val certificateFieldNames = arrayOf(
        "cert",
        "certificate",
        "tlsCert",
        "tls_cert",
        "tlsCertificate",
        "tls_certificate",
        "ca",
        "caCert",
        "ca_cert",
        "rootCert",
        "root_cert"
    )

    private fun certificateValue(query: Map<String, List<String>>): String? {
        for (name in certificateFieldNames) {
            query.firstValue(name)?.let { return it }
        }
        return null
    }

    private fun decodeCertificateToBase64(value: String): String {
        val trimmed = value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .trim()
        if (trimmed.isEmpty()) {
            throw CoreLightningWalletException.InvalidCertificate
        }

        hexToBytes(trimmed)?.let {
            return Base64.getEncoder().encodeToString(it)
        }

        pemCertificateBytes(trimmed)?.let {
            return Base64.getEncoder().encodeToString(it)
        }

        return runCatching {
            Base64.getDecoder().decode(trimmed)
        }.recoverCatching {
            Base64.getUrlDecoder().decode(trimmed)
        }.map {
            Base64.getEncoder().encodeToString(it)
        }.getOrElse {
            throw CoreLightningWalletException.InvalidCertificate
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, MutableList<String>> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val values = linkedMapOf<String, MutableList<String>>()
        rawQuery.split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val parts = pair.split("=", limit = 2)
            val key = urlDecode(parts[0]).lowercase(Locale.US)
            val value = urlDecode(parts.getOrNull(1).orEmpty())
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        return values
    }

    private fun urlDecode(value: String): String {
        if (!value.contains('%')) return value

        val output = StringBuilder(value.length)
        val bytes = ArrayList<Byte>()
        var index = 0

        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                output.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.clear()
            }
        }

        while (index < value.length) {
            val current = value[index]
            if (
                current == '%' &&
                index + 2 < value.length &&
                value[index + 1].isHexDigit() &&
                value[index + 2].isHexDigit()
            ) {
                bytes.add(value.substring(index + 1, index + 3).toInt(16).toByte())
                index += 3
            } else {
                flushBytes()
                output.append(current)
                index += 1
            }
        }

        flushBytes()
        return output.toString()
    }

    private fun Char.isHexDigit(): Boolean {
        return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }

    private fun Map<String, List<String>>.firstValue(name: String): String? {
        return get(name.lowercase(Locale.US))?.firstOrNull()?.trim()?.ifBlank { null }
    }

    private fun Map<String, List<String>>.containsAny(name: String): Boolean {
        return containsKey(name.lowercase(Locale.US))
    }

    private fun Map<String, MutableList<String>>.mergedWith(
        extraQuery: Map<String, List<String>>
    ): Map<String, MutableList<String>> {
        val merged = linkedMapOf<String, MutableList<String>>()
        forEach { (key, values) -> merged[key] = values.toMutableList() }
        extraQuery.forEach { (key, values) ->
            merged.getOrPut(key.lowercase(Locale.US)) { mutableListOf() }.addAll(values)
        }
        return merged
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            if (has(key) && !isNull(key)) {
                val value = optString(key).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            if (opt(key) is Number) return optInt(key)
            optString(key).trim().toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun hexToBytes(value: String): ByteArray? {
        val normalized = value.removePrefix("0x")
        if (normalized.length % 2 != 0) return null
        if (!normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun pemCertificateBytes(value: String): ByteArray? {
        val begin = "-----BEGIN CERTIFICATE-----"
        val end = "-----END CERTIFICATE-----"
        val beginIndex = value.indexOf(begin)
        val endIndex = value.indexOf(end)
        if (beginIndex < 0 || endIndex < 0 || endIndex <= beginIndex) return null
        val base64 = value
            .substring(beginIndex + begin.length, endIndex)
            .replace(Regex("\\s+"), "")
        return runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
    }
}
