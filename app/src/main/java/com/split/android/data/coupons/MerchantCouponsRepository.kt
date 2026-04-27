package com.split.android.data.coupons

import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.wallet.WalletManager
import org.json.JSONArray
import org.json.JSONObject

data class NearbyCouponAddress(
    val formattedAddress: String,
    val line1: String,
    val line2: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val countryCode: String,
    val placeId: String,
    val latitude: Double,
    val longitude: Double
)

data class NearbyCouponSearchOrigin(
    val source: String,
    val latitude: Double,
    val longitude: Double,
    val postalCode: String?,
    val formattedAddress: String?
)

data class NearbyCoupon(
    val id: String,
    val businessName: String,
    val businessLogoUrl: String?,
    val dealDescription: String,
    val appliesToAllLocations: Boolean,
    val hasRedeemedThisMonth: Boolean,
    val currentUserRedeemedAt: String?,
    val primaryBusinessAddress: NearbyCouponAddress,
    val distanceMiles: Double?
)

data class NearbyCouponsResponse(
    val coupons: List<NearbyCoupon>,
    val searchOrigin: NearbyCouponSearchOrigin,
    val radiusMiles: Double
)

data class RedeemNearbyCouponResponse(
    val ok: Boolean,
    val didRedeem: Boolean,
    val alreadyRedeemedThisMonth: Boolean,
    val redemptionMonth: String,
    val redeemedAt: String?
)

class MerchantCouponsRepository(
    private val httpClient: SplitHttpClient
) {
    suspend fun fetchNearbyCoupons(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double,
        authManager: AuthManager,
        walletManager: WalletManager
    ): NearbyCouponsResponse {
        val path = "/v1/merchant-coupons/nearby?latitude=$latitude&longitude=$longitude&radiusMiles=$radiusMiles"
        val response = authenticatedGet(
            path = path,
            authManager = authManager,
            walletManager = walletManager
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(serverErrorMessage(response.body) ?: "Failed to load nearby promos (${response.statusCode}).")
        }

        return response.body.toNearbyCouponsResponse()
    }

    suspend fun fetchNearbyCoupons(
        postalCode: String,
        radiusMiles: Double,
        authManager: AuthManager,
        walletManager: WalletManager
    ): NearbyCouponsResponse {
        val path = "/v1/merchant-coupons/nearby?postalCode=$postalCode&radiusMiles=$radiusMiles"
        val response = authenticatedGet(
            path = path,
            authManager = authManager,
            walletManager = walletManager
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(serverErrorMessage(response.body) ?: "Failed to load nearby promos (${response.statusCode}).")
        }

        return response.body.toNearbyCouponsResponse()
    }

    suspend fun redeemCoupon(
        couponId: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ): RedeemNearbyCouponResponse {
        val response = authenticatedPost(
            path = "/v1/merchant-coupons/$couponId/redeem",
            jsonBody = JSONObject().toString(),
            authManager = authManager,
            walletManager = walletManager
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(serverErrorMessage(response.body) ?: "Failed to redeem promo (${response.statusCode}).")
        }

        return JSONObject(response.body).toRedeemNearbyCouponResponse()
    }

    private suspend fun authenticatedGet(
        path: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ) = authenticatedRequest(
        authManager = authManager,
        walletManager = walletManager
    ) {
        httpClient.get(path)
    }

    private suspend fun authenticatedPost(
        path: String,
        jsonBody: String,
        authManager: AuthManager,
        walletManager: WalletManager
    ) = authenticatedRequest(
        authManager = authManager,
        walletManager = walletManager
    ) {
        httpClient.postJson(path, jsonBody)
    }

    private suspend fun authenticatedRequest(
        authManager: AuthManager,
        walletManager: WalletManager,
        request: suspend () -> com.split.android.data.network.HttpResponse
    ): com.split.android.data.network.HttpResponse {
        authManager.ensureSession(walletManager)

        var response = request()
        if (response.statusCode == 401 || response.statusCode == 403) {
            authManager.invalidateSession()
            authManager.ensureSession(walletManager)
            response = request()
        }

        return response
    }
}

private fun String.toNearbyCouponsResponse(): NearbyCouponsResponse {
    val json = JSONObject(this)
    val coupons = json.optJSONArray("coupons") ?: JSONArray()
    val searchOrigin = json.optJSONObject("searchOrigin")
        ?: throw IllegalStateException("Invalid nearby promos response.")

    return NearbyCouponsResponse(
        coupons = buildList(coupons.length()) {
            for (index in 0 until coupons.length()) {
                val couponJson = coupons.optJSONObject(index) ?: continue
                add(couponJson.toNearbyCoupon())
            }
        },
        searchOrigin = searchOrigin.toNearbyCouponSearchOrigin(),
        radiusMiles = json.optDoubleOrNull("radiusMiles") ?: 25.0
    )
}

private fun JSONObject.toNearbyCoupon(): NearbyCoupon {
    val address = optJSONObject("primaryBusinessAddress")
        ?: throw IllegalStateException("Invalid nearby promo address.")

    return NearbyCoupon(
        id = optString("id"),
        businessName = optString("businessName"),
        businessLogoUrl = optNullableString("businessLogoUrl"),
        dealDescription = optString("dealDescription"),
        appliesToAllLocations = optBoolean("appliesToAllLocations"),
        hasRedeemedThisMonth = optBoolean("hasRedeemedThisMonth"),
        currentUserRedeemedAt = optNullableString("currentUserRedeemedAt"),
        primaryBusinessAddress = address.toNearbyCouponAddress(),
        distanceMiles = optDoubleOrNull("distanceMiles")
    )
}

private fun JSONObject.toNearbyCouponAddress(): NearbyCouponAddress {
    return NearbyCouponAddress(
        formattedAddress = optString("formattedAddress"),
        line1 = optString("line1"),
        line2 = optString("line2"),
        city = optString("city"),
        state = optString("state"),
        postalCode = optString("postalCode"),
        countryCode = optString("countryCode"),
        placeId = optString("placeId"),
        latitude = optDouble("latitude"),
        longitude = optDouble("longitude")
    )
}

private fun JSONObject.toNearbyCouponSearchOrigin(): NearbyCouponSearchOrigin {
    return NearbyCouponSearchOrigin(
        source = optString("source"),
        latitude = optDouble("latitude"),
        longitude = optDouble("longitude"),
        postalCode = optNullableString("postalCode"),
        formattedAddress = optNullableString("formattedAddress")
    )
}

private fun JSONObject.toRedeemNearbyCouponResponse(): RedeemNearbyCouponResponse {
    return RedeemNearbyCouponResponse(
        ok = optBoolean("ok"),
        didRedeem = optBoolean("didRedeem"),
        alreadyRedeemedThisMonth = optBoolean("alreadyRedeemedThisMonth"),
        redemptionMonth = optString("redemptionMonth"),
        redeemedAt = optNullableString("redeemedAt")
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    if (isNull(key)) {
        return null
    }

    return optString(key).takeIf { it.isNotBlank() }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (isNull(key) || !has(key)) {
        return null
    }

    val value = optDouble(key)
    return if (value.isNaN()) null else value
}

private fun serverErrorMessage(body: String): String? {
    return runCatching {
        JSONObject(body).optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()
}
