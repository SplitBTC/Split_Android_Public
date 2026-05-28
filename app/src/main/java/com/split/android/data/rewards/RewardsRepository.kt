package com.split.android.data.rewards

import android.util.Base64
import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class RewardStatsResponse(
    val monthKey: String,
    val monthlyPot: MonthlyPot,
    val platform: PlatformTotals,
    val user: UserTotals,
    val stats: RewardStats
) {
    data class MonthlyPot(
        val sats: Long
    )

    data class PlatformTotals(
        val rewardSpendCents: Long,
        val transactions: Int
    )

    data class UserTotals(
        val rewardSpendCents: Long,
        val transactions: Int
    )

    data class RewardStats(
        val shareBps: Int,
        val projectedEarningsSats: Long,
        val lifetimeEarningsSats: Long
    )
}

data class RewardSpendResponse(
    val ok: Boolean,
    val rewardSpendApplied: Boolean
)

data class LocalRewardsCheckResult(
    val rewardEligible: Boolean,
    val merchantPubkeyHash: String?
)

private data class RewardMerchantPubkeyHashList(
    val cacheTtlSeconds: Long,
    val hashes: Set<String>
)

private data class RewardClaimEncryptionKeyResponse(
    val ok: Boolean,
    val keyId: String,
    val algorithm: String,
    val publicKey: String
)

private data class EncryptedRewardSpendClaimPayload(
    val merchantPubkeyHash: String,
    val paymentHash: String,
    val preimage: String,
    val btcAmountSats: Long,
    val usdAmountCents: Int,
    val occurredAt: String,
    val invoice: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("merchantPubkeyHash", merchantPubkeyHash)
        .put("paymentHash", paymentHash)
        .put("preimage", preimage)
        .put("btcAmountSats", btcAmountSats)
        .put("usdAmountCents", usdAmountCents)
        .put("occurredAt", occurredAt)
        .put("invoice", invoice)
}

private data class EncryptedRewardSpendClaimEnvelope(
    val keyId: String,
    val algorithm: String,
    val ephemeralPublicKey: String,
    val nonce: String,
    val ciphertext: String,
    val tag: String,
    val clientClaimId: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keyId", keyId)
        .put("algorithm", algorithm)
        .put("ephemeralPublicKey", ephemeralPublicKey)
        .put("nonce", nonce)
        .put("ciphertext", ciphertext)
        .put("tag", tag)
        .put("clientClaimId", clientClaimId)
}

class RewardsRepository(
    private val httpClient: SplitHttpClient
) {
    private var cachedMerchantHashList: RewardMerchantPubkeyHashList? = null
    private var cachedMerchantHashListAtMillis: Long = 0L

    suspend fun fetchRewardsStats(
        authManager: AuthManager,
        walletManager: WalletManager
    ): RewardStatsResponse {
        authManager.ensureSession(walletManager)

        var response = httpClient.get("/v1/RewardStats")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get("/v1/RewardStats")
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to load rewards stats (${response.statusCode}).")
        }

        return response.body.toRewardStatsResponse()
    }

    suspend fun localRewardsCheck(destinationPubkey: String?): LocalRewardsCheckResult {
        val merchantPubkeyHash = rewardMerchantPubkeyHash(destinationPubkey)
            ?: run {
                RewardTrace.i("localRewardsCheck skip missing-destination")
                return LocalRewardsCheckResult(
                    rewardEligible = false,
                    merchantPubkeyHash = null
                )
            }

        val hashList = fetchMerchantHashList()
        val eligible = merchantPubkeyHash in hashList.hashes
        RewardTrace.i(
            "localRewardsCheck result eligible=$eligible " +
                "merchantHashFp=${RewardTrace.fp(merchantPubkeyHash)} listSize=${hashList.hashes.size}"
        )
        return LocalRewardsCheckResult(
            rewardEligible = eligible,
            merchantPubkeyHash = merchantPubkeyHash.takeIf { eligible }
        )
    }

    suspend fun postEncryptedRewardSpendClaim(
        merchantPubkeyHash: String?,
        paymentHash: String?,
        preimage: String?,
        btcAmountSats: Long,
        usdAmountCents: Int,
        invoice: String?,
        authManager: AuthManager,
        walletManager: WalletManager,
        occurredAt: Instant = Instant.now()
    ): RewardSpendResponse {
        val normalizedMerchantPubkeyHash = merchantPubkeyHash.normalizedProofField()
        val paymentHashProof = paymentHash.normalizedRewardProof32ByteHex()
        val preimageProof = preimage.normalizedRewardProof32ByteHex()
        val normalizedPaymentHash = paymentHashProof?.hex
        val normalizedPreimage = preimageProof?.hex
        val normalizedInvoice = invoice?.trim()?.ifBlank { null }
        val missingFields = buildList {
            if (normalizedMerchantPubkeyHash == null) add("merchant match")
            if (normalizedPaymentHash == null) add("payment hash")
            if (normalizedPreimage == null) add("preimage")
            if (normalizedInvoice == null) add("invoice")
        }

        if (missingFields.isNotEmpty()) {
            RewardTrace.w("encryptedClaim skip missing=${missingFields.joinToString("|")}")
            throw IllegalStateException("Missing ${missingFields.joinToString(", ")}.")
        }

        val requiredMerchantPubkeyHash = checkNotNull(normalizedMerchantPubkeyHash)
        val requiredPaymentHash = checkNotNull(normalizedPaymentHash)
        val requiredPreimage = checkNotNull(normalizedPreimage)
        val requiredInvoice = checkNotNull(normalizedInvoice)

        RewardTrace.i(
            "encryptedClaim preparing " +
                "merchantHashFp=${RewardTrace.fp(requiredMerchantPubkeyHash)} " +
                "paymentHashFp=${RewardTrace.fp(requiredPaymentHash)} " +
                "paymentHashEncoding=${paymentHashProof.encoding} " +
                "preimagePresent=true preimageEncoding=${preimageProof.encoding} " +
                "invoiceLen=${requiredInvoice.length} " +
                "sats=$btcAmountSats usdCents=$usdAmountCents"
        )

        authManager.ensureSession(walletManager)

        val key = fetchRewardClaimEncryptionKey()
        RewardTrace.i("encryptedClaim key keyId=${key.keyId} algorithm=${key.algorithm}")
        val payload = EncryptedRewardSpendClaimPayload(
            merchantPubkeyHash = requiredMerchantPubkeyHash,
            paymentHash = requiredPaymentHash,
            preimage = requiredPreimage,
            btcAmountSats = btcAmountSats,
            usdAmountCents = usdAmountCents,
            occurredAt = occurredAt.toString(),
            invoice = requiredInvoice
        )
        val envelope = RewardClaimEncryption.encrypt(payload, key)

        RewardTrace.i("encryptedClaim post start clientClaimId=${envelope.clientClaimId}")
        var response = httpClient.postJson("/v2/reward-spend-claims", envelope.toJson().toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            RewardTrace.w("encryptedClaim auth retry status=${response.statusCode}")
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/v2/reward-spend-claims", envelope.toJson().toString())
        }

        if (response.statusCode !in 200..299) {
            RewardTrace.w("encryptedClaim post failed status=${response.statusCode}")
            throw IllegalStateException("Failed to post encrypted reward spend claim (${response.statusCode}).")
        }

        val rewardSpendResponse = response.body.toRewardSpendResponse()
        RewardTrace.i(
            "encryptedClaim post success status=${response.statusCode} " +
                "ok=${rewardSpendResponse.ok} applied=${rewardSpendResponse.rewardSpendApplied}"
        )
        return rewardSpendResponse
    }

    private suspend fun fetchMerchantHashList(): RewardMerchantPubkeyHashList {
        val cached = cachedMerchantHashList
        val now = System.currentTimeMillis()
        if (
            cached != null &&
            now - cachedMerchantHashListAtMillis < cached.cacheTtlSeconds.coerceAtLeast(60L) * 1_000L
        ) {
            return cached
        }

        val response = httpClient.get("/v1/reward-merchant-pubkey-hashes")
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to load reward merchant hash list (${response.statusCode}).")
        }

        val parsed = response.body.toRewardMerchantPubkeyHashList()
        cachedMerchantHashList = parsed
        cachedMerchantHashListAtMillis = now
        return parsed
    }

    private suspend fun fetchRewardClaimEncryptionKey(): RewardClaimEncryptionKeyResponse {
        val response = httpClient.get("/v1/reward-claim-encryption-key")
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to load reward claim encryption key (${response.statusCode}).")
        }

        return response.body.toRewardClaimEncryptionKeyResponse()
    }
}

private object RewardClaimEncryption {
    private const val ALGORITHM = "p256-hkdf-sha256-aes-256-gcm-v1"
    private val salt = "split-reward-claim-v1".toByteArray(Charsets.UTF_8)
    private val info = "reward-spend-claim-payload".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    fun encrypt(
        payload: EncryptedRewardSpendClaimPayload,
        key: RewardClaimEncryptionKeyResponse
    ): EncryptedRewardSpendClaimEnvelope {
        if (!key.ok || key.algorithm != ALGORITHM) {
            throw IllegalStateException("The server returned an invalid reward claim encryption key.")
        }

        val serverPublicKey = decodeP256PublicKey(key.publicKey)
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        }.generateKeyPair()

        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(serverPublicKey, true)
            generateSecret()
        }
        val aesKey = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = salt,
            info = info,
            outputLength = 32
        )
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val plaintext = payload.toJson().toString().toByteArray(Charsets.UTF_8)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, nonce)
            )
            doFinal(plaintext)
        }
        val tagLength = 16
        val ciphertext = sealed.copyOfRange(0, sealed.size - tagLength)
        val tag = sealed.copyOfRange(sealed.size - tagLength, sealed.size)
        val ephemeralPublicKey = (keyPair.public as ECPublicKey).x963Representation()

        return EncryptedRewardSpendClaimEnvelope(
            keyId = key.keyId,
            algorithm = key.algorithm,
            ephemeralPublicKey = ephemeralPublicKey.toBase64(),
            nonce = nonce.toBase64(),
            ciphertext = ciphertext.toBase64(),
            tag = tag.toBase64(),
            clientClaimId = UUID.randomUUID().toString()
        )
    }

    private fun decodeP256PublicKey(base64PublicKey: String): ECPublicKey {
        val publicKeyBytes = Base64.decode(base64PublicKey, Base64.NO_WRAP)
        if (publicKeyBytes.size != 65 || publicKeyBytes[0].toInt() != 0x04) {
            throw IllegalStateException("The server returned an invalid reward claim encryption key.")
        }

        val x = BigInteger(1, publicKeyBytes.copyOfRange(1, 33))
        val y = BigInteger(1, publicKeyBytes.copyOfRange(33, 65))
        val spec = p256ParameterSpec()
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    private fun p256ParameterSpec(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray {
        val pseudorandomKey = hmacSha256(salt, inputKeyMaterial)
        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1

        while (output.size() < outputLength) {
            previous = hmacSha256(
                pseudorandomKey,
                previous + info + byteArrayOf(counter.toByte())
            )
            output.write(previous)
            counter += 1
        }

        return output.toByteArray().copyOf(outputLength)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
    }
}

private const val REWARD_MERCHANT_HASH_ALGORITHM = "sha256"
private const val REWARD_MERCHANT_HASH_NORMALIZATION = "trim-lowercase"
private const val REWARD_MERCHANT_HASH_VERSION = "split-merchant-pubkey-sha256-v1"
private const val REWARD_MERCHANT_HASH_PREFIX = "split:merchant-pubkey:v1:"

internal fun rewardMerchantPubkeyHash(pubkey: String?): String? {
    val normalized = pubkey
        ?.trim()
        ?.lowercase(Locale.US)
        ?.ifBlank { null }
        ?: return null
    return sha256Hex((REWARD_MERCHANT_HASH_PREFIX + normalized).toByteArray(Charsets.UTF_8))
}

private fun String.toRewardStatsResponse(): RewardStatsResponse {
    val json = JSONObject(this)

    val monthlyPot = json.getJSONObject("monthlyPot")
    val platform = json.getJSONObject("platform")
    val user = json.getJSONObject("user")
    val stats = json.getJSONObject("stats")

    return RewardStatsResponse(
        monthKey = json.getString("monthKey"),
        monthlyPot = RewardStatsResponse.MonthlyPot(
            sats = monthlyPot.optLong("sats", 0L)
        ),
        platform = RewardStatsResponse.PlatformTotals(
            rewardSpendCents = platform.optLong("rewardSpendCents", 0L),
            transactions = platform.optInt("transactions", 0)
        ),
        user = RewardStatsResponse.UserTotals(
            rewardSpendCents = user.optLong("rewardSpendCents", 0L),
            transactions = user.optInt("transactions", 0)
        ),
        stats = RewardStatsResponse.RewardStats(
            shareBps = stats.optInt("shareBps", 0),
            projectedEarningsSats = stats.optLong("projectedEarningsSats", 0L),
            lifetimeEarningsSats = stats.optLong("lifetimeEarningsSats", 0L)
        )
    )
}

private fun String.toRewardSpendResponse(): RewardSpendResponse {
    val json = JSONObject(this)
    return RewardSpendResponse(
        ok = json.optBoolean("ok", false),
        rewardSpendApplied = json.optBoolean("rewardSpendApplied", false)
    )
}

private fun String.toRewardMerchantPubkeyHashList(): RewardMerchantPubkeyHashList {
    val json = JSONObject(this)
    val algorithm = json.optString("algorithm")
    val normalization = json.optString("normalization")
    val hashVersion = json.optString("hashVersion")
    val hashPrefix = json.optString("hashPrefix")

    if (
        algorithm != REWARD_MERCHANT_HASH_ALGORITHM ||
        normalization != REWARD_MERCHANT_HASH_NORMALIZATION ||
        hashVersion != REWARD_MERCHANT_HASH_VERSION ||
        hashPrefix != REWARD_MERCHANT_HASH_PREFIX
    ) {
        throw IllegalStateException("The server returned an unsupported reward merchant hash list.")
    }

    val hashesJson = json.optJSONArray("hashes")
        ?: throw IllegalStateException("The server returned an invalid reward merchant hash list.")
    val hashes = mutableSetOf<String>()
    for (index in 0 until hashesJson.length()) {
        hashesJson.optString(index)
            .trim()
            .lowercase(Locale.US)
            .ifBlank { null }
            ?.let(hashes::add)
    }

    return RewardMerchantPubkeyHashList(
        cacheTtlSeconds = json.optLong("cacheTtlSeconds", 3_600L).coerceAtLeast(60L),
        hashes = hashes
    )
}

private fun String.toRewardClaimEncryptionKeyResponse(): RewardClaimEncryptionKeyResponse {
    val json = JSONObject(this)
    return RewardClaimEncryptionKeyResponse(
        ok = json.optBoolean("ok", false),
        keyId = json.optString("keyId"),
        algorithm = json.optString("algorithm"),
        publicKey = json.optString("publicKey")
    )
}

private fun sha256Hex(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun String?.normalizedProofField(): String? {
    return this
        ?.trim()
        ?.lowercase(Locale.US)
        ?.ifBlank { null }
}

private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun ECPublicKey.x963Representation(): ByteArray {
    return byteArrayOf(0x04) +
        w.affineX.toUnsignedFixedLength(32) +
        w.affineY.toUnsignedFixedLength(32)
}

private fun BigInteger.toUnsignedFixedLength(length: Int): ByteArray {
    val unsigned = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    if (unsigned.size > length) {
        throw IllegalStateException("Invalid P-256 public key coordinate.")
    }
    return ByteArray(length - unsigned.size) + unsigned
}
