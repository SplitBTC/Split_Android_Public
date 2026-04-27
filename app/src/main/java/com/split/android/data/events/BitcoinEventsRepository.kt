package com.split.android.data.events

import com.split.android.data.network.SplitHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class BitcoinEventSearchOrigin(
    val source: String,
    val latitude: Double,
    val longitude: Double,
    val postalCode: String?,
    val formattedAddress: String?
)

data class BitcoinEventItem(
    val id: String,
    val source: String,
    val sourceUrl: String,
    val externalEventId: String,
    val title: String,
    val description: String,
    val coverImageUrl: String?,
    val hostName: String,
    val startsAt: String,
    val endsAt: String?,
    val timezone: String,
    val venueName: String,
    val address: String,
    val city: String,
    val region: String,
    val postalCode: String,
    val country: String,
    val latitude: Double?,
    val longitude: Double?,
    val distanceMiles: Double?
)

data class BitcoinEventsResponse(
    val nearbyEvents: List<BitcoinEventItem>,
    val moreEvents: List<BitcoinEventItem>,
    val searchOrigin: BitcoinEventSearchOrigin?,
    val radiusMiles: Double
)

class BitcoinEventsRepository(
    private val httpClient: SplitHttpClient
) {
    suspend fun fetchEvents(
        latitude: Double,
        longitude: Double
    ): BitcoinEventsResponse {
        val response = httpClient.get(
            "/v1/bitcoin-events?latitude=$latitude&longitude=$longitude"
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                serverErrorMessage(response.body) ?: "Failed to load Bitcoin events (${response.statusCode})."
            )
        }

        return response.body.toBitcoinEventsResponse()
    }

    suspend fun fetchEvents(
        postalCode: String
    ): BitcoinEventsResponse {
        val encodedPostalCode = URLEncoder.encode(postalCode, Charsets.UTF_8.name())
        val response = httpClient.get(
            "/v1/bitcoin-events?postalCode=$encodedPostalCode"
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                serverErrorMessage(response.body) ?: "Failed to load Bitcoin events (${response.statusCode})."
            )
        }

        return response.body.toBitcoinEventsResponse()
    }
}

private fun String.toBitcoinEventsResponse(): BitcoinEventsResponse {
    val json = JSONObject(this)
    val nearbyEvents = json.optJSONArray("nearbyEvents") ?: JSONArray()
    val moreEvents = json.optJSONArray("moreEvents") ?: JSONArray()

    return BitcoinEventsResponse(
        nearbyEvents = nearbyEvents.toBitcoinEventList(),
        moreEvents = moreEvents.toBitcoinEventList(),
        searchOrigin = json.optJSONObject("searchOrigin")?.toBitcoinEventSearchOrigin(),
        radiusMiles = json.optDoubleOrNull("radiusMiles") ?: 25.0
    )
}

private fun JSONArray.toBitcoinEventList(): List<BitcoinEventItem> {
    return buildList(length()) {
        for (index in 0 until length()) {
            val eventJson = optJSONObject(index) ?: continue
            add(eventJson.toBitcoinEventItem())
        }
    }
}

private fun JSONObject.toBitcoinEventItem(): BitcoinEventItem {
    return BitcoinEventItem(
        id = optString("id"),
        source = optString("source"),
        sourceUrl = optString("sourceUrl"),
        externalEventId = optString("externalEventId"),
        title = optString("title"),
        description = optString("description"),
        coverImageUrl = optNullableString("coverImageUrl"),
        hostName = optString("hostName"),
        startsAt = optString("startsAt"),
        endsAt = optNullableString("endsAt"),
        timezone = optString("timezone"),
        venueName = optString("venueName"),
        address = optString("address"),
        city = optString("city"),
        region = optString("region"),
        postalCode = optString("postalCode"),
        country = optString("country"),
        latitude = optDoubleOrNull("latitude"),
        longitude = optDoubleOrNull("longitude"),
        distanceMiles = optDoubleOrNull("distanceMiles")
    )
}

private fun JSONObject.toBitcoinEventSearchOrigin(): BitcoinEventSearchOrigin {
    return BitcoinEventSearchOrigin(
        source = optString("source"),
        latitude = optDouble("latitude"),
        longitude = optDouble("longitude"),
        postalCode = optNullableString("postalCode"),
        formattedAddress = optNullableString("formattedAddress")
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    if (isNull(key)) {
        return null
    }

    return optString(key).trim().ifBlank { null }
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
        val json = JSONObject(body)
        json.optString("message").takeIf { it.isNotBlank() }
            ?: json.optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()
}
