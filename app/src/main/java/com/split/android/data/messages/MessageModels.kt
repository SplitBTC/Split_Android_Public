package com.split.android.data.messages

import org.json.JSONObject
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val DELIVERY_STATE_FAILED_SAME_KEY = "failed_same_key"

object MessagingPrivacyV4 {
    const val LIGHTNING_ADDRESS_CLIENT_HASH_SCHEME = "split-ln-address-sha256-v1"
    private const val LIGHTNING_ADDRESS_CLIENT_HASH_PREFIX = "split:messaging-ln:v1:"

    fun normalizeLightningAddress(value: String): String {
        val normalized = value.trim().lowercase()
        require(normalized.isNotEmpty() && normalized.contains("@")) {
            "The Lightning address is invalid."
        }
        return normalized
    }

    fun lightningAddressClientHash(lightningAddress: String): String {
        val normalized = normalizeLightningAddress(lightningAddress)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$LIGHTNING_ADDRESS_CLIENT_HASH_PREFIX$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

data class MessagingIdentityBindingPayload(
    val walletPubkey: String,
    val lightningAddress: String,
    val messagingPubkey: String,
    val messagingIdentitySignature: String,
    val messagingIdentitySignatureVersion: Int,
    val messagingIdentitySignedAtSeconds: Long
)

data class MessagingIdentityBindingPayloadV4(
    val walletPubkey: String,
    val lightningAddressHash: String,
    val lightningAddressHashScheme: String,
    val messagingPubkey: String,
    val messagingIdentitySignature: String,
    val messagingIdentitySignatureVersion: Int,
    val messagingIdentitySignedAtSeconds: Long
)

data class MessagingDirectoryCheckpoint(
    val rootHash: String,
    val treeSize: Int,
    val issuedAtMillis: Long
)

data class MessagingDirectoryProofNode(
    val position: String,
    val hash: String
)

data class MessagingDirectoryProofPayload(
    val leafIndex: Int,
    val leafHash: String,
    val proof: List<MessagingDirectoryProofNode>,
    val checkpoint: MessagingDirectoryCheckpoint
)

data class MessagingRecipient(
    val walletPubkey: String,
    val lightningAddress: String,
    val lightningAddressHash: String? = null,
    val lightningAddressHashScheme: String? = null,
    val messagingPubkey: String,
    val messagingIdentitySignature: String,
    val messagingIdentitySignatureVersion: Int,
    val messagingIdentitySignedAtMillis: Long,
    val profilePicUrl: String?
) {
    val identityBindingPayload: MessagingIdentityBindingPayload
        get() = MessagingIdentityBindingPayload(
            walletPubkey = walletPubkey,
            lightningAddress = lightningAddress.trim().lowercase(),
            messagingPubkey = messagingPubkey,
            messagingIdentitySignature = messagingIdentitySignature,
            messagingIdentitySignatureVersion = messagingIdentitySignatureVersion,
            messagingIdentitySignedAtSeconds = messagingIdentitySignedAtMillis / 1000L
        )

    val identityBindingPayloadV4: MessagingIdentityBindingPayloadV4?
        get() {
            val hash = lightningAddressHash
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val scheme = lightningAddressHashScheme
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return MessagingIdentityBindingPayloadV4(
                walletPubkey = walletPubkey,
                lightningAddressHash = hash,
                lightningAddressHashScheme = scheme,
                messagingPubkey = messagingPubkey,
                messagingIdentitySignature = messagingIdentitySignature,
                messagingIdentitySignatureVersion = messagingIdentitySignatureVersion,
                messagingIdentitySignedAtSeconds = messagingIdentitySignedAtMillis / 1000L
            )
        }
}

data class MessageSigningCertificate(
    val walletPubkey: String,
    val lightningAddress: String,
    val messagingPubkey: String,
    val messagingSigningPubkey: String,
    val messagingSigningPubkeySignature: String,
    val messagingSigningPubkeySignatureVersion: Int,
    val messagingSigningPubkeySignedAt: Long
)

data class MessageSigningCertificateV4(
    val walletPubkey: String,
    val lightningAddressHash: String,
    val lightningAddressHashScheme: String,
    val messagingPubkey: String,
    val messagingSigningPubkey: String,
    val messagingSigningPubkeySignature: String,
    val messagingSigningPubkeySignatureVersion: Int,
    val messagingSigningPubkeySignedAt: Long
)

data class SealedSenderMessagePayload(
    val body: String,
    val sender: MessagingIdentityBindingPayload,
    val messagingSigningPubkey: String,
    val messagingSigningPubkeySignature: String,
    val messagingSigningPubkeySignatureVersion: Int,
    val messagingSigningPubkeySignedAt: Long,
    val messageSignature: String,
    val messageSignatureVersion: Int
)

data class SealedSenderMessagePayloadV4(
    val body: String,
    val sender: MessagingIdentityBindingPayloadV4,
    val senderLightningAddress: String,
    val messagingSigningPubkey: String,
    val messagingSigningPubkeySignature: String,
    val messagingSigningPubkeySignatureVersion: Int,
    val messagingSigningPubkeySignedAt: Long,
    val messageSignature: String,
    val messageSignatureVersion: Int
)

data class InboxMessage(
    val messageId: String,
    val clientMessageId: String,
    val senderMessagingAccountId: String?,
    val senderWalletPubkey: String?,
    val senderMessagingPubkey: String,
    val senderLightningAddress: String?,
    val senderMessagingIdentitySignature: String?,
    val senderMessagingIdentitySignatureVersion: Int?,
    val senderMessagingIdentitySignedAtMillis: Long?,
    val senderEnvelopeSignature: String?,
    val senderEnvelopeSignatureVersion: Int?,
    val recipientMessagingAccountId: String?,
    val recipientWalletPubkey: String?,
    val recipientMessagingPubkey: String,
    val recipientLightningAddress: String?,
    val messageType: String,
    val envelopeVersion: Int,
    val ciphertext: String?,
    val nonce: String?,
    val senderEphemeralPubkey: String?,
    val status: String,
    val createdAtMillis: Long?,
    val createdAtClientMillis: Long?,
    val expiresAtMillis: Long?,
    val deliveredAtMillis: Long?,
    val rekeyRequiredAtMillis: Long?,
    val expiredAtMillis: Long?
) {
    val senderIdentityBindingPayload: MessagingIdentityBindingPayload?
        get() {
            val normalizedLightningAddress = senderLightningAddress
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val signature = senderMessagingIdentitySignature
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val signatureVersion = senderMessagingIdentitySignatureVersion ?: return null
            val signedAtMillis = senderMessagingIdentitySignedAtMillis ?: return null
            val walletPubkey = senderWalletPubkey
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            return MessagingIdentityBindingPayload(
                walletPubkey = walletPubkey,
                lightningAddress = normalizedLightningAddress,
                messagingPubkey = senderMessagingPubkey,
                messagingIdentitySignature = signature,
                messagingIdentitySignatureVersion = signatureVersion,
                messagingIdentitySignedAtSeconds = signedAtMillis / 1000L
            )
        }
}

data class OutgoingMessageStatus(
    val messageId: String,
    val clientMessageId: String,
    val recipientLightningAddress: String?,
    val recipientWalletPubkey: String?,
    val status: String,
    val sameKeyRetryCount: Int,
    val createdAtMillis: Long?,
    val deliveredAtMillis: Long?,
    val rekeyRequiredAtMillis: Long?,
    val sameKeyDecryptFailedAtMillis: Long?,
    val failedAtMillis: Long?,
    val expiredAtMillis: Long?
)

data class StoredMessage(
    val id: String,
    val conversationId: String,
    val clientMessageId: String,
    val body: String,
    val createdAtMillis: Long,
    val isIncoming: Boolean,
    val isRead: Boolean,
    val senderWalletPubkey: String,
    val senderMessagingPubkey: String,
    val senderLightningAddress: String?,
    val recipientWalletPubkey: String,
    val recipientMessagingPubkey: String,
    val recipientLightningAddress: String,
    val messageType: String,
    val deliveryState: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("conversationId", conversationId)
            .put("clientMessageId", clientMessageId)
            .put("body", body)
            .put("createdAtMillis", createdAtMillis)
            .put("isIncoming", isIncoming)
            .put("isRead", isRead)
            .put("senderWalletPubkey", senderWalletPubkey)
            .put("senderMessagingPubkey", senderMessagingPubkey)
            .put("senderLightningAddress", senderLightningAddress)
            .put("recipientWalletPubkey", recipientWalletPubkey)
            .put("recipientMessagingPubkey", recipientMessagingPubkey)
            .put("recipientLightningAddress", recipientLightningAddress)
            .put("messageType", messageType)
            .put("deliveryState", deliveryState)
    }

    val hasFailedDelivery: Boolean
        get() = !isIncoming && deliveryState == DELIVERY_STATE_FAILED_SAME_KEY

    companion object {
        fun fromJson(json: JSONObject): StoredMessage {
            return StoredMessage(
                id = json.getString("id"),
                conversationId = json.getString("conversationId"),
                clientMessageId = json.getString("clientMessageId"),
                body = json.getString("body"),
                createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
                isIncoming = json.optBoolean("isIncoming", false),
                isRead = json.optBoolean("isRead", false),
                senderWalletPubkey = json.getString("senderWalletPubkey"),
                senderMessagingPubkey = json.getString("senderMessagingPubkey"),
                senderLightningAddress = json.optString("senderLightningAddress").ifBlank { null },
                recipientWalletPubkey = json.getString("recipientWalletPubkey"),
                recipientMessagingPubkey = json.getString("recipientMessagingPubkey"),
                recipientLightningAddress = json.getString("recipientLightningAddress"),
                messageType = json.optString("messageType", "text").ifBlank { "text" },
                deliveryState = json.optString("deliveryState").ifBlank { null }
            )
        }
    }
}

data class MessageConversationPreview(
    val id: String,
    val title: String,
    val lightningAddress: String?,
    val profilePicUrl: String?,
    val latestBody: String,
    val latestAtMillis: Long,
    val hasUnreadMessages: Boolean,
    val hasFailedOutgoingMessage: Boolean
)

data class MessageRecipientMetadata(
    val conversationId: String,
    val lightningAddress: String?,
    val profilePicUrl: String?
) {
    val displayTitle: String
        get() = lightningAddress?.toConversationHandle() ?: conversationId.take(12)
}

data class MessagingBlockedUser(
    val blockId: String,
    val blockedMessagingAccountId: String?,
    val blockedUserId: String,
    val blockedWalletPubkey: String,
    val blockedLightningAddress: String?,
    val blockedProfilePicUrl: String?,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?
) {
    val normalizedLightningAddress: String?
        get() = blockedLightningAddress
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}

data class MessageSendResult(
    val conversationId: String,
    val conversationTitle: String,
    val lightningAddress: String,
    val storedMessage: StoredMessage
)

data class MessagingUiState(
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val errorMessage: String? = null
)

data class PaymentRequestMessagePayload(
    val invoice: String,
    val amountSats: Long,
    val requesterLightningAddress: String?,
    val note: String?
)

data class PaymentRequestPaidMessagePayload(
    val requestMessageId: String,
    val invoice: String,
    val paidAtIso: String?
)

data class AttachmentMessagePayload(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Int,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val attachmentNonce: String,
    val attachmentSenderEphemeralPubkey: String
)

data class MessageReactionPayload(
    val targetMessageId: String,
    val reactionKey: String
)

enum class MessageReactionKind(
    val rawValue: String,
    val menuTitle: String,
    val badgeText: String
) {
    LOVE("love", "Love", "Love"),
    LIKE("like", "Like", "Like"),
    DISLIKE("dislike", "Dislike", "Dislike"),
    LAUGH("laugh", "Laugh", "HaHa"),
    EMPHASIZE("emphasize", "Emphasize", "!!"),
    QUESTION("question", "Question", "?"),
    REMOVE("remove", "Remove Reaction", "Remove");

    companion object {
        val selectableCases = listOf(LOVE, LIKE, DISLIKE, LAUGH, EMPHASIZE, QUESTION)

        fun fromRawValue(value: String?): MessageReactionKind? {
            return entries.firstOrNull { it.rawValue == value }
        }
    }

    val previewText: String
        get() = when (this) {
            LOVE -> "Loved a message"
            LIKE -> "Liked a message"
            DISLIKE -> "Disliked a message"
            LAUGH -> "Laughed at a message"
            EMPHASIZE -> "Emphasized a message"
            QUESTION -> "Questioned a message"
            REMOVE -> "Removed a reaction"
        }
}

object MessagePayloadCodec {
    private val satsFormatter: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault())

    fun previewText(message: StoredMessage): String {
        return when (message.messageType) {
            "payment_request" -> {
                decodePaymentRequest(message.body)?.let { payload ->
                    "Payment request: ${satsFormatter.format(payload.amountSats)} sats"
                } ?: "Payment request"
            }

            "payment_request_paid" -> "Payment request paid"
            "attachment" -> {
                val payload = decodeAttachment(message.body)
                when {
                    payload == null -> "Attachment"
                    payload.mimeType.lowercase(Locale.US).startsWith("image/") -> "Photo: ${payload.fileName}"
                    payload.mimeType.lowercase(Locale.US).startsWith("video/") -> "Video: ${payload.fileName}"
                    else -> "File: ${payload.fileName}"
                }
            }

            "reaction" -> decodeReactionKind(message.body)?.previewText ?: "Reacted to a message"
            else -> message.body
        }
    }

    fun bubbleText(message: StoredMessage): String {
        return when (message.messageType) {
            "text" -> message.body
            "payment_request" -> {
                val payload = decodePaymentRequest(message.body)
                if (payload == null) {
                    "Payment request"
                } else {
                    buildString {
                        append("Payment request for ${satsFormatter.format(payload.amountSats)} sats")
                        payload.note?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                    }
                }
            }

            "payment_request_paid" -> "Payment request paid"
            "attachment" -> {
                val payload = decodeAttachment(message.body)
                if (payload == null) {
                    "Attachment"
                } else if (payload.mimeType.lowercase(Locale.US).startsWith("image/")) {
                    "Photo: ${payload.fileName}"
                } else if (payload.mimeType.lowercase(Locale.US).startsWith("video/")) {
                    "Video: ${payload.fileName}"
                } else {
                    "File: ${payload.fileName}"
                }
            }

            "reaction" -> decodeReactionKind(message.body)?.previewText ?: "Reacted to a message"
            else -> message.body
        }
    }

    fun formatConversationTime(timestampMillis: Long): String {
        return timeFormatter.format(Instant.ofEpochMilli(timestampMillis))
    }

    fun decodePaymentRequest(body: String): PaymentRequestMessagePayload? {
        return runCatching {
            val json = JSONObject(body)
            PaymentRequestMessagePayload(
                invoice = json.getString("invoice"),
                amountSats = json.optLong("amountSats", 0L),
                requesterLightningAddress = json.optString("requesterLightningAddress").ifBlank { null },
                note = json.optString("note").ifBlank { null }
            )
        }.getOrNull()
    }

    private fun decodeAttachment(body: String): AttachmentMessagePayload? {
        return runCatching {
            val json = JSONObject(body)
            AttachmentMessagePayload(
                attachmentId = json.getString("attachmentId"),
                fileName = json.getString("fileName"),
                mimeType = json.getString("mimeType"),
                sizeBytes = json.optInt("sizeBytes", 0),
                imageWidth = json.optInt("imageWidth").takeIf { it > 0 },
                imageHeight = json.optInt("imageHeight").takeIf { it > 0 },
                attachmentNonce = json.getString("attachmentNonce"),
                attachmentSenderEphemeralPubkey = json.getString("attachmentSenderEphemeralPubkey")
            )
        }.getOrNull()
    }

    fun decodeReaction(body: String): MessageReactionPayload? {
        return decodeReactionInternal(body)
    }

    fun decodeReactionKind(body: String): MessageReactionKind? {
        return decodeReactionInternal(body)?.reactionKey?.let(MessageReactionKind::fromRawValue)
    }

    fun decodeAttachmentPayload(body: String): AttachmentMessagePayload? = decodeAttachment(body)

    fun decodePaymentRequestPaid(body: String): PaymentRequestPaidMessagePayload? {
        return runCatching {
            val json = JSONObject(body)
            PaymentRequestPaidMessagePayload(
                requestMessageId = json.getString("requestMessageId"),
                invoice = json.getString("invoice"),
                paidAtIso = json.optString("paidAt").ifBlank { null }
            )
        }.getOrNull()
    }

    private fun decodeReactionInternal(body: String): MessageReactionPayload? {
        return runCatching {
            val json = JSONObject(body)
            MessageReactionPayload(
                targetMessageId = json.getString("targetMessageId"),
                reactionKey = json.getString("reactionKey")
            )
        }.getOrNull()
    }
}

internal fun String.toConversationHandle(): String {
    return trim()
        .substringBefore('@')
        .ifBlank { trim() }
}
