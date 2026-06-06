package com.split.android.data.messages

import com.split.android.core.AppConfig
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger

object MessageBindingVerifier {
    private val supportedBindingSignatureVersions = setOf(1, 2)
    private const val MESSAGING_IDENTITY_V4_SIGNATURE_VERSION = 4
    private val supportedEnvelopeSignatureVersions = setOf(1, 2)
    private const val MESSAGING_ENVELOPE_V4_SIGNATURE_VERSION = 3
    private val MESSAGING_IDENTITY_DOMAIN = AppConfig.messagingIdentityDomain
    private const val MESSAGING_IDENTITY_V4_DOMAIN = "splitrewards.messaging"
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

    fun verifyRecipientBindingV4(recipient: MessagingRecipient) {
        val binding = recipient.identityBindingPayloadV4
            ?: throw IllegalArgumentException("Recipient messaging identity is incomplete.")
        verifyBindingV4(binding)
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

    fun verifyBindingV4(binding: MessagingIdentityBindingPayloadV4) {
        require(binding.messagingIdentitySignatureVersion == MESSAGING_IDENTITY_V4_SIGNATURE_VERSION) {
            "Unsupported messaging identity signature version."
        }

        val normalizedHash = binding.lightningAddressHash.trim().lowercase()
        require(normalizedHash.length == 64 && normalizedHash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Recipient Lightning address hash is invalid."
        }
        require(binding.lightningAddressHashScheme.trim() == MessagingPrivacyV4.LIGHTNING_ADDRESS_CLIENT_HASH_SCHEME) {
            "Recipient Lightning address hash is invalid."
        }

        val canonicalMessage = buildMessagingIdentityBindingMessageV4(
            version = binding.messagingIdentitySignatureVersion,
            walletPubkey = binding.walletPubkey,
            lightningAddressHash = normalizedHash,
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
        domain=$MESSAGING_IDENTITY_DOMAIN
        walletPubkey=$walletPubkey
        lightningAddress=$lightningAddress
        messagingPubkey=$messagingPubkey
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun buildMessagingIdentityBindingMessageV4(
        version: Int,
        walletPubkey: String,
        lightningAddressHash: String,
        messagingPubkey: String,
        signedAtSeconds: Long
    ): String {
        return """
        SplitRewards Messaging Identity Authorization
        version=$version
        domain=$MESSAGING_IDENTITY_V4_DOMAIN
        hashScheme=${MessagingPrivacyV4.LIGHTNING_ADDRESS_CLIENT_HASH_SCHEME}
        walletPubkey=$walletPubkey
        lightningAddressHash=$lightningAddressHash
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
        domain=$MESSAGING_IDENTITY_DOMAIN
        walletPubkey=$walletPubkey
        messagingPubkey=$messagingPubkey
        platform=$platform
        environment=$environment
        deviceToken=$deviceToken
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun buildMessagingDeviceRegistrationMessageV4(
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
        domain=$MESSAGING_IDENTITY_V4_DOMAIN
        walletPubkey=$walletPubkey
        messagingPubkey=$messagingPubkey
        platform=$platform
        environment=$environment
        deviceToken=$deviceToken
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun buildMessagingSigningKeyBindingMessage(
        version: Int,
        walletPubkey: String,
        lightningAddress: String,
        messagingPubkey: String,
        messagingSigningPubkey: String,
        signedAtSeconds: Long
    ): String {
        return """
        SplitRewards Messaging Signing Key Authorization
        version=$version
        domain=$MESSAGING_IDENTITY_DOMAIN
        walletPubkey=$walletPubkey
        lightningAddress=$lightningAddress
        messagingPubkey=$messagingPubkey
        messagingSigningPubkey=$messagingSigningPubkey
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun buildMessagingSigningKeyBindingMessageV4(
        version: Int,
        walletPubkey: String,
        lightningAddressHash: String,
        messagingPubkey: String,
        messagingSigningPubkey: String,
        signedAtSeconds: Long
    ): String {
        return """
        SplitRewards Messaging Signing Key Authorization
        version=$version
        domain=$MESSAGING_IDENTITY_V4_DOMAIN
        hashScheme=${MessagingPrivacyV4.LIGHTNING_ADDRESS_CLIENT_HASH_SCHEME}
        walletPubkey=$walletPubkey
        lightningAddressHash=$lightningAddressHash
        messagingPubkey=$messagingPubkey
        messagingSigningPubkey=$messagingSigningPubkey
        signedAt=$signedAtSeconds
        """.trimIndent()
    }

    fun verifyMessagingSigningCertificate(
        certificate: MessageSigningCertificate
    ) {
        require(certificate.messagingSigningPubkeySignatureVersion == 1) {
            "Unsupported messaging identity signature version."
        }

        val canonicalMessage = buildMessagingSigningKeyBindingMessage(
            version = certificate.messagingSigningPubkeySignatureVersion,
            walletPubkey = certificate.walletPubkey,
            lightningAddress = certificate.lightningAddress,
            messagingPubkey = certificate.messagingPubkey,
            messagingSigningPubkey = certificate.messagingSigningPubkey,
            signedAtSeconds = certificate.messagingSigningPubkeySignedAt
        )

        verifySignedMessage(
            canonicalMessage = canonicalMessage,
            walletPubkey = certificate.walletPubkey,
            signatureHex = certificate.messagingSigningPubkeySignature,
            invalidSignatureMessage = "Recipient messaging identity signature could not be verified."
        )
    }

    fun verifyMessagingSigningCertificateV4(
        certificate: MessageSigningCertificateV4
    ) {
        require(certificate.messagingSigningPubkeySignatureVersion == 2) {
            "Unsupported messaging identity signature version."
        }

        val canonicalMessage = buildMessagingSigningKeyBindingMessageV4(
            version = certificate.messagingSigningPubkeySignatureVersion,
            walletPubkey = certificate.walletPubkey,
            lightningAddressHash = certificate.lightningAddressHash,
            messagingPubkey = certificate.messagingPubkey,
            messagingSigningPubkey = certificate.messagingSigningPubkey,
            signedAtSeconds = certificate.messagingSigningPubkeySignedAt
        )

        verifySignedMessage(
            canonicalMessage = canonicalMessage,
            walletPubkey = certificate.walletPubkey,
            signatureHex = certificate.messagingSigningPubkeySignature,
            invalidSignatureMessage = "Recipient messaging identity signature could not be verified."
        )
    }

    fun buildMessagingEnvelopeSignatureMessageV4(
        version: Int,
        clientMessageId: String,
        senderBinding: MessagingIdentityBindingPayloadV4,
        recipientBinding: MessagingIdentityBindingPayloadV4,
        messageType: String,
        plaintext: String,
        createdAtClientMs: Long,
        envelopeVersion: Int
    ): String {
        return """
        SplitRewards Messaging Envelope Authorization
        version=$version
        domain=$MESSAGING_IDENTITY_V4_DOMAIN
        clientMessageId=$clientMessageId
        senderWalletPubkey=${senderBinding.walletPubkey}
        senderLightningAddressHash=${senderBinding.lightningAddressHash}
        senderMessagingPubkey=${senderBinding.messagingPubkey}
        recipientWalletPubkey=${recipientBinding.walletPubkey}
        recipientLightningAddressHash=${recipientBinding.lightningAddressHash}
        recipientMessagingPubkey=${recipientBinding.messagingPubkey}
        messageType=$messageType
        plaintext=$plaintext
        createdAtClientMs=$createdAtClientMs
        envelopeVersion=$envelopeVersion
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
            domain=$MESSAGING_IDENTITY_DOMAIN
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
        domain=$MESSAGING_IDENTITY_DOMAIN
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
            recipientWalletPubkey = message.recipientWalletPubkey
                ?: throw IllegalArgumentException("The message timestamp is missing."),
            recipientLightningAddress = message.recipientLightningAddress
                ?: throw IllegalArgumentException("The message timestamp is missing."),
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
        require(sealedPayload.messageSignatureVersion in supportedEnvelopeSignatureVersions) {
            "Unsupported messaging envelope signature version."
        }

        val createdAtClientMillis = message.createdAtClientMillis
            ?: throw IllegalArgumentException("The message timestamp is missing.")

        verifyBinding(sealedPayload.sender)
        verifyMessagingSigningCertificate(
            MessageSigningCertificate(
                walletPubkey = sealedPayload.sender.walletPubkey,
                lightningAddress = sealedPayload.sender.lightningAddress,
                messagingPubkey = sealedPayload.sender.messagingPubkey,
                messagingSigningPubkey = sealedPayload.messagingSigningPubkey,
                messagingSigningPubkeySignature = sealedPayload.messagingSigningPubkeySignature,
                messagingSigningPubkeySignatureVersion = sealedPayload.messagingSigningPubkeySignatureVersion,
                messagingSigningPubkeySignedAt = sealedPayload.messagingSigningPubkeySignedAt
            )
        )

        val canonicalMessage = buildMessagingEnvelopeSignatureMessage(
            version = sealedPayload.messageSignatureVersion,
            clientMessageId = message.clientMessageId,
            senderBinding = sealedPayload.sender,
            recipientWalletPubkey = message.recipientWalletPubkey
                ?: throw IllegalArgumentException("The message timestamp is missing."),
            recipientLightningAddress = message.recipientLightningAddress
                ?: throw IllegalArgumentException("The message timestamp is missing."),
            recipientMessagingPubkey = message.recipientMessagingPubkey,
            messageType = message.messageType,
            plaintext = sealedPayload.body,
            createdAtClientMs = createdAtClientMillis,
            envelopeVersion = message.envelopeVersion
        )

        verifyMessagingSignature(
            canonicalMessage = canonicalMessage,
            messagingSigningPubkey = sealedPayload.messagingSigningPubkey,
            signatureHex = sealedPayload.messageSignature,
            invalidSignatureMessage = "The sender message signature could not be verified."
        )
    }

    fun verifySealedIncomingEnvelopeV4(
        message: InboxMessage,
        sealedPayload: SealedSenderMessagePayloadV4,
        recipientBinding: MessagingIdentityBindingPayloadV4
    ) {
        require(sealedPayload.messageSignatureVersion == MESSAGING_ENVELOPE_V4_SIGNATURE_VERSION) {
            "Unsupported messaging envelope signature version."
        }

        val createdAtClientMillis = message.createdAtClientMillis
            ?: throw IllegalArgumentException("The message timestamp is missing.")

        verifyBindingV4(sealedPayload.sender)
        verifyBindingV4(recipientBinding)
        verifyMessagingSigningCertificateV4(
            MessageSigningCertificateV4(
                walletPubkey = sealedPayload.sender.walletPubkey,
                lightningAddressHash = sealedPayload.sender.lightningAddressHash,
                lightningAddressHashScheme = sealedPayload.sender.lightningAddressHashScheme,
                messagingPubkey = sealedPayload.sender.messagingPubkey,
                messagingSigningPubkey = sealedPayload.messagingSigningPubkey,
                messagingSigningPubkeySignature = sealedPayload.messagingSigningPubkeySignature,
                messagingSigningPubkeySignatureVersion = sealedPayload.messagingSigningPubkeySignatureVersion,
                messagingSigningPubkeySignedAt = sealedPayload.messagingSigningPubkeySignedAt
            )
        )

        val canonicalMessage = buildMessagingEnvelopeSignatureMessageV4(
            version = sealedPayload.messageSignatureVersion,
            clientMessageId = message.clientMessageId,
            senderBinding = sealedPayload.sender,
            recipientBinding = recipientBinding,
            messageType = message.messageType,
            plaintext = sealedPayload.body,
            createdAtClientMs = createdAtClientMillis,
            envelopeVersion = message.envelopeVersion
        )

        verifyMessagingSignature(
            canonicalMessage = canonicalMessage,
            messagingSigningPubkey = sealedPayload.messagingSigningPubkey,
            signatureHex = sealedPayload.messageSignature,
            invalidSignatureMessage = "The sender message signature could not be verified."
        )
    }

    private fun verifyMessagingSignature(
        canonicalMessage: String,
        messagingSigningPubkey: String,
        signatureHex: String,
        invalidSignatureMessage: String
    ) {
        val publicKeyBytes = messagingSigningPubkey.hexToByteArray(strictLength = 32)
        val signatureBytes = signatureHex.hexToByteArray(strictLength = 64)
        val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
        val messageBytes = canonicalMessage.toByteArray(Charsets.UTF_8)
        val verifier = Ed25519Signer().apply {
            init(false, publicKey)
            update(messageBytes, 0, messageBytes.size)
        }

        require(verifier.verifySignature(signatureBytes)) { invalidSignatureMessage }
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
