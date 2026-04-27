package com.split.android.data.pricing

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

data class BitcoinPricePoint(
    val timestampMillis: Long,
    val priceUsd: Double
)

enum class BitcoinChartRange {
    DAY,
    MONTH,
    YEAR,
    YEAR_TO_DATE
}

class BtcPriceRepository {
    suspend fun fetchSpotUsdPrice(): Double = withContext(Dispatchers.IO) {
        fetchSpotUsdPriceBlocking()
    }

    suspend fun fetchUsdPriceSeries(range: BitcoinChartRange): List<BitcoinPricePoint> =
        withContext(Dispatchers.IO) {
            fetchUsdPriceSeriesBlocking(range)
        }

    suspend fun fetchUsdPriceAt(timestampMillis: Long): Double = withContext(Dispatchers.IO) {
        val nowMillis = System.currentTimeMillis()
        if (abs(nowMillis - timestampMillis) <= RECENT_SPOT_WINDOW_MILLIS) {
            return@withContext fetchSpotUsdPriceBlocking()
        }

        val request = historicalRequestFor(
            timestampMillis = timestampMillis,
            nowMillis = nowMillis
        )
        val cacheKey = historicalCacheKey(
            timestampMillis = timestampMillis,
            granularitySeconds = request.granularitySeconds
        )

        synchronized(cacheLock) {
            historicalPriceCache[cacheKey]
        }?.let { return@withContext it }

        val nearestPriceUsd = fetchCoinbaseCandles(
            startMillis = request.startMillis,
            endMillis = request.endMillis,
            granularitySeconds = request.granularitySeconds
        ).minByOrNull { candle ->
            abs(candle.timestampMillis - timestampMillis)
        }?.closeUsd ?: throw IllegalStateException(
            "No BTC/USD candle found near transaction time."
        )

        synchronized(cacheLock) {
            historicalPriceCache[cacheKey] = nearestPriceUsd
        }
        nearestPriceUsd
    }

    @Volatile
    private var cachedSpotPrice: CachedSpotPrice? = null

    private val historicalPriceCache = mutableMapOf<String, Double>()
    private val chartSeriesCache = mutableMapOf<String, CachedChartSeries>()
    private val cacheLock = Any()

    private fun fetchSpotUsdPriceBlocking(): Double {
        val nowMillis = System.currentTimeMillis()
        cachedSpotPrice?.takeIf {
            nowMillis - it.fetchedAtMillis <= SPOT_CACHE_TTL_MILLIS
        }?.let { return it.priceUsd }

        val responseBody = performGet(COINBASE_SPOT_URL)
        val priceUsd = JSONObject(responseBody)
            .getJSONObject("data")
            .getString("amount")
            .toDouble()

        cachedSpotPrice = CachedSpotPrice(
            priceUsd = priceUsd,
            fetchedAtMillis = nowMillis
        )
        return priceUsd
    }

    private fun fetchUsdPriceSeriesBlocking(range: BitcoinChartRange): List<BitcoinPricePoint> {
        val nowMillis = System.currentTimeMillis()
        val request = chartRequestFor(range = range, nowMillis = nowMillis)
        val cacheKey = "${range.name}:${request.granularitySeconds}"

        synchronized(cacheLock) {
            chartSeriesCache[cacheKey]
        }?.takeIf { cached ->
            nowMillis - cached.fetchedAtMillis <= SERIES_CACHE_TTL_MILLIS
        }?.let { return it.points }

        val maxCandlesPerRequest = 300L
        val maxSpanMillis = request.granularitySeconds * maxCandlesPerRequest * 1_000L
        val dedupedCandles = linkedMapOf<Long, BitcoinCandle>()

        var windowStartMillis = request.startMillis
        while (windowStartMillis < nowMillis) {
            val windowEndMillis = minOf(nowMillis, windowStartMillis + maxSpanMillis)
            if (windowEndMillis <= windowStartMillis) break

            fetchCoinbaseCandles(
                startMillis = windowStartMillis,
                endMillis = windowEndMillis,
                granularitySeconds = request.granularitySeconds
            ).forEach { candle ->
                if (candle.timestampMillis in request.startMillis..nowMillis) {
                    dedupedCandles[candle.timestampMillis] = candle
                }
            }

            windowStartMillis = windowEndMillis
        }

        val points = dedupedCandles.values
            .sortedBy { it.timestampMillis }
            .map { candle ->
                BitcoinPricePoint(
                    timestampMillis = candle.timestampMillis,
                    priceUsd = candle.closeUsd
                )
            }

        synchronized(cacheLock) {
            chartSeriesCache[cacheKey] = CachedChartSeries(
                points = points,
                fetchedAtMillis = nowMillis
            )
        }

        return points
    }

    private fun fetchCoinbaseCandles(
        startMillis: Long,
        endMillis: Long,
        granularitySeconds: Int
    ): List<BitcoinCandle> {
        val url = Uri.parse(COINBASE_CANDLES_URL).buildUpon()
            .appendQueryParameter("start", Instant.ofEpochMilli(startMillis).toString())
            .appendQueryParameter("end", Instant.ofEpochMilli(endMillis).toString())
            .appendQueryParameter("granularity", granularitySeconds.toString())
            .build()
            .toString()

        val root = JSONArray(performGet(url))
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONArray(index) ?: continue
                if (item.length() < 5) continue

                add(
                    BitcoinCandle(
                        timestampMillis = item.getDouble(0).toLong() * 1_000L,
                        closeUsd = item.getDouble(4)
                    )
                )
            }
        }.sortedBy { it.timestampMillis }
    }

    private fun performGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            doInput = true
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("Failed to load BTC price ($statusCode).")
            }

            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun historicalRequestFor(
        timestampMillis: Long,
        nowMillis: Long
    ): HistoricalPriceRequest {
        val granularitySeconds = 300
        val windowMillis = 3L * 60L * 60L * 1_000L

        val halfWindowMillis = windowMillis / 2L
        val safeTimestampMillis = minOf(timestampMillis, nowMillis)
        val startMillis = maxOf(0L, safeTimestampMillis - halfWindowMillis)
        val endMillis = maxOf(
            startMillis + (granularitySeconds * 1_000L),
            minOf(nowMillis, timestampMillis + halfWindowMillis)
        )

        return HistoricalPriceRequest(
            startMillis = startMillis,
            endMillis = endMillis,
            granularitySeconds = granularitySeconds
        )
    }

    private fun historicalCacheKey(
        timestampMillis: Long,
        granularitySeconds: Int
    ): String {
        val bucketSizeMillis = granularitySeconds * 1_000L
        return "$granularitySeconds:${timestampMillis / bucketSizeMillis}"
    }

    private fun chartRequestFor(
        range: BitcoinChartRange,
        nowMillis: Long
    ): ChartSeriesRequest {
        val zoneId = ZoneId.systemDefault()
        val startMillis = when (range) {
            BitcoinChartRange.DAY -> nowMillis - 24L * 60L * 60L * 1_000L
            BitcoinChartRange.MONTH -> nowMillis - 30L * 24L * 60L * 60L * 1_000L
            BitcoinChartRange.YEAR -> nowMillis - 365L * 24L * 60L * 60L * 1_000L
            BitcoinChartRange.YEAR_TO_DATE -> LocalDate.now(zoneId)
                .withDayOfYear(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        }

        val granularitySeconds = when (range) {
            BitcoinChartRange.DAY -> 300
            BitcoinChartRange.MONTH -> 21_600
            BitcoinChartRange.YEAR,
            BitcoinChartRange.YEAR_TO_DATE -> 86_400
        }

        return ChartSeriesRequest(
            startMillis = startMillis,
            granularitySeconds = granularitySeconds
        )
    }

    private companion object {
        const val COINBASE_SPOT_URL = "https://api.coinbase.com/v2/prices/BTC-USD/spot"
        const val COINBASE_CANDLES_URL = "https://api.exchange.coinbase.com/products/BTC-USD/candles"
        const val SPOT_CACHE_TTL_MILLIS = 60_000L
        const val SERIES_CACHE_TTL_MILLIS = 60_000L
        const val RECENT_SPOT_WINDOW_MILLIS = 300_000L
    }

    private data class CachedSpotPrice(
        val priceUsd: Double,
        val fetchedAtMillis: Long
    )

    private data class HistoricalPriceRequest(
        val startMillis: Long,
        val endMillis: Long,
        val granularitySeconds: Int
    )

    private data class ChartSeriesRequest(
        val startMillis: Long,
        val granularitySeconds: Int
    )

    private data class CachedChartSeries(
        val points: List<BitcoinPricePoint>,
        val fetchedAtMillis: Long
    )

    private data class BitcoinCandle(
        val timestampMillis: Long,
        val closeUsd: Double
    )
}
