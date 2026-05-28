package com.split.android.data.wallet

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.ECAlgorithms
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

internal object Bolt11InvoiceMetadataDecoder {
    private const val TIMESTAMP_BASE32_LENGTH = 7
    private const val SIGNATURE_BASE32_LENGTH = 104
    private const val SIGNATURE_BYTE_COUNT = 65

    private val curveParams = SECNamedCurves.getByName("secp256k1")
    private val domainParameters = ECDomainParameters(
        curveParams.curve,
        curveParams.g,
        curveParams.n,
        curveParams.h
    )
    private val halfCurveOrder = curveParams.n.shiftRight(1)

    fun decode(invoice: String): Bolt11InvoiceMetadata? {
        val normalized = invoice.trim()
        val decoded = Bolt11Bech32.decode(normalized) ?: return null
        if (!decoded.hrp.startsWith("lnbc")) return null

        val amountSats = parseAmountSats(decoded.hrp)
        if (decoded.data.size <= TIMESTAMP_BASE32_LENGTH) {
            return Bolt11InvoiceMetadata(
                amountSats = amountSats,
                paymentHash = null,
                destinationPubkey = null,
                description = null
            )
        }

        val taggedFields = decoded.data.drop(TIMESTAMP_BASE32_LENGTH)
        val fieldsEnd = (taggedFields.size - SIGNATURE_BASE32_LENGTH).coerceAtLeast(0)
        var index = 0
        var paymentHash: String? = null
        var destinationPubkey: String? = null
        var description: String? = null

        while (index + 3 <= fieldsEnd) {
            val tag = taggedFields[index]
            val dataLength = (taggedFields[index + 1] shl 5) + taggedFields[index + 2]
            index += 3

            if (index + dataLength > fieldsEnd) break

            val fieldData = taggedFields.subList(index, index + dataLength)
            index += dataLength

            val fieldBytes = convertBits(fieldData, fromBits = 5, toBits = 8, pad = false) ?: continue
            when (tag) {
                1 -> if (fieldBytes.size == 32) paymentHash = fieldBytes.toBolt11Hex().ifBlank { null }
                13 -> description = fieldBytes.toString(Charsets.UTF_8).trim().ifBlank { null }
                19 -> if (fieldBytes.size == 33) destinationPubkey = fieldBytes.toBolt11Hex().ifBlank { null }
            }
        }

        val signaturePayload = invoiceSignaturePayload(decoded)

        val verifiedDestinationPubkey = if (destinationPubkey != null) {
            if (signaturePayload == null ||
                !verifyExplicitDestinationPubkey(destinationPubkey, signaturePayload)
            ) {
                return null
            }
            destinationPubkey
        } else {
            signaturePayload?.let { recoverDestinationPubkey(it) }
        }

        return Bolt11InvoiceMetadata(
            amountSats = amountSats,
            paymentHash = paymentHash,
            destinationPubkey = verifiedDestinationPubkey,
            description = description
        )
    }

    private fun parseAmountSats(hrp: String): Long? {
        if (!hrp.startsWith("lnbc")) return null
        val amountPart = hrp.removePrefix("lnbc")
        if (amountPart.isBlank()) return null

        val multiplier = amountPart.last().takeIf { it in setOf('m', 'u', 'n', 'p') }
        val numericPart = if (multiplier == null) amountPart else amountPart.dropLast(1)
        val amount = numericPart.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return null

        val btc = when (multiplier) {
            'm' -> amount.divide(BigDecimal("1000"))
            'u' -> amount.divide(BigDecimal("1000000"))
            'n' -> amount.divide(BigDecimal("1000000000"))
            'p' -> amount.divide(BigDecimal("1000000000000"))
            else -> amount
        }

        return btc.multiply(BigDecimal("100000000")).toLong()
    }

    private fun invoiceSignaturePayload(decoded: Bolt11Bech32Data): InvoiceSignaturePayload? {
        if (decoded.data.size < SIGNATURE_BASE32_LENGTH) return null

        val signatureBytes = convertBits(
            decoded.data.takeLast(SIGNATURE_BASE32_LENGTH),
            fromBits = 5,
            toBits = 8,
            pad = false
        ) ?: return null
        if (signatureBytes.size != SIGNATURE_BYTE_COUNT) return null

        val messageBytes = convertBits(
            decoded.data.dropLast(SIGNATURE_BASE32_LENGTH),
            fromBits = 5,
            toBits = 8,
            pad = true
        ) ?: return null

        val signedPayload = decoded.hrp.toByteArray(Charsets.UTF_8) + messageBytes
        val messageHash = MessageDigest.getInstance("SHA-256").digest(signedPayload)
        val r = BigInteger(1, signatureBytes.copyOfRange(0, 32))
        val s = BigInteger(1, signatureBytes.copyOfRange(32, 64))
        val recoveryId = signatureBytes[64].toInt()

        if (!isValidCanonicalSignature(r, s)) return null

        return InvoiceSignaturePayload(
            messageHash = messageHash,
            r = r,
            s = s,
            recoveryId = recoveryId
        )
    }

    private fun verifyExplicitDestinationPubkey(
        destinationPubkey: String,
        payload: InvoiceSignaturePayload
    ): Boolean {
        val publicKeyPoint = runCatching {
            curveParams.curve.decodePoint(destinationPubkey.hexToByteArray())
        }.getOrNull() ?: return false

        val signer = ECDSASigner()
        signer.init(false, ECPublicKeyParameters(publicKeyPoint, domainParameters))
        return signer.verifySignature(payload.messageHash, payload.r, payload.s)
    }

    private fun recoverDestinationPubkey(payload: InvoiceSignaturePayload): String? {
        val recoveryId = payload.recoveryId
        if (recoveryId !in 0..3) return null

        val n = curveParams.n
        val i = BigInteger.valueOf((recoveryId / 2).toLong())
        val x = payload.r.add(i.multiply(n))
        val prime = curveParams.curve.field.characteristic
        if (x >= prime) return null

        val rPoint = decompressKey(x, (recoveryId and 1) == 1) ?: return null
        if (!rPoint.multiply(n).isInfinity) return null

        val e = BigInteger(1, payload.messageHash)
        val rInverse = payload.r.modInverse(n)
        val eInverse = BigInteger.ZERO.subtract(e).mod(n)
        val srInverse = payload.s.multiply(rInverse).mod(n)
        val eInvrInverse = eInverse.multiply(rInverse).mod(n)
        val q = ECAlgorithms.sumOfTwoMultiplies(curveParams.g, eInvrInverse, rPoint, srInverse)
            .normalize()

        return q.getEncoded(true).toBolt11Hex().ifBlank { null }
    }

    private fun decompressKey(x: BigInteger, yBit: Boolean) = runCatching {
        val prefix = if (yBit) 0x03.toByte() else 0x02.toByte()
        curveParams.curve.decodePoint(byteArrayOf(prefix) + x.toFixedByteArray(32))
    }.getOrNull()

    private fun isValidCanonicalSignature(r: BigInteger, s: BigInteger): Boolean {
        val n = curveParams.n
        return r > BigInteger.ZERO &&
            r < n &&
            s > BigInteger.ZERO &&
            s < n &&
            s <= halfCurveOrder
    }

    private fun convertBits(
        data: List<Int>,
        fromBits: Int,
        toBits: Int,
        pad: Boolean
    ): ByteArray? {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val result = mutableListOf<Byte>()

        for (value in data) {
            if (value shr fromBits != 0) return null
            acc = (acc shl fromBits) or value
            bits += fromBits

            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) result.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }

        return result.toByteArray()
    }

    private data class InvoiceSignaturePayload(
        val messageHash: ByteArray,
        val r: BigInteger,
        val s: BigInteger,
        val recoveryId: Int
    )
}

private data class Bolt11Bech32Data(
    val hrp: String,
    val data: List<Int>
)

private object Bolt11Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(value: String): Bolt11Bech32Data? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.code < 33 || it.code > 126 }) return null
        if (trimmed.any { it.isLowerCase() } && trimmed.any { it.isUpperCase() }) return null

        val lower = trimmed.lowercase()
        val separatorIndex = lower.lastIndexOf('1')
        if (separatorIndex <= 0 || separatorIndex + 7 > lower.length) return null

        val hrp = lower.substring(0, separatorIndex)
        val values = lower.substring(separatorIndex + 1).map { char ->
            CHARSET.indexOf(char).takeIf { it >= 0 } ?: return null
        }

        if (!verifyChecksum(hrp, values)) return null
        return Bolt11Bech32Data(hrp = hrp, data = values.dropLast(6))
    }

    private fun verifyChecksum(hrp: String, values: List<Int>): Boolean {
        return polymod(hrpExpand(hrp) + values) == 1
    }

    private fun hrpExpand(hrp: String): List<Int> {
        return hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
    }

    private fun polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        for (value in values) {
            val top = checksum shr 25
            checksum = (checksum and 0x1ffffff) shl 5 xor value
            for (index in generators.indices) {
                if (((top shr index) and 1) == 1) {
                    checksum = checksum xor generators[index]
                }
            }
        }
        return checksum
    }
}

private fun String.hexToByteArray(): ByteArray {
    val normalized = trim()
    require(normalized.length % 2 == 0 && normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toBolt11Hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun BigInteger.toFixedByteArray(size: Int): ByteArray {
    val raw = toByteArray()
    val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.drop(1).toByteArray() else raw
    require(unsigned.size <= size)
    return ByteArray(size - unsigned.size) + unsigned
}
