package com.split.android.data.rewards

import java.util.Base64

internal data class RewardProofField(
    val hex: String,
    val encoding: String
)

internal fun String?.normalizedRewardProof32ByteHex(): RewardProofField? {
    val trimmed = this?.trim()?.ifBlank { null } ?: return null

    if (trimmed.length == 64 && trimmed.all { it.isHexDigit() }) {
        return RewardProofField(
            hex = trimmed.lowercase(),
            encoding = "hex"
        )
    }

    val base64Hex = decodeBase64Like32ByteHex(trimmed, urlSafe = false)
        ?: decodeBase64Like32ByteHex(trimmed, urlSafe = true)
    return base64Hex?.let {
        RewardProofField(
            hex = it,
            encoding = if (trimmed.contains('-') || trimmed.contains('_')) "base64url" else "base64"
        )
    }
}

private fun decodeBase64Like32ByteHex(value: String, urlSafe: Boolean): String? {
    val normalized = value
        .replace("\n", "")
        .replace("\r", "")
        .replace("\t", "")
        .replace(" ", "")
    val padded = normalized.padEnd(
        normalized.length + ((4 - (normalized.length % 4)) % 4),
        '='
    )
    val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
    val decoded = runCatching { decoder.decode(padded) }.getOrNull() ?: return null
    if (decoded.size != 32) return null
    return decoded.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun Char.isHexDigit(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
