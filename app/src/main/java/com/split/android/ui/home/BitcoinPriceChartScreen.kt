package com.split.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.split.android.data.pricing.BitcoinChartRange
import com.split.android.data.pricing.BitcoinPricePoint
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import com.split.android.ui.theme.SplitSurface
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val ChartBerry = Color(0xFF7A1248)
private val ChartIndigo = Color(0xFF111B31)
private val ChartSurfaceRaised = Color(0xFF18181D)
private val ChartPositive = Color(0xFF45D483)
private val ChartNegative = Color(0xFFFF6E7A)

@Composable
fun EmbeddedBitcoinPriceChartCard(
    rootViewModel: SplitRootViewModel,
    fallbackPriceText: String,
    isRefreshingPrice: Boolean,
    onRefreshPrice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var selectedRange by remember { mutableStateOf(BitcoinChartRange.DAY) }
    var points by remember { mutableStateOf<List<BitcoinPricePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPoint by remember { mutableStateOf<BitcoinPricePoint?>(null) }

    suspend fun load() {
        isLoading = true
        errorMessage = null

        runCatching {
            rootViewModel.fetchBtcUsdPriceSeries(selectedRange)
        }.onSuccess { loadedPoints ->
            points = loadedPoints
        }.onFailure { error ->
            points = emptyList()
            errorMessage = error.message ?: "Failed to load BTC chart data."
        }

        isLoading = false
    }

    LaunchedEffect(selectedRange) {
        selectedPoint = null
        load()
    }

    val displayedPoint = selectedPoint ?: points.lastOrNull()
    val priceText = displayedPoint?.priceUsd?.let(::formatUsd) ?: fallbackPriceText
    val subtitleText = displayedPoint?.let { point ->
        formatChartSubtitle(
            range = selectedRange,
            point = point,
            isInspectingPoint = selectedPoint != null
        )
    }
    val percentChange = remember(points, displayedPoint) {
        calculatePercentChange(points = points, displayedPoint = displayedPoint)
    }
    val shape = RoundedCornerShape(26.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.22f)
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Black.copy(alpha = 0.82f)
                    )
                ),
                shape = shape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = shape
            )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Column(
                modifier = Modifier.padding(end = 34.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = priceText.takeUnless { it.isBlank() } ?: fallbackPriceText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = percentChange.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = percentChange.color
                    )

                    subtitleText?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.70f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when {
                isLoading -> BitcoinPriceChartState(
                    title = "Loading chart...",
                    body = null,
                    showProgress = true
                )

                !errorMessage.isNullOrBlank() -> BitcoinPriceChartState(
                    title = "Couldn't load chart",
                    body = errorMessage,
                    actionLabel = "Retry",
                    onAction = { scope.launch { load() } }
                )

                points.isEmpty() -> BitcoinPriceChartState(
                    title = "No data.",
                    body = null
                )

                else -> BitcoinPriceLineChart(
                    points = points,
                    selectedPoint = selectedPoint,
                    onSelectPoint = { selectedPoint = it }
                )
            }

            BitcoinPriceRangePicker(
                selectedRange = selectedRange,
                onSelectRange = { selectedRange = it }
            )
        }

        WalletPriceRefreshButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
            isLoading = isRefreshingPrice || isLoading,
            onClick = {
                onRefreshPrice()
                scope.launch { load() }
            }
        )
    }
}

@Composable
private fun WalletPriceRefreshButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(enabled = !isLoading, onClick = onClick)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                color = Color.White,
                strokeWidth = 1.8.dp
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Refresh Bitcoin price",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun BitcoinPriceChartState(
    title: String,
    body: String?,
    showProgress: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        SplitSurface,
                        ChartIndigo.copy(alpha = 0.90f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.4.dp
            )
            Spacer(modifier = Modifier.size(12.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        body?.let { message ->
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f)
            )
        }

        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(modifier = Modifier.size(14.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun BitcoinPriceLineChart(
    points: List<BitcoinPricePoint>,
    selectedPoint: BitcoinPricePoint?,
    onSelectPoint: (BitcoinPricePoint?) -> Unit
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        SplitSurface,
                        ChartIndigo.copy(alpha = 0.90f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = shape
            )
            .pointerInput(points) {
                if (points.isEmpty()) return@pointerInput

                awaitEachGesture {
                    val down = awaitPointerEvent().changes.firstOrNull() ?: return@awaitEachGesture
                    onSelectPoint(
                        findNearestPoint(
                            points = points,
                            touchX = down.position.x,
                            chartWidth = size.width.toFloat()
                        )
                    )

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break

                        onSelectPoint(
                            findNearestPoint(
                                points = points,
                                touchX = change.position.x,
                                chartWidth = size.width.toFloat()
                            )
                        )
                    } while (true)

                    onSelectPoint(null)
                }
            }
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            if (points.isEmpty()) return@Canvas

            val prices = points.map { it.priceUsd }
            val minPrice = prices.minOrNull() ?: return@Canvas
            val maxPrice = prices.maxOrNull() ?: return@Canvas
            val rawRange = maxPrice - minPrice
            val pricePadding = if (rawRange > 0.0) {
                rawRange * 0.10
            } else {
                max(1.0, maxPrice * 0.002)
            }

            val lowerBound = minPrice - pricePadding
            val upperBound = maxPrice + pricePadding
            val minTimestamp = points.first().timestampMillis
            val maxTimestamp = points.last().timestampMillis

            fun xFor(point: BitcoinPricePoint): Float {
                if (maxTimestamp == minTimestamp) return size.width / 2f
                val progress = (point.timestampMillis - minTimestamp).toFloat() /
                    (maxTimestamp - minTimestamp).toFloat()
                return progress * size.width
            }

            fun yFor(priceUsd: Double): Float {
                if (upperBound == lowerBound) return size.height / 2f
                val progress = ((priceUsd - lowerBound) / (upperBound - lowerBound)).toFloat()
                return size.height - (progress * size.height)
            }

            val coordinates = points.map { point ->
                Offset(x = xFor(point), y = yFor(point.priceUsd))
            }

            if (coordinates.size == 1) {
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = coordinates.first()
                )
                return@Canvas
            }

            val linePath = smoothChartPath(coordinates)
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(coordinates.last().x, size.height)
                lineTo(coordinates.first().x, size.height)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SplitBrandPink.copy(alpha = 0.16f),
                        Color.Transparent
                    )
                )
            )

            drawPath(
                path = linePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(SplitBrandBlue, SplitBrandPink)
                ),
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            selectedPoint?.let { point ->
                val selectedX = xFor(point)
                val selectedY = yFor(point.priceUsd)

                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(selectedX, 0f),
                    end = Offset(selectedX, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(selectedX, selectedY)
                )
            }
        }
    }
}

@Composable
private fun BitcoinPriceRangePicker(
    selectedRange: BitcoinChartRange,
    onSelectRange: (BitcoinChartRange) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ChartRangeButton(
            title = "Day",
            isSelected = selectedRange == BitcoinChartRange.DAY,
            onClick = { onSelectRange(BitcoinChartRange.DAY) }
        )
        ChartRangeButton(
            title = "Month",
            isSelected = selectedRange == BitcoinChartRange.MONTH,
            onClick = { onSelectRange(BitcoinChartRange.MONTH) }
        )
        ChartRangeButton(
            title = "Year",
            isSelected = selectedRange == BitcoinChartRange.YEAR,
            onClick = { onSelectRange(BitcoinChartRange.YEAR) }
        )
        ChartRangeButton(
            title = "YTD",
            isSelected = selectedRange == BitcoinChartRange.YEAR_TO_DATE,
            onClick = { onSelectRange(BitcoinChartRange.YEAR_TO_DATE) }
        )
    }
}

@Composable
private fun RowScope.ChartRangeButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                brush = if (isSelected) {
                    Brush.linearGradient(
                        colors = listOf(ChartBerry, SplitBrandPink)
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(ChartSurfaceRaised, ChartSurfaceRaised)
                    )
                }
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isSelected) 0.16f else 0.08f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

private fun smoothChartPath(points: List<Offset>): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points.first().x, points.first().y)

        if (points.size == 1) return@apply

        var previous = points.first()
        for (index in 1 until points.size) {
            val current = points[index]
            val midPoint = Offset(
                x = (previous.x + current.x) / 2f,
                y = (previous.y + current.y) / 2f
            )
            quadraticTo(previous.x, previous.y, midPoint.x, midPoint.y)
            previous = current
        }

        lineTo(points.last().x, points.last().y)
    }
}

private fun findNearestPoint(
    points: List<BitcoinPricePoint>,
    touchX: Float,
    chartWidth: Float
): BitcoinPricePoint? {
    if (points.isEmpty() || chartWidth <= 0f) return null

    val minTimestamp = points.first().timestampMillis
    val maxTimestamp = points.last().timestampMillis
    if (maxTimestamp == minTimestamp) return points.lastOrNull()

    val clampedX = touchX.coerceIn(0f, chartWidth)
    val selectedTimestamp = minTimestamp + (
        (clampedX / chartWidth) * (maxTimestamp - minTimestamp).toFloat()
    ).toLong()

    return points.minByOrNull { point ->
        abs(point.timestampMillis - selectedTimestamp)
    }
}

private fun formatUsd(priceUsd: Double): String {
    return NumberFormat.getCurrencyInstance(Locale.US).format(priceUsd)
}

private fun formatPercent(value: Double): String {
    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }
    val prefix = if (value >= 0.0) "+" else ""
    return prefix + formatter.format(value) + "%"
}

private fun calculatePercentChange(
    points: List<BitcoinPricePoint>,
    displayedPoint: BitcoinPricePoint?
): PercentChangeDisplay {
    val firstPrice = points.firstOrNull()?.priceUsd
    val displayedPrice = displayedPoint?.priceUsd
    if (firstPrice == null || displayedPrice == null || firstPrice == 0.0) {
        return PercentChangeDisplay(text = "—", color = Color.White.copy(alpha = 0.75f))
    }

    val changePercent = ((displayedPrice - firstPrice) / firstPrice) * 100.0
    val color = when {
        changePercent > 0.0 -> ChartPositive
        changePercent < 0.0 -> ChartNegative
        else -> Color.White.copy(alpha = 0.75f)
    }

    return PercentChangeDisplay(
        text = formatPercent(changePercent),
        color = color
    )
}

private fun formatChartSubtitle(
    range: BitcoinChartRange,
    point: BitcoinPricePoint,
    isInspectingPoint: Boolean
): String {
    val zonedDateTime = Instant.ofEpochMilli(point.timestampMillis)
        .atZone(ZoneId.systemDefault())

    return if (isInspectingPoint) {
        DateTimeFormatter.ofLocalizedDateTime(
            FormatStyle.MEDIUM,
            FormatStyle.SHORT
        ).format(zonedDateTime)
    } else {
        val asOfFormatter = when (range) {
            BitcoinChartRange.DAY -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            BitcoinChartRange.MONTH,
            BitcoinChartRange.YEAR,
            BitcoinChartRange.YEAR_TO_DATE -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        }
        "As of ${asOfFormatter.format(zonedDateTime)}"
    }
}

private data class PercentChangeDisplay(
    val text: String,
    val color: Color
)
