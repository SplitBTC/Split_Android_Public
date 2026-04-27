package com.split.android.data.map

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class BtcMerchantPlace(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val icon: String?,
    val name: String?,
    val address: String?,
    val phone: String?,
    val website: String?,
    val description: String?,
    val image: String?,
    val paymentProvider: String?,
    val verifiedAt: String?,
    val osmUrl: String?
)

class BtcMerchantMapRepository {
    private companion object {
        const val TAG = "BtcMerchantMapRepo"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val MAX_ATTEMPTS = 2
        const val USER_AGENT = "SplitAndroid/0.1"
    }

    suspend fun fetchPlaces(
        latitude: Double,
        longitude: Double,
        radiusKilometers: Double
    ): List<BtcMerchantPlace> = withContext(Dispatchers.IO) {
        if (!latitude.isFinite() || !longitude.isFinite() || !radiusKilometers.isFinite()) {
            return@withContext emptyList()
        }

        val queryUrl = Uri.parse("https://api.btcmap.org/v4/places/search/")
            .buildUpon()
            .appendQueryParameter("lat", latitude.toString())
            .appendQueryParameter("lon", longitude.toString())
            .appendQueryParameter("radius_km", radiusKilometers.toString())
            .appendQueryParameter(
                "fields",
                "id,lat,lon,icon,name,address,phone,website,description,image,payment_provider,verified_at,osm_url"
            )
            .build()
            .toString()

        Log.d(
            TAG,
            "fetchPlaces lat=$latitude lon=$longitude radiusKm=$radiusKilometers url=$queryUrl"
        )

        var lastIoError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val places = performFetch(queryUrl)
                Log.d(TAG, "fetchPlaces success count=${places.size}")
                return@withContext places
            } catch (error: IOException) {
                lastIoError = error
                Log.w(TAG, "BTC Map fetch attempt ${attempt + 1} failed", error)
            }
        }

        throw lastIoError ?: IllegalStateException("BTC Map request failed.")
    }

    private fun performFetch(urlString: String): List<BtcMerchantPlace> {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doInput = true
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val statusCode = connection.responseCode
            val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (statusCode !in 200..299) {
                Log.w(TAG, "BTC Map HTTP $statusCode: $body")
                throw IllegalStateException(
                    body.ifBlank { "BTC Map returned HTTP $statusCode." }
                )
            }

            val trimmedBody = body.trim()
            if (trimmedBody.isEmpty() || trimmedBody == "null") {
                return emptyList()
            }

            val places = when {
                trimmedBody.startsWith("[") -> JSONArray(trimmedBody)
                trimmedBody.startsWith("{") -> JSONObject(trimmedBody).optJSONArray("data") ?: JSONArray()
                else -> {
                    Log.w(TAG, "Unexpected BTC Map payload: $trimmedBody")
                    JSONArray()
                }
            }

            return buildList<BtcMerchantPlace>(places.length()) {
                for (index in 0 until places.length()) {
                    val item = places.optJSONObject(index) ?: continue
                    add(item.toBtcMerchantPlace())
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "BTC Map fetch failed", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

private fun JSONObject.toBtcMerchantPlace(): BtcMerchantPlace {
    return BtcMerchantPlace(
        id = optInt("id"),
        lat = optDouble("lat"),
        lon = optDouble("lon"),
        icon = optString("icon").ifBlank { null },
        name = optString("name").ifBlank { null },
        address = optString("address").ifBlank { null },
        phone = optString("phone").ifBlank { null },
        website = optString("website").ifBlank { null },
        description = optString("description").ifBlank { null },
        image = optString("image").ifBlank { null },
        paymentProvider = optString("payment_provider").ifBlank { null },
        verifiedAt = optString("verified_at").ifBlank { null },
        osmUrl = optString("osm_url").ifBlank { null }
    )
}
