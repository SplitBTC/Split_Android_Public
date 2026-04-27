package com.split.android.data.messages

import com.split.android.core.AppConfig
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import java.math.BigInteger

object MessageBindingVerifier {
    private val supportedBindingSignatureVersions = setOf(1, 2)
    private val supportedEnvelopeSignatureVersions = setOf(1, 2)
    private val messagingIdentityDomain: String
        get() = AppConfig.messagingIdentityDomain
    private val curveParams = SECNamedCurves.getByName("secp256k1")
    private val domainParameters = ECDomainParameters(
        curveParams.curve,
        curveParams.g,
        curveParams.n,
        curveParams.h
    )

    fun verifyRecipientBinding(recipient: MessagingRecipient) {
        verifyBinding(recipient.identityBindingPayload)
    }

    fun verifyBinding(binding: MessagingIdentityBindingPayload) {
        require(binding.messagingIdentitySignatureVersion in supportedBindingSignatureVersions) {
            "Unsupported messaging identity signature version."
        }

        val canonicalMessage = buildMessagingIdentityBindingMessage(
            version = binding.messagingIdentitySignatureVersion,
            walletPubkey = binding.walletPubkey,
            lightningAddress = binding.lightningAddress,
            messagingPubkey = binding.messagingPubkey,
            signedAtSeconds = binding.messagingIdentitySignedAtSeconds
        )

        verifySignedMessage(
            canonicalMessage = canonicalMessage,
            walletPubkey = binding.walletPubkey,
            signatureHex = binding.messagingIdentitySignature,
            invalidSignatureMessage = "Recipient messaging identity signature could not be verified."
        )
    }

    fun buildMessagingIdentityBindingMessage(
        version: Int,
        walletPubkey: String,
        lightningAddress: String,
        messagingPubkey: String,
        signedAtSeconds: Long
    ): String {
        return """
        SplitRewards Messaging Identity Authorization
        version=$version
        domain=$messagingIdentityDomain
        walletPubkey=$walletPubkey
        lightningAddress=$lightningAddress
        messagingPubkey=$messagingPubkey
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun buildDirectoryLeafMessage(binding: MessagingIdentityBindingPayload): String {
        return """
        SplitRewards Messaging Directory Leaf
        version=${binding.messagingIdentitySignatureVersion}
        walletPubkey=${binding.walletPubkey}
        lightningAddress=${binding.lightningAddress}
        messagingPubkey=${binding.messagingPubkey}
        signature=${binding.messagingIdentitySignature}
        signedAt=${binding.messagingIdentitySignedAtSeconds}
        """.trimIndent()
    }

    fun buildMessagingDeviceRegistrationMessage(
        version: Int,
        walletPubkey: String,
        messagingPubkey: String,
        platform: String,
        environment: String,
        deviceToken: String,
        signedAtSeconds: Long
    ): String {
        return """
        SplitRewards Messaging Device Registration
        version=$version
        domain=$messagingIdentityDomain
        walletPubkey=$walletPubkey
        messagingPubkey=$messagingPubkey
        platform=$platform
        environment=$environment
        deviceToken=$deviceToken
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun buildMessagingEnvelopeSignatureMessage(
        version: Int,
        clientMessageId: String,
        senderBinding: MessagingIdentityBindingPayload,
        recipientWalletPubkey: String,
        recipientLightningAddress: String,
        recipientMessagingPubkey: String,
        messageType: String,
        plaintext: String? = null,
        ciphertext: String? = null,
        nonce: String? = null,
        senderEphemeralPubkey: String? = null,
        createdAtClientMs: Long,
        envelopeVersion: Int
    ): String {
        if (version >= 2) {
            return """
            SplitRewards Messaging Envelope Authorization
            version=$version
            domain=$messagingIdentityDomain
            clientMessageId=$clientMessageId
            senderWalletPubkey=${senderBinding.walletPubkey}
            senderLightningAddress=${senderBinding.lightningAddress}
            senderMessagingPubkey=${senderBinding.messagingPubkey}
            recipientWalletPubkey=$recipientWalletPubkey
            recipientLightningAddress=$recipientLightningAddress
            recipientMessagingPubkey=$recipientMessagingPubkey
            messageType=$messageType
            plaintext=${plaintext ?: ""}
            createdAtClientMs=$createdAtClientMs
            envelopeVersion=$envelopeVersion
            """.trimIndent()
        }

        return """
        SplitRewards Messaging Envelope Authorization
        version=$version
        domain=$messagingIdentityDomain
        clientMessageId=$clientMessageId
        senderWalletPubkey=${senderBinding.walletPubkey}
        senderLightningAddress=${senderBinding.lightningAddress}
        senderMessagingPubkey=${senderBinding.messagingPubkey}
        recipientWalletPubkey=$recipientWalletPubkey
        recipientLightningAddress=$recipientLightningAddress
        recipientMessagingPubkey=$recipientMessagingPubkey
        messageType=$messageType
        ciphertext=${ciphertext ?: ""}
        nonce=${nonce ?: ""}
        senderEphemeralPubkey=${senderEphemeralPubkey ?: ""}
        createdAtClientMs=$createdAtClientMs
        envelopeVersion=$envelopeVersion
        """.trimIndent()
    }

    fun verifyIncomingEnvelope(message: InboxMessage) {
        if (message.envelopeVersion != 2) {
            return
        }

        val senderBinding = message.senderIdentityBindingPayload
            ?: throw IllegalArgumentException("Recipient messaging identity is incomplete.")
        val senderEnvelopeSignature = message.senderEnvelopeSignature
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("The sender message envelope signature is incomplete.")
        val senderEnvelopeSignatureVersion = message.senderEnvelopeSignatureVersion
            ?: throw IllegalArgumentException("The sender message envelope signature is incomplete.")
        require(senderEnvelopeSignatureVersion in supportedEnvelopeSignatureVersions) {
            "Unsupported messaging envelope signature version."
        }

        val ciphertext = message.ciphertext
        val nonce = message.nonce
        val senderEphemeralPubkey = message.senderEphemeralPubkey
        val createdAtClientMillis = message.createdAtClientMillis
        if (ciphertext == null ||
            nonce == null ||
            senderEphemeralPubkey == null ||
            createdAtClientMillis == null
        ) {
            throw IllegalArgumentException("The message timestamp is missing.")
        }

        verifyBinding(senderBinding)

        val canonicalMessage = buildMessagingEnvelopeSignatureMessage(
            version = senderEnvelopeSignatureVersion,
            clientMessageId = message.clientMessageId,
            senderBinding = senderBinding,
            recipientWalletPubkey = message.recipientWalletPubkey,
            recipientLightningAddress = message.recipientLightningAddress,
            recipientMessagingPubkey = message.recipientMessagingPubkey,
            messageType = message.messageType,
            ciphertext = ciphertext,
            nonce = nonce,
            senderEphemeralPubkey = senderEphemeralPubkey,
            createdAtClientMs = createdAtClientMillis,
            envelopeVersion = message.envelopeVersion
        )

        verifySignedMessage(
            canonicalMessage = canonicalMessage,
            walletPubkey = senderBinding.walletPubkey,
            signatureHex = senderEnvelopeSignature,
            invalidSignatureMessage = "The sender message signature could not be verified."
        )
    }

    fun verifySealedIncomingEnvelope(
        message: InboxMessage,
        sealedPayload: SealedSenderMessagePayload
    ) {
        require(sealedPayload.senderEnvelopeSignatureVersion in supportedEnvelopeSignatureVersions) {
            "Unsupported messaging envelope signature version."
        }

        val createdAtClientMillis = message.createdAtClientMillis
            ?: throw IllegalArgumentException("The message timestamp is missing.")

        verifyBinding(sealedPayload.sender)

        val canonicalMessage = buildMessagingEnvelopeSignatureMessage(
            version = sealedPayload.senderEnvelopeSignatureVersion,
            clientMessageId = message.clientMessageId,
            senderBinding = sealedPayload.sender,
            recipientWalletPubkey = message.recipientWalletPubkey,
            recipientLightningAddress = message.recipientLightningAddress,
            recipientMessagingPubkey = message.recipientMessagingPubkey,
            messageType = message.messageType,
            plaintext = sealedPayload.body,
            createdAtClientMs = createdAtClientMillis,
            envelopeVersion = message.envelopeVersion
        )

        verifySignedMessage(
            canonicalMessage = canonicalMessage,
            walletPubkey = sealedPayload.sender.walletPubkey,
            signatureHex = sealedPayload.senderEnvelopeSignature,
            invalidSignatureMessage = "The sender message signature could not be verified."
        )
    }

    private fun verifySignedMessage(
        canonicalMessage: String,
        walletPubkey: String,
        signatureHex: String,
        invalidSignatureMessage: String
    ) {
        val normalizedWalletPubkey = normalizeWalletPubkeyHex(walletPubkey)
            ?: throw IllegalArgumentException("Recipient wallet pubkey is invalid.")

        val pubkeyPoint = runCatching {
            curveParams.curve.decodePoint(normalizedWalletPubkey.hexToByteArray())
        }.getOrElse {
            throw IllegalArgumentException("Recipient wallet pubkey is invalid.")
        }

        val digestBytes = sha256(canonicalMessage.toByteArray(Charsets.UTF_8))
        val signatureCandidates = compactSignatureCandidates(signatureHex)
        val signer = ECDSASigner().apply {
            init(false, ECPublicKeyParameters(pubkeyPoint, domainParameters))
        }

        val isValid = signatureCandidates.any { signature ->
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            signer.verifySignature(digestBytes, r, s)
        }

        require(isValid) { invalidSignatureMessage }
    }

    private fun compactSignatureCandidates(signatureHex: String): List<ByteArray> {
        val signatureBytes = signatureHex.hexToByteArray()
        return when (signatureBytes.size) {
            64 -> listOf(signatureBytes)
            65 -> listOf(
                signatureBytes.copyOfRange(0, 64),
                signatureBytes.copyOfRange(1, 65)
            )

            else -> throw IllegalArgumentException("Recipient messaging identity signature format is invalid.")
        }
    }

    private fun normalizeWalletPubkeyHex(hex: String): String? {
        var value = hex.trim()
        if (value.startsWith("0x", ignoreCase = true)) {
            value = value.drop(2)
        }

        if (value.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }

        val lowercased = value.lowercase()
        return when (lowercased.length) {
            66, 130 -> lowercased
            128 -> "04$lowercased"
            else -> null
        }
    }
}
