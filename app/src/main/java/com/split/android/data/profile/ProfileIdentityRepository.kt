package com.split.android.data.profile

import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import org.json.JSONObject

class ProfileIdentityRepository(
    private val httpClient: SplitHttpClient
) {
    suspend fun fetchProfilePicUrl(
        authManager: AuthManager,
        walletManager: WalletManager
    ): String? {
        authManager.ensureSession(walletManager)

        var response = httpClient.get("/Profile_Pic")
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.get("/Profile_Pic")
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to load profile picture (${response.statusCode}).")
        }

        return runCatching {
            JSONObject(response.body).optString("profilePicUrl").trim().ifBlank { null }
        }.getOrNull()
    }

    suspend fun uploadProfilePic(
        fileData: ByteArray,
        fileName: String,
        mimeType: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): String? {
        require(fileData.isNotEmpty()) { "Profile picture data is empty." }

        authManager.ensureSession(walletManager)

        var response = httpClient.postMultipart(
            path = "/Upload_Profile_Pic",
            formFields = emptyMap(),
            fileFieldName = "profilePic",
            fileName = fileName,
            mimeType = mimeType,
            fileData = fileData
        )

        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postMultipart(
                path = "/Upload_Profile_Pic",
                formFields = emptyMap(),
                fileFieldName = "profilePic",
                fileName = fileName,
                mimeType = mimeType,
                fileData = fileData
            )
        }

        if (response.statusCode !in 200..299) {
            val serverMessage = runCatching {
                JSONObject(response.body).optString("error")
            }.getOrNull().orEmpty()

            throw IllegalStateException(
                serverMessage.ifBlank {
                    "Failed to upload profile picture (${response.statusCode})."
                }
            )
        }

        return runCatching {
            JSONObject(response.body).optString("profilePicUrl").trim().ifBlank { null }
        }.getOrNull()
    }

    suspend fun syncLightningAddress(
        lightningAddress: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        val normalized = lightningAddress.trim().lowercase()
        require(normalized.isNotBlank()) { "Lightning address is invalid." }

        authManager.ensureSession(walletManager)

        val body = JSONObject()
            .put("lightningAddress", normalized)
            .toString()

        var response = httpClient.postJson("/lightning-address", body)
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/lightning-address", body)
        }

        if (response.statusCode !in 200..299) {
            val serverMessage = runCatching {
                JSONObject(response.body).optString("error")
            }.getOrNull().orEmpty()

            throw IllegalStateException(
                serverMessage.ifBlank {
                    "Failed to sync Lightning address (${response.statusCode})."
                }
            )
        }
    }
}
