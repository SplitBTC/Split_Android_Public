package com.split.android.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.split.android.data.wallet.WalletPaymentDirection
import com.split.android.data.wallet.WalletPaymentResultEvent
import com.split.android.data.wallet.WalletPaymentToastKind
import com.split.android.data.wallet.WalletToastManager
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val ToastBrandBlue = Color(0xFF132B62)
private val ToastBrandPink = Color(0xFFBE3287)
private val ToastIndigo = Color(0xFF2B397A)
private val ToastGlow = Color(0xFF1B357E)
private val ToastTextPrimary = Color(0xDB000000)
private val ToastTextSecondary = Color(0xA3000000)

@Composable
fun GlobalPaymentResultHost() {
    val event by WalletToastManager.activeToast.collectAsState()

    LaunchedEffect(event?.id) {
        val activeEvent = event ?: return@LaunchedEffect
        when (activeEvent.kind) {
            WalletPaymentToastKind.PENDING -> Unit
            WalletPaymentToastKind.SUCCESS -> {
                delay(3000)
                WalletToastManager.clear(activeEvent.id)
            }
            WalletPaymentToastKind.FAILURE -> {
                delay(4000)
                WalletToastManager.clear(activeEvent.id)
            }
        }
    }

    event?.let { activeEvent ->
        GlobalPaymentResultOverlay(
            event = activeEvent,
            onDismiss = { WalletToastManager.clear(activeEvent.id) }
        )
    }
}

@Composable
fun GlobalPaymentResultOverlay(
    event: WalletPaymentResultEvent,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val style = remember(event.kind, event.direction, event.subtitle) {
        PaymentToastStyle.forEvent(event)
    }
    val cardScale = remember(event.id) { Animatable(0.9f) }
    val cardOpacity = remember(event.id) { Animatable(0f) }
    val burstScale = remember(event.id) { Animatable(0.92f) }
    val burstOpacity = remember(event.id) { Animatable(0f) }
    val pulseScale = remember(event.id) { Animatable(1f) }
    var canDismiss by remember(event.id) { mutableStateOf(false) }
    val isPending = event.kind == WalletPaymentToastKind.PENDING

    LaunchedEffect(event.id) {
        runCatching {
            PaymentResultHaptics.play(context, event)
        }
        cardScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
        )
        cardOpacity.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
        )
        burstScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
        )
        burstOpacity.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
        )
        delay(180)
        pulseScale.animateTo(
            targetValue = 1.025f,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
        )
        pulseScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(event.id) {
        if (!isPending) {
            delay(350)
            canDismiss = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (isPending) 0.08f else 0.14f))
            .then(
                if (isPending) {
                    Modifier
                } else {
                    Modifier.clickable(
                        enabled = canDismiss,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .height(292.dp)
                .shadow(
                    elevation = 30.dp,
                    shape = RoundedCornerShape(34.dp),
                    ambientColor = style.burstGlow.copy(alpha = 0.30f),
                    spotColor = style.burstGlow.copy(alpha = 0.30f)
                )
                .graphicsLayer {
                    val animatedScale = cardScale.value * pulseScale.value
                    scaleX = animatedScale
                    scaleY = animatedScale
                    alpha = cardOpacity.value
                },
            color = Color.White,
            shape = RoundedCornerShape(34.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(34.dp))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(34.dp),
                    )
            ) {
                if (!style.showsPendingHero) {
                    PaymentToastBurstBackground(
                        style = style,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = burstScale.value
                                scaleY = burstScale.value
                                alpha = burstOpacity.value
                            }
                    )
                }

                if (style.showsPendingHero) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp, vertical = 30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PendingPaymentHero(style = style)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(14.dp))

                        PaymentToastHeroSymbol(style = style)

                        Spacer(modifier = Modifier.height(18.dp))

                        style.displayTitle?.let { title ->
                            Text(
                                text = title,
                                fontSize = 30.sp,
                                lineHeight = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = ToastTextPrimary,
                                textAlign = TextAlign.Center
                            )
                        }

                        style.subtitle?.let { subtitle ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = subtitle,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = ToastTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }

                        style.eyebrow?.let { eyebrow ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = eyebrow,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = style.accent,
                                modifier = Modifier
                                    .background(
                                        color = style.accent.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(999.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = style.accent.copy(alpha = 0.22f),
                                        shape = RoundedCornerShape(999.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentToastHeroSymbol(
    style: PaymentToastStyle
) {
    Box(
        modifier = Modifier
            .size(112.dp)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                ambientColor = style.heroGradientEnd.copy(alpha = 0.26f),
                spotColor = style.heroGradientStart.copy(alpha = 0.18f)
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        style.heroGradientStart,
                        style.heroGradientEnd
                    )
                ),
                shape = CircleShape
            )
            .border(
                width = 1.6.dp,
                color = Color.White.copy(alpha = 0.42f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (style.symbolKind) {
                PaymentToastHeroSymbolKind.BITCOIN -> Icons.Rounded.CurrencyBitcoin
                PaymentToastHeroSymbolKind.CLOSE -> Icons.Rounded.Close
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(if (style.symbolKind == PaymentToastHeroSymbolKind.BITCOIN) 54.dp else 44.dp)
        )
    }
}

@Composable
private fun PendingPaymentHero(
    style: PaymentToastStyle
) {
    val tokenScale = remember { Animatable(0.98f) }

    LaunchedEffect(Unit) {
        while (true) {
            tokenScale.animateTo(
                targetValue = 1.02f,
                animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing)
            )
            tokenScale.animateTo(
                targetValue = 0.98f,
                animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing)
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(138.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(138.dp)
                    .background(
                        color = style.heroGradientStart.copy(alpha = 0.08f),
                        shape = CircleShape
                    )
            )

            Box(
                modifier = Modifier
                    .size(130.dp)
                    .border(
                        width = 8.dp,
                        color = style.heroGradientStart.copy(alpha = 0.10f),
                        shape = CircleShape
                    )
            )

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = tokenScale.value
                        scaleY = tokenScale.value
                    }
                    .size(110.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = style.heroGradientEnd.copy(alpha = 0.10f),
                        spotColor = style.heroGradientStart.copy(alpha = 0.12f)
                    )
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(style.heroGradientStart, style.heroGradientEnd)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CurrencyBitcoin,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        PendingPaymentDotsRow()
    }
}

@Composable
private fun PendingPaymentDotsRow() {
    var activeIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(180)
            activeIndex = (activeIndex + 1) % 4
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) { index ->
            val alpha = when {
                index == activeIndex -> 1f
                index == previousDotIndex(activeIndex) -> 0.42f
                else -> 0.16f
            }
            val scale = when {
                index == activeIndex -> 1.18f
                index == previousDotIndex(activeIndex) -> 0.98f
                else -> 0.82f
            }

            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(
                        color = ToastBrandBlue,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun PaymentToastBurstBackground(
    style: PaymentToastStyle,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cornerRadius = CornerRadius(34.dp.toPx(), 34.dp.toPx())
        val burstCenter = Offset(size.width * 0.5f, size.height * 0.5f)

        drawRoundRect(
            color = Color.White,
            cornerRadius = cornerRadius
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    style.burstSecondary.copy(alpha = 0.26f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.28f, size.height * 0.68f),
                radius = size.minDimension * 0.72f
            ),
            radius = size.minDimension * 0.72f,
            center = Offset(size.width * 0.28f, size.height * 0.68f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    style.burstPrimary.copy(alpha = 0.20f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.74f, size.height * 0.28f),
                radius = size.minDimension * 0.78f
            ),
            radius = size.minDimension * 0.78f,
            center = Offset(size.width * 0.74f, size.height * 0.28f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    style.burstGlow.copy(alpha = 0.24f),
                    Color.Transparent
                ),
                center = burstCenter,
                radius = size.minDimension * 0.98f
            ),
            radius = size.minDimension * 0.98f,
            center = burstCenter
        )

        if (style.isElectric) {
            electricStreakSpecs.forEach { spec ->
                drawElectricStreak(
                    spec = spec,
                    primary = style.burstPrimary,
                    secondary = style.burstSecondary
                )
            }
        }

        burstRaySpecs.forEach { spec ->
            drawToastBurstRay(
                spec = spec,
                primary = style.burstPrimary,
                secondary = style.burstSecondary
            )
            drawToastBurstRay(
                spec = spec.highlighted(),
                primary = style.burstPrimary.copy(alpha = 0.30f),
                secondary = Color.White
            )
        }

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    style.burstSecondary.copy(alpha = 0.14f),
                    Color.Transparent,
                    style.burstPrimary.copy(alpha = 0.14f)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            ),
            cornerRadius = cornerRadius
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    Color.White,
                    Color.White.copy(alpha = 0.94f),
                    Color.White.copy(alpha = 0.56f),
                    Color.White.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                center = burstCenter,
                radius = minOf(size.width, size.height) * 0.42f
            ),
            radius = minOf(size.width, size.height) * 0.42f,
            center = burstCenter
        )
    }
}

private data class PaymentToastStyle(
    val accent: Color,
    val burstPrimary: Color,
    val burstSecondary: Color,
    val burstGlow: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    val symbolKind: PaymentToastHeroSymbolKind,
    val eyebrow: String? = null,
    val showsPendingHero: Boolean = false,
    val displayTitle: String?,
    val subtitle: String? = null,
    val isElectric: Boolean = true
) {
    companion object {
        fun forEvent(event: WalletPaymentResultEvent): PaymentToastStyle {
            return when (event.kind) {
                WalletPaymentToastKind.PENDING -> PaymentToastStyle(
                    accent = ToastBrandBlue,
                    burstPrimary = ToastBrandBlue,
                    burstSecondary = ToastBrandPink,
                    burstGlow = ToastGlow,
                    heroGradientStart = ToastBrandBlue,
                    heroGradientEnd = ToastBrandPink,
                    symbolKind = PaymentToastHeroSymbolKind.BITCOIN,
                    showsPendingHero = true,
                    displayTitle = null,
                    isElectric = true
                )

                WalletPaymentToastKind.SUCCESS -> PaymentToastStyle(
                    accent = ToastBrandBlue,
                    burstPrimary = ToastBrandBlue,
                    burstSecondary = ToastIndigo,
                    burstGlow = ToastGlow,
                    heroGradientStart = ToastBrandBlue,
                    heroGradientEnd = ToastBrandPink,
                    symbolKind = PaymentToastHeroSymbolKind.BITCOIN,
                    displayTitle = when (event.direction) {
                        WalletPaymentDirection.SENT -> "Bitcoin sent"
                        WalletPaymentDirection.RECEIVED -> "Bitcoin received"
                    },
                    subtitle = event.subtitle,
                    isElectric = true
                )

                WalletPaymentToastKind.FAILURE -> PaymentToastStyle(
                    accent = Color(0xFFE03B40),
                    burstPrimary = Color(0xFFD12A2E),
                    burstSecondary = Color(0xFFFF6B1A),
                    burstGlow = Color(0xFFF54829),
                    heroGradientStart = Color(0xFFE03B40),
                    heroGradientEnd = Color(0xFFFF6B1A),
                    symbolKind = PaymentToastHeroSymbolKind.CLOSE,
                    eyebrow = "FAILED",
                    displayTitle = when (event.direction) {
                        WalletPaymentDirection.SENT -> "Send failed"
                        WalletPaymentDirection.RECEIVED -> "Receive failed"
                    },
                    subtitle = event.subtitle ?: "Something went wrong. Please try again.",
                    isElectric = false
                )
            }
        }
    }
}

private enum class PaymentToastHeroSymbolKind {
    BITCOIN,
    CLOSE
}

private data class ToastBurstRaySpec(
    val angleDegrees: Float,
    val startFraction: Float,
    val endFraction: Float,
    val startWidthFraction: Float,
    val endWidthFraction: Float,
    val opacity: Float
) {
    fun highlighted(): ToastBurstRaySpec {
        return copy(
            startWidthFraction = startWidthFraction * 0.54f,
            endWidthFraction = endWidthFraction * 0.40f,
            opacity = (opacity + 0.08f).coerceAtMost(1f)
        )
    }
}

private data class ElectricStreakSpec(
    val widthFraction: Float,
    val thicknessFraction: Float,
    val rotationDegrees: Float,
    val xOffsetFraction: Float,
    val yOffsetFraction: Float,
    val opacity: Float
)

private val burstRaySpecs = listOf(
    ToastBurstRaySpec(-80f, 0.20f, 0.96f, 0.009f, 0.182f, 0.98f),
    ToastBurstRaySpec(-49f, 0.26f, 0.88f, 0.007f, 0.104f, 0.78f),
    ToastBurstRaySpec(-20f, 0.23f, 0.97f, 0.007f, 0.146f, 0.92f),
    ToastBurstRaySpec(9f, 0.30f, 1.00f, 0.006f, 0.082f, 0.68f),
    ToastBurstRaySpec(34f, 0.24f, 0.95f, 0.008f, 0.138f, 0.92f),
    ToastBurstRaySpec(67f, 0.20f, 0.97f, 0.009f, 0.176f, 0.96f),
    ToastBurstRaySpec(121f, 0.22f, 0.95f, 0.008f, 0.146f, 0.92f),
    ToastBurstRaySpec(170f, 0.28f, 0.90f, 0.006f, 0.084f, 0.68f),
    ToastBurstRaySpec(207f, 0.23f, 0.98f, 0.009f, 0.156f, 0.94f),
    ToastBurstRaySpec(246f, 0.21f, 0.98f, 0.009f, 0.184f, 0.97f),
    ToastBurstRaySpec(307f, 0.23f, 0.93f, 0.008f, 0.126f, 0.88f),
    ToastBurstRaySpec(338f, 0.29f, 0.95f, 0.006f, 0.088f, 0.70f)
)

private val electricStreakSpecs = listOf(
    ElectricStreakSpec(0.62f, 0.085f, -28f, 0.19f, -0.20f, 0.94f),
    ElectricStreakSpec(0.56f, 0.074f, 26f, -0.24f, 0.20f, 0.90f),
    ElectricStreakSpec(0.38f, 0.046f, -62f, 0.04f, 0.24f, 0.78f)
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawToastBurstRay(
    spec: ToastBurstRaySpec,
    primary: Color,
    secondary: Color
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxDimension = max(size.width, size.height)
    val theta = Math.toRadians(spec.angleDegrees.toDouble())
    val direction = Offset(cos(theta).toFloat(), sin(theta).toFloat())
    val perpendicular = Offset(-direction.y, direction.x)

    val startRadius = maxDimension * spec.startFraction
    val endRadius = maxDimension * spec.endFraction
    val startWidth = maxDimension * spec.startWidthFraction
    val endWidth = maxDimension * spec.endWidthFraction

    val startCenter = center + (direction * startRadius)
    val endCenter = center + (direction * endRadius)

    val p1 = startCenter + (perpendicular * (startWidth / 2f))
    val p2 = endCenter + (perpendicular * (endWidth / 2f))
    val p3 = endCenter - (perpendicular * (endWidth / 2f))
    val p4 = startCenter - (perpendicular * (startWidth / 2f))

    val path = Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        lineTo(p4.x, p4.y)
        close()
    }

    val gradientEndpoints = gradientEndpointsForAngle(size = size, angleDegrees = spec.angleDegrees)

    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                secondary.copy(alpha = spec.opacity),
                primary.copy(alpha = spec.opacity),
                primary.copy(alpha = spec.opacity * 0.72f),
                primary.copy(alpha = spec.opacity * 0.05f)
            ),
            start = gradientEndpoints.first,
            end = gradientEndpoints.second
        )
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElectricStreak(
    spec: ElectricStreakSpec,
    primary: Color,
    secondary: Color
) {
    val streakWidth = size.width * spec.widthFraction
    val streakHeight = size.height * spec.thicknessFraction
    val streakCenter = Offset(
        x = size.width * (0.5f + spec.xOffsetFraction),
        y = size.height * (0.5f + spec.yOffsetFraction)
    )
    val topLeft = Offset(
        x = streakCenter.x - (streakWidth / 2f),
        y = streakCenter.y - (streakHeight / 2f)
    )
    val coreHeight = streakHeight * 0.34f
    val coreTopLeft = Offset(
        x = streakCenter.x - (streakWidth / 2f),
        y = streakCenter.y - (coreHeight / 2f)
    )

    rotate(degrees = spec.rotationDegrees, pivot = streakCenter) {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    secondary.copy(alpha = spec.opacity),
                    primary.copy(alpha = spec.opacity * 0.96f)
                ),
                start = Offset(topLeft.x, streakCenter.y),
                end = Offset(topLeft.x + streakWidth, streakCenter.y)
            ),
            topLeft = topLeft,
            size = Size(streakWidth, streakHeight),
            cornerRadius = CornerRadius(streakHeight / 2f, streakHeight / 2f)
        )

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = spec.opacity * 0.98f),
                    secondary.copy(alpha = spec.opacity * 0.96f),
                    Color.White.copy(alpha = spec.opacity * 0.82f)
                ),
                start = Offset(coreTopLeft.x, streakCenter.y),
                end = Offset(coreTopLeft.x + streakWidth, streakCenter.y)
            ),
            topLeft = coreTopLeft,
            size = Size(streakWidth, coreHeight),
            cornerRadius = CornerRadius(coreHeight / 2f, coreHeight / 2f)
        )
    }
}

private fun gradientEndpointsForAngle(
    size: Size,
    angleDegrees: Float
): Pair<Offset, Offset> {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val startUnitX = 0.5 + (cos(radians) * 0.5)
    val startUnitY = 0.5 + (sin(radians) * 0.5)
    val endRadians = Math.toRadians((angleDegrees + 180f).toDouble())
    val endUnitX = 0.5 + (cos(endRadians) * 0.5)
    val endUnitY = 0.5 + (sin(endRadians) * 0.5)
    return Offset(
        x = size.width * startUnitX.toFloat(),
        y = size.height * startUnitY.toFloat()
    ) to Offset(
        x = size.width * endUnitX.toFloat(),
        y = size.height * endUnitY.toFloat()
    )
}

private operator fun Offset.times(value: Float): Offset {
    return Offset(x * value, y * value)
}

private object PaymentResultHaptics {
    private val electricWaveform = longArrayOf(0L, 18L, 28L, 22L, 26L, 38L)
    private val electricAmplitudes = intArrayOf(0, 130, 0, 190, 0, 255)

    fun play(context: Context, event: WalletPaymentResultEvent) {
        when (event.kind) {
            WalletPaymentToastKind.PENDING -> playPending(context)
            WalletPaymentToastKind.SUCCESS -> playElectricSuccess(context)
            WalletPaymentToastKind.FAILURE -> playFailure(context)
        }
    }

    private fun playPending(context: Context) {
        runCatching {
            resolveVibrator(context)?.let { vibrator ->
                if (!vibrator.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(22L, 110)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(22L)
                }
            }
        }
    }

    private fun playElectricSuccess(context: Context) {
        runCatching {
            resolveVibrator(context)?.let { vibrator ->
                if (!vibrator.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            electricWaveform,
                            electricAmplitudes,
                            -1
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(electricWaveform, -1)
                }
            }
        }
    }

    private fun playFailure(context: Context) {
        runCatching {
            resolveVibrator(context)?.let { vibrator ->
                if (!vibrator.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0L, 28L, 36L, 42L),
                            intArrayOf(0, 180, 0, 255),
                            -1
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0L, 28L, 36L, 42L), -1)
                }
            }
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}

private fun previousDotIndex(activeIndex: Int): Int {
    return (activeIndex - 1 + 4) % 4
}
