package com.split.android.ui.coupons

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.split.android.data.coupons.NearbyCoupon
import com.split.android.data.coupons.NearbyCouponSearchOrigin
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import androidx.compose.ui.text.input.KeyboardType

private val PromoBackground = Color(0xFF050508)
private val PromoCardSurface = Color(0xFF141830)
private val PromoInputSurface = Color.White.copy(alpha = 0.12f)
private val PromoBrandBlue = Color(0xFF132B62)
private val PromoBrandPink = Color(0xFFBE3287)

private data class CouponCoordinateSnapshot(
    val latitude: Double,
    val longitude: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyCouponsScreen(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit,
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationClient = remember { CouponLocationClient(context) }
    val radiusMiles = remember { 25.0 }

    var coupons by remember { mutableStateOf<List<NearbyCoupon>>(emptyList()) }
    var searchOrigin by remember { mutableStateOf<NearbyCouponSearchOrigin?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var hasStartedLookup by remember { mutableStateOf(false) }
    var showZipSheet by remember { mutableStateOf(false) }
    var showPromoInfo by remember { mutableStateOf(false) }
    var zipCode by rememberSaveable { mutableStateOf("") }
    var zipErrorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmittingZip by remember { mutableStateOf(false) }
    var selectedCouponId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedCoupon = remember(coupons, selectedCouponId) {
        coupons.firstOrNull { it.id == selectedCouponId }
    }

    suspend fun loadCouponsForCoordinate(coordinate: CouponCoordinateSnapshot) {
        isLoading = true
        errorMessage = null

        runCatching {
            rootViewModel.fetchNearbyCoupons(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                radiusMiles = radiusMiles
            )
        }.onSuccess { response ->
            coupons = response.coupons
            searchOrigin = response.searchOrigin
        }.onFailure { error ->
            errorMessage = userFacingErrorMessage(error)
        }

        isLoading = false
    }

    suspend fun requestLocationLookup() {
        isLoading = true
        errorMessage = null

        val location = locationClient.awaitCurrentOrLastKnownLocation()
        if (location == null) {
            isLoading = false
            if (coupons.isEmpty()) {
                errorMessage = "We couldn’t get your location. Enter a ZIP code to browse nearby promos."
                showZipSheet = true
            }
            return
        }

        loadCouponsForCoordinate(
            coordinate = CouponCoordinateSnapshot(
                latitude = location.latitude,
                longitude = location.longitude
            )
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            scope.launch {
                requestLocationLookup()
            }
        } else {
            isLoading = false
            if (coupons.isEmpty()) {
                showZipSheet = true
            }
        }
    }

    fun requestLocationOrZipFallback() {
        if (hasLocationPermission(context)) {
            scope.launch {
                requestLocationLookup()
            }
        } else {
            isLoading = true
            errorMessage = null
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    suspend fun submitZipCode() {
        val normalizedZip = normalizedPostalCode(zipCode)
        if (!isValidPostalCode(normalizedZip)) {
            zipErrorMessage = "Enter a valid ZIP code."
            return
        }

        zipCode = normalizedZip
        zipErrorMessage = null
        isSubmittingZip = true
        isLoading = true

        runCatching {
            rootViewModel.fetchNearbyCoupons(
                postalCode = normalizedZip,
                radiusMiles = radiusMiles
            )
        }.onSuccess { response ->
            coupons = response.coupons
            searchOrigin = response.searchOrigin
            errorMessage = null
            showZipSheet = false
        }.onFailure { error ->
            zipErrorMessage = userFacingErrorMessage(error)
        }

        isSubmittingZip = false
        isLoading = false
    }

    suspend fun refreshSearch() {
        when (searchOrigin?.source) {
            "postalCode" -> {
                zipCode = searchOrigin?.postalCode ?: zipCode
                submitZipCode()
            }

            else -> requestLocationOrZipFallback()
        }
    }

    fun updateCouponRedemptionState(couponId: String, redeemedAt: String?) {
        coupons = coupons.map { coupon ->
            if (coupon.id == couponId) {
                coupon.copy(
                    hasRedeemedThisMonth = true,
                    currentUserRedeemedAt = redeemedAt
                )
            } else {
                coupon
            }
        }
    }

    BackHandler {
        if (selectedCouponId != null) {
            selectedCouponId = null
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasStartedLookup) {
            hasStartedLookup = true
            requestLocationOrZipFallback()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PromoBackground)
    ) {
        if (selectedCoupon != null) {
            NearbyCouponDetailScreen(
                coupon = selectedCoupon,
                onBack = { selectedCouponId = null },
                onRedeemed = { redeemedAt ->
                    updateCouponRedemptionState(
                        couponId = selectedCoupon.id,
                        redeemedAt = redeemedAt
                    )
                },
                onRedeem = { couponId ->
                    rootViewModel.redeemNearbyCoupon(couponId)
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                PromoOverlayHeader(
                    title = "Promos",
                    onBack = onDismiss,
                    showBackButton = showBackButton
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 6.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PromoSecondaryCapsuleButton(
                        text = "Use ZIP Code",
                        icon = Icons.Rounded.Numbers,
                        onClick = {
                            zipErrorMessage = null
                            showZipSheet = true
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    PromoInfoIconButton(
                        onClick = { showPromoInfo = true }
                    )
                }

                PullToRefreshBox(
                    isRefreshing = isLoading && coupons.isNotEmpty(),
                    onRefresh = { scope.launch { refreshSearch() } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        when {
                            isLoading && coupons.isEmpty() -> {
                                item {
                                    PromoLoadingCard()
                                }
                            }

                            errorMessage != null && coupons.isEmpty() -> {
                                item {
                                    PromoStateCard(
                                        title = "Promos unavailable",
                                        message = errorMessage ?: "",
                                        actionLabel = "Enter ZIP Code",
                                        onAction = {
                                            zipErrorMessage = null
                                            showZipSheet = true
                                        }
                                    )
                                }
                            }

                            coupons.isEmpty() -> {
                                item {
                                    PromoStateCard(
                                        title = "No nearby promos yet",
                                        message = "We couldn’t find any approved coupons within 25 miles of this area.",
                                        actionLabel = null,
                                        onAction = null
                                    )
                                }
                            }

                            else -> {
                                items(
                                    items = coupons,
                                    key = { it.id }
                                ) { coupon ->
                                    PromoCouponCard(
                                        coupon = coupon,
                                        onClick = { selectedCouponId = coupon.id }
                                    )
                                }
                            }
                        }

                        item {
                            Spacer(
                                modifier = Modifier
                                    .height(32.dp)
                                    .navigationBarsPadding()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPromoInfo) {
        PromoInfoOverlay(
            onDismiss = { showPromoInfo = false }
        )
    }

    if (showZipSheet) {
        ModalBottomSheet(
            onDismissRequest = { showZipSheet = false },
            containerColor = PromoBackground
        ) {
            NearbyCouponsZipSheet(
                zipCode = zipCode,
                errorMessage = zipErrorMessage,
                isSubmitting = isSubmittingZip,
                onZipCodeChange = { zipCode = formatZipEntry(it) },
                onCancel = {
                    zipErrorMessage = null
                    showZipSheet = false
                },
                onSubmit = {
                    scope.launch {
                        submitZipCode()
                    }
                }
            )
        }
    }
}

@Composable
private fun PromoOverlayHeader(
    title: String,
    onBack: () -> Unit,
    showBackButton: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBackButton) {
            PromoHeaderActionButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(46.dp))
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(46.dp))
    }
}

@Composable
private fun PromoHeaderActionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PromoSecondaryCapsuleButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PromoInfoIconButton(
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = "Promo Info",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PromoInfoOverlay(
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.74f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 420.dp),
            shape = RoundedCornerShape(28.dp),
            color = PromoCardSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = PromoBrandPink,
                        modifier = Modifier.size(24.dp)
                    )

                    Text(
                        text = "Promo Info",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                Text(
                    text = "Promos can be redeemed once a month. All promos become available on the 1st of every month (UTC time). Always confirm with merchant staff before redeeming a promo, and show the staff your screen while redeeming a promo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f)
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun PromoLoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = PromoCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.2.dp,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Looking for nearby promos...",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PromoStateCard(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = PromoCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )

            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PromoBrandPink,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = actionLabel,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun PromoCouponCard(
    coupon: NearbyCoupon,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        color = PromoCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            PromoCouponLogo(
                businessLogoUrl = coupon.businessLogoUrl,
                size = 68.dp,
                iconSize = 24.dp
            )

            Text(
                text = coupon.dealDescription,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f)
            )
        }
    }
}

@Composable
private fun PromoCouponLogo(
    businessLogoUrl: String?,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(18.dp),
        color = Color.White
    ) {
        if (!businessLogoUrl.isNullOrBlank()) {
            AsyncImage(
                model = businessLogoUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SplitFeatureIcons.Tag,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(iconSize)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(PromoBrandBlue, PromoBrandPink)
                            ),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun NearbyCouponsZipSheet(
    zipCode: String,
    errorMessage: String?,
    isSubmitting: Boolean,
    onZipCodeChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = "Enter ZIP Code",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Text(
            text = "Location is unavailable. Enter a ZIP code and we’ll show approved promos within 25 miles.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f)
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = zipCode,
                onValueChange = onZipCodeChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ZIP code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = promoTextFieldColors()
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB3CF)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.White.copy(alpha = 0.80f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Cancel",
                    fontWeight = FontWeight.SemiBold
                )
            }

            PromoGradientButton(
                text = "Show Promos",
                isLoading = isSubmitting,
                modifier = Modifier.weight(1f),
                onClick = onSubmit
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun PromoGradientButton(
    text: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(PromoBrandBlue, PromoBrandPink)
                )
            )
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun NearbyCouponDetailScreen(
    coupon: NearbyCoupon,
    onBack: () -> Unit,
    onRedeemed: (String?) -> Unit,
    onRedeem: suspend (String) -> com.split.android.data.coupons.RedeemNearbyCouponResponse
) {
    var hasRedeemedThisMonth by remember(coupon.id) { mutableStateOf(coupon.hasRedeemedThisMonth) }
    var currentUserRedeemedAt by remember(coupon.id) { mutableStateOf(coupon.currentUserRedeemedAt) }
    var showRedeemConfirmation by remember { mutableStateOf(false) }
    var showRedeemSuccess by remember { mutableStateOf(false) }
    var isRedeeming by remember { mutableStateOf(false) }
    var redeemErrorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(coupon.hasRedeemedThisMonth, coupon.currentUserRedeemedAt) {
        hasRedeemedThisMonth = coupon.hasRedeemedThisMonth
        currentUserRedeemedAt = coupon.currentUserRedeemedAt
    }

    LaunchedEffect(showRedeemSuccess) {
        if (showRedeemSuccess) {
            delay(10_000L)
            showRedeemSuccess = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PromoBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PromoOverlayHeader(
                title = coupon.businessName,
                onBack = onBack
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(2.dp))
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        PromoCouponLogo(
                            businessLogoUrl = coupon.businessLogoUrl,
                            size = 86.dp,
                            iconSize = 28.dp
                        )
                    }
                }

                item {
                    PromoSectionCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "Promo"
                    ) {
                        Text(
                            text = coupon.dealDescription,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }

                item {
                    PromoSectionCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = null
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = coupon.businessName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = coupon.primaryBusinessAddress.formattedAddress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f)
                            )

                            if (coupon.appliesToAllLocations) {
                                Text(
                                    text = "Applies to all locations.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.60f)
                                )
                            }

                            coupon.distanceMiles?.let { distanceMiles ->
                                Text(
                                    text = String.format(Locale.US, "%.1f miles away", distanceMiles),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.60f)
                                )
                            }
                        }
                    }
                }

                item {
                    if (hasRedeemedThisMonth) {
                        PromoRedeemedStatusCard(
                            redeemedAt = currentUserRedeemedAt,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        Button(
                            onClick = { showRedeemConfirmation = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            enabled = !isRedeeming,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PromoBrandPink,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                text = "Redeem",
                                modifier = Modifier.padding(vertical = 6.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                item {
                    Spacer(
                        modifier = Modifier
                            .height(28.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }

        if (showRedeemConfirmation) {
            PromoRedeemConfirmationDialog(
                isRedeeming = isRedeeming,
                onCancel = { showRedeemConfirmation = false },
                onConfirm = {
                    if (isRedeeming) return@PromoRedeemConfirmationDialog
                    scope.launch {
                        isRedeeming = true

                        runCatching {
                            onRedeem(coupon.id)
                        }.onSuccess { response ->
                            hasRedeemedThisMonth = response.didRedeem || response.alreadyRedeemedThisMonth
                            currentUserRedeemedAt = response.redeemedAt
                            onRedeemed(response.redeemedAt)
                            showRedeemConfirmation = false

                            if (response.didRedeem) {
                                showRedeemSuccess = true
                            }
                        }.onFailure { error ->
                            showRedeemConfirmation = false
                            redeemErrorMessage = error.message ?: "We couldn’t redeem this promo right now."
                        }

                        isRedeeming = false
                    }
                }
            )
        }

        if (showRedeemSuccess) {
            PromoRedeemedSuccessOverlay(
                coupon = coupon
            )
        }
    }

    if (!redeemErrorMessage.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { redeemErrorMessage = null },
            containerColor = PromoCardSurface,
            title = {
                Text(
                    text = "Unable to Redeem Promo",
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = redeemErrorMessage ?: "",
                    color = Color.White.copy(alpha = 0.78f)
                )
            },
            confirmButton = {
                Button(
                    onClick = { redeemErrorMessage = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PromoBrandPink,
                        contentColor = Color.White
                    )
                ) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun PromoSectionCard(
    modifier: Modifier = Modifier,
    title: String?,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = PromoCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }

            content()
        }
    }
}

@Composable
private fun PromoRedeemedStatusCard(
    redeemedAt: String?,
    modifier: Modifier = Modifier
) {
    val label = nearbyCouponRedeemedDisplayString(redeemedAt)?.let {
        "Promo redeemed $it."
    } ?: "Promo redeemed."

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PromoCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

@Composable
private fun PromoRedeemConfirmationDialog(
    isRedeeming: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = {
            if (!isRedeeming) {
                onCancel()
            }
        }
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = PromoCardSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = "Confirm the promo with merchant before redeeming",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        enabled = !isRedeeming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = !isRedeeming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PromoBrandPink,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isRedeeming) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                text = "Confirm",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromoRedeemedSuccessOverlay(
    coupon: NearbyCoupon
) {
    val transition = rememberInfiniteTransition(label = "promo-redeemed")
    val animatedScale by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "promo-redeemed-scale"
    )

    val animatedAlpha by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "promo-redeemed-alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            PromoCouponLogo(
                businessLogoUrl = coupon.businessLogoUrl,
                size = 170.dp,
                iconSize = 48.dp
            )

            Text(
                text = "Coupon Redeemed",
                modifier = Modifier.scale(animatedScale),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = animatedAlpha),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun promoTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = PromoInputSurface,
    unfocusedContainerColor = PromoInputSurface,
    disabledContainerColor = PromoInputSurface,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Color.White.copy(alpha = 0.72f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
    cursorColor = Color.White
)

private fun normalizedPostalCode(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length > 5 -> {
            val prefix = digits.take(5)
            val suffix = digits.drop(5).take(4)
            if (suffix.isEmpty()) prefix else "$prefix-$suffix"
        }

        else -> digits.take(5)
    }
}

private fun formatZipEntry(value: String): String {
    return normalizedPostalCode(value)
}

private fun isValidPostalCode(value: String): Boolean {
    val digits = value.filter(Char::isDigit)
    return digits.length == 5 || digits.length == 9
}

private fun userFacingErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return if (
        raw.contains("That ZIP code") ||
        raw.contains("We could not") ||
        raw.contains("Enter a valid US ZIP code")
    ) {
        raw
    } else {
        "We couldn’t load nearby promos right now."
    }
}

private fun nearbyCouponRedeemedDisplayString(value: String?): String? {
    if (value.isNullOrBlank()) {
        return null
    }

    return runCatching {
        NearbyCouponRedeemedDateFormatter.displayFormatter.format(
            Instant.parse(value)
        ).lowercase(Locale.US)
    }.getOrNull()
}

private object NearbyCouponRedeemedDateFormatter {
    val displayFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("M/d hh:mma", Locale.US)
        .withZone(ZoneId.systemDefault())
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

private class CouponLocationClient(
    private val context: Context
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun awaitCurrentOrLastKnownLocation(): Location? {
        if (!hasLocationPermission(context)) {
            return null
        }

        val currentLocation = withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellationTokenSource = CancellationTokenSource()
                fusedLocationClient
                    .getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cancellationTokenSource.token
                    )
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                    .addOnCanceledListener {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            }
        }

        if (currentLocation != null) {
            return currentLocation
        }

        return suspendCancellableCoroutine { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
                .addOnCanceledListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        }
    }
}
