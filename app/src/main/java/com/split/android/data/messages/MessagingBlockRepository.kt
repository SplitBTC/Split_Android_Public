package com.split.android.data.messages

import android.content.Context
import com.split.android.core.AppConfig
import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class MessagingBlockRepository(
    context: Context,
    private val httpClient: SplitHttpClient
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "split_messaging_v4_block_metadata",
        Context.MODE_PRIVATE
    )

    suspend fun fetchBlocks(
        authManager: AuthManager,
        walletManager: WalletManager
    ): List<MessagingBlockedUser> {
        authManager.ensureSession(walletManager)

        val headers = authenticatedWalletPubkeyHeaders(walletManager.currentWalletPubkey())
        var response = httpClient.get("/messaging/v4/blocks", headers)
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get("/messaging/v4/blocks", headers)
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                extractServerMessage(
                    body = response.body,
                    statusCode = response.statusCode,
                    fallback = "Failed to load blocked users."
                )
            )
        }

        return response.body.toMessagingBlockedUsers().map(::applyCachedMetadata)
    }

    suspend fun blockUser(
        walletPubkey: String?,
        lightningAddress: String?,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessagingBlockedUser {
        val normalizedWalletPubkey = walletPubkey?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedLightningAddress = lightningAddress
            ?.let { MessagingPrivacyV4.normalizeLightningAddress(it) }

        require(normalizedLightningAddress != null) {
            "Select a valid user to block."
        }

        authManager.ensureSession(walletManager)
        val headers = authenticatedWalletPubkeyHeaders(walletManager.currentWalletPubkey())

        val requestBody = JSONObject()
            .put("lightningAddressHash", MessagingPrivacyV4.lightningAddressClientHash(normalizedLightningAddress))
            .put("lightningAddressHashScheme", MessagingPrivacyV4.LIGHTNING_ADDRESS_CLIENT_HASH_SCHEME)

        var response = httpClient.postJson(
            path = "/messaging/v4/blocks",
            jsonBody = requestBody.toString(),
            headers = headers
        )
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson(
                path = "/messaging/v4/blocks",
                jsonBody = requestBody.toString(),
                headers = headers
            )
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                extractServerMessage(
                    body = response.body,
                    statusCode = response.statusCode,
                    fallback = "Failed to block user."
                )
            )
        }

        val rootJson = JSONObject(response.body)
        val blockJson = rootJson.optJSONObject("block")
            ?: throw IllegalStateException("Invalid blocked user response.")

        return blockJson.toMessagingBlockedUser()
            .withLocalMetadata(
                walletPubkey = normalizedWalletPubkey,
                lightningAddress = normalizedLightningAddress
            )
            .also(::cacheMetadata)
    }

    suspend fun unblockUser(
        blockedWalletPubkey: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): Boolean {
        val targetHash = blockTargetHash(blockedWalletPubkey)

        authManager.ensureSession(walletManager)

        val headers = authenticatedWalletPubkeyHeaders(walletManager.currentWalletPubkey())
        var response = httpClient.delete("/messaging/v4/blocks/$targetHash", headers)
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.delete("/messaging/v4/blocks/$targetHash", headers)
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                extractServerMessage(
                    body = response.body,
                    statusCode = response.statusCode,
                    fallback = "Failed to unblock user."
                )
            )
        }

        val didDelete = JSONObject(response.body).optBoolean("didDelete", false)
        if (didDelete) {
            removeCachedMetadata(blockedWalletPubkey)
        }
        return didDelete
    }

    private fun blockTargetHash(target: String): String {
        val normalizedTarget = target.trim()
        require(normalizedTarget.isNotEmpty()) { "Blocked user identifier is required." }
        if (normalizedTarget.contains("@")) {
            return MessagingPrivacyV4.lightningAddressClientHash(normalizedTarget)
        }

        val cached = cachedMetadata().firstOrNull { metadata ->
            metadata.blockId == normalizedTarget ||
                metadata.blockedMessagingAccountId == normalizedTarget ||
                metadata.blockedWalletPubkey == normalizedTarget
        }
        val lightningAddress = cached?.blockedLightningAddress
            ?: throw IllegalStateException("Blocked user's Lightning address is unavailable on this device.")
        return MessagingPrivacyV4.lightningAddressClientHash(lightningAddress)
    }

    private fun cacheMetadata(block: MessagingBlockedUser) {
        val entries = cachedMetadata()
            .filterNot { metadata ->
                metadata.blockId == block.blockId ||
                    metadata.blockedMessagingAccountId == block.blockedMessagingAccountId ||
                    metadata.blockedWalletPubkey == block.blockedWalletPubkey
            }
            .plus(
                CachedBlockMetadata(
                    blockId = block.blockId,
                    blockedMessagingAccountId = block.blockedMessagingAccountId,
                    blockedWalletPubkey = block.blockedWalletPubkey.takeIf { it.isNotBlank() },
                    blockedLightningAddress = block.blockedLightningAddress?.takeIf { it.isNotBlank() }
                )
            )
        saveCachedMetadata(entries)
    }

    private fun applyCachedMetadata(block: MessagingBlockedUser): MessagingBlockedUser {
        val cached = cachedMetadata().firstOrNull { metadata ->
            metadata.blockId == block.blockId ||
                metadata.blockedMessagingAccountId == block.blockedMessagingAccountId
        } ?: return block
        return block.withLocalMetadata(
            walletPubkey = cached.blockedWalletPubkey,
            lightningAddress = cached.blockedLightningAddress
        )
    }

    private fun removeCachedMetadata(target: String) {
        val normalizedTarget = target.trim()
        saveCachedMetadata(cachedMetadata().filterNot { metadata ->
            metadata.blockId == normalizedTarget ||
                metadata.blockedMessagingAccountId == normalizedTarget ||
                metadata.blockedWalletPubkey == normalizedTarget ||
                metadata.blockedLightningAddress == normalizedTarget
        })
    }

    private fun cachedMetadata(): List<CachedBlockMetadata> {
        val raw = preferences.getString(cacheKey(), null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    add(
                        CachedBlockMetadata(
                            blockId = json.getString("blockId"),
                            blockedMessagingAccountId = json.optString("blockedMessagingAccountId").ifBlank { null },
                            blockedWalletPubkey = json.optString("blockedWalletPubkey").ifBlank { null },
                            blockedLightningAddress = json.optString("blockedLightningAddress").ifBlank { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCachedMetadata(entries: List<CachedBlockMetadata>) {
        val array = JSONArray()
        entries.forEach { metadata ->
            array.put(
                JSONObject()
                    .put("blockId", metadata.blockId)
                    .put("blockedMessagingAccountId", metadata.blockedMessagingAccountId)
                    .put("blockedWalletPubkey", metadata.blockedWalletPubkey)
                    .put("blockedLightningAddress", metadata.blockedLightningAddress)
            )
        }
        preferences.edit().putString(cacheKey(), array.toString()).apply()
    }

    private fun cacheKey(): String = "blocks.${AppConfig.messagingPushEnvironment}"

    private data class CachedBlockMetadata(
        val blockId: String,
        val blockedMessagingAccountId: String?,
        val blockedWalletPubkey: String?,
        val blockedLightningAddress: String?
    )
}

private fun String.toMessagingBlockedUsers(): List<MessagingBlockedUser> {
    val rootJson = JSONObject(this)
    val blocks = rootJson.optJSONArray("blocks") ?: JSONArray()

    return buildList(blocks.length()) {
        for (index in 0 until blocks.length()) {
            val blockJson = blocks.optJSONObject(index) ?: continue
            add(blockJson.toMessagingBlockedUser())
        }
    }
}

private fun JSONObject.toMessagingBlockedUser(): MessagingBlockedUser {
    return MessagingBlockedUser(
        blockId = optString("blockId").ifBlank { optString("_id") },
        blockedMessagingAccountId = optString("blockedMessagingAccountId").ifBlank { null },
        blockedUserId = optString("blockedUserId").ifBlank {
            optString("blockedMessagingAccountId").ifBlank { optString("blockId") }
        },
        blockedWalletPubkey = optString("blockedWalletPubkey").ifBlank {
            optString("blockedMessagingAccountId")
        },
        blockedLightningAddress = optString("blockedLightningAddress").ifBlank { null },
        blockedProfilePicUrl = optString("blockedProfilePicUrl").ifBlank { null },
        createdAtMillis = optString("createdAt").toIsoMillisOrNull(),
        updatedAtMillis = optString("updatedAt").toIsoMillisOrNull()
    )
}

private fun MessagingBlockedUser.withLocalMetadata(
    walletPubkey: String?,
    lightningAddress: String?
): MessagingBlockedUser {
    return copy(
        blockedWalletPubkey = walletPubkey?.trim()?.takeIf { it.isNotEmpty() } ?: blockedWalletPubkey,
        blockedLightningAddress = lightningAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: blockedLightningAddress
    )
}

private fun String.toIsoMillisOrNull(): Long? {
    return runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
}

private fun extractServerMessage(
    body: String,
    statusCode: Int,
    fallback: String
): String {
    val message = runCatching {
        JSONObject(body).optString("error")
    }.getOrNull().orEmpty()

    return message.ifBlank { "$fallback ($statusCode)." }
}
