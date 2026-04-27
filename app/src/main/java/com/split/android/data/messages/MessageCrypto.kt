package com.split.android.data.messages

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

data class MessageEnvelopePayload(
    val ciphertextBase64: String,
    val nonceBase64: String,
    val senderEphemeralPubkeyHex: String,
    val envelopeVersion: Int = 3
)

class MessageCrypto(
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun encrypt(
        plaintext: String,
        recipientMessagingPubkeyHex: String
    ): MessageEnvelopePayload {
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        return encryptBytes(plaintextBytes, recipientMessagingPubkeyHex)
    }

    fun encryptBytes(
        plaintextBytes: ByteArray,
        recipientMessagingPubkeyHex: String
    ): MessageEnvelopePayload {
        val recipientPublicKey = recipientMessagingPubkeyHex.hexToByteArray(strictLength = 32)

        val ephemeralPrivateKey = ByteArray(32)
        X25519.generatePrivateKey(secureRandom, ephemeralPrivateKey)

        val ephemeralPublicKey = ByteArray(32)
        X25519.generatePublicKey(ephemeralPrivateKey, 0, ephemeralPublicKey, 0)

        val sharedSecret = ByteArray(32)
        X25519.calculateAgreement(
            ephemeralPrivateKey,
            0,
            recipientPublicKey,
            0,
            sharedSecret,
            0
        )

        val symmetricKey = deriveSymmetricKey(sharedSecret)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val ciphertext = encryptChaCha20Poly1305(plaintextBytes, symmetricKey, nonce)

        return MessageEnvelopePayload(
            ciphertextBase64 = ciphertext.toBase64(),
            nonceBase64 = nonce.toBase64(),
            senderEphemeralPubkeyHex = ephemeralPublicKey.toHex()
        )
    }

    fun decrypt(
        ciphertextBase64: String,
        nonceBase64: String,
        senderEphemeralPubkeyHex: String,
        recipientPrivateKeyHex: String
    ): String {
        val plaintext = decryptBytes(
            ciphertextBase64 = ciphertextBase64,
            nonceBase64 = nonceBase64,
            senderEphemeralPubkeyHex = senderEphemeralPubkeyHex,
            recipientPrivateKeyHex = recipientPrivateKeyHex
        )
        return plaintext.toString(Charsets.UTF_8)
    }

    fun decryptBytes(
        ciphertextBase64: String,
        nonceBase64: String,
        senderEphemeralPubkeyHex: String,
        recipientPrivateKeyHex: String
    ): ByteArray {
        val ciphertext = ciphertextBase64.fromBase64()
        val nonce = nonceBase64.fromBase64()
        val senderEphemeralPublicKey = senderEphemeralPubkeyHex.hexToByteArray(strictLength = 32)
        val recipientPrivateKey = recipientPrivateKeyHex.hexToByteArray(strictLength = 32)

        val sharedSecret = ByteArray(32)
        X25519.calculateAgreement(
            recipientPrivateKey,
            0,
            senderEphemeralPublicKey,
            0,
            sharedSecret,
            0
        )

        val symmetricKey = deriveSymmetricKey(sharedSecret)
        return decryptChaCha20Poly1305(ciphertext, symmetricKey, nonce)
    }

    private fun deriveSymmetricKey(sharedSecret: ByteArray): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(sharedSecret, SALT, null))
        return ByteArray(32).also { derived ->
            generator.generateBytes(derived, 0, derived.size)
        }
    }

    private fun encryptChaCha20Poly1305(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val processLength = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        val finalLength = cipher.doFinal(output, processLength)
        return output.copyOf(processLength + finalLength)
    }

    private fun decryptChaCha20Poly1305(
        ciphertext: ByteArray,
        key: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        val processLength = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        val finalLength = cipher.doFinal(output, processLength)
        return output.copyOf(processLength + finalLength)
    }

    private companion object {
        val SALT = "split.messaging.v1".toByteArray(Charsets.UTF_8)
    }
}
