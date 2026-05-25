package com.split.android.data.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import com.split.android.MainActivity
import com.split.android.R

object MessageNotificationManager {
    const val EXTRA_CONVERSATION_ID = "split_message_conversation_id"

    private const val CHANNEL_ID = "split_messages"
    private const val CHANNEL_NAME = "Messages"
    private const val CHANNEL_DESCRIPTION = "Notifications for new Split messages"
    private val fallbackVibrationPattern = longArrayOf(0L, 250L, 150L, 250L)

    fun showNewMessageNotification(
        context: Context,
        conversationId: String,
        unreadConversationCount: Int
    ) {
        val normalizedConversationId = conversationId.trim()
        if (normalizedConversationId.isEmpty()) {
            return
        }

        getOrCreateChannel(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, normalizedConversationId)
        }

        val pendingIntent = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(launchIntent)
            getPendingIntent(
                normalizedConversationId.hashCode(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("New message")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setNumber(unreadConversationCount.coerceAtLeast(1))
            .build()

        NotificationManagerCompat.from(context).notify(
            normalizedConversationId.hashCode(),
            notification
        )
    }

    fun playInAppAlertIfEnabled(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        val channel = getOrCreateChannel(context) ?: return
        if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            return
        }

        playChannelSound(context, channel)
        vibrateForChannel(context, channel)
    }

    fun dismissConversationNotification(context: Context, conversationId: String) {
        val normalizedConversationId = conversationId.trim()
        if (normalizedConversationId.isEmpty()) {
            return
        }

        NotificationManagerCompat.from(context).cancel(normalizedConversationId.hashCode())
    }

    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun getOrCreateChannel(context: Context): NotificationChannel? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.getNotificationChannel(CHANNEL_ID)?.let { existingChannel ->
            return existingChannel
        }

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
            setShowBadge(true)
            setSound(soundUri, notificationAudioAttributes())
        }

        notificationManager.createNotificationChannel(channel)
        return notificationManager.getNotificationChannel(CHANNEL_ID)
    }

    private fun playChannelSound(context: Context, channel: NotificationChannel) {
        val soundUri = channel.sound ?: return

        runCatching {
            RingtoneManager.getRingtone(context, soundUri)
        }.getOrNull()?.let { ringtone ->
            playRingtone(ringtone, channel.audioAttributes)
        }
    }

    private fun playRingtone(ringtone: Ringtone, audioAttributes: AudioAttributes?) {
        runCatching {
            ringtone.audioAttributes = audioAttributes ?: notificationAudioAttributes()
            ringtone.play()
        }
    }

    private fun vibrateForChannel(context: Context, channel: NotificationChannel) {
        if (!channel.shouldVibrate()) {
            return
        }

        val vibrationPattern = channel.vibrationPattern
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackVibrationPattern

        runCatching {
            resolveVibrator(context)?.let { vibrator ->
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1))
                }
            }
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun notificationAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
    }
}
