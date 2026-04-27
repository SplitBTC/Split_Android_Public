package com.split.android.data.messages

import android.content.SharedPreferences
import android.content.Context
import com.split.android.core.AppConfig
import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.security.SecurePreferencesAccessException
import com.split.android.data.security.openEncryptedPreferences
import com.split.android.data.wallet.WalletManager
import kotlinx.coroutines.delay
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class MessageKeyManager(
    context: Context,
    private val httpClient: SplitHttpClient,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private enum class IdentityEndpoint(
        val path: String,
        val signatureVersion: Int,
        val claimsActiveBinding: Boolean,
        val requiresDirectoryProof: Boolean
    ) {
        V3(
            path = "/messaging/v3/identity",
            signatureVersion = 2,
            claimsActiveBinding = true,
            requiresDirectoryProof = false
        )
    }

    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null

    private class MessageKeyStorageUnavailableException(
        cause: Throwable
    ) : IllegalStateException(
        "Local messaging identity is temporarily unavailable.",
        cause
    )

    suspend fun ensureRegistered(
        authManager: AuthManager,
        walletManager: WalletManager
    ): RegistrationResponse {
        val identityEndpoint = IdentityEndpoint.V3
        authManager.ensureSession(walletManager)

        val walletPubkey = walletManager.currentWalletPubkey()
        val lightningAddress = fetchLocalLightningAddressWithRetry(walletManager)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Create a Lightning Address before activating messaging.")

        var keyState = loadOrCreatePrivateKey()
        var localIdentity = LocalIdentity(
            walletPubkey = walletPubkey,
            lightningAddress = lightningAddress,
            messagingPubkey = keyState.publicKeyHex
        )

        val current = fetchCurrentRegistration(identityEndpoint, authManager, walletManager)
        if (isRegistrationValid(current, identityEndpoint, localIdentity)) {
            return current
        }

        val currentMessagingPubkey = current.messagingPubkey
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

        if (!identityEndpoint.claimsActiveBinding &&
            !currentMessagingPubkey.isNullOrBlank() &&
            currentMessagingPubkey != localIdentity.messagingPubkey &&
            !keyState.didCreate
        ) {
            throw IllegalStateException("Messaging is active on another device.")
        }

        if (identityEndpoint.claimsActiveBinding &&
            !currentMessagingPubkey.isNullOrBlank() &&
            currentMessagingPubkey != localIdentity.messagingPubkey
        ) {
            keyState = rotateMessagingPrivateKey()
            localIdentity = localIdentity.copy(
                messagingPubkey = keyState.publicKeyHex
            )
        }

        val signedAtSeconds = System.currentTimeMillis() / 1000L
        val canonicalMessage = MessageBindingVerifier.buildMessagingIdentityBindingMessage(
            version = identityEndpoint.signatureVersion,
            walletPubkey = localIdentity.walletPubkey,
            lightningAddress = localIdentity.lightningAddress,
            messagingPubkey = localIdentity.messagingPubkey,
            signedAtSeconds = signedAtSeconds
        )
        val signedMessage = walletManager.signAuthMessage(canonicalMessage)
        require(
            signedMessage.pubkey.trim().lowercase() == localIdentity.walletPubkey.lowercase()
        ) {
            "Invalid messaging key registration response."
        }

        val requestBody = JSONObject()
            .put("walletPubkey", signedMessage.pubkey)
            .put("lightningAddress", localIdentity.lightningAddress)
            .put("messagingPubkey", localIdentity.messagingPubkey)
            .put("messagingIdentitySignature", signedMessage.signature)
            .put("messagingIdentitySignatureVersion", identityEndpoint.signatureVersion)
            .put("messagingIdentitySignedAt", signedAtSeconds)

        var response = httpClient.postJson(identityEndpoint.path, requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson(identityEndpoint.path, requestBody.toString())
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        val registration = response.body.toRegistrationResponse()
        require(isRegistrationValid(registration, identityEndpoint, localIdentity)) {
            "Invalid messaging key registration response."
        }

        return registration
    }

    fun currentMessagingPublicKeyHex(): String {
        return publicKeyHexForPrivateKey(storedPrivateKeyHex())
    }

    fun currentMessagingPrivateKeyHex(): String = storedPrivateKeyHex()

    fun clearStoredMessagingKey() {
        runCatching {
            val editor = preferences().edit()
            allMessagingPrivateKeyKeys().forEach(editor::remove)
            allDirectoryCheckpointKeys().forEach(editor::remove)
            editor.apply()
        }
    }

    fun shouldSilentlyDeferActivation(error: Throwable): Boolean {
        if (unwrapMessagingFailure(error) is MessageKeyStorageUnavailableException) {
            return true
        }

        val description = error.message.orEmpty().lowercase()
        return description.contains("create a lightning address before activating messaging") ||
            description.contains("messaging is active on another device") ||
            description.contains("lightningaddress must exist before messaging can be activated") ||
            description.contains("messaging identity is not registered") ||
            description.contains("messaging key is not registered") ||
            description.contains("local messaging identity is temporarily unavailable")
    }

    fun storeDirectoryCheckpointIfNewer(checkpoint: MessagingDirectoryCheckpoint) {
        removeLegacyDirectoryCheckpointIfPresent()
        val existing = readDirectoryCheckpoint()
        if (existing != null) {
            require(checkpoint.treeSize >= existing.treeSize) {
                "The messaging directory checkpoint is stale."
            }
            if (checkpoint.treeSize == existing.treeSize) {
                require(
                    checkpoint.rootHash.trim().lowercase() == existing.rootHash.trim().lowercase()
                ) {
                    "The messaging directory checkpoint conflicts with a previously stored checkpoint."
                }
                return
            }
        }

        preferences().edit()
            .putString(preferredDirectoryRootHashKey(), checkpoint.rootHash.trim().lowercase())
            .putInt(preferredDirectoryTreeSizeKey(), checkpoint.treeSize)
            .putLong(preferredDirectoryIssuedAtKey(), checkpoint.issuedAtMillis)
            .apply()
    }

    private suspend fun fetchCurrentRegistration(
        identityEndpoint: IdentityEndpoint,
        authManager: AuthManager,
        walletManager: WalletManager
    ): RegistrationResponse {
        var response = httpClient.get(identityEndpoint.path)
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get(identityEndpoint.path)
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(extractServerError(response.body, response.statusCode))
        }

        return response.body.toRegistrationResponse()
    }

    private fun isRegistrationValid(
        response: RegistrationResponse,
        identityEndpoint: IdentityEndpoint,
        localIdentity: LocalIdentity
    ): Boolean {
        val binding = response.identityBindingPayload ?: return false
        if (binding.messagingIdentitySignatureVersion != identityEndpoint.signatureVersion) {
            return false
        }
        if (binding.walletPubkey.lowercase() != localIdentity.walletPubkey.lowercase()) {
            return false
        }
        if (binding.lightningAddress != localIdentity.lightningAddress) {
            return false
        }
        if (binding.messagingPubkey != localIdentity.messagingPubkey) {
            return false
        }

        return runCatching {
            MessageBindingVerifier.verifyBinding(binding)
            val directory = response.directory
            if (directory == null) {
                require(!identityEndpoint.requiresDirectoryProof) {
                    "The messaging directory proof is missing."
                }
            } else {
                MessageDirectoryVerifier.verifyDirectoryProof(binding, directory)
                storeDirectoryCheckpointIfNewer(directory.checkpoint)
            }
        }.isSuccess
    }

    private suspend fun fetchLocalLightningAddressWithRetry(
        walletManager: WalletManager
    ): String? {
        var lastResult: String? = null
        repeat(LIGHTNING_LOOKUP_MAX_ATTEMPTS) { attempt ->
            lastResult = walletManager.currentLightningAddress()
            if (!lastResult.isNullOrBlank()) {
                return lastResult
            }
            if (attempt < LIGHTNING_LOOKUP_MAX_ATTEMPTS - 1) {
                delay(LIGHTNING_LOOKUP_RETRY_DELAY_MILLIS)
            }
        }
        return lastResult
    }

    private fun loadOrCreatePrivateKey(): KeyState {
        val existing = storedPrivateKeyHexOrNull()

        if (existing != null) {
            return KeyState(
                privateKeyHex = existing,
                publicKeyHex = publicKeyHexForPrivateKey(existing),
                didCreate = false
            )
        }

        val privateKey = ByteArray(32)
        X25519.generatePrivateKey(secureRandom, privateKey)
        val privateKeyHex = privateKey.toHex()
        saveStoredPrivateKeyHex(privateKeyHex)
        return KeyState(
            privateKeyHex = privateKeyHex,
            publicKeyHex = publicKeyHexForPrivateKey(privateKeyHex),
            didCreate = true
        )
    }

    private fun rotateMessagingPrivateKey(): KeyState {
        val privateKey = ByteArray(32)
        X25519.generatePrivateKey(secureRandom, privateKey)
        val privateKeyHex = privateKey.toHex()
        saveStoredPrivateKeyHex(privateKeyHex)
        return KeyState(
            privateKeyHex = privateKeyHex,
            publicKeyHex = publicKeyHexForPrivateKey(privateKeyHex),
            didCreate = true
        )
    }

    private fun storedPrivateKeyHex(): String {
        return storedPrivateKeyHexOrNull()
            ?: throw IllegalStateException("Stored messaging key is invalid.")
    }

    private fun storedPrivateKeyHexOrNull(): String? {
        val preferredKey = preferredMessagingPrivateKeyKey()

        for (key in readableMessagingPrivateKeyKeys()) {
            val stored = preferences().getString(key, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue

            val isValidStoredKey = runCatching {
                publicKeyHexForPrivateKey(stored)
            }.isSuccess
            if (!isValidStoredKey) {
                runCatching { preferences().edit().remove(key).apply() }
                continue
            }

            if (key != preferredKey &&
                preferences().getString(preferredKey, null).isNullOrBlank()
            ) {
                preferences().edit().putString(preferredKey, stored).apply()
            }

            return stored
        }

        return null
    }

    private fun saveStoredPrivateKeyHex(privateKeyHex: String) {
        preferences().edit().putString(preferredMessagingPrivateKeyKey(), privateKeyHex).apply()
    }

    private fun readDirectoryCheckpoint(): MessagingDirectoryCheckpoint? {
        removeLegacyDirectoryCheckpointIfPresent()

        val rootHash = preferences().getString(preferredDirectoryRootHashKey(), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val treeSize = preferences().getInt(preferredDirectoryTreeSizeKey(), -1)
        val issuedAtMillis = preferences().getLong(preferredDirectoryIssuedAtKey(), -1L)
        if (treeSize < 0 || issuedAtMillis < 0L) {
            return null
        }

        return MessagingDirectoryCheckpoint(
            rootHash = rootHash,
            treeSize = treeSize,
            issuedAtMillis = issuedAtMillis
        )
    }

    private fun removeLegacyDirectoryCheckpointIfPresent() {
        if (preferences().contains(KEY_DIRECTORY_ROOT_HASH) ||
            preferences().contains(KEY_DIRECTORY_TREE_SIZE) ||
            preferences().contains(KEY_DIRECTORY_ISSUED_AT_MILLIS)
        ) {
            preferences().edit()
                .remove(KEY_DIRECTORY_ROOT_HASH)
                .remove(KEY_DIRECTORY_TREE_SIZE)
                .remove(KEY_DIRECTORY_ISSUED_AT_MILLIS)
                .apply()
        }
    }

    private fun preferredMessagingPrivateKeyKey(): String {
        return "$KEY_MESSAGING_PRIVATE_KEY.${AppConfig.messagingPushEnvironment}"
    }

    private fun readableMessagingPrivateKeyKeys(): List<String> {
        return listOf(
            preferredMessagingPrivateKeyKey(),
            KEY_MESSAGING_PRIVATE_KEY
        )
    }

    private fun allMessagingPrivateKeyKeys(): List<String> {
        return listOf(
            KEY_MESSAGING_PRIVATE_KEY,
            "$KEY_MESSAGING_PRIVATE_KEY.dev",
            "$KEY_MESSAGING_PRIVATE_KEY.prod"
        )
    }

    private fun preferredDirectoryRootHashKey(): String {
        return "$KEY_DIRECTORY_ROOT_HASH.${AppConfig.messagingPushEnvironment}"
    }

    private fun preferredDirectoryTreeSizeKey(): String {
        return "$KEY_DIRECTORY_TREE_SIZE.${AppConfig.messagingPushEnvironment}"
    }

    private fun preferredDirectoryIssuedAtKey(): String {
        return "$KEY_DIRECTORY_ISSUED_AT_MILLIS.${AppConfig.messagingPushEnvironment}"
    }

    private fun allDirectoryCheckpointKeys(): List<String> {
        return listOf(
            KEY_DIRECTORY_ROOT_HASH,
            KEY_DIRECTORY_TREE_SIZE,
            KEY_DIRECTORY_ISSUED_AT_MILLIS,
            "$KEY_DIRECTORY_ROOT_HASH.dev",
            "$KEY_DIRECTORY_ROOT_HASH.prod",
            "$KEY_DIRECTORY_TREE_SIZE.dev",
            "$KEY_DIRECTORY_TREE_SIZE.prod",
            "$KEY_DIRECTORY_ISSUED_AT_MILLIS.dev",
            "$KEY_DIRECTORY_ISSUED_AT_MILLIS.prod"
        )
    }

    private fun publicKeyHexForPrivateKey(privateKeyHex: String): String {
        val publicKey = ByteArray(32)
        X25519.generatePublicKey(privateKeyHex.hexToByteArray(strictLength = 32), 0, publicKey, 0)
        return publicKey.toHex()
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: try {
                openEncryptedPreferences(
                    context = appContext,
                    fileName = FILE_NAME,
                    logTag = "MessageKeyManager"
                )
            } catch (error: SecurePreferencesAccessException) {
                throw MessageKeyStorageUnavailableException(error)
            }.also { preferences = it }
        }
    }

    private fun unwrapMessagingFailure(error: Throwable): Throwable {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable = error
        seen += current

        while (true) {
            val next = current.cause ?: break
            if (!seen.add(next)) {
                break
            }
            current = next
        }

        return current
    }

    private fun extractServerError(body: String, statusCode: Int): String {
        return runCatching {
            JSONObject(body).optString("error").ifBlank { "Server error ($statusCode)." }
        }.getOrDefault(
            body.ifBlank { "Server error ($statusCode)." }
        )
    }

    private data class KeyState(
        val privateKeyHex: String,
        val publicKeyHex: String,
        val didCreate: Boolean
    )

    private data class LocalIdentity(
        val walletPubkey: String,
        val lightningAddress: String,
        val messagingPubkey: String
    )

    data class RegistrationResponse(
        val ok: Boolean,
        val walletPubkey: String?,
        val lightningAddress: String?,
        val didUpdate: Boolean,
        val didRotate: Boolean,
        val messagingPubkey: String?,
        val messagingIdentitySignature: String?,
        val messagingIdentitySignatureVersion: Int?,
        val messagingIdentitySignedAtMillis: Long?,
        val messagingIdentityUpdatedAtMillis: Long?,
        val directory: MessagingDirectoryProofPayload?,
        val error: String?
    ) {
        val normalizedLightningAddress: String?
            get() = lightningAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val identityBindingPayload: MessagingIdentityBindingPayload?
            get() {
                val normalizedAddress = normalizedLightningAddress ?: return null
                val pubkey = walletPubkey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val messagePubkey = messagingPubkey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val signature = messagingIdentitySignature?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val signatureVersion = messagingIdentitySignatureVersion ?: return null
                val signedAtMillis = messagingIdentitySignedAtMillis ?: return null

                return MessagingIdentityBindingPayload(
                    walletPubkey = pubkey,
                    lightningAddress = normalizedAddress,
                    messagingPubkey = messagePubkey,
                    messagingIdentitySignature = signature,
                    messagingIdentitySignatureVersion = signatureVersion,
                    messagingIdentitySignedAtSeconds = signedAtMillis / 1000L
                )
            }
    }

    private fun String.toRegistrationResponse(): RegistrationResponse {
        val json = JSONObject(this)
        return RegistrationResponse(
            ok = json.optBoolean("ok", false),
            walletPubkey = json.optString("walletPubkey").ifBlank { null },
            lightningAddress = json.optString("lightningAddress").ifBlank { null },
            didUpdate = json.optBoolean("didUpdate", false),
            didRotate = json.optBoolean("didRotate", false),
            messagingPubkey = json.optString("messagingPubkey").ifBlank { null },
            messagingIdentitySignature = json.optString("messagingIdentitySignature").ifBlank { null },
            messagingIdentitySignatureVersion = json.optInt("messagingIdentitySignatureVersion").takeIf { it != 0 },
            messagingIdentitySignedAtMillis = isoStringToMillis(json.optString("messagingIdentitySignedAt").ifBlank { null }),
            messagingIdentityUpdatedAtMillis = isoStringToMillis(json.optString("messagingIdentityUpdatedAt").ifBlank { null }),
            directory = json.optJSONObject("directory")?.toDirectoryProofPayload(),
            error = json.optString("error").ifBlank { null }
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

    private companion object {
        const val FILE_NAME = "split_secure_messaging_keys"
        const val KEY_MESSAGING_PRIVATE_KEY = "messaging_private_key"
        const val KEY_DIRECTORY_ROOT_HASH = "messaging_directory_root_hash"
        const val KEY_DIRECTORY_TREE_SIZE = "messaging_directory_tree_size"
        const val KEY_DIRECTORY_ISSUED_AT_MILLIS = "messaging_directory_issued_at_millis"
        const val LIGHTNING_LOOKUP_RETRY_DELAY_MILLIS = 500L
        const val LIGHTNING_LOOKUP_MAX_ATTEMPTS = 4
    }
}
