package com.split.android.data.messages

import com.split.android.data.auth.AuthManager
import com.split.android.data.network.BinaryHttpResponse
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletTransactionRow
import com.split.android.data.wallet.WalletManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MessagingRepository(
    private val httpClient: SplitHttpClient,
    private val messageKeyManager: MessageKeyManager,
    private val messageStore: MessageStore,
    private val attachmentManager: MessageAttachmentManager,
    private val deviceTokenManager: MessagingDeviceTokenManager? = null,
    private val messageCrypto: MessageCrypto = MessageCrypto()
) {
    private data class OutgoingMessageDraft(
        val clientMessageId: String,
        val createdAtMillis: Long,
        val sameKeyRetryCount: Int? = null
    )

    private data class InboxRecoveryCandidate(
        val message: InboxMessage,
        val reason: InboxRecoveryFailureReason
    )

    private enum class InboxRecoveryFailureReason(
        val rawValue: String,
        val shouldRotateMessagingIdentity: Boolean
    ) {
        INVALID_STORED_KEY("invalidStoredKey", true),
        CIPHERTEXT_DECRYPT_FAILED("ciphertextDecryptFailed", true),
        SEALED_PAYLOAD_DECODE_FAILED("sealedPayloadDecodeFailed", false),
        SEALED_ENVELOPE_VERIFICATION_FAILED("sealedEnvelopeVerificationFailed", false)
    }

    private val syncMutex = Mutex()
    private val outgoingStatusSyncMutex = Mutex()
    private var lastSuccessfulSyncAtMillis: Long? = null
    private var lastSuccessfulOutgoingStatusSyncAtMillis: Long? = null

    private val _uiState = MutableStateFlow(MessagingUiState())
    val uiState: StateFlow<MessagingUiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<StoredMessage>> = messageStore.messages
    val recipientMetadataByConversationId: StateFlow<Map<String, MessageRecipientMetadata>> =
        messageStore.recipientMetadataByConversationId

    suspend fun syncInbox(
        authManager: AuthManager,
        walletManager: WalletManager,
        force: Boolean = false,
        minimumIntervalMillis: Long = DEFAULT_MINIMUM_SYNC_INTERVAL_MILLIS
    ) {
        syncMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (lastSuccessfulSyncAtMillis ?: 0L) > 0L) {
                val elapsed = now - (lastSuccessfulSyncAtMillis ?: 0L)
                if (elapsed < minimumIntervalMillis) {
                    if (!_uiState.value.hasLoadedOnce) {
                        _uiState.value = _uiState.value.copy(hasLoadedOnce = true)
                    }
                    return
                }
            }

            val hadMessages = messages.value.isNotEmpty()
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null
            )

            runCatching {
                messageKeyManager.ensureRegistered(authManager, walletManager)
                val inboxMessages = fetchInboxMessages(authManager, walletManager)
                if (inboxMessages.isNotEmpty()) {
                    val decryptedMessages = mutableListOf<StoredMessage>()
                    val recoveryCandidates = mutableListOf<InboxRecoveryCandidate>()
                    inboxMessages.forEach { inboxMessage ->
                        try {
                            decryptInboxMessage(inboxMessage)?.let(decryptedMessages::add)
                        } catch (error: RecoverableInboxMessageException) {
                            recoveryCandidates += InboxRecoveryCandidate(
                                message = inboxMessage,
                                reason = error.reason
                            )
                        }
                    }

                    val persistedIds = if (decryptedMessages.isNotEmpty()) {
                        messageStore.upsert(decryptedMessages)
                    } else {
                        emptyList()
                    }

                    if (recoveryCandidates.isNotEmpty()) {
                        val currentMessagingPubkeyHex = runCatching {
                            messageKeyManager.ensureRegistered(authManager, walletManager)
                            deviceTokenManager?.syncCurrentDeviceToken(
                                authManager = authManager,
                                walletManager = walletManager,
                                force = true
                            )
                            messageKeyManager.currentMessagingPublicKeyHex()
                        }.getOrElse { error ->
                            println(
                                "Failed to realign messaging identity before requesting rekey: ${error.localizedMessage}"
                            )
                            null
                        }

                        val rekeyRequiredMessageIds = recoveryCandidates
                            .filter { candidate ->
                                shouldRequestRekey(
                                    inboxMessage = candidate.message,
                                    currentMessagingPubkeyHex = currentMessagingPubkeyHex
                                )
                            }
                            .map { it.message.messageId }
                        val decryptFailedCandidates = recoveryCandidates
                            .filter { candidate ->
                                shouldMarkDecryptFailed(
                                    inboxMessage = candidate.message,
                                    currentMessagingPubkeyHex = currentMessagingPubkeyHex
                                )
                            }

                        if (rekeyRequiredMessageIds.isNotEmpty()) {
                            markMessagesRekeyRequired(
                                messageIds = rekeyRequiredMessageIds,
                                authManager = authManager,
                                walletManager = walletManager
                            )
                        }

                        if (decryptFailedCandidates.isNotEmpty()) {
                            markMessagesDecryptFailed(
                                failureReasons = decryptFailedCandidates.associate {
                                    it.message.messageId to it.reason.rawValue
                                },
                                authManager = authManager,
                                walletManager = walletManager
                            )
                            if (decryptFailedCandidates.any { it.reason.shouldRotateMessagingIdentity }) {
                                val reason = decryptFailedCandidates
                                    .map { it.reason.rawValue }
                                    .distinct()
                                    .sorted()
                                    .joinToString(",")
                                runCatching {
                                    messageKeyManager.rotateMessagingIdentityAfterSameKeyFailure(
                                        authManager = authManager,
                                        walletManager = walletManager,
                                        reason = "same-key $reason"
                                    )
                                    deviceTokenManager?.syncCurrentDeviceToken(
                                        authManager = authManager,
                                        walletManager = walletManager,
                                        force = true
                                    )
                                }.onFailure { error ->
                                    println(
                                        "Failed to rotate messaging identity after decrypt failure: ${error.localizedMessage}"
                                    )
                                }
                            }
                        }
                    }

                    if (persistedIds.isNotEmpty()) {
                        acknowledgeMessages(persistedIds, authManager, walletManager)
                    }
                }
                refreshRecipientMetadataForMessages(
                    messages = messageStore.messages.value,
                    authManager = authManager,
                    walletManager = walletManager
                )
                lastSuccessfulSyncAtMillis = System.currentTimeMillis()
                _uiState.value = MessagingUiState(
                    isLoading = false,
                    hasLoadedOnce = true,
                    errorMessage = null
                )
            }.onFailure { error ->
                if (messageKeyManager.shouldSilentlyDeferActivation(error)) {
                    _uiState.value = MessagingUiState(
                        isLoading = false,
                        hasLoadedOnce = true,
                        errorMessage = null
                    )
                } else {
                    _uiState.value = MessagingUiState(
                        isLoading = false,
                        hasLoadedOnce = hadMessages,
                        errorMessage = error.message ?: "Failed to sync messages."
                    )
                }
            }
        }
    }

    suspend fun syncOutgoingStatuses(
        authManager: AuthManager,
        walletManager: WalletManager,
        force: Boolean = false,
        minimumIntervalMillis: Long = DEFAULT_MINIMUM_OUTGOING_STATUS_SYNC_INTERVAL_MILLIS
    ) {
        outgoingStatusSyncMutex.withLock {
            if (messageStore.messages.value.none { message -> !message.isIncoming }) {
                return
            }

            val now = System.currentTimeMillis()
            if (!force && (lastSuccessfulOutgoingStatusSyncAtMillis ?: 0L) > 0L) {
                val elapsed = now - (lastSuccessfulOutgoingStatusSyncAtMillis ?: 0L)
                if (elapsed < minimumIntervalMillis) {
                    return
                }
            }

            retryRekeyRequiredOutgoingMessages(
                authManager = authManager,
                walletManager = walletManager
            )
            lastSuccessfulOutgoingStatusSyncAtMillis = System.currentTimeMillis()
        }
    }

    suspend fun sendTextMessage(
        lightningAddress: String,
        plaintext: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessageSendResult {
        require(plaintext.trim().isNotEmpty()) { "Enter a message to continue." }
        return sendEncodedMessage(
            lightningAddress = lightningAddress,
            plaintext = plaintext.trim(),
            messageType = "text",
            attachmentIds = null,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun sendPaymentRequestMessage(
        lightningAddress: String,
        payload: PaymentRequestMessagePayload,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessageSendResult {
        val body = JSONObject()
            .put("invoice", payload.invoice)
            .put("amountSats", payload.amountSats)
            .put("requesterLightningAddress", payload.requesterLightningAddress)
            .put("note", payload.note)
            .toString()

        return sendEncodedMessage(
            lightningAddress = lightningAddress,
            plaintext = body,
            messageType = "payment_request",
            attachmentIds = null,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun sendPaymentRequestPaidMessage(
        lightningAddress: String,
        payload: PaymentRequestPaidMessagePayload,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessageSendResult {
        val body = JSONObject()
            .put("requestMessageId", payload.requestMessageId)
            .put("invoice", payload.invoice)
            .put("paidAt", payload.paidAtIso)
            .toString()

        return sendEncodedMessage(
            lightningAddress = lightningAddress,
            plaintext = body,
            messageType = "payment_request_paid",
            attachmentIds = null,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun sendAttachmentMessage(
        lightningAddress: String,
        fileData: ByteArray,
        fileName: String,
        mimeType: String,
        imageWidth: Int?,
        imageHeight: Int?,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessageSendResult {
        return sendAttachmentMessage(
            lightningAddress = lightningAddress,
            fileData = fileData,
            fileName = fileName,
            mimeType = mimeType,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            authManager = authManager,
            walletManager = walletManager,
            draft = null
        )
    }

    private suspend fun sendAttachmentMessage(
        lightningAddress: String,
        fileData: ByteArray,
        fileName: String,
        mimeType: String,
        imageWidth: Int?,
        imageHeight: Int?,
        authManager: AuthManager,
        walletManager: WalletManager,
        draft: OutgoingMessageDraft?
    ): MessageSendResult {
        require(fileData.isNotEmpty()) { "Attachment data is empty." }

        val senderBinding = ensureMessagingBinding(authManager, walletManager)
        val recipient = resolveRecipient(lightningAddress.trim().lowercase(), authManager, walletManager)
        val encryptedAttachment = messageCrypto.encryptBytes(
            plaintextBytes = fileData,
            recipientMessagingPubkeyHex = recipient.messagingPubkey
        )

        val attachmentRecord = uploadEncryptedAttachment(
            recipient = recipient,
            fileName = "${UUID.randomUUID().toString().lowercase()}.bin",
            fileData = encryptedAttachment.ciphertextBase64.fromBase64(),
            authManager = authManager,
            walletManager = walletManager
        )

        attachmentManager.cacheAttachment(
            attachmentId = attachmentRecord.attachmentId,
            fileName = fileName,
            plaintextData = fileData
        )

        val payloadBody = JSONObject()
            .put("attachmentId", attachmentRecord.attachmentId)
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("sizeBytes", fileData.size)
            .put("imageWidth", imageWidth)
            .put("imageHeight", imageHeight)
            .put("attachmentNonce", encryptedAttachment.nonceBase64)
            .put("attachmentSenderEphemeralPubkey", encryptedAttachment.senderEphemeralPubkeyHex)
            .toString()

        return sendResolvedEncodedMessage(
            senderBinding = senderBinding,
            recipient = recipient,
            plaintext = payloadBody,
            messageType = "attachment",
            attachmentIds = listOf(attachmentRecord.attachmentId),
            authManager = authManager,
            walletManager = walletManager,
            draft = draft
        )
    }

    suspend fun sendReactionMessage(
        lightningAddress: String,
        targetMessageId: String,
        reactionKind: MessageReactionKind,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessageSendResult {
        val body = JSONObject()
            .put("targetMessageId", targetMessageId)
            .put("reactionKey", reactionKind.rawValue)
            .toString()

        return sendEncodedMessage(
            lightningAddress = lightningAddress,
            plaintext = body,
            messageType = "reaction",
            attachmentIds = null,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun resendStoredMessage(
        storedMessage: StoredMessage,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessageSendResult? {
        val resentMessage = resendStoredMessageIfNeeded(
            storedMessage = storedMessage,
            authManager = authManager,
            walletManager = walletManager
        ) ?: return null

        messageStore.replaceOutgoingMessage(
            storedMessageId = storedMessage.id,
            replacement = resentMessage.storedMessage
        )
        return resentMessage
    }

    fun conversationPreviews(searchQuery: String = ""): List<MessageConversationPreview> {
        return messageStore.conversationPreviews(searchQuery)
    }

    fun messagesForConversation(conversationId: String): List<StoredMessage> {
        return messageStore.messagesForConversation(conversationId)
    }

    fun recipientMetadata(conversationId: String): MessageRecipientMetadata? {
        return messageStore.recipientMetadata(conversationId)
    }

    fun markConversationAsRead(conversationId: String) {
        messageStore.markConversationAsRead(conversationId)
    }

    fun deleteConversation(conversationId: String) {
        messageStore.deleteConversation(conversationId)
    }

    fun hasCachedAttachment(payload: AttachmentMessagePayload): Boolean {
        return attachmentManager.hasCachedAttachment(payload)
    }

    fun cachedAttachmentData(payload: AttachmentMessagePayload): ByteArray? {
        return attachmentManager.cachedAttachmentData(payload)
    }

    suspend fun prepareAttachmentData(
        payload: AttachmentMessagePayload,
        shouldMarkReceived: Boolean,
        authManager: AuthManager,
        walletManager: WalletManager
    ): ByteArray {
        attachmentManager.cachedAttachmentData(payload)?.let { return it }

        val response = fetchAttachmentBytes(payload.attachmentId, authManager, walletManager)
        val decrypted = messageCrypto.decryptBytes(
            ciphertextBase64 = response.body.toBase64(),
            nonceBase64 = payload.attachmentNonce,
            senderEphemeralPubkeyHex = payload.attachmentSenderEphemeralPubkey,
            recipientPrivateKeyHex = messageKeyManager.currentMessagingPrivateKeyHex()
        )
        attachmentManager.cacheAttachment(
            attachmentId = payload.attachmentId,
            fileName = payload.fileName,
            plaintextData = decrypted
        )

        if (shouldMarkReceived) {
            acknowledgeAttachmentReceipt(listOf(payload.attachmentId), authManager, walletManager)
        }

        return decrypted
    }

    fun clearAll() {
        messageStore.clearAll()
        attachmentManager.clearAll()
        messageKeyManager.clearStoredMessagingKey()
        _uiState.value = MessagingUiState()
        lastSuccessfulSyncAtMillis = null
    }

    suspend fun syncPaidPaymentRequestStatus(
        originalRequest: StoredMessage,
        invoice: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        val alreadyHasMarker = messageStore.messagesForConversation(originalRequest.conversationId).any { message ->
            message.messageType == "payment_request_paid" &&
                MessagePayloadCodec.decodePaymentRequestPaid(message.body)?.requestMessageId == originalRequest.id
        }
        if (alreadyHasMarker) return

        sendPaymentRequestPaidMessage(
            lightningAddress = originalRequest.recipientLightningAddress,
            payload = PaymentRequestPaidMessagePayload(
                requestMessageId = originalRequest.id,
                invoice = invoice,
                paidAtIso = millisToIsoString(System.currentTimeMillis())
            ),
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun syncPaidPaymentRequestStatuses(
        transactions: List<WalletTransactionRow>,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        val paidInvoices = transactions
            .filter { it.direction == "received" && it.status.equals("Completed", ignoreCase = true) }
            .mapNotNull { transaction -> transaction.invoice?.trim()?.takeIf { it.isNotEmpty() } }
            .toSet()

        if (paidInvoices.isEmpty()) return

        val outgoingRequests = messageStore.messages.value
            .filter { !it.isIncoming && it.messageType == "payment_request" }

        for (requestMessage in outgoingRequests) {
            val payload = MessagePayloadCodec.decodePaymentRequest(requestMessage.body) ?: continue
            val normalizedInvoice = payload.invoice.trim()
            if (normalizedInvoice.isEmpty() || normalizedInvoice !in paidInvoices) continue

            val alreadyHasMarker = messageStore.messagesForConversation(requestMessage.conversationId).any { message ->
                message.messageType == "payment_request_paid" &&
                    MessagePayloadCodec.decodePaymentRequestPaid(message.body)?.requestMessageId == requestMessage.id
            }
            if (alreadyHasMarker) continue

            sendPaymentRequestPaidMessage(
                lightningAddress = requestMessage.recipientLightningAddress,
                payload = PaymentRequestPaidMessagePayload(
                    requestMessageId = requestMessage.id,
                    invoice = normalizedInvoice,
                    paidAtIso = millisToIsoString(System.currentTimeMillis())
                ),
                authManager = authManager,
                walletManager = walletManager
            )
        }
    }

    private suspend fun resolveRecipient(
        lightningAddress: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessagingRecipient {
        authManager.ensureSession(walletManager)

        val requestBody = JSONObject()
            .put("lightningAddress", lightningAddress)

        var response = httpClient.postJson("/messaging/v3/directory/lookup", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/messaging/v3/directory/lookup", requestBody.toString())
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        val rootJson = JSONObject(response.body)
        val recipientJson = rootJson.getJSONObject("recipient")
        val directoryJson = rootJson.getJSONObject("directory")
        val recipient = MessagingRecipient(
            walletPubkey = recipientJson.getString("walletPubkey"),
            lightningAddress = recipientJson.getString("lightningAddress"),
            messagingPubkey = recipientJson.getString("messagingPubkey"),
            messagingIdentitySignature = recipientJson.getString("messagingIdentitySignature"),
            messagingIdentitySignatureVersion = recipientJson.getInt("messagingIdentitySignatureVersion"),
            messagingIdentitySignedAtMillis = isoStringToMillis(recipientJson.getString("messagingIdentitySignedAt"))
                ?: throw IllegalStateException("Recipient messaging signature timestamp is missing."),
            profilePicUrl = recipientJson.optString("profilePicUrl").ifBlank { null }
        )
        val directory = directoryJson.toDirectoryProofPayload()
        MessageBindingVerifier.verifyRecipientBinding(recipient)
        MessageDirectoryVerifier.verifyDirectoryProof(recipient.identityBindingPayload, directory)
        messageKeyManager.storeDirectoryCheckpointIfNewer(directory.checkpoint)
        messageStore.upsertRecipientMetadata(
            listOf(
                MessageRecipientMetadata(
                    conversationId = recipient.walletPubkey,
                    lightningAddress = recipient.lightningAddress,
                    profilePicUrl = recipient.profilePicUrl
                )
            )
        )
        return recipient
    }

    private suspend fun ensureMessagingBinding(
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessagingIdentityBindingPayload {
        val registration = messageKeyManager.ensureRegistered(authManager, walletManager)
        return registration.identityBindingPayload
            ?: throw IllegalStateException("Invalid messaging key registration response.")
    }

    private suspend fun sendEncodedMessage(
        lightningAddress: String,
        plaintext: String,
        messageType: String,
        attachmentIds: List<String>?,
        authManager: AuthManager,
        walletManager: WalletManager,
        draft: OutgoingMessageDraft? = null
    ): MessageSendResult {
        val normalizedLightningAddress = lightningAddress.trim().lowercase()
        require(normalizedLightningAddress.isNotEmpty()) { "Enter a Lightning Address to continue." }

        val senderBinding = ensureMessagingBinding(authManager, walletManager)
        val recipient = resolveRecipient(normalizedLightningAddress, authManager, walletManager)
        return sendResolvedEncodedMessage(
            senderBinding = senderBinding,
            recipient = recipient,
            plaintext = plaintext,
            messageType = messageType,
            attachmentIds = attachmentIds,
            authManager = authManager,
            walletManager = walletManager,
            draft = draft
        )
    }

    private suspend fun sendResolvedEncodedMessage(
        senderBinding: MessagingIdentityBindingPayload,
        recipient: MessagingRecipient,
        plaintext: String,
        messageType: String,
        attachmentIds: List<String>?,
        authManager: AuthManager,
        walletManager: WalletManager,
        draft: OutgoingMessageDraft? = null
    ): MessageSendResult {
        val effectiveDraft = draft ?: OutgoingMessageDraft(
            clientMessageId = UUID.randomUUID().toString().lowercase(),
            createdAtMillis = System.currentTimeMillis()
        )
        val createdAtMillis = effectiveDraft.createdAtMillis
        val clientMessageId = effectiveDraft.clientMessageId
        val sameKeyRetryCount = effectiveDraft.sameKeyRetryCount
        var currentRecipient = recipient
        var encryptedPayload = messageCrypto.encrypt(
            plaintext = buildSealedSenderPayloadString(
                plaintext = plaintext,
                messageType = messageType,
                senderBinding = senderBinding,
                recipient = currentRecipient,
                createdAtClientMs = createdAtMillis,
                envelopeVersion = 3,
                clientMessageId = clientMessageId,
                messageKeyManager = messageKeyManager,
                walletManager = walletManager
            ),
            recipientMessagingPubkeyHex = currentRecipient.messagingPubkey
        )
        val responseJson = try {
            sendEnvelope(
                recipient = currentRecipient,
                clientMessageId = clientMessageId,
                createdAtMillis = createdAtMillis,
                encryptedPayload = encryptedPayload,
                messageType = messageType,
                attachmentIds = attachmentIds,
                sameKeyRetryCount = sameKeyRetryCount,
                authManager = authManager,
                walletManager = walletManager
            )
        } catch (_: RecipientBindingStaleException) {
            if (!attachmentIds.isNullOrEmpty()) {
                throw IllegalStateException("Recipient messaging binding is stale. Resolve again.")
            }

            currentRecipient = resolveRecipient(
                lightningAddress = recipient.lightningAddress,
                authManager = authManager,
                walletManager = walletManager
            )
            encryptedPayload = messageCrypto.encrypt(
                plaintext = buildSealedSenderPayloadString(
                    plaintext = plaintext,
                    messageType = messageType,
                    senderBinding = senderBinding,
                    recipient = currentRecipient,
                    createdAtClientMs = createdAtMillis,
                    envelopeVersion = 3,
                    clientMessageId = clientMessageId,
                    messageKeyManager = messageKeyManager,
                    walletManager = walletManager
                ),
                recipientMessagingPubkeyHex = currentRecipient.messagingPubkey
            )
            sendEnvelope(
                recipient = currentRecipient,
                clientMessageId = clientMessageId,
                createdAtMillis = createdAtMillis,
                encryptedPayload = encryptedPayload,
                messageType = messageType,
                attachmentIds = attachmentIds,
                sameKeyRetryCount = sameKeyRetryCount,
                authManager = authManager,
                walletManager = walletManager
            )
        }
        val sentMessageJson = responseJson.optJSONObject("message")
        val outgoingMessage = StoredMessage(
            id = sentMessageJson?.optString("messageId")?.ifBlank { clientMessageId } ?: clientMessageId,
            conversationId = currentRecipient.walletPubkey,
            clientMessageId = clientMessageId,
            body = plaintext,
            createdAtMillis = createdAtMillis,
            isIncoming = false,
            isRead = true,
            senderWalletPubkey = senderBinding.walletPubkey,
            senderMessagingPubkey = senderBinding.messagingPubkey,
            senderLightningAddress = senderBinding.lightningAddress,
            recipientWalletPubkey = currentRecipient.walletPubkey,
            recipientMessagingPubkey = currentRecipient.messagingPubkey,
            recipientLightningAddress = currentRecipient.lightningAddress,
            messageType = messageType
        )

        messageStore.upsert(listOf(outgoingMessage))

        return MessageSendResult(
            conversationId = currentRecipient.walletPubkey,
            conversationTitle = currentRecipient.lightningAddress.toConversationHandle(),
            lightningAddress = currentRecipient.lightningAddress,
            storedMessage = outgoingMessage
        )
    }

    private suspend fun sendEnvelope(
        recipient: MessagingRecipient,
        clientMessageId: String,
        createdAtMillis: Long,
        encryptedPayload: MessageEnvelopePayload,
        messageType: String,
        attachmentIds: List<String>?,
        sameKeyRetryCount: Int?,
        authManager: AuthManager,
        walletManager: WalletManager
    ): JSONObject {
        val requestBody = JSONObject()
            .put("clientMessageId", clientMessageId)
            .put("recipient", recipient.identityBindingPayload.toJson())
            .put("ciphertext", encryptedPayload.ciphertextBase64)
            .put("nonce", encryptedPayload.nonceBase64)
            .put("senderEphemeralPubkey", encryptedPayload.senderEphemeralPubkeyHex)
            .put("createdAtClientMs", createdAtMillis)
            .put("envelopeVersion", encryptedPayload.envelopeVersion)
            .put("messageType", messageType)

        if (!attachmentIds.isNullOrEmpty()) {
            val array = JSONArray().apply { attachmentIds.forEach { put(it) } }
            requestBody.put("attachmentIds", array)
        }

        sameKeyRetryCount?.let { requestBody.put("sameKeyRetryCount", it) }

        var response = httpClient.postJson("/messaging/v3/send", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/messaging/v3/send", requestBody.toString())
        }

        if (response.statusCode == 409 && isRecipientBindingStale(response.body)) {
            throw RecipientBindingStaleException()
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        return JSONObject(response.body)
    }

    private suspend fun fetchInboxMessages(
        authManager: AuthManager,
        walletManager: WalletManager
    ): List<InboxMessage> {
        authManager.ensureSession(walletManager)

        var response = httpClient.get("/messaging/v3/inbox")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get("/messaging/v3/inbox")
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        val root = JSONObject(response.body)
        val messageArray = root.optJSONArray("messages") ?: JSONArray()
        return buildList {
            for (index in 0 until messageArray.length()) {
                val json = messageArray.getJSONObject(index)
                add(
                    InboxMessage(
                        messageId = json.getString("messageId"),
                        clientMessageId = json.getString("clientMessageId"),
                        senderWalletPubkey = json.getString("senderWalletPubkey"),
                        senderMessagingPubkey = json.getString("senderMessagingPubkey"),
                        senderLightningAddress = json.optString("senderLightningAddress").ifBlank { null },
                        senderMessagingIdentitySignature = json.optString("senderMessagingIdentitySignature").ifBlank { null },
                        senderMessagingIdentitySignatureVersion = json.optInt("senderMessagingIdentitySignatureVersion").takeIf { it != 0 },
                        senderMessagingIdentitySignedAtMillis = isoStringToMillis(
                            json.optString("senderMessagingIdentitySignedAt").ifBlank { null }
                        ),
                        senderEnvelopeSignature = json.optString("senderEnvelopeSignature").ifBlank { null },
                        senderEnvelopeSignatureVersion = json.optInt("senderEnvelopeSignatureVersion").takeIf { it != 0 },
                        recipientWalletPubkey = json.getString("recipientWalletPubkey"),
                        recipientMessagingPubkey = json.getString("recipientMessagingPubkey"),
                        recipientLightningAddress = json.getString("recipientLightningAddress"),
                        messageType = json.optString("messageType", "text").ifBlank { "text" },
                        envelopeVersion = json.optInt("envelopeVersion", 1),
                        ciphertext = json.optString("ciphertext").ifBlank { null },
                        nonce = json.optString("nonce").ifBlank { null },
                        senderEphemeralPubkey = json.optString("senderEphemeralPubkey").ifBlank { null },
                        status = json.optString("status", "pending"),
                        createdAtMillis = isoStringToMillis(json.optString("createdAt").ifBlank { null }),
                        createdAtClientMillis = isoStringToMillis(json.optString("createdAtClient").ifBlank { null }),
                        expiresAtMillis = isoStringToMillis(json.optString("expiresAt").ifBlank { null }),
                        deliveredAtMillis = isoStringToMillis(json.optString("deliveredAt").ifBlank { null }),
                        rekeyRequiredAtMillis = isoStringToMillis(json.optString("rekeyRequiredAt").ifBlank { null }),
                        expiredAtMillis = isoStringToMillis(json.optString("expiredAt").ifBlank { null })
                    )
                )
            }
        }
    }

    private suspend fun fetchOutgoingMessageStatuses(
        authManager: AuthManager,
        walletManager: WalletManager
    ): List<OutgoingMessageStatus> {
        authManager.ensureSession(walletManager)

        var response = httpClient.get("/messaging/v3/outgoing-statuses?limit=200")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get("/messaging/v3/outgoing-statuses?limit=200")
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        val root = JSONObject(response.body)
        val messageArray = root.optJSONArray("messages") ?: JSONArray()
        return buildList {
            for (index in 0 until messageArray.length()) {
                val json = messageArray.getJSONObject(index)
                add(
                    OutgoingMessageStatus(
                        messageId = json.getString("messageId"),
                        clientMessageId = json.getString("clientMessageId"),
                        recipientLightningAddress = json.optString("recipientLightningAddress").ifBlank { null },
                        recipientWalletPubkey = json.optString("recipientWalletPubkey").ifBlank { null },
                        status = json.optString("status", "pending"),
                        sameKeyRetryCount = json.optInt("sameKeyRetryCount", 0),
                        createdAtMillis = isoStringToMillis(json.optString("createdAt").ifBlank { null }),
                        deliveredAtMillis = isoStringToMillis(json.optString("deliveredAt").ifBlank { null }),
                        rekeyRequiredAtMillis = isoStringToMillis(json.optString("rekeyRequiredAt").ifBlank { null }),
                        sameKeyDecryptFailedAtMillis = isoStringToMillis(
                            json.optString("sameKeyDecryptFailedAt").ifBlank { null }
                        ),
                        failedAtMillis = isoStringToMillis(json.optString("failedAt").ifBlank { null }),
                        expiredAtMillis = isoStringToMillis(json.optString("expiredAt").ifBlank { null })
                    )
                )
            }
        }
    }

    private suspend fun refreshRecipientMetadataForMessages(
        messages: List<StoredMessage>,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        val candidates = messages
            .mapNotNull { message ->
                val conversationId = message.conversationId.trim()
                val lightningAddress = if (message.isIncoming) {
                    message.senderLightningAddress
                } else {
                    message.recipientLightningAddress
                }?.trim()?.ifBlank { null }

                if (conversationId.isBlank() || lightningAddress.isNullOrBlank()) {
                    null
                } else {
                    MessageRecipientMetadata(
                        conversationId = conversationId,
                        lightningAddress = lightningAddress,
                        profilePicUrl = null
                    )
                }
            }
            .distinctBy { it.conversationId }

        if (candidates.isEmpty()) return

        val metadataItems = buildList {
            candidates.forEach { candidate ->
                val existing = messageStore.recipientMetadata(candidate.conversationId)
                if (existing?.profilePicUrl != null && existing.lightningAddress == candidate.lightningAddress) {
                    return@forEach
                }

                val refreshed = runCatching {
                    resolveRecipient(candidate.lightningAddress ?: return@runCatching null, authManager, walletManager)
                }.getOrNull()

                add(
                    MessageRecipientMetadata(
                        conversationId = candidate.conversationId,
                        lightningAddress = refreshed?.lightningAddress ?: candidate.lightningAddress,
                        profilePicUrl = refreshed?.profilePicUrl ?: existing?.profilePicUrl
                    )
                )
            }
        }

        messageStore.upsertRecipientMetadata(metadataItems)
    }

    private fun decryptInboxMessage(inboxMessage: InboxMessage): StoredMessage? {
        return if (inboxMessage.envelopeVersion >= 3) {
            decryptSealedInboxMessage(inboxMessage)
        } else {
            decryptLegacyInboxMessage(inboxMessage)
        }
    }

    private fun decryptSealedInboxMessage(inboxMessage: InboxMessage): StoredMessage {
        val plaintext = decryptInboxCiphertext(inboxMessage)
        val sealedPayload = try {
            plaintext.toSealedSenderMessagePayload()
        } catch (error: Exception) {
            throw RecoverableInboxMessageException(
                reason = InboxRecoveryFailureReason.SEALED_PAYLOAD_DECODE_FAILED,
                message = "Inbox message payload decode failed.",
                cause = error
            )
        }
        try {
            MessageBindingVerifier.verifySealedIncomingEnvelope(inboxMessage, sealedPayload)
        } catch (error: Exception) {
            throw RecoverableInboxMessageException(
                reason = InboxRecoveryFailureReason.SEALED_ENVELOPE_VERIFICATION_FAILED,
                message = "Inbox sealed message verification failed.",
                cause = error
            )
        }
        return StoredMessage(
            id = inboxMessage.messageId,
            conversationId = sealedPayload.sender.walletPubkey,
            clientMessageId = inboxMessage.clientMessageId,
            body = sealedPayload.body,
            createdAtMillis = inboxMessage.createdAtClientMillis
                ?: inboxMessage.createdAtMillis
                ?: System.currentTimeMillis(),
            isIncoming = true,
            isRead = false,
            senderWalletPubkey = sealedPayload.sender.walletPubkey,
            senderMessagingPubkey = sealedPayload.sender.messagingPubkey,
            senderLightningAddress = sealedPayload.sender.lightningAddress,
            recipientWalletPubkey = inboxMessage.recipientWalletPubkey,
            recipientMessagingPubkey = inboxMessage.recipientMessagingPubkey,
            recipientLightningAddress = inboxMessage.recipientLightningAddress,
            messageType = inboxMessage.messageType
        )
    }

    private fun decryptLegacyInboxMessage(inboxMessage: InboxMessage): StoredMessage {
        try {
            MessageBindingVerifier.verifyIncomingEnvelope(inboxMessage)
        } catch (error: Exception) {
            throw RecoverableInboxMessageException(
                reason = InboxRecoveryFailureReason.SEALED_ENVELOPE_VERIFICATION_FAILED,
                message = "Inbox legacy message verification failed.",
                cause = error
            )
        }
        val plaintext = decryptInboxCiphertext(inboxMessage)
        return StoredMessage(
            id = inboxMessage.messageId,
            conversationId = inboxMessage.senderWalletPubkey,
            clientMessageId = inboxMessage.clientMessageId,
            body = plaintext,
            createdAtMillis = inboxMessage.createdAtClientMillis
                ?: inboxMessage.createdAtMillis
                ?: System.currentTimeMillis(),
            isIncoming = true,
            isRead = false,
            senderWalletPubkey = inboxMessage.senderWalletPubkey,
            senderMessagingPubkey = inboxMessage.senderMessagingPubkey,
            senderLightningAddress = inboxMessage.senderLightningAddress,
            recipientWalletPubkey = inboxMessage.recipientWalletPubkey,
            recipientMessagingPubkey = inboxMessage.recipientMessagingPubkey,
            recipientLightningAddress = inboxMessage.recipientLightningAddress,
            messageType = inboxMessage.messageType
        )
    }

    private fun decryptInboxCiphertext(inboxMessage: InboxMessage): String {
        val ciphertext = inboxMessage.ciphertext
            ?: throw RecoverableInboxMessageException(
                reason = InboxRecoveryFailureReason.CIPHERTEXT_DECRYPT_FAILED,
                message = "Inbox message ciphertext is missing."
            )
        val nonce = inboxMessage.nonce
            ?: throw RecoverableInboxMessageException(
                reason = InboxRecoveryFailureReason.CIPHERTEXT_DECRYPT_FAILED,
                message = "Inbox message nonce is missing."
            )
        val senderEphemeralPubkey = inboxMessage.senderEphemeralPubkey
            ?: throw RecoverableInboxMessageException(
                reason = InboxRecoveryFailureReason.CIPHERTEXT_DECRYPT_FAILED,
                message = "Inbox sender ephemeral pubkey is missing."
            )
        return try {
            messageCrypto.decrypt(
                ciphertextBase64 = ciphertext,
                nonceBase64 = nonce,
                senderEphemeralPubkeyHex = senderEphemeralPubkey,
                recipientPrivateKeyHex = messageKeyManager.currentMessagingPrivateKeyHex()
            )
        } catch (error: Exception) {
            val reason = if (error.message.orEmpty().lowercase().contains("stored messaging key is invalid")) {
                InboxRecoveryFailureReason.INVALID_STORED_KEY
            } else {
                InboxRecoveryFailureReason.CIPHERTEXT_DECRYPT_FAILED
            }
            throw RecoverableInboxMessageException(
                reason = reason,
                message = "Inbox message decrypt failed.",
                cause = error
            )
        }
    }

    private fun shouldRequestRekey(
        inboxMessage: InboxMessage,
        currentMessagingPubkeyHex: String?
    ): Boolean {
        val normalizedCurrentMessagingPubkey = currentMessagingPubkeyHex
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val normalizedMessageRecipientMessagingPubkey = inboxMessage.recipientMessagingPubkey
            .trim()
            .lowercase()
        return normalizedMessageRecipientMessagingPubkey != normalizedCurrentMessagingPubkey
    }

    private fun shouldMarkDecryptFailed(
        inboxMessage: InboxMessage,
        currentMessagingPubkeyHex: String?
    ): Boolean {
        val normalizedCurrentMessagingPubkey = currentMessagingPubkeyHex
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val normalizedMessageRecipientMessagingPubkey = inboxMessage.recipientMessagingPubkey
            .trim()
            .lowercase()
        return normalizedMessageRecipientMessagingPubkey == normalizedCurrentMessagingPubkey
    }

    private suspend fun retryRekeyRequiredOutgoingMessages(
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        if (messageStore.messages.value.none { message -> !message.isIncoming }) {
            return
        }

        val statuses = runCatching {
            fetchOutgoingMessageStatuses(authManager, walletManager)
        }.getOrElse { error ->
            println("Failed to fetch outgoing messaging statuses: ${error.localizedMessage}")
            return
        }

        statuses.forEach { status ->
            val localMessage = messageStore.outgoingMessage(
                messageId = status.messageId,
                clientMessageId = status.clientMessageId
            ) ?: return@forEach

            when (status.status) {
                "rekey_required" -> {
                    runCatching {
                        val resentMessage = resendStoredMessageIfNeeded(
                            storedMessage = localMessage,
                            authManager = authManager,
                            walletManager = walletManager
                        ) ?: return@runCatching

                        messageStore.replaceOutgoingMessage(
                            storedMessageId = localMessage.id,
                            replacement = resentMessage.storedMessage
                        )
                    }.onFailure { error ->
                        println(
                            "Failed to resend rekey-required message ${status.messageId}: ${error.localizedMessage}"
                        )
                    }
                }

                "same_key_retry_required" -> {
                    runCatching {
                        val resentMessage = resendStoredMessageIfNeeded(
                            storedMessage = localMessage,
                            authManager = authManager,
                            walletManager = walletManager,
                            sameKeyRetryCount = 1
                        ) ?: return@runCatching

                        messageStore.replaceOutgoingMessage(
                            storedMessageId = localMessage.id,
                            replacement = resentMessage.storedMessage
                        )
                    }.onFailure { error ->
                        println(
                            "Failed to resend same-key retry-required message ${status.messageId}: ${error.localizedMessage}"
                        )
                    }
                }

                "failed_same_key", "undelivered" -> {
                    runCatching {
                        messageStore.updateOutgoingDeliveryState(
                            messageId = status.messageId,
                            clientMessageId = status.clientMessageId,
                            deliveryState = DELIVERY_STATE_FAILED_SAME_KEY
                        )
                    }.onFailure { error ->
                        println(
                            "Failed to mark message ${status.messageId} as failed: ${error.localizedMessage}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun resendStoredMessageIfNeeded(
        storedMessage: StoredMessage,
        authManager: AuthManager,
        walletManager: WalletManager,
        sameKeyRetryCount: Int? = null
    ): MessageSendResult? {
        if (storedMessage.isIncoming) {
            return null
        }

        val retryDraft = OutgoingMessageDraft(
            clientMessageId = UUID.randomUUID().toString().lowercase(),
            createdAtMillis = storedMessage.createdAtMillis,
            sameKeyRetryCount = sameKeyRetryCount
        )

        if (storedMessage.messageType == "attachment") {
            val payload = MessagePayloadCodec.decodeAttachmentPayload(storedMessage.body) ?: return null
            val fileData = attachmentManager.cachedAttachmentData(payload) ?: return null

            return sendAttachmentMessage(
                lightningAddress = storedMessage.recipientLightningAddress,
                fileData = fileData,
                fileName = payload.fileName,
                mimeType = payload.mimeType,
                imageWidth = payload.imageWidth,
                imageHeight = payload.imageHeight,
                authManager = authManager,
                walletManager = walletManager,
                draft = retryDraft
            )
        }

        return sendEncodedMessage(
            lightningAddress = storedMessage.recipientLightningAddress,
            plaintext = storedMessage.body,
            messageType = storedMessage.messageType,
            attachmentIds = null,
            authManager = authManager,
            walletManager = walletManager,
            draft = retryDraft
        )
    }

    private suspend fun uploadEncryptedAttachment(
        recipient: MessagingRecipient,
        fileName: String,
        fileData: ByteArray,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessagingAttachmentRecord {
        authManager.ensureSession(walletManager)

        var response = httpClient.postMultipart(
            path = "/messaging/v2/attachments/upload",
            formFields = recipient.identityBindingPayload.toMultipartFields(),
            fileFieldName = "attachment",
            fileName = fileName,
            mimeType = "application/octet-stream",
            fileData = fileData
        )
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postMultipart(
                path = "/messaging/v2/attachments/upload",
                formFields = recipient.identityBindingPayload.toMultipartFields(),
                fileFieldName = "attachment",
                fileName = fileName,
                mimeType = "application/octet-stream",
                fileData = fileData
            )
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        val attachmentJson = JSONObject(response.body).getJSONObject("attachment")
        return MessagingAttachmentRecord(
            attachmentId = attachmentJson.getString("attachmentId"),
            recipientLightningAddress = attachmentJson.getString("recipientLightningAddress"),
            sizeBytes = attachmentJson.optInt("sizeBytes", 0),
            uploadContentType = attachmentJson.optString("uploadContentType", "application/octet-stream"),
            status = attachmentJson.optString("status", "uploaded"),
            expiresAtMillis = isoStringToMillis(attachmentJson.optString("expiresAt").ifBlank { null }),
            linkedMessageId = attachmentJson.optString("linkedMessageId").ifBlank { null },
            receivedAtMillis = isoStringToMillis(attachmentJson.optString("receivedAt").ifBlank { null }),
            deletedAtMillis = isoStringToMillis(attachmentJson.optString("deletedAt").ifBlank { null })
        )
    }

    private suspend fun fetchAttachmentBytes(
        attachmentId: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): BinaryHttpResponse {
        authManager.ensureSession(walletManager)

        var response = httpClient.getBytes("/messaging/attachments/$attachmentId/download")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.getBytes("/messaging/attachments/$attachmentId/download")
        }

        if (response.statusCode !in 200..299) {
            val bodyText = response.body.toString(Charsets.UTF_8)
            throw IllegalStateException(extractServerError(bodyText, response.statusCode))
        }

        return response
    }

    private suspend fun acknowledgeAttachmentReceipt(
        attachmentIds: List<String>,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        if (attachmentIds.isEmpty()) return
        val idArray = JSONArray().apply { attachmentIds.forEach { put(it) } }
        val requestBody = JSONObject().put("attachmentIds", idArray)
        var response = httpClient.postJson("/messaging/attachments/mark-received", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/messaging/attachments/mark-received", requestBody.toString())
        }
        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }
    }

    private suspend fun acknowledgeMessages(
        messageIds: List<String>,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        if (messageIds.isEmpty()) return

        val messageIdArray = JSONArray().apply {
            messageIds.forEach { put(it) }
        }
        val requestBody = JSONObject().put("messageIds", messageIdArray)
        var response = httpClient.postJson("/messaging/v3/ack", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/messaging/v3/ack", requestBody.toString())
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }
    }

    private suspend fun markMessagesRekeyRequired(
        messageIds: List<String>,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        if (messageIds.isEmpty()) return

        val messageIdArray = JSONArray().apply {
            messageIds.forEach { put(it) }
        }
        val requestBody = JSONObject().put("messageIds", messageIdArray)
        var response = httpClient.postJson("/messaging/v3/rekey-required", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/messaging/v3/rekey-required", requestBody.toString())
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }
    }

    private suspend fun markMessagesDecryptFailed(
        failureReasons: Map<String, String>,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        val messageIds = failureReasons.keys.toList()
        if (messageIds.isEmpty()) return

        val messageIdArray = JSONArray().apply {
            messageIds.forEach { put(it) }
        }
        val reasonJson = JSONObject().apply {
            failureReasons.forEach { (messageId, reason) ->
                put(messageId, reason)
            }
        }
        val requestBody = JSONObject()
            .put("messageIds", messageIdArray)
            .put("failureReasons", reasonJson)
        var response = httpClient.postJson("/messaging/v3/decrypt-failed", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/messaging/v3/decrypt-failed", requestBody.toString())
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }
    }

    private fun extractServerError(body: String, statusCode: Int): String {
        return runCatching {
            val json = JSONObject(body)
            json.optString("error")
                .ifBlank { json.optString("message") }
                .ifBlank { "Server error ($statusCode)." }
        }.getOrDefault(body.ifBlank { "Server error ($statusCode)." })
    }

    private fun isRecipientBindingStale(body: String): Boolean {
        val normalized = body.lowercase()
        return normalized.contains("recipient messaging") &&
            normalized.contains("stale") &&
            normalized.contains("resolve again")
    }

    private companion object {
        const val DEFAULT_MINIMUM_SYNC_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_MINIMUM_OUTGOING_STATUS_SYNC_INTERVAL_MILLIS = 30_000L
    }

    private class RecipientBindingStaleException : Exception()

    private class RecoverableInboxMessageException(
        val reason: InboxRecoveryFailureReason,
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)
}

private suspend fun buildSealedSenderPayloadString(
    plaintext: String,
    messageType: String,
    senderBinding: MessagingIdentityBindingPayload,
    recipient: MessagingRecipient,
    createdAtClientMs: Long,
    envelopeVersion: Int,
    clientMessageId: String,
    messageKeyManager: MessageKeyManager,
    walletManager: WalletManager
): String {
    val messageSignatureVersion = 2
    val canonicalEnvelopeMessage = MessageBindingVerifier.buildMessagingEnvelopeSignatureMessage(
        version = messageSignatureVersion,
        clientMessageId = clientMessageId,
        senderBinding = senderBinding,
        recipientWalletPubkey = recipient.walletPubkey,
        recipientLightningAddress = recipient.lightningAddress,
        recipientMessagingPubkey = recipient.messagingPubkey,
        messageType = messageType,
        plaintext = plaintext,
        createdAtClientMs = createdAtClientMs,
        envelopeVersion = envelopeVersion
    )
    val signingCertificate = messageKeyManager.currentMessageSigningCertificate(
        senderBinding = senderBinding,
        walletManager = walletManager
    )
    val messageSignature = messageKeyManager.signMessageEnvelope(canonicalEnvelopeMessage)

    return JSONObject()
        .put("body", plaintext)
        .put("sender", senderBinding.toJson())
        .put("messagingSigningPubkey", signingCertificate.messagingSigningPubkey)
        .put("messagingSigningPubkeySignature", signingCertificate.messagingSigningPubkeySignature)
        .put("messagingSigningPubkeySignatureVersion", signingCertificate.messagingSigningPubkeySignatureVersion)
        .put("messagingSigningPubkeySignedAt", signingCertificate.messagingSigningPubkeySignedAt)
        .put("messageSignature", messageSignature)
        .put("messageSignatureVersion", messageSignatureVersion)
        .toString()
}

private fun MessagingIdentityBindingPayload.toJson(): JSONObject {
    return JSONObject()
        .put("walletPubkey", walletPubkey)
        .put("lightningAddress", lightningAddress)
        .put("messagingPubkey", messagingPubkey)
        .put("messagingIdentitySignature", messagingIdentitySignature)
        .put("messagingIdentitySignatureVersion", messagingIdentitySignatureVersion)
        .put("messagingIdentitySignedAt", messagingIdentitySignedAtSeconds)
}

private fun MessagingIdentityBindingPayload.toMultipartFields(): Map<String, String> {
    return mapOf(
        "walletPubkey" to walletPubkey,
        "lightningAddress" to lightningAddress,
        "messagingPubkey" to messagingPubkey,
        "messagingIdentitySignature" to messagingIdentitySignature,
        "messagingIdentitySignatureVersion" to messagingIdentitySignatureVersion.toString(),
        "messagingIdentitySignedAt" to messagingIdentitySignedAtSeconds.toString()
    )
}

private fun String.toSealedSenderMessagePayload(): SealedSenderMessagePayload {
    val json = JSONObject(this)
    val senderJson = json.getJSONObject("sender")
    return SealedSenderMessagePayload(
        body = json.getString("body"),
        sender = MessagingIdentityBindingPayload(
            walletPubkey = senderJson.getString("walletPubkey"),
            lightningAddress = senderJson.getString("lightningAddress"),
            messagingPubkey = senderJson.getString("messagingPubkey"),
            messagingIdentitySignature = senderJson.getString("messagingIdentitySignature"),
            messagingIdentitySignatureVersion = senderJson.getInt("messagingIdentitySignatureVersion"),
            messagingIdentitySignedAtSeconds = senderJson.getLong("messagingIdentitySignedAt")
        ),
        messagingSigningPubkey = json.getString("messagingSigningPubkey"),
        messagingSigningPubkeySignature = json.getString("messagingSigningPubkeySignature"),
        messagingSigningPubkeySignatureVersion = json.getInt("messagingSigningPubkeySignatureVersion"),
        messagingSigningPubkeySignedAt = json.getLong("messagingSigningPubkeySignedAt"),
        messageSignature = json.getString("messageSignature"),
        messageSignatureVersion = json.getInt("messageSignatureVersion")
    )
}

private fun JSONObject.toDirectoryProofPayload(): MessagingDirectoryProofPayload {
    val proofArray = optJSONArray("proof") ?: JSONArray()
    val proofNodes = buildList {
        for (index in 0 until proofArray.length()) {
            val nodeJson = proofArray.getJSONObject(index)
            add(
                MessagingDirectoryProofNode(
                    position = nodeJson.getString("position"),
                    hash = nodeJson.getString("hash")
                )
            )
        }
    }

    val checkpointJson = getJSONObject("checkpoint")
    return MessagingDirectoryProofPayload(
        leafIndex = optInt("leafIndex"),
        leafHash = getString("leafHash"),
        proof = proofNodes,
        checkpoint = MessagingDirectoryCheckpoint(
            rootHash = checkpointJson.getString("rootHash"),
            treeSize = checkpointJson.getInt("treeSize"),
            issuedAtMillis = isoStringToMillis(checkpointJson.getString("issuedAt"))
                ?: throw IllegalStateException("The messaging directory checkpoint timestamp is invalid.")
        )
    )
}

data class MessagingAttachmentRecord(
    val attachmentId: String,
    val recipientLightningAddress: String,
    val sizeBytes: Int,
    val uploadContentType: String,
    val status: String,
    val expiresAtMillis: Long?,
    val linkedMessageId: String?,
    val receivedAtMillis: Long?,
    val deletedAtMillis: Long?
)
