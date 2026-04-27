package com.split.android.data.messages

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.split.android.core.AppConfig
import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MessagingDeviceTokenManager(
    context: Context,
    private val httpClient: SplitHttpClient,
    private val messageKeyManager: MessageKeyManager
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun syncCurrentDeviceToken(
        authManager: AuthManager,
        walletManager: WalletManager,
        force: Boolean = false
    ): Boolean {
        if (!isFirebaseConfigured()) {
            return false
        }

        val token = currentFirebaseTokenOrNull() ?: return false
        return syncProvidedDeviceToken(token, authManager, walletManager, force)
    }

    suspend fun syncProvidedDeviceToken(
        token: String,
        authManager: AuthManager,
        walletManager: WalletManager,
        force: Boolean = false
    ): Boolean {
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty() || !isFirebaseConfigured()) {
            return false
        }

        cacheCurrentToken(normalizedToken)

        return runCatching {
            val registration = messageKeyManager.ensureRegistered(authManager, walletManager)
            val activeMessagingPubkey = registration.messagingPubkey
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return false

            if (!force &&
                syncedDeviceToken() == normalizedToken &&
                syncedMessagingPubkey() == activeMessagingPubkey &&
                syncedEnvironment() == AppConfig.messagingPushEnvironment
            ) {
                return true
            }

            val walletPubkey = walletManager.currentWalletPubkey()
            val signedAtSeconds = System.currentTimeMillis() / 1000L
            val canonicalMessage = MessageBindingVerifier.buildMessagingDeviceRegistrationMessage(
                version = 1,
                walletPubkey = walletPubkey,
                messagingPubkey = activeMessagingPubkey,
                platform = "fcm",
                environment = AppConfig.messagingPushEnvironment,
                deviceToken = normalizedToken,
                signedAtSeconds = signedAtSeconds
            )
            val signedMessage = walletManager.signAuthMessage(canonicalMessage)
            require(
                signedMessage.pubkey.trim().lowercase() == walletPubkey.trim().lowercase()
            ) {
                "Signed wallet pubkey did not match the active wallet."
            }

            val body = JSONObject()
                .put("walletPubkey", signedMessage.pubkey)
                .put("messagingPubkey", activeMessagingPubkey)
                .put("platform", "fcm")
                .put("environment", AppConfig.messagingPushEnvironment)
                .put("deviceToken", normalizedToken)
                .put("registrationSignature", signedMessage.signature)
                .put("registrationSignatureVersion", 1)
                .put("registrationSignedAt", signedAtSeconds)

            var response = httpClient.postJson("/messaging/v3/device-registrations", body.toString())
            if (response.statusCode == 401 || response.statusCode == 403) {
                authManager.invalidateSession()
                authManager.ensureSession(walletManager)
                response = httpClient.postJson("/messaging/v3/device-registrations", body.toString())
            }

            if (response.statusCode == 409) {
                return false
            }

            if (response.statusCode !in 200..299) {
                throw IllegalStateException(extractServerError(response.body, response.statusCode))
            }

            preferences.edit()
                .putString(KEY_SYNCED_DEVICE_TOKEN, normalizedToken)
                .putString(KEY_SYNCED_MESSAGING_PUBKEY, activeMessagingPubkey)
                .putString(KEY_SYNCED_ENVIRONMENT, AppConfig.messagingPushEnvironment)
                .apply()

            true
        }.getOrElse { error ->
            if (shouldSilentlySkip(error)) {
                false
            } else {
                throw error
            }
        }
    }

    fun shouldSilentlySkip(error: Throwable): Boolean {
        val description = error.message.orEmpty().lowercase()
        return description.contains("default firebaseapp is not initialized") ||
            description.contains("firebase app with name") ||
            description.contains("missing google app id value") ||
            description.contains("failed resolution of") ||
            description.contains("noclassdeffounderror") ||
            description.contains("messaging key is not registered") ||
            description.contains("messaging identity is not registered") ||
            description.contains("create a lightning address before activating messaging") ||
            description.contains("messaging is active on another device") ||
            description.contains("lightningaddress must exist before messaging can be activated")
    }

    private fun isFirebaseConfigured(): Boolean {
        return runCatching {
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                true
            } else {
                FirebaseApp.initializeApp(appContext) != null
            }
        }.getOrElse { error ->
            Log.w(
                "MessagingDeviceTokenManager",
                "Firebase initialization unavailable on this build. Skipping device-token sync.",
                error
            )
            false
        }
    }

    private suspend fun currentFirebaseTokenOrNull(): String? {
        return runCatching {
            awaitTask(FirebaseMessaging.getInstance().token)
                .trim()
                .ifBlank { null }
        }.getOrNull()
    }

    private fun extractServerError(body: String, statusCode: Int): String {
        return runCatching {
            val json = JSONObject(body)
            json.optString("error")
                .ifBlank { json.optString("message") }
                .ifBlank { "Server error ($statusCode)." }
        }.getOrDefault(
            body.ifBlank { "Server error ($statusCode)." }
        )
    }

    private fun cacheCurrentToken(token: String) {
        val previous = preferences.getString(KEY_CURRENT_DEVICE_TOKEN, null)
        if (previous != token) {
            preferences.edit()
                .putString(KEY_CURRENT_DEVICE_TOKEN, token)
                .remove(KEY_SYNCED_DEVICE_TOKEN)
                .remove(KEY_SYNCED_MESSAGING_PUBKEY)
                .remove(KEY_SYNCED_ENVIRONMENT)
                .apply()
        }
    }

    private fun syncedDeviceToken(): String? = preferences.getString(KEY_SYNCED_DEVICE_TOKEN, null)

    private fun syncedMessagingPubkey(): String? = preferences.getString(KEY_SYNCED_MESSAGING_PUBKEY, null)

    private fun syncedEnvironment(): String? = preferences.getString(KEY_SYNCED_ENVIRONMENT, null)

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { continuation ->
        task.addOnSuccessListener { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }.addOnFailureListener { error ->
            if (continuation.isActive) {
                continuation.resumeWithException(error)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "split_messaging_device_token_state"
        const val KEY_CURRENT_DEVICE_TOKEN = "current_device_token"
        const val KEY_SYNCED_DEVICE_TOKEN = "synced_device_token"
        const val KEY_SYNCED_MESSAGING_PUBKEY = "synced_messaging_pubkey"
        const val KEY_SYNCED_ENVIRONMENT = "synced_environment"
    }
}
