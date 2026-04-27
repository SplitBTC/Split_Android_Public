package com.split.android.data.rewards

import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import org.json.JSONObject

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

class RewardsRepository(
    private val httpClient: SplitHttpClient
) {
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

    suspend fun postRewardSpend(
        direction: String,
        usdAmountCents: Int,
        btcAmountSats: Long,
        destinationPubkey: String?,
        network: String,
        status: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): RewardSpendResponse {
        authManager.ensureSession(walletManager)

        val requestBody = JSONObject()
            .put("direction", direction)
            .put("usdAmountCents", usdAmountCents)
            .put("btcAmountSats", btcAmountSats)
            .put("destinationPubkey", destinationPubkey)
            .put("network", network)
            .put("status", status)

        var response = httpClient.postJson("/LogRewardSpend", requestBody.toString())
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = httpClient.postJson("/LogRewardSpend", requestBody.toString())
        }

        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Failed to log reward spend (${response.statusCode}).")
        }

        return response.body.toRewardSpendResponse()
    }
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
