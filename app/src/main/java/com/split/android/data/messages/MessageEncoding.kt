package com.split.android.data.messages

import android.util.Base64
import java.security.MessageDigest
import java.time.Instant

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.hexToByteArray(strictLength: Int? = null): ByteArray {
    val normalized = trim().removePrefix("0x").removePrefix("0X")
    require(normalized.length % 2 == 0) { "Invalid hex length." }
    val bytes = ByteArray(normalized.length / 2)
    for (index in bytes.indices) {
        val start = index * 2
        bytes[index] = normalized.substring(start, start + 2).toInt(16).toByte()
    }
    if (strictLength != null) {
        require(bytes.size == strictLength) { "Invalid hex byte length." }
    }
    return bytes
}

internal fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

internal fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.DEFAULT)

internal fun isoStringToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

internal fun millisToIsoString(value: Long): String = Instant.ofEpochMilli(value).toString()

internal fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
