package com.split.android.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.example.splitandroid.BuildConfig
import com.split.android.data.map.BtcMerchantPlace
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.cos
import com.google.android.gms.maps.GoogleMap as NativeGoogleMap

private data class MerchantViewport(
    val latitude: Double,
    val longitude: Double,
    val latitudeSpan: Double,
    val longitudeSpan: Double
)

private const val MAP_TAG = "SplitMerchantMap"

@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun BtcMerchantMapScreen(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationClient = remember { MerchantLocationClient(context) }
    val hasConfiguredMapsApiKey = remember { BuildConfig.MAPS_API_KEY.isNotBlank() }
    val fallbackCenter = remember { LatLng(39.8283, -98.5795) }
    val fallbackViewport = remember {
        MerchantViewport(
            latitude = fallbackCenter.latitude,
            longitude = fallbackCenter.longitude,
            latitudeSpan = 18.0,
            longitudeSpan = 24.0
        )
    }

    var viewport by remember { mutableStateOf(fallbackViewport) }
    var places by remember { mutableStateOf<List<BtcMerchantPlace>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var runtimeErrorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPlace by remember { mutableStateOf<BtcMerchantPlace?>(null) }
    var centerTarget by remember { mutableStateOf(fallbackCenter) }
    var centerZoom by remember { mutableStateOf(5.5) }
    var centerRequestNonce by remember { mutableLongStateOf(0L) }
    var isResolvingInitialLocation by remember { mutableStateOf(true) }
    var hasCompletedInitialLocationAttempt by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var activeFetchId by remember { mutableLongStateOf(0L) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fallbackCenter, 5.5f)
    }
    val mapProperties = remember(hasConfiguredMapsApiKey) {
        MapProperties(
            isMyLocationEnabled = false,
            minZoomPreference = 3f,
            maxZoomPreference = 20f
        )
    }
    val mapUiSettings = remember {
        MapUiSettings(
            compassEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false
        )
    }
    val errorMessage = runtimeErrorMessage ?: if (!hasConfiguredMapsApiKey) {
        "Google Maps API key isn't configured for this build."
    } else {
        null
    }

    fun applyViewport(
        nextViewport: MerchantViewport,
        zoom: Double,
        forceRefresh: Boolean
    ) {
        Log.d(
            MAP_TAG,
            "applyViewport lat=${nextViewport.latitude} lon=${nextViewport.longitude} " +
                "latSpan=${nextViewport.latitudeSpan} lonSpan=${nextViewport.longitudeSpan} " +
                "zoom=$zoom forceRefresh=$forceRefresh"
        )
        viewport = nextViewport
        centerTarget = LatLng(nextViewport.latitude, nextViewport.longitude)
        centerZoom = zoom
        centerRequestNonce += 1L
        if (forceRefresh) {
            refreshNonce += 1
        }
    }

    fun startLocationResolution() {
        scope.launch {
            isResolvingInitialLocation = true
            Log.d(MAP_TAG, "startLocationResolution")
            val location = locationClient.awaitCurrentOrLastKnownLocation()
            hasCompletedInitialLocationAttempt = true
            isResolvingInitialLocation = false
            Log.d(
                MAP_TAG,
                if (location != null) {
                    "location resolved lat=${location.latitude} lon=${location.longitude} acc=${location.accuracy}"
                } else {
                    "location unavailable; falling back to default viewport"
                }
            )

            if (location != null) {
                applyViewport(
                    nextViewport = MerchantViewport(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        latitudeSpan = 0.03,
                        longitudeSpan = 0.03
                    ),
                    zoom = 15.0,
                    forceRefresh = true
                )
            } else {
                applyViewport(
                    nextViewport = fallbackViewport,
                    zoom = 5.5,
                    forceRefresh = true
                )
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        Log.d(MAP_TAG, "locationPermissionResult granted=$granted grants=$grants")
        if (granted) {
            startLocationResolution()
        } else {
            hasCompletedInitialLocationAttempt = true
            isResolvingInitialLocation = false
            applyViewport(
                nextViewport = fallbackViewport,
                zoom = 5.5,
                forceRefresh = true
            )
        }
    }

    fun requestLocationOnLaunch() {
        Log.d(MAP_TAG, "requestLocationOnLaunch hasPermission=${hasLocationPermission(context)}")
        if (hasLocationPermission(context)) {
            startLocationResolution()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        if (hasConfiguredMapsApiKey) {
            requestLocationOnLaunch()
        } else {
            hasCompletedInitialLocationAttempt = true
            isResolvingInitialLocation = false
        }
    }

    val viewportQueryKey = currentQueryKey(viewport)
    LaunchedEffect(viewportQueryKey, refreshNonce, hasCompletedInitialLocationAttempt, hasConfiguredMapsApiKey) {
        if (!hasConfiguredMapsApiKey || !hasCompletedInitialLocationAttempt) return@LaunchedEffect

        activeFetchId += 1L
        val fetchId = activeFetchId
        isLoading = true
        runtimeErrorMessage = null
        Log.d(
            MAP_TAG,
            "fetchPlaces start fetchId=$fetchId key=$viewportQueryKey lat=${viewport.latitude} " +
                "lon=${viewport.longitude} radius=${searchRadius(viewport)}"
        )

        try {
            val fetched = rootViewModel.fetchBtcMerchantPlaces(
                latitude = viewport.latitude,
                longitude = viewport.longitude,
                radiusKilometers = searchRadius(viewport)
            )
            if (activeFetchId == fetchId) {
                places = fetched
                Log.d(MAP_TAG, "fetchPlaces success fetchId=$fetchId count=${fetched.size}")
            }
        } catch (error: CancellationException) {
            Log.d(MAP_TAG, "fetchPlaces cancelled fetchId=$fetchId")
            throw error
        } catch (error: Exception) {
            Log.e(MAP_TAG, "fetchPlaces failure fetchId=$fetchId", error)
            if (activeFetchId == fetchId) {
                runtimeErrorMessage = if (places.isEmpty()) {
                    "Couldn’t load BTC merchants right now."
                } else {
                    "Couldn’t refresh this area right now."
                }
            }
        } finally {
            if (activeFetchId == fetchId) {
                isLoading = false
            }
        }
    }

    LaunchedEffect(centerRequestNonce) {
        if (centerRequestNonce <= 0L) return@LaunchedEffect
        Log.d(MAP_TAG, "centerMap lat=${centerTarget.latitude} lon=${centerTarget.longitude} zoom=$centerZoom")
        cameraPositionState.move(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(centerTarget)
                    .zoom(centerZoom.toFloat())
                    .build()
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
    ) {
        if (hasConfiguredMapsApiKey) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = mapProperties.copy(
                    isMyLocationEnabled = hasLocationPermission(context)
                ),
                uiSettings = mapUiSettings,
                onMapClick = { selectedPlace = null }
            ) {
                MapEffect(Unit) { map ->
                    map.setOnCameraIdleListener {
                        updateViewportFromMap(map)?.let { updated ->
                            if (shouldUpdateViewport(viewport, updated)) {
                                viewport = updated
                            }
                        }
                    }
                }

                places.forEach { place ->
                    Marker(
                        state = MarkerState(position = LatLng(place.lat, place.lon)),
                        title = place.name ?: "BTC Merchant",
                        icon = BitmapDescriptorFactory.defaultMarker(
                            if (selectedPlace?.id == place.id) {
                                BitmapDescriptorFactory.HUE_ROSE
                            } else {
                                BitmapDescriptorFactory.HUE_RED
                            }
                        ),
                        onClick = {
                            selectedPlace = place
                            true
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            MapHeaderCard(
                errorMessage = errorMessage,
                onDismiss = onDismiss,
                onRecenter = {
                    if (!hasConfiguredMapsApiKey) return@MapHeaderCard
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startLocationResolution()
                    } else {
                        isResolvingInitialLocation = true
                        hasCompletedInitialLocationAttempt = false
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            )
        }

        if (isResolvingInitialLocation) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.78f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Finding your location...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        } else if (isLoading && places.isEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.78f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Loading merchants...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        selectedPlace?.let { place ->
            MerchantPlaceBottomSheet(
                place = place,
                onDismiss = { selectedPlace = null }
            )
        }
    }
}

@Composable
private fun MapHeaderCard(
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRecenter: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                MapActionButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    onClick = onDismiss
                )

                Row(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = SplitFeatureIcons.Store,
                        contentDescription = null,
                        tint = SplitBrandPink
                    )
                    Text(
                        text = "BTC Merchant Map",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                MapActionButton(
                    icon = Icons.Rounded.MyLocation,
                    contentDescription = "Recenter map",
                    onClick = onRecenter
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red.copy(alpha = 0.92f)
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Paid a bitcoin-accepting merchant and didn't get rewarded? Submit the business from your transaction details. We'll add them to our rewards program ASAP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.76f)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Just tap the",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                        Icon(
                            imageVector = SplitFeatureIcons.Store,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "in your transaction.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {}

        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MerchantPlaceBottomSheet(
    place: BtcMerchantPlace,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        contentColor = Color.White,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.16f)
            ) {
                Spacer(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                )
            }
        }
    ) {
        MerchantPlaceSheetContent(
            place = place,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun MerchantPlaceSheetContent(
    place: BtcMerchantPlace,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        MerchantPlaceMedia(place = place)

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = place.name ?: "BTC Merchant",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            place.address?.takeIf { it.isNotBlank() }?.let {
                MerchantPlaceDetailRow(
                    icon = Icons.Rounded.LocationOn,
                    value = it
                )
            }

            place.paymentProvider?.takeIf { it.isNotBlank() }?.let {
                MerchantPlaceDetailRow(
                    icon = Icons.Rounded.Bolt,
                    value = it.replaceFirstChar { char -> char.uppercase() }
                )
            }

            place.verifiedAt?.takeIf { it.isNotBlank() }?.let {
                MerchantPlaceDetailRow(
                    icon = Icons.Rounded.CheckCircle,
                    value = "Verified $it"
                )
            }

            place.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f)
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                normalizedWebsiteUrl(place.website)?.let { websiteUrl ->
                    MerchantPlaceActionPill(
                        icon = Icons.Rounded.Language,
                        title = "Open Website",
                        onClick = { openUrl(context, websiteUrl) }
                    )
                }

                place.osmUrl?.takeIf { it.isNotBlank() }?.let { osmUrl ->
                    MerchantPlaceActionPill(
                        icon = Icons.Rounded.Map,
                        title = "Open in OpenStreetMap",
                        onClick = { openUrl(context, osmUrl) }
                    )
                }

                googleMapsUrl(place)?.let { mapsUrl ->
                    MerchantPlaceActionPill(
                        icon = Icons.Rounded.MyLocation,
                        title = "Open in Google Maps",
                        onClick = { openUrl(context, mapsUrl) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MerchantPlaceMedia(
    place: BtcMerchantPlace
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF111114))
    ) {
        MerchantPlaceFallbackMedia()

        if (!place.image.isNullOrBlank()) {
            AsyncImage(
                model = place.image,
                contentDescription = place.name ?: "BTC Merchant",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Surface(
            modifier = Modifier.matchParentSize(),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {}
    }
}

@Composable
private fun MerchantPlaceFallbackMedia() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    color = Color.Transparent
                )
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                SplitBrandBlue.copy(alpha = 0.28f),
                                SplitBrandPink.copy(alpha = 0.24f),
                                Color.Black.copy(alpha = 0.94f)
                            )
                        )
                    )
            )
        }

        Icon(
            imageVector = SplitFeatureIcons.Store,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.86f),
            modifier = Modifier.size(38.dp)
        )
    }
}

@Composable
private fun MerchantPlaceDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.68f),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.82f)
        )
    }
}

@Composable
private fun MerchantPlaceActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF17171B),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun updateViewportFromMap(map: NativeGoogleMap): MerchantViewport? {
    val visibleRegion = map.projection.visibleRegion
    val bounds = visibleRegion.latLngBounds
    val center = map.cameraPosition.target
    val latitudeSpan = kotlin.math.abs(bounds.northeast.latitude - bounds.southwest.latitude)
    val longitudeSpan = kotlin.math.abs(bounds.northeast.longitude - bounds.southwest.longitude)
    if (!center.latitude.isFinite() ||
        !center.longitude.isFinite() ||
        !latitudeSpan.isFinite() ||
        !longitudeSpan.isFinite()
    ) {
        return null
    }

    return MerchantViewport(
        latitude = center.latitude,
        longitude = center.longitude,
        latitudeSpan = latitudeSpan,
        longitudeSpan = longitudeSpan
    )
}

private fun currentQueryKey(viewport: MerchantViewport): String {
    val lat = "%.4f".format(viewport.latitude)
    val lon = "%.4f".format(viewport.longitude)
    val radius = "%.2f".format(searchRadius(viewport))
    return "$lat|$lon|$radius"
}

private fun shouldUpdateViewport(
    current: MerchantViewport,
    updated: MerchantViewport
): Boolean {
    return currentQueryKey(current) != currentQueryKey(updated)
}

private fun searchRadius(viewport: MerchantViewport): Double {
    val latKm = viewport.latitudeSpan * 111.0
    val lonKm = viewport.longitudeSpan * 111.0 * cos(viewport.latitude * Math.PI / 180.0)
    val radius = maxOf(latKm, kotlin.math.abs(lonKm)) * 0.55
    return radius.coerceIn(0.05, 50.0)
}

private fun openUrl(
    context: Context,
    url: String
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun normalizedWebsiteUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    return if (value.startsWith("http://") || value.startsWith("https://")) {
        value
    } else {
        "https://$value"
    }
}

private fun googleMapsUrl(place: BtcMerchantPlace): String? {
    if (place.lat == 0.0 && place.lon == 0.0) return null
    val query = Uri.encode(
        buildString {
            append(place.lat)
            append(",")
            append(place.lon)
            place.name?.takeIf { it.isNotBlank() }?.let {
                append(" ")
                append(it)
            }
        }
    )
    return "https://www.google.com/maps/search/?api=1&query=$query"
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

private class MerchantLocationClient(
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
