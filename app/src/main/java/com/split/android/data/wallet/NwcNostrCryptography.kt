package com.split.android.data.wallet

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.ECPoint
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.log2

object NwcNostrCryptography {
    private val curveParams = SECNamedCurves.getByName("secp256k1")
    private val curve = curveParams.curve
    private val generator = curveParams.g
    private val order = curveParams.n
    private val random = SecureRandom()

    fun publicKeyHex(privateKeyHex: String): String {
        val secret = normalizedSecretKey(privateKeyHex)
        val point = generator.multiply(secret).normalize()
        return point.xCoord.encoded.toFixedHex(32)
    }

    fun signEvent(
        privateKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): NwcNostrEvent {
        val pubkey = publicKeyHex(privateKeyHex)
        val id = eventId(pubkey, createdAt, kind, tags, content)
        val signature = schnorrSignature(privateKeyHex, id)
        return NwcNostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = signature
        )
    }

    fun verifyEvent(event: NwcNostrEvent): Boolean {
        if (eventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content) != event.id) {
            return false
        }
        return verifySchnorrSignature(
            pubkeyHex = event.pubkey,
            messageHex = event.id,
            signatureHex = event.sig
        )
    }

    fun nip44Encrypt(
        plaintext: String,
        senderPrivateKeyHex: String,
        recipientPublicKeyHex: String
    ): String {
        val plaintextData = plaintext.toByteArray(Charsets.UTF_8)
        require(plaintextData.isNotEmpty() && plaintextData.size <= 65_535) {
            "The NWC request payload is invalid."
        }

        val conversationKey = conversationKey(senderPrivateKeyHex, recipientPublicKeyHex)
        val nonce = secureRandomBytes(32)
        val keys = messageKeys(conversationKey, nonce)
        val padded = pad(plaintextData)
        val ciphertext = chacha20(padded, keys.chachaKey, keys.chachaNonce)
        val mac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        return (byteArrayOf(0x02) + nonce + ciphertext + mac).base64()
    }

    fun nip44Decrypt(
        payload: String,
        recipientPrivateKeyHex: String,
        senderPublicKeyHex: String
    ): String {
        require(!payload.startsWith("#")) { "The encrypted NWC payload is invalid." }
        val decoded = payload.base64Bytes()
        require(decoded.size >= 99 && decoded.first() == 0x02.toByte()) {
            "The encrypted NWC payload version is not supported."
        }

        val nonce = decoded.copyOfRange(1, 33)
        val ciphertext = decoded.copyOfRange(33, decoded.size - 32)
        val mac = decoded.copyOfRange(decoded.size - 32, decoded.size)
        val keys = messageKeys(conversationKey(recipientPrivateKeyHex, senderPublicKeyHex), nonce)
        val expectedMac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        require(MessageDigest.isEqual(mac, expectedMac)) {
            "The encrypted NWC payload could not be authenticated."
        }
        val padded = chacha20(ciphertext, keys.chachaKey, keys.chachaNonce)
        return unpad(padded).toString(Charsets.UTF_8)
    }

    fun nip04Encrypt(
        plaintext: String,
        senderPrivateKeyHex: String,
        recipientPublicKeyHex: String
    ): String {
        val sharedSecret = sharedSecretX(senderPrivateKeyHex, recipientPublicKeyHex)
        val iv = secureRandomBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${ciphertext.base64()}?iv=${iv.base64()}"
    }

    fun nip04Decrypt(
        payload: String,
        recipientPrivateKeyHex: String,
        senderPublicKeyHex: String
    ): String {
        val parts = payload.split("?iv=", limit = 2)
        require(parts.size == 2) { "The encrypted NWC payload is invalid." }
        val ciphertext = parts[0].base64Bytes()
        val iv = parts[1].base64Bytes()
        require(iv.size == 16) { "The encrypted NWC payload is invalid." }
        val sharedSecret = sharedSecretX(recipientPrivateKeyHex, senderPublicKeyHex)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun eventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val tagsJson = tags.joinToString(separator = ",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(separator = ",", prefix = "[", postfix = "]") { JSONObject.quote(it) }
        }
        val payload = "[0,${JSONObject.quote(pubkey)},$createdAt,$kind,$tagsJson,${JSONObject.quote(content)}]"
        return sha256(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun schnorrSignature(privateKeyHex: String, messageIdHex: String): String {
        val secret = normalizedSecretKey(privateKeyHex)
        val message = messageIdHex.hexBytes()
        require(message.size == 32) { "The NWC event is invalid." }
        val publicPoint = generator.multiply(secret).normalize()
        val secretEvenY = if (hasEvenY(publicPoint)) secret else order.subtract(secret)
        val pubkeyX = publicPoint.xCoord.encoded
        val aux = secureRandomBytes(32)
        val t = secretEvenY.toFixedBytes(32).xor(taggedHash("BIP0340/aux", aux))
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t + pubkeyX + message)).mod(order)
        require(k0 != BigInteger.ZERO) { "Unable to sign the NWC event." }
        val rPoint = generator.multiply(k0).normalize()
        val k = if (hasEvenY(rPoint)) k0 else order.subtract(k0)
        val rX = rPoint.xCoord.encoded
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rX + pubkeyX + message)).mod(order)
        val s = k.add(e.multiply(secretEvenY)).mod(order)
        return (rX + s.toFixedBytes(32)).toHex()
    }

    private fun verifySchnorrSignature(
        pubkeyHex: String,
        messageHex: String,
        signatureHex: String
    ): Boolean {
        val pubkey = runCatching { pubkeyHex.hexBytes() }.getOrNull() ?: return false
        val message = runCatching { messageHex.hexBytes() }.getOrNull() ?: return false
        val signature = runCatching { signatureHex.hexBytes() }.getOrNull() ?: return false
        if (pubkey.size != 32 || message.size != 32 || signature.size != 64) return false

        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= curve.field.characteristic || s >= order) return false
        val publicPoint = liftX(pubkey) ?: return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + pubkey + message)).mod(order)
        val rPoint = generator.multiply(s).subtract(publicPoint.multiply(e)).normalize()
        if (rPoint.isInfinity || !hasEvenY(rPoint)) return false
        return rPoint.xCoord.toBigInteger() == r
    }

    private fun conversationKey(privateKeyHex: String, publicKeyHex: String): ByteArray {
        val sharedX = sharedSecretX(privateKeyHex, publicKeyHex)
        return hkdfExtract(sharedX, "nip44-v2".toByteArray(Charsets.UTF_8))
    }

    private fun sharedSecretX(privateKeyHex: String, publicKeyHex: String): ByteArray {
        val secret = normalizedSecretKey(privateKeyHex)
        val publicKey = publicKeyHex.hexBytes()
        require(publicKey.size == 32) { "The NWC public key is invalid." }
        val point = liftX(publicKey) ?: throw IllegalArgumentException("The NWC public key is invalid.")
        return point.multiply(secret).normalize().xCoord.encoded
    }

    private fun liftX(xBytes: ByteArray): ECPoint? {
        if (xBytes.size != 32) return null
        return runCatching { curve.decodePoint(byteArrayOf(0x02) + xBytes).normalize() }.getOrNull()
    }

    private fun normalizedSecretKey(value: String): BigInteger {
        val bytes = value.hexBytes()
        require(bytes.size == 32) { "The NWC private key is invalid." }
        val secret = BigInteger(1, bytes)
        require(secret > BigInteger.ZERO && secret < order) { "The NWC private key is invalid." }
        return secret
    }

    private fun messageKeys(
        conversationKey: ByteArray,
        nonce: ByteArray
    ): NwcMessageKeys {
        require(conversationKey.size == 32 && nonce.size == 32) { "The encrypted NWC payload is invalid." }
        val expanded = hkdfExpand(conversationKey, nonce, 76)
        return NwcMessageKeys(
            chachaKey = expanded.copyOfRange(0, 32),
            chachaNonce = expanded.copyOfRange(32, 44),
            hmacKey = expanded.copyOfRange(44, 76)
        )
    }

    private fun hkdfExtract(ikm: ByteArray, salt: ByteArray): ByteArray {
        return hmacSha256(salt, ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters.skipExtractParameters(prk, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + data)
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val paddedLength = calcPaddedLength(plaintext.size)
        val output = ByteArray(2 + paddedLength)
        output[0] = ((plaintext.size shr 8) and 0xff).toByte()
        output[1] = (plaintext.size and 0xff).toByte()
        plaintext.copyInto(output, 2)
        return output
    }

    private fun unpad(padded: ByteArray): ByteArray {
        require(padded.size >= 34) { "The encrypted NWC payload padding is invalid." }
        val length = ((padded[0].toInt() and 0xff) shl 8) + (padded[1].toInt() and 0xff)
        require(length > 0 && length <= 65_535 && padded.size == 2 + calcPaddedLength(length)) {
            "The encrypted NWC payload padding is invalid."
        }
        return padded.copyOfRange(2, 2 + length)
    }

    private fun calcPaddedLength(length: Int): Int {
        if (length <= 32) return 32
        val nextPower = 1 shl ceil(log2(length.toDouble())).toInt()
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ceil(length.toDouble() / chunk.toDouble()).toInt()
    }

    private fun chacha20(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val output = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, output, 0)
        return output
    }

    private fun secureRandomBytes(count: Int): ByteArray {
        return ByteArray(count).also(random::nextBytes)
    }

    private fun hasEvenY(point: ECPoint): Boolean {
        return !point.normalize().yCoord.toBigInteger().testBit(0)
    }
}

private data class NwcMessageKeys(
    val chachaKey: ByteArray,
    val chachaNonce: ByteArray,
    val hmacKey: ByteArray
)

internal fun String.hexBytes(): ByteArray {
    var normalized = trim()
    if (normalized.startsWith("0x", ignoreCase = true)) normalized = normalized.drop(2)
    require(normalized.length % 2 == 0 && normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "Invalid hex string."
    }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String {
    return joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun BigInteger.toFixedBytes(size: Int): ByteArray {
    val raw = toByteArray()
    val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.drop(1).toByteArray() else raw
    require(unsigned.size <= size) { "Integer does not fit." }
    return ByteArray(size - unsigned.size) + unsigned
}

private fun ByteArray.toFixedHex(size: Int): String = BigInteger(1, this).toFixedBytes(size).toHex()

private fun ByteArray.xor(other: ByteArray): ByteArray {
    require(size == other.size)
    return ByteArray(size) { index -> (this[index].toInt() xor other[index].toInt()).toByte() }
}

private fun ByteArray.base64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.base64Bytes(): ByteArray {
    return Base64.getDecoder().decode(this)
}
