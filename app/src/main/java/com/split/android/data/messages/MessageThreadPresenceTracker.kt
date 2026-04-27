package com.split.android.data.messages

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MessageThreadPresenceTracker {
    private val _isAppForeground = MutableStateFlow(false)
    val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    fun setAppForeground(isForeground: Boolean) {
        _isAppForeground.value = isForeground
    }

    fun enterConversation(conversationId: String?) {
        val normalizedConversationId = normalizeConversationId(conversationId)
        if (normalizedConversationId != null) {
            _activeConversationId.value = normalizedConversationId
        }
    }

    fun leaveConversation(conversationId: String?) {
        val normalizedConversationId = normalizeConversationId(conversationId) ?: return
        if (_activeConversationId.value == normalizedConversationId) {
            _activeConversationId.value = null
        }
    }

    fun shouldSuppressNotification(conversationId: String?): Boolean {
        val normalizedConversationId = normalizeConversationId(conversationId) ?: return false
        return _isAppForeground.value && _activeConversationId.value == normalizedConversationId
    }

    fun shouldTreatIncomingMessageAsRead(conversationId: String?): Boolean {
        return shouldSuppressNotification(conversationId)
    }

    private fun normalizeConversationId(conversationId: String?): String? {
        return conversationId
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }
}
