package com.split.android.data.messages

import android.content.SharedPreferences
import android.content.Context
import com.split.android.data.security.openEncryptedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class MessageStore private constructor(
    context: Context
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null

    private var messagesStoreUnavailable = false
    private var recipientMetadataStoreUnavailable = false

    private val _messages = MutableStateFlow(emptyList<StoredMessage>())
    val messages: StateFlow<List<StoredMessage>> = _messages.asStateFlow()
    private val _recipientMetadataByConversationId = MutableStateFlow(emptyMap<String, MessageRecipientMetadata>())
    val recipientMetadataByConversationId: StateFlow<Map<String, MessageRecipientMetadata>> =
        _recipientMetadataByConversationId.asStateFlow()

    init {
        _messages.value = loadMessages()
        _recipientMetadataByConversationId.value = loadRecipientMetadata()
    }

    val unreadMessageCount: Int
        get() {
            reloadMessagesIfNeeded()
            return _messages.value.count { it.isIncoming && !it.isRead }
        }

    val unreadConversationCount: Int
        get() {
            reloadMessagesIfNeeded()
            return _messages.value
                .filter { it.isIncoming && !it.isRead }
                .map { it.conversationId }
                .toSet()
                .size
        }

    fun upsert(incomingMessages: List<StoredMessage>): List<String> {
        if (incomingMessages.isEmpty()) return emptyList()

        val normalizedIncomingMessages = incomingMessages.map { message ->
            if (shouldMarkMessageRead(message)) {
                message.copy(isRead = true)
            } else {
                message
            }
        }
        val mergedById = _messages.value.associateBy { it.id }.toMutableMap()
        normalizedIncomingMessages.forEach { mergedById[it.id] = it }
        val merged = mergedById.values.sortedWith(compareBy<StoredMessage> { it.createdAtMillis }.thenBy { it.id })

        if (!persistMessagesIfPossible(merged)) {
            _messages.value = merged
            return emptyList()
        }

        _messages.value = merged
        return normalizedIncomingMessages.map { it.id }
    }

    fun messagesForConversation(conversationId: String): List<StoredMessage> {
        reloadMessagesIfNeeded()
        return _messages.value
            .filter { it.conversationId == conversationId }
            .sortedWith(compareBy<StoredMessage> { it.createdAtMillis }.thenBy { it.id })
    }

    fun conversationPreviews(searchQuery: String = ""): List<MessageConversationPreview> {
        reloadMessagesIfNeeded()
        reloadRecipientMetadataIfNeeded()
        val normalizedQuery = searchQuery.trim().lowercase()
        return _messages.value
            .groupBy { it.conversationId }
            .mapNotNull { (conversationId, conversationMessages) ->
                val latest = conversationMessages.maxWithOrNull(
                    compareBy<StoredMessage> { it.createdAtMillis }.thenBy { it.id }
                ) ?: return@mapNotNull null

                val lightningAddress = counterpartyLightningAddress(latest)
                val title = lightningAddress?.toConversationHandle()
                    ?: conversationId.take(12)

                val previewBody = MessagePayloadCodec.previewText(latest)
                if (normalizedQuery.isNotEmpty()) {
                    val matchesTitle = title.lowercase().contains(normalizedQuery)
                    val matchesBody = conversationMessages.any {
                        MessagePayloadCodec.previewText(it).lowercase().contains(normalizedQuery)
                    }
                    if (!matchesTitle && !matchesBody) {
                        return@mapNotNull null
                    }
                }

                MessageConversationPreview(
                    id = conversationId,
                    title = recipientMetadataByConversationId.value[conversationId]?.displayTitle ?: title,
                    lightningAddress = recipientMetadataByConversationId.value[conversationId]?.lightningAddress ?: lightningAddress,
                    profilePicUrl = recipientMetadataByConversationId.value[conversationId]?.profilePicUrl,
                    latestBody = previewBody,
                    latestAtMillis = latest.createdAtMillis,
                    hasUnreadMessages = conversationMessages.any { it.isIncoming && !it.isRead },
                    hasFailedOutgoingMessage = latest.hasFailedDelivery
                )
            }
            .sortedWith(compareByDescending<MessageConversationPreview> { it.latestAtMillis }.thenBy { it.id })
    }

    fun markConversationAsRead(conversationId: String) {
        reloadMessagesIfNeeded()
        val updated = _messages.value.map { message ->
            if (message.conversationId == conversationId && message.isIncoming && !message.isRead) {
                message.copy(isRead = true)
            } else {
                message
            }
        }

        if (updated != _messages.value) {
            persistMessagesIfPossible(updated)
            _messages.value = updated
            MessageNotificationManager.dismissConversationNotification(appContext, conversationId)
        }
    }

    fun deleteConversation(conversationId: String) {
        reloadMessagesIfNeeded()
        reloadRecipientMetadataIfNeeded()

        val filteredMessages = _messages.value.filterNot { it.conversationId == conversationId }
        if (filteredMessages.size == _messages.value.size) {
            return
        }

        val filteredRecipientMetadata = _recipientMetadataByConversationId.value - conversationId

        persistMessagesIfPossible(filteredMessages)
        if (filteredRecipientMetadata != _recipientMetadataByConversationId.value) {
            persistRecipientMetadataIfPossible(filteredRecipientMetadata)
        }

        _messages.value = filteredMessages
        _recipientMetadataByConversationId.value = filteredRecipientMetadata
        MessageNotificationManager.dismissConversationNotification(appContext, conversationId)
    }

    fun outgoingMessage(
        messageId: String,
        clientMessageId: String
    ): StoredMessage? {
        reloadMessagesIfNeeded()
        return _messages.value.firstOrNull { message ->
            !message.isIncoming && (message.id == messageId || message.clientMessageId == clientMessageId)
        }
    }

    fun replaceOutgoingMessage(
        storedMessageId: String,
        replacement: StoredMessage
    ) {
        reloadMessagesIfNeeded()
        val updated = _messages.value
            .filter { message -> message.id != storedMessageId && message.id != replacement.id }
            .plus(replacement)
            .sortedWith(compareBy<StoredMessage> { it.createdAtMillis }.thenBy { it.id })

        if (!persistMessagesIfPossible(updated)) {
            _messages.value = updated
            return
        }

        _messages.value = updated
    }

    fun updateOutgoingDeliveryState(
        messageId: String,
        clientMessageId: String,
        deliveryState: String?
    ): Boolean {
        reloadMessagesIfNeeded()

        var didChange = false
        val updated = _messages.value.map { message ->
            if (!message.isIncoming &&
                (message.id == messageId || message.clientMessageId == clientMessageId) &&
                message.deliveryState != deliveryState
            ) {
                didChange = true
                message.copy(deliveryState = deliveryState)
            } else {
                message
            }
        }

        if (!didChange) {
            return false
        }

        if (!persistMessagesIfPossible(updated)) {
            _messages.value = updated
            return true
        }

        _messages.value = updated
        return true
    }

    fun clearAll() {
        runCatching {
            preferences().edit()
                .remove(KEY_MESSAGES_JSON)
                .remove(KEY_RECIPIENT_METADATA_JSON)
                .apply()
            messagesStoreUnavailable = false
            recipientMetadataStoreUnavailable = false
        }.onFailure { error ->
            messagesStoreUnavailable = true
            recipientMetadataStoreUnavailable = true
            println("MessageStore: failed to clear encrypted message store without deleting files. ${error.localizedMessage}")
        }
        _messages.value = emptyList()
        _recipientMetadataByConversationId.value = emptyMap()
        MessageNotificationManager.clearAll(appContext)
    }

    fun recipientMetadata(conversationId: String): MessageRecipientMetadata? {
        reloadRecipientMetadataIfNeeded()
        return _recipientMetadataByConversationId.value[conversationId]
    }

    fun upsertRecipientMetadata(metadataItems: List<MessageRecipientMetadata>) {
        if (metadataItems.isEmpty()) return

        reloadRecipientMetadataIfNeeded()

        val merged = _recipientMetadataByConversationId.value.toMutableMap()
        metadataItems.forEach { item ->
            val normalizedAddress = item.lightningAddress?.trim()?.ifBlank { null }
            val existing = merged[item.conversationId]
            merged[item.conversationId] = MessageRecipientMetadata(
                conversationId = item.conversationId,
                lightningAddress = normalizedAddress ?: existing?.lightningAddress,
                profilePicUrl = item.profilePicUrl ?: existing?.profilePicUrl
            )
        }
        persistRecipientMetadataIfPossible(merged)
        _recipientMetadataByConversationId.value = merged
    }

    private fun loadMessages(): List<StoredMessage> {
        val raw = runCatching {
            preferences().getString(KEY_MESSAGES_JSON, null)?.trim().orEmpty()
        }.getOrElse { error ->
            messagesStoreUnavailable = true
            println("MessageStore: message history unavailable, deferring reload. ${error.localizedMessage}")
            return _messages.value
        }

        if (raw.isEmpty()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(StoredMessage.fromJson(array.getJSONObject(index)))
                }
            }
        }.onSuccess {
            messagesStoreUnavailable = false
        }.getOrElse { error ->
            messagesStoreUnavailable = true
            println("MessageStore: could not decode encrypted message history, deferring reload. ${error.localizedMessage}")
            _messages.value
        }
    }

    private fun persistMessagesIfPossible(messages: List<StoredMessage>): Boolean {
        reloadMessagesIfNeeded()
        if (messagesStoreUnavailable) {
            println("MessageStore: skipping message persistence while local store is unavailable.")
            return false
        }

        val array = JSONArray()
        messages.forEach { array.put(it.toJson()) }
        return runCatching {
            preferences().edit().putString(KEY_MESSAGES_JSON, array.toString()).apply()
            messagesStoreUnavailable = false
        }.isSuccess.also { success ->
            if (!success) {
                messagesStoreUnavailable = true
                println("MessageStore: failed to persist message history, keeping in-memory copy only.")
            }
        }
    }

    private fun loadRecipientMetadata(): Map<String, MessageRecipientMetadata> {
        val raw = runCatching {
            preferences().getString(KEY_RECIPIENT_METADATA_JSON, null)?.trim().orEmpty()
        }.getOrElse { error ->
            recipientMetadataStoreUnavailable = true
            println("MessageStore: recipient metadata unavailable, deferring reload. ${error.localizedMessage}")
            return _recipientMetadataByConversationId.value
        }

        if (raw.isEmpty()) return emptyMap()

        return runCatching {
            val root = JSONArray(raw)
            buildMap {
                for (index in 0 until root.length()) {
                    val json = root.getJSONObject(index)
                    val item = MessageRecipientMetadata(
                        conversationId = json.getString("conversationId"),
                        lightningAddress = json.optString("lightningAddress").ifBlank { null },
                        profilePicUrl = json.optString("profilePicUrl").ifBlank { null }
                    )
                    put(item.conversationId, item)
                }
            }
        }.onSuccess {
            recipientMetadataStoreUnavailable = false
        }.getOrElse { error ->
            recipientMetadataStoreUnavailable = true
            println("MessageStore: could not decode recipient metadata, deferring reload. ${error.localizedMessage}")
            _recipientMetadataByConversationId.value
        }
    }

    private fun persistRecipientMetadataIfPossible(metadata: Map<String, MessageRecipientMetadata>) {
        reloadRecipientMetadataIfNeeded()
        if (recipientMetadataStoreUnavailable) {
            println("MessageStore: skipping recipient metadata persistence while local store is unavailable.")
            return
        }

        val array = JSONArray()
        metadata.values.sortedBy { it.conversationId }.forEach { item ->
            array.put(
                org.json.JSONObject()
                    .put("conversationId", item.conversationId)
                    .put("lightningAddress", item.lightningAddress)
                    .put("profilePicUrl", item.profilePicUrl)
            )
        }
        runCatching {
            preferences().edit().putString(KEY_RECIPIENT_METADATA_JSON, array.toString()).apply()
            recipientMetadataStoreUnavailable = false
        }.onFailure { error ->
            recipientMetadataStoreUnavailable = true
            println("MessageStore: failed to persist recipient metadata, keeping in-memory copy only. ${error.localizedMessage}")
        }
    }

    private fun reloadMessagesIfNeeded() {
        if (!messagesStoreUnavailable) return

        val reloaded = loadMessages()
        if (!messagesStoreUnavailable) {
            _messages.value = reloaded
        }
    }

    private fun reloadRecipientMetadataIfNeeded() {
        if (!recipientMetadataStoreUnavailable) return

        val reloaded = loadRecipientMetadata()
        if (!recipientMetadataStoreUnavailable) {
            _recipientMetadataByConversationId.value = reloaded
        }
    }

    private fun counterpartyLightningAddress(message: StoredMessage): String? {
        return if (message.isIncoming) {
            message.senderLightningAddress
        } else {
            message.recipientLightningAddress
        }?.trim()?.ifBlank { null }
    }

    private fun shouldMarkMessageRead(message: StoredMessage): Boolean {
        if (!message.isIncoming || message.isRead) {
            return false
        }

        return MessageThreadPresenceTracker.shouldTreatIncomingMessageAsRead(
            conversationId = message.conversationId
        )
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = FILE_NAME,
                logTag = "MessageStore"
            ).also { preferences = it }
        }
    }

    companion object {
        @Volatile
        private var instance: MessageStore? = null

        fun getInstance(context: Context): MessageStore {
            return instance ?: synchronized(this) {
                instance ?: MessageStore(
                    context = context.applicationContext
                ).also { instance = it }
            }
        }

        private const val FILE_NAME = "split_secure_messages"
        private const val KEY_MESSAGES_JSON = "messages_json"
        private const val KEY_RECIPIENT_METADATA_JSON = "recipient_metadata_json"
    }
}
