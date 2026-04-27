package com.split.android.data.auth

import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

sealed interface AuthState {
    data object Idle : AuthState
    data object Authenticating : AuthState
    data object Authenticated : AuthState
    data class Failed(
        val message: String
    ) : AuthState
}

class AuthManager(
    private val httpClient: SplitHttpClient
) {
    private val authMutex = Mutex()
    private var sessionValidUntilMillis: Long? = null

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _hasValidSession = MutableStateFlow(false)
    val hasValidSession: StateFlow<Boolean> = _hasValidSession.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun ensureSession(walletManager: WalletManager) {
        authMutex.withLock {
            val now = System.currentTimeMillis()
            if ((sessionValidUntilMillis ?: 0L) > now) {
                _hasValidSession.value = true
                _state.value = AuthState.Authenticated
                return
            }

            val authProvider = walletManager.currentAuthProvider()
                ?: throw IllegalStateException("Wallet is not ready for authentication.")

            _state.value = AuthState.Authenticating
            _lastError.value = null

            runCatching {
                val existingSessionIsValid = checkSessionCookieOnServer()
                if (!existingSessionIsValid) {
                    val nonceResponse = fetchNonce()
                    val signedMessage = authProvider.signMessage(nonceResponse.messageToSign)
                    val sparkAddress = authProvider.getSparkAddress()

                    exchangeSignatureForCookie(
                        pubkey = signedMessage.pubkey,
                        nonce = nonceResponse.nonce,
                        signature = signedMessage.signature,
                        sparkAddress = sparkAddress
                    )
                }

                sessionValidUntilMillis = now + SESSION_LIFETIME_MILLIS
                _hasValidSession.value = true
                _state.value = AuthState.Authenticated
            }.onFailure { error ->
                sessionValidUntilMillis = null
                _hasValidSession.value = false
                _lastError.value = error.message
                _state.value = AuthState.Failed(
                    error.message ?: "Authentication failed."
                )
                throw error
            }
        }
    }

    fun invalidateSession() {
        sessionValidUntilMillis = null
        _hasValidSession.value = false
        _lastError.value = null
        _state.value = AuthState.Idle
    }

    private suspend fun checkSessionCookieOnServer(): Boolean {
        val response = httpClient.get("/session")
        return response.statusCode == 200
    }

    private suspend fun fetchNonce(): NonceResponse {
        val response = httpClient.postJson("/auth/nonce", jsonBody = "{}")
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to fetch auth nonce.")
        }

        val json = JSONObject(response.body)
        return NonceResponse(
            nonce = json.getString("nonce"),
            messageToSign = json.getString("messageToSign")
        )
    }

    private suspend fun exchangeSignatureForCookie(
        pubkey: String,
        nonce: String,
        signature: String,
        sparkAddress: String
    ) {
        val requestBody = JSONObject()
            .put("pubkey", pubkey)
            .put("nonce", nonce)
            .put("signature", signature)
            .put("iat", System.currentTimeMillis() / 1000L)
            .put("sparkAddress", sparkAddress)

        val response = httpClient.postJson(
            path = "/auth/wallet-login",
            jsonBody = requestBody.toString()
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                "Wallet login failed (${response.statusCode})."
            )
        }
    }

    private data class NonceResponse(
        val nonce: String,
        val messageToSign: String
    )

    private companion object {
        const val SESSION_LIFETIME_MILLIS = 60 * 60 * 1000L
    }
}
