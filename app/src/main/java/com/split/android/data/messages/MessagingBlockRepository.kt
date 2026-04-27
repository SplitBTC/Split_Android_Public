package com.split.android.data.messages

import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class MessagingBlockRepository(
    private val httpClient: SplitHttpClient
) {
    suspend fun fetchBlocks(
        authManager: AuthManager,
        walletManager: WalletManager
    ): List<MessagingBlockedUser> {
        authManager.ensureSession(walletManager)

        var response = httpClient.get("/messaging/blocks")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get("/messaging/blocks")
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

        return response.body.toMessagingBlockedUsers()
    }

    suspend fun blockUser(
        walletPubkey: String?,
        lightningAddress: String?,
        authManager: AuthManager,
        walletManager: WalletManager
    ): MessagingBlockedUser {
        val normalizedWalletPubkey = walletPubkey?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedLightningAddress = lightningAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        require(normalizedWalletPubkey != null || normalizedLightningAddress != null) {
            "Select a valid user to block."
        }

        authManager.ensureSession(walletManager)

        val requestBody = JSONObject()
        if (normalizedWalletPubkey != null) {
            requestBody.put("walletPubkey", normalizedWalletPubkey)
        }
        if (normalizedLightningAddress != null) {
            requestBody.put("lightningAddress", normalizedLightningAddress)
        }

        var response = httpClient.postJson(
            path = "/messaging/blocks",
            jsonBody = requestBody.toString()
        )
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson(
                path = "/messaging/blocks",
                jsonBody = requestBody.toString()
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
    }

    suspend fun unblockUser(
        blockedWalletPubkey: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): Boolean {
        val normalizedWalletPubkey = blockedWalletPubkey.trim()
        require(normalizedWalletPubkey.isNotEmpty()) { "Blocked wallet pubkey is required." }

        authManager.ensureSession(walletManager)

        var response = httpClient.delete("/messaging/blocks/$normalizedWalletPubkey")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.delete("/messaging/blocks/$normalizedWalletPubkey")
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

        return JSONObject(response.body).optBoolean("didDelete", false)
    }
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
        blockedUserId = optString("blockedUserId"),
        blockedWalletPubkey = optString("blockedWalletPubkey"),
        blockedLightningAddress = optString("blockedLightningAddress").ifBlank { null },
        blockedProfilePicUrl = optString("blockedProfilePicUrl").ifBlank { null },
        createdAtMillis = optString("createdAt").toIsoMillisOrNull(),
        updatedAtMillis = optString("updatedAt").toIsoMillisOrNull()
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
