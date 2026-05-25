package com.split.android.data.wallet

import org.json.JSONObject
import java.net.URI
import java.util.Locale

object EclairConnectParser {
    fun parse(
        scheme: String,
        host: String,
        port: Int,
        apiPassword: String,
        label: String? = null
    ): EclairNodeCredentials {
        val normalizedScheme = scheme.trim().lowercase(Locale.US).ifBlank { "http" }
        if (normalizedScheme != "http" && normalizedScheme != "https") {
            throw EclairWalletException.InvalidConnection
        }

        val normalizedHost = host
            .trim()
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .substringBefore("/")
            .let { value ->
                if (value.count { it == ':' } == 1 && !value.startsWith("[")) {
                    value.substringBefore(":")
                } else {
                    value
                }
            }
            .trim()
        EclairHostAccessPolicy.validateHost(normalizedHost)

        val normalizedPassword = apiPassword.trim()
        if (normalizedPassword.isBlank()) {
            throw EclairWalletException.MissingPassword
        }

        if (port !in 1..65_535) {
            throw EclairWalletException.InvalidConnection
        }

        return EclairNodeCredentials(
            scheme = normalizedScheme,
            host = normalizedHost,
            port = port,
            apiPassword = normalizedPassword,
            label = label?.trim()?.ifBlank { null },
            nodeId = null,
            nodeAlias = null,
            connectedAtMillis = System.currentTimeMillis(),
            lastVerifiedAtMillis = null
        )
    }

    fun parse(connectionString: String, label: String? = null): EclairNodeCredentials {
        val trimmed = connectionString.trim()
        if (trimmed.isBlank()) throw EclairWalletException.InvalidConnection

        parseJson(trimmed, label)?.let { return it }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw EclairWalletException.InvalidConnection

        val scheme = when (uri.scheme?.lowercase(Locale.US)) {
            "eclair+http" -> "http"
            "eclair+https" -> "https"
            "http" -> "http"
            "https" -> "https"
            "eclair" -> uri.getQueryParameter("scheme") ?: "http"
            else -> throw EclairWalletException.InvalidConnection
        }
        val host = uri.host ?: uri.getQueryParameter("host") ?: throw EclairWalletException.MissingNodeHost
        val port = uri.port.takeIf { it > 0 }
            ?: uri.getQueryParameter("port")?.toIntOrNull()
            ?: if (scheme == "https") 443 else 8080
        val password = uri.userInfo?.substringAfter(':', missingDelimiterValue = uri.userInfo).orEmpty()
            .ifBlank {
                uri.getQueryParameter("password")
                    ?: uri.getQueryParameter("apiPassword")
                    ?: uri.getQueryParameter("api_password")
                    ?: ""
            }

        return parse(
            scheme = scheme,
            host = host,
            port = port,
            apiPassword = password,
            label = label ?: uri.getQueryParameter("name")
        )
    }

    private fun parseJson(value: String, label: String?): EclairNodeCredentials? {
        if (!value.startsWith("{")) return null
        val json = runCatching { JSONObject(value) }.getOrNull()
            ?: throw EclairWalletException.InvalidConnection

        val urlString = json.eclairOptNullableString("url")
            ?: json.eclairOptNullableString("baseUrl")
            ?: json.eclairOptNullableString("base_url")
            ?: json.eclairOptNullableString("restUrl")
            ?: json.eclairOptNullableString("rest_url")
        if (!urlString.isNullOrBlank()) {
            val uri = runCatching { URI(urlString.trim()) }.getOrNull()
                ?: throw EclairWalletException.InvalidConnection
            val scheme = json.eclairOptNullableString("scheme")
                ?: json.eclairOptNullableString("protocol")
                ?: uri.scheme
                ?: "http"
            val host = uri.host ?: throw EclairWalletException.MissingNodeHost
            val port = json.eclairOptNullableInt("port")
                ?: uri.port.takeIf { it > 0 }
                ?: if (scheme.lowercase(Locale.US) == "https") 443 else 8080
            val password = json.eclairOptNullableString("password")
                ?: json.eclairOptNullableString("apiPassword")
                ?: json.eclairOptNullableString("api_password")
                ?: uri.userInfo?.substringAfter(':', missingDelimiterValue = uri.userInfo)
                ?: uri.getQueryParameter("password")
                ?: uri.getQueryParameter("apiPassword")
                ?: uri.getQueryParameter("api_password")
                ?: throw EclairWalletException.MissingPassword

            return parse(
                scheme = scheme,
                host = host,
                port = port,
                apiPassword = password,
                label = label ?: json.eclairOptNullableString("label") ?: json.eclairOptNullableString("name")
            )
        }

        val scheme = json.eclairOptNullableString("scheme")
            ?: json.eclairOptNullableString("protocol")
            ?: "http"
        val host = json.eclairOptNullableString("host")
            ?: json.eclairOptNullableString("address")
            ?: throw EclairWalletException.MissingNodeHost
        val port = json.eclairOptNullableInt("port") ?: if (scheme == "https") 443 else 8080
        val password = json.eclairOptNullableString("password")
            ?: json.eclairOptNullableString("apiPassword")
            ?: json.eclairOptNullableString("api_password")
            ?: throw EclairWalletException.MissingPassword

        return parse(
            scheme = scheme,
            host = host,
            port = port,
            apiPassword = password,
            label = label ?: json.eclairOptNullableString("label") ?: json.eclairOptNullableString("name")
        )
    }
}

private fun URI.getQueryParameter(name: String): String? {
    val rawQuery = rawQuery ?: return null
    return rawQuery.split("&")
        .mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            val key = parts.getOrNull(0)?.decodeUrlComponent()
            val value = parts.getOrNull(1)?.decodeUrlComponent().orEmpty()
            if (key == name) value else null
        }
        .firstOrNull()
        ?.trim()
        ?.ifBlank { null }
}

private fun String?.decodeUrlComponent(): String? {
    val value = this ?: return null
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
