package com.split.android.data.wallet

import com.split.android.data.network.SplitHttpClient
import org.json.JSONObject

class BreezApiRepository(
    private val httpClient: SplitHttpClient
) {

    suspend fun getApiKey(): String {
        val response = httpClient.get("/breez-api-key")
        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                "Failed to fetch Breez API key (${response.statusCode})."
            )
        }

        val json = JSONObject(response.body)
        val apiKey = json.optString("apiKey").trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("Server returned an empty Breez API key.")
        }

        return apiKey
    }
}

