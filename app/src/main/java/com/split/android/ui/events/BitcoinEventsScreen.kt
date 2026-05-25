package com.split.android.ui.events

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.split.android.data.events.BitcoinEventItem
import com.split.android.data.events.BitcoinEventSearchOrigin
import com.split.android.ui.SplitRootViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val EventsBackground = Color(0xFF050508)
private val EventsCardSurface = Color(0xFF141830)
private val EventsInputSurface = Color.White.copy(alpha = 0.12f)
private val EventsBrandBlue = Color(0xFF132B62)
private val EventsBrandPink = Color(0xFFBE3287)

private data class EventCoordinateSnapshot(
    val latitude: Double,
    val longitude: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitcoinEventsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationClient = remember { EventLocationClient(context) }

    var nearbyEvents by remember { mutableStateOf<List<BitcoinEventItem>>(emptyList()) }
    var moreEvents by remember { mutableStateOf<List<BitcoinEventItem>>(emptyList()) }
    var searchOrigin by remember { mutableStateOf<BitcoinEventSearchOrigin?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var hasStartedLookup by remember { mutableStateOf(false) }
    var showZipSheet by remember { mutableStateOf(false) }
    var zipCode by rememberSaveable { mutableStateOf("") }
    var zipErrorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmittingZip by remember { mutableStateOf(false) }
    var showAllEvents by rememberSaveable { mutableStateOf(false) }

    val visibleEvents = remember(nearbyEvents, moreEvents, showAllEvents) {
        if (showAllEvents) {
            (nearbyEvents + moreEvents)
                .distinctBy { it.id }
                .sortedBy { parseEventInstant(it.startsAt) ?: Instant.MAX }
        } else {
            nearbyEvents.sortedBy { parseEventInstant(it.startsAt) ?: Instant.MAX }
        }
    }
    val emptyTitle = if (showAllEvents) "No events listed yet" else "No nearby events yet"
    val emptyMessage = if (showAllEvents) {
        "We don’t have any upcoming Bitcoin events across the US yet."
    } else {
        "We couldn’t find any Bitcoin events within 25 miles of this area."
    }

    suspend fun loadEventsForCoordinate(coordinate: EventCoordinateSnapshot) {
        isLoading = true
        errorMessage = null

        runCatching {
            rootViewModel.fetchBitcoinEvents(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude
            )
        }.onSuccess { response ->
            nearbyEvents = response.nearbyEvents
            moreEvents = response.moreEvents
            searchOrigin = response.searchOrigin
        }.onFailure { error ->
            errorMessage = userFacingEventsErrorMessage(error)
        }

        isLoading = false
    }

    suspend fun requestLocationLookup() {
        isLoading = true
        errorMessage = null

        val location = locationClient.awaitCurrentOrLastKnownLocation()
        if (location == null) {
            isLoading = false
            if (nearbyEvents.isEmpty()) {
                errorMessage = "We couldn’t get your location. Enter a ZIP code to browse nearby Bitcoin events."
                showZipSheet = true
            }
            return
        }

        loadEventsForCoordinate(
            coordinate = EventCoordinateSnapshot(
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
            if (nearbyEvents.isEmpty()) {
                showZipSheet = true
            }
        }
    }

    fun requestLocationOrZipFallback() {
        if (hasEventsLocationPermission(context)) {
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
            rootViewModel.fetchBitcoinEvents(postalCode = normalizedZip)
        }.onSuccess { response ->
            nearbyEvents = response.nearbyEvents
            moreEvents = response.moreEvents
            searchOrigin = response.searchOrigin
            errorMessage = null
            showAllEvents = false
            showZipSheet = false
        }.onFailure { error ->
            zipErrorMessage = userFacingEventsErrorMessage(error)
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

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        if (!hasStartedLookup) {
            hasStartedLookup = true
            requestLocationOrZipFallback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EventsBackground)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            EventsOverlayHeader(
                title = "Bitcoin Events",
                onBack = onBack
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EventsSecondaryCapsuleButton(
                    text = "Use ZIP Code",
                    icon = Icons.Rounded.Numbers,
                    onClick = {
                        zipErrorMessage = null
                        showZipSheet = true
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                EventsScopeToggle(
                    showAllEvents = showAllEvents,
                    onSelectNearby = { showAllEvents = false },
                    onSelectAllEvents = { showAllEvents = true }
                )
            }

            PullToRefreshBox(
                isRefreshing = isLoading && visibleEvents.isNotEmpty(),
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
                        isLoading && visibleEvents.isEmpty() -> {
                            item {
                                EventsLoadingCard()
                            }
                        }

                        errorMessage != null && visibleEvents.isEmpty() -> {
                            item {
                                EventsStateCard(
                                    title = "Events unavailable",
                                    message = errorMessage ?: "",
                                    actionLabel = "Enter ZIP Code",
                                    onAction = {
                                        zipErrorMessage = null
                                        showZipSheet = true
                                    }
                                )
                            }
                        }

                        visibleEvents.isEmpty() -> {
                            item {
                                EventsStateCard(
                                    title = emptyTitle,
                                    message = emptyMessage,
                                    actionLabel = null,
                                    onAction = null
                                )
                            }
                        }

                        else -> {
                            items(
                                items = visibleEvents,
                                key = { it.id }
                            ) { event ->
                                BitcoinEventCard(event = event)
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

    if (showZipSheet) {
        ModalBottomSheet(
            onDismissRequest = { showZipSheet = false },
            containerColor = EventsBackground
        ) {
            BitcoinEventsZipSheet(
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
private fun EventsOverlayHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EventsHeaderActionButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
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
private fun EventsHeaderActionButton(
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
private fun EventsSecondaryCapsuleButton(
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
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun EventsScopeToggle(
    showAllEvents: Boolean,
    onSelectNearby: () -> Unit,
    onSelectAllEvents: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .width(184.dp)
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp)
    ) {
        EventsScopeToggleOption(
            text = "Nearby",
            selected = !showAllEvents,
            onClick = onSelectNearby,
            modifier = Modifier.weight(1f)
        )
        EventsScopeToggleOption(
            text = "All Events",
            selected = showAllEvents,
            onClick = onSelectAllEvents,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EventsScopeToggleOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(if (selected) EventsBrandPink else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun EventsLoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = EventsCardSurface,
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
                text = "Looking for nearby Bitcoin events...",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EventsStateCard(
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
        color = EventsCardSurface,
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
                        containerColor = EventsBrandPink,
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
private fun BitcoinEventCard(
    event: BitcoinEventItem
) {
    val uriHandler = LocalUriHandler.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        color = EventsCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        onClick = {
            if (event.sourceUrl.isNotBlank()) {
                runCatching { uriHandler.openUri(event.sourceUrl) }
            }
        }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EventCover(event = event)

            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventMetaRow(
                        icon = Icons.Rounded.CalendarMonth,
                        text = eventDisplayString(
                            startsAt = event.startsAt,
                            timezone = event.timezone
                        )
                    )
                    EventMetaRow(
                        icon = Icons.Rounded.LocationOn,
                        text = eventLocationLine(event)
                    )

                    if (event.hostName.isNotBlank()) {
                        EventMetaRow(
                            icon = Icons.Rounded.People,
                            text = event.hostName
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCover(
    event: BitcoinEventItem
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        EventsBrandBlue.copy(alpha = 0.28f),
                        EventsBrandPink.copy(alpha = 0.24f)
                    )
                )
            )
    ) {
        if (!event.coverImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = event.coverImageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Event,
                contentDescription = null,
                tint = EventsBrandPink,
                modifier = Modifier
                    .size(38.dp)
                    .align(Alignment.Center)
            )
        }

        Text(
            text = distanceText(event.distanceMiles),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.54f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EventMetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EventsBrandBlue,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun BitcoinEventsZipSheet(
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
            text = "Location is unavailable. Enter a ZIP code and we’ll show Bitcoin events within 25 miles.",
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
                colors = eventsTextFieldColors()
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

            EventsGradientButton(
                text = "Show Events",
                isLoading = isSubmitting,
                modifier = Modifier.weight(1f),
                onClick = onSubmit
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun EventsGradientButton(
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
                    colors = listOf(EventsBrandBlue, EventsBrandPink)
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
private fun eventsTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = EventsInputSurface,
    unfocusedContainerColor = EventsInputSurface,
    disabledContainerColor = EventsInputSurface,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Color.White.copy(alpha = 0.72f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
    cursorColor = Color.White
)

private fun eventLocationLine(event: BitcoinEventItem): String {
    val venue = event.venueName.trim()
    val cityState = listOf(event.city, event.region)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ")

    return when {
        venue.isNotEmpty() && cityState.isNotEmpty() -> "$venue • $cityState"
        venue.isNotEmpty() -> venue
        else -> cityState
    }
}

private fun distanceText(distanceMiles: Double?): String {
    if (distanceMiles == null) {
        return "Nearby"
    }

    if (distanceMiles < 0.1) {
        return "Near you"
    }

    return if (distanceMiles >= 10.0) {
        "${distanceMiles.roundToInt()} mi"
    } else {
        String.format(Locale.US, "%.1f mi", distanceMiles)
    }
}

private fun eventDisplayString(
    startsAt: String,
    timezone: String
): String {
    val instant = parseEventInstant(startsAt) ?: return "Date TBD"
    val zoneId = runCatching {
        ZoneId.of(timezone)
    }.getOrElse {
        ZoneId.systemDefault()
    }

    return EventDateFormatter.displayFormatter
        .withZone(zoneId)
        .format(instant)
}

private fun parseEventInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) {
        return null
    }

    return runCatching {
        Instant.parse(value)
    }.getOrNull()
}

private object EventDateFormatter {
    val displayFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("EEE, MMM d • h:mm a", Locale.US)
}

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

private fun userFacingEventsErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return if (
        raw.contains("That ZIP code") ||
        raw.contains("We could not") ||
        raw.contains("Enter a valid US ZIP code")
    ) {
        raw
    } else {
        "We couldn’t load nearby Bitcoin events right now."
    }
}

private fun hasEventsLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

private class EventLocationClient(
    private val context: Context
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun awaitCurrentOrLastKnownLocation(): Location? {
        if (!hasEventsLocationPermission(context)) {
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
