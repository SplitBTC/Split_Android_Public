package com.split.android.data.merchants

import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import com.split.android.data.wallet.WalletTransactionRow
import org.json.JSONObject

class MerchantReportRepository(
    private val httpClient: SplitHttpClient
) {
    suspend fun reportMerchant(
        merchantName: String,
        merchantAddress: String,
        transaction: WalletTransactionRow,
        authManager: AuthManager,
        walletManager: WalletManager
    ) {
        val destinationPubkey = transaction.destinationPubkey?.trim().orEmpty()
        require(destinationPubkey.isNotEmpty()) { "This transaction does not include a merchant pubkey." }

        authManager.ensureSession(walletManager)

        val requestBody = JSONObject()
            .put("merchantName", merchantName.trim())
            .put("merchantAddress", merchantAddress.trim())
            .put("destinationPubkey", destinationPubkey)

        var response = httpClient.postJson(
            path = "/ReportMerchantPubkey",
            jsonBody = requestBody.toString()
        )

        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson(
                path = "/ReportMerchantPubkey",
                jsonBody = requestBody.toString()
            )
        }

        if (response.statusCode !in 200..299) {
            val message = runCatching {
                JSONObject(response.body).optString("error")
            }.getOrNull().orEmpty()

            throw IllegalStateException(
                message.ifBlank { "Failed to add merchant (${response.statusCode})." }
            )
        }
    }
}
