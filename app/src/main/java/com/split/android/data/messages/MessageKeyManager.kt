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
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
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
        val current = fetchCurrentRegistration(identityEndpoint, authManager, walletManager)
        val currentMessagingPubkey = current.messagingPubkey
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

        var keyState = loadOrCreatePrivateKey(matchingServerPubkey = currentMessagingPubkey)
        var localIdentity = LocalIdentity(
            walletPubkey = walletPubkey,
            lightningAddress = lightningAddress,
            messagingPubkey = keyState.publicKeyHex
        )

        if (isRegistrationValid(current, identityEndpoint, localIdentity)) {
            return current
        }

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

    suspend fun rotateMessagingIdentityAfterSameKeyFailure(
        authManager: AuthManager,
        walletManager: WalletManager,
        reason: String
    ): RegistrationResponse? {
        val now = System.currentTimeMillis()
        val lastRotationAt = preferences().getLong(preferredSelfHealingRotationKey(), 0L)
        if (lastRotationAt > 0L && now - lastRotationAt < SELF_HEALING_ROTATION_COOLDOWN_MILLIS) {
            println("Messaging self-healing rotation skipped during cooldown. reason=$reason")
            return null
        }

        return rotateMessagingIdentity(
            authManager = authManager,
            walletManager = walletManager,
            reason = reason
        ).also {
            preferences().edit()
                .putLong(preferredSelfHealingRotationKey(), now)
                .apply()
        }
    }

    fun currentMessagingPublicKeyHex(): String {
        return publicKeyHexForPrivateKey(storedPrivateKeyHex())
    }

    fun currentMessagingPrivateKeyHex(): String = storedPrivateKeyHex()

    suspend fun currentMessageSigningCertificate(
        senderBinding: MessagingIdentityBindingPayload,
        walletManager: WalletManager
    ): MessageSigningCertificate {
        val signingPrivateKey = loadOrCreateSigningPrivateKey()
        val signingPubkey = signingPrivateKey.generatePublicKey().encoded.toHex()

        val existingCertificate = loadSigningCertificateIfPresent()
        if (existingCertificate != null &&
            isSigningCertificateValid(existingCertificate, senderBinding, signingPubkey)
        ) {
            return existingCertificate
        }

        val signedAtSeconds = System.currentTimeMillis() / 1000L
        val canonicalMessage = MessageBindingVerifier.buildMessagingSigningKeyBindingMessage(
            version = 1,
            walletPubkey = senderBinding.walletPubkey,
            lightningAddress = senderBinding.lightningAddress,
            messagingPubkey = senderBinding.messagingPubkey,
            messagingSigningPubkey = signingPubkey,
            signedAtSeconds = signedAtSeconds
        )
        val signedMessage = walletManager.signAuthMessage(canonicalMessage)
        require(signedMessage.pubkey.trim().lowercase() == senderBinding.walletPubkey.lowercase()) {
            "Invalid messaging signing key certificate response."
        }

        val certificate = MessageSigningCertificate(
            walletPubkey = senderBinding.walletPubkey,
            lightningAddress = senderBinding.lightningAddress,
            messagingPubkey = senderBinding.messagingPubkey,
            messagingSigningPubkey = signingPubkey,
            messagingSigningPubkeySignature = signedMessage.signature,
            messagingSigningPubkeySignatureVersion = 1,
            messagingSigningPubkeySignedAt = signedAtSeconds
        )
        saveSigningCertificate(certificate)
        return certificate
    }

    fun signMessageEnvelope(canonicalMessage: String): String {
        val signingPrivateKey = loadOrCreateSigningPrivateKey()
        val messageBytes = canonicalMessage.toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer().apply {
            init(true, signingPrivateKey)
            update(messageBytes, 0, messageBytes.size)
        }
        return signer.generateSignature().toHex()
    }

    fun clearStoredMessagingKey() {
        runCatching {
            val editor = preferences().edit()
            allMessagingPrivateKeyKeys().forEach(editor::remove)
            allSigningPrivateKeyKeys().forEach(editor::remove)
            allSigningCertificateKeys().forEach(editor::remove)
            allDirectoryCheckpointKeys().forEach(editor::remove)
            editor.remove(preferredSelfHealingRotationKey())
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

    private suspend fun rotateMessagingIdentity(
        authManager: AuthManager,
        walletManager: WalletManager,
        reason: String
    ): RegistrationResponse {
        val identityEndpoint = IdentityEndpoint.V3
        authManager.ensureSession(walletManager)

        val walletPubkey = walletManager.currentWalletPubkey()
        val lightningAddress = fetchLocalLightningAddressWithRetry(walletManager)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Create a Lightning Address before activating messaging.")
        val keyState = rotateMessagingPrivateKey()
        clearSigningMaterial()

        val signedAtSeconds = System.currentTimeMillis() / 1000L
        val canonicalMessage = MessageBindingVerifier.buildMessagingIdentityBindingMessage(
            version = identityEndpoint.signatureVersion,
            walletPubkey = walletPubkey,
            lightningAddress = lightningAddress,
            messagingPubkey = keyState.publicKeyHex,
            signedAtSeconds = signedAtSeconds
        )
        val signedMessage = walletManager.signAuthMessage(canonicalMessage)
        require(signedMessage.pubkey.trim().lowercase() == walletPubkey.lowercase()) {
            "Invalid messaging key registration response."
        }

        val requestBody = JSONObject()
            .put("walletPubkey", signedMessage.pubkey)
            .put("lightningAddress", lightningAddress)
            .put("messagingPubkey", keyState.publicKeyHex)
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

        val localIdentity = LocalIdentity(
            walletPubkey = walletPubkey,
            lightningAddress = lightningAddress,
            messagingPubkey = keyState.publicKeyHex
        )
        val registration = response.body.toRegistrationResponse()
        require(isRegistrationValid(registration, identityEndpoint, localIdentity)) {
            "Invalid messaging key registration response."
        }
        println("Messaging self-healing rotation completed. reason=$reason")
        return registration
    }

    private fun loadOrCreatePrivateKey(matchingServerPubkey: String?): KeyState {
        val existing = storedPrivateKeyHexOrNull()

        if (existing != null) {
            return KeyState(
                privateKeyHex = existing,
                publicKeyHex = publicKeyHexForPrivateKey(existing),
                didCreate = false
            )
        }

        val legacy = loadLegacyPrivateKeyIfPresent(matchingServerPubkey)
        if (legacy != null) {
            return KeyState(
                privateKeyHex = legacy,
                publicKeyHex = publicKeyHexForPrivateKey(legacy),
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
        val stored = preferences().getString(preferredKey, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val isValidStoredKey = runCatching {
            publicKeyHexForPrivateKey(stored)
        }.isSuccess
        if (!isValidStoredKey) {
            runCatching { preferences().edit().remove(preferredKey).apply() }
            return null
        }

        return stored
    }

    private fun loadLegacyPrivateKeyIfPresent(matchingServerPubkey: String?): String? {
        val expectedPubkey = matchingServerPubkey
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val stored = preferences().getString(KEY_MESSAGING_PRIVATE_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val storedPubkey = runCatching {
            publicKeyHexForPrivateKey(stored)
        }.getOrElse {
            runCatching { preferences().edit().remove(KEY_MESSAGING_PRIVATE_KEY).apply() }
            return null
        }

        if (storedPubkey.lowercase() != expectedPubkey) {
            return null
        }

        saveStoredPrivateKeyHex(stored)
        return stored
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

    private fun allMessagingPrivateKeyKeys(): List<String> {
        return listOf(
            KEY_MESSAGING_PRIVATE_KEY,
            "$KEY_MESSAGING_PRIVATE_KEY.dev",
            "$KEY_MESSAGING_PRIVATE_KEY.prod"
        )
    }

    private fun preferredSigningPrivateKeyKey(): String {
        return "$KEY_MESSAGING_SIGNING_PRIVATE_KEY.${AppConfig.messagingPushEnvironment}"
    }

    private fun allSigningPrivateKeyKeys(): List<String> {
        return listOf(
            KEY_MESSAGING_SIGNING_PRIVATE_KEY,
            "$KEY_MESSAGING_SIGNING_PRIVATE_KEY.dev",
            "$KEY_MESSAGING_SIGNING_PRIVATE_KEY.prod"
        )
    }

    private fun preferredSigningCertificateKey(): String {
        return "$KEY_MESSAGING_SIGNING_CERTIFICATE.${AppConfig.messagingPushEnvironment}"
    }

    private fun allSigningCertificateKeys(): List<String> {
        return listOf(
            KEY_MESSAGING_SIGNING_CERTIFICATE,
            "$KEY_MESSAGING_SIGNING_CERTIFICATE.dev",
            "$KEY_MESSAGING_SIGNING_CERTIFICATE.prod"
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

    private fun preferredSelfHealingRotationKey(): String {
        return "$KEY_LAST_SELF_HEALING_ROTATION.${AppConfig.messagingPushEnvironment}"
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

    private fun loadSigningPrivateKeyIfPresent(): Ed25519PrivateKeyParameters? {
        val preferredKey = preferredSigningPrivateKeyKey()
        val stored = preferences().getString(preferredKey, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val privateKey = runCatching {
            Ed25519PrivateKeyParameters(stored.hexToByteArray(strictLength = 32), 0)
        }.getOrNull()
        if (privateKey == null) {
            runCatching { preferences().edit().remove(preferredKey).apply() }
            return null
        }

        return privateKey
    }

    private fun loadOrCreateSigningPrivateKey(): Ed25519PrivateKeyParameters {
        loadSigningPrivateKeyIfPresent()?.let { return it }

        val privateKey = Ed25519PrivateKeyParameters(secureRandom)
        preferences().edit()
            .putString(preferredSigningPrivateKeyKey(), privateKey.encoded.toHex())
            .apply()
        return privateKey
    }

    private fun loadSigningCertificateIfPresent(): MessageSigningCertificate? {
        val preferredKey = preferredSigningCertificateKey()
        val stored = preferences().getString(preferredKey, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val certificate = runCatching {
            val json = JSONObject(stored)
            MessageSigningCertificate(
                walletPubkey = json.getString("walletPubkey"),
                lightningAddress = json.getString("lightningAddress"),
                messagingPubkey = json.getString("messagingPubkey"),
                messagingSigningPubkey = json.getString("messagingSigningPubkey"),
                messagingSigningPubkeySignature = json.getString("messagingSigningPubkeySignature"),
                messagingSigningPubkeySignatureVersion = json.getInt("messagingSigningPubkeySignatureVersion"),
                messagingSigningPubkeySignedAt = json.getLong("messagingSigningPubkeySignedAt")
            )
        }.getOrNull()
        if (certificate == null) {
            runCatching { preferences().edit().remove(preferredKey).apply() }
            return null
        }

        return certificate
    }

    private fun saveSigningCertificate(certificate: MessageSigningCertificate) {
        val json = JSONObject()
            .put("walletPubkey", certificate.walletPubkey)
            .put("lightningAddress", certificate.lightningAddress)
            .put("messagingPubkey", certificate.messagingPubkey)
            .put("messagingSigningPubkey", certificate.messagingSigningPubkey)
            .put("messagingSigningPubkeySignature", certificate.messagingSigningPubkeySignature)
            .put("messagingSigningPubkeySignatureVersion", certificate.messagingSigningPubkeySignatureVersion)
            .put("messagingSigningPubkeySignedAt", certificate.messagingSigningPubkeySignedAt)

        preferences().edit()
            .putString(preferredSigningCertificateKey(), json.toString())
            .apply()
    }

    private fun clearSigningMaterial() {
        preferences().edit()
            .remove(preferredSigningPrivateKeyKey())
            .remove(preferredSigningCertificateKey())
            .apply()
    }

    private fun isSigningCertificateValid(
        certificate: MessageSigningCertificate,
        senderBinding: MessagingIdentityBindingPayload,
        signingPubkey: String
    ): Boolean {
        if (certificate.walletPubkey.lowercase() != senderBinding.walletPubkey.lowercase() ||
            certificate.lightningAddress.lowercase() != senderBinding.lightningAddress.lowercase() ||
            certificate.messagingPubkey.lowercase() != senderBinding.messagingPubkey.lowercase() ||
            certificate.messagingSigningPubkey.lowercase() != signingPubkey.lowercase() ||
            certificate.messagingSigningPubkeySignatureVersion != 1
        ) {
            return false
        }

        return runCatching {
            MessageBindingVerifier.verifyMessagingSigningCertificate(certificate)
        }.isSuccess
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
        const val KEY_MESSAGING_SIGNING_PRIVATE_KEY = "messaging_signing_private_key"
        const val KEY_MESSAGING_SIGNING_CERTIFICATE = "messaging_signing_certificate"
        const val KEY_DIRECTORY_ROOT_HASH = "messaging_directory_root_hash"
        const val KEY_DIRECTORY_TREE_SIZE = "messaging_directory_tree_size"
        const val KEY_DIRECTORY_ISSUED_AT_MILLIS = "messaging_directory_issued_at_millis"
        const val KEY_LAST_SELF_HEALING_ROTATION = "messaging_last_self_healing_rotation"
        const val LIGHTNING_LOOKUP_RETRY_DELAY_MILLIS = 500L
        const val LIGHTNING_LOOKUP_MAX_ATTEMPTS = 4
        const val SELF_HEALING_ROTATION_COOLDOWN_MILLIS = 12 * 60 * 60 * 1000L
    }
}
