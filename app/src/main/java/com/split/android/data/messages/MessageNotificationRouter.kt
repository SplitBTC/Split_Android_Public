package com.split.android.data.messages

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MessageNotificationRouter {
    private val _pendingConversationId = MutableStateFlow<String?>(null)
    val pendingConversationId: StateFlow<String?> = _pendingConversationId.asStateFlow()

    fun queueConversation(conversationId: String?) {
        val normalizedConversationId = conversationId?.trim().orEmpty()
        if (normalizedConversationId.isBlank()) {
            return
        }

        _pendingConversationId.value = normalizedConversationId
    }

    fun consumeConversation(conversationId: String?) {
        if (_pendingConversationId.value == conversationId) {
            _pendingConversationId.value = null
        }
    }
}
