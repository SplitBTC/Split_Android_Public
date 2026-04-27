package com.split.android.data.messages

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SplitFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (token.isBlank()) {
            return
        }

        MessagingDeviceTokenSyncScheduler.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) {
            return
        }

        val pushType = data["type"]?.trim().orEmpty()
        if (pushType == "messaging.new_message") {
            val conversationId = data["conversationId"]?.trim().orEmpty()
            if (conversationId.isNotEmpty()) {
                val shouldSuppressNotification = MessageThreadPresenceTracker.shouldSuppressNotification(
                    conversationId = conversationId
                )
                if (!shouldSuppressNotification) {
                    val unreadConversationCount = MessageStore.getInstance(applicationContext)
                        .unreadConversationCount + 1
                    MessageNotificationManager.showNewMessageNotification(
                        context = applicationContext,
                        conversationId = conversationId,
                        unreadConversationCount = unreadConversationCount
                    )
                } else {
                    MessageNotificationManager.playInAppAlertIfEnabled(applicationContext)
                }
            }
            MessageSyncScheduler.triggerImmediateSync(applicationContext)
            return
        }

        if (pushType == "messaging.rekey_required" || pushType == "messaging.outgoing_status") {
            MessageSyncScheduler.triggerImmediateSync(
                applicationContext,
                reason = MessageSyncScheduler.outgoingStatusReason()
            )
        }
    }
}
