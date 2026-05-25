package com.split.android.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.split.android.R
import com.split.android.data.auth.AuthState
import com.split.android.data.wallet.CoreLightningBalanceSummary
import com.split.android.data.wallet.EclairBalanceSummary
import com.split.android.data.wallet.LndBalanceSummary
import com.split.android.data.wallet.NwcBalanceSummary
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.SparkSubwalletBalanceSummary
import com.split.android.data.wallet.WalletLightningAddressInfo
import com.split.android.data.wallet.WalletState
import com.split.android.ui.MainTabHeader
import com.split.android.ui.NwcSymbolIcon
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.profile.CreateLightningAddressDialog
import com.split.android.ui.profile.readProfilePhotoSelection
import com.split.android.ui.qr.IdentityShareSheet
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import com.split.android.ui.theme.SplitSurface
import com.split.android.ui.theme.SplitSurfaceAlt
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val CustomerIndexBlue = Color(0xFF132B62)
private val CustomerIndexPink = Color(0xFFBE3287)
private val CustomerIndexBlack = Color(0xFF050508)
private const val WalletBalancePreferencesName = "split_wallet_balance_preferences"
private const val WalletBalanceHiddenKey = "split.walletBalanceHidden.v1"

data class SpendWalletMenuItem(
    val source: SpendWalletSource,
    val walletId: String?,
    val title: String,
    val subtitle: String,
    val isActive: Boolean
)

@Composable
fun WalletHomeScreen(
    rootViewModel: SplitRootViewModel,
    walletState: WalletState.Ready,
    authState: AuthState,
    hasValidSession: Boolean,
    activeSpendWallet: SpendWalletSource,
    walletMenuItems: List<SpendWalletMenuItem>,
    lndBalanceSummary: LndBalanceSummary?,
    nwcBalanceSummary: NwcBalanceSummary?,
    coreLightningBalanceSummary: CoreLightningBalanceSummary?,
    eclairBalanceSummary: EclairBalanceSummary?,
    sparkSubwalletBalanceSummary: SparkSubwalletBalanceSummary?,
    isStartingTorForActiveWallet: Boolean,
    onSelectWalletMenuItem: (SpendWalletMenuItem) -> Unit,
    onOpenBitcoinEvents: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenMerchantMap: () -> Unit,
    loadBtcUsdPrice: suspend () -> Double,
    onStartCashAppBuyBitcoin: (amountSats: Long) -> Unit,
    onTapClaimBitcoin: () -> Unit,
    onTapSend: () -> Unit,
    onTapReceive: () -> Unit,
    onTapTransactions: () -> Unit,
    unseenTransactionCount: Int,
    onRetryAuth: () -> Unit,
    onClearWallet: () -> Unit,
    isLaunchingBuyBitcoin: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var btcUsdPrice by remember { mutableStateOf<Double?>(null) }
    var btcPriceText by remember { mutableStateOf<String?>(null) }
    var isRefreshingPrice by remember { mutableStateOf(false) }
    var activeOverlay by remember { mutableStateOf<HomeOverlay?>(null) }
    val fiatBalanceText = formatWalletFiat(
        balanceSats = walletState.balanceSats,
        btcUsdPrice = btcUsdPrice
    )
    val displayedBalanceSats = when (activeSpendWallet) {
        SpendWalletSource.SPARK -> walletState.balanceSats
        SpendWalletSource.LND -> lndBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.NWC -> nwcBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.CORE_LIGHTNING -> coreLightningBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.ECLAIR -> eclairBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.SPARK_SUBWALLET -> sparkSubwalletBalanceSummary?.spendableSats ?: 0L
    }
    val displayedFiatBalanceText = formatWalletFiat(
        balanceSats = displayedBalanceSats,
        btcUsdPrice = btcUsdPrice
    )
    val displayedBtcBalanceText = formatBtc(displayedBalanceSats)
    val authStatusText = walletAuthStatusText(authState)
    val authStatusIsError = authState is AuthState.Failed

    fun refreshPrice() {
        if (isRefreshingPrice) return
        scope.launch {
            isRefreshingPrice = true
            btcPriceText = runCatching {
                val price = loadBtcUsdPrice()
                btcUsdPrice = price
                formatUsdPrice(price)
            }.getOrElse {
                btcUsdPrice = null
                "Price unavailable"
            }
            isRefreshingPrice = false
        }
    }

    LaunchedEffect(Unit) {
        refreshPrice()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CustomerIndexBlack)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        MainTabHeader(
            onOpenBitcoinEvents = onOpenBitcoinEvents,
            onOpenContacts = onOpenContacts,
            onOpenProfile = onOpenProfile,
            onOpenMerchantMap = onOpenMerchantMap
        )

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            WalletPrimarySurface(
                fiatBalanceText = displayedFiatBalanceText,
                btcBalanceText = displayedBtcBalanceText,
                authStatusText = authStatusText,
                authStatusIsError = authStatusIsError,
                activeSpendWallet = activeSpendWallet,
                walletMenuItems = walletMenuItems,
                isStartingTorForActiveWallet = isStartingTorForActiveWallet,
                onSelectWalletMenuItem = onSelectWalletMenuItem,
                onTapQrScanner = onOpenQrScanner,
                onTapSend = onTapSend,
                onTapReceive = onTapReceive,
                onTapTransactions = onTapTransactions,
                unseenTransactionCount = unseenTransactionCount
            )

            WalletIdentitySection(
                rootViewModel = rootViewModel,
                walletState = walletState,
                onShowBuyBitcoinInfo = { activeOverlay = HomeOverlay.BuyBitcoinInfo },
                onTapBuyBitcoin = { activeOverlay = HomeOverlay.CashAppAmount },
                isLaunchingBuyBitcoin = isLaunchingBuyBitcoin
            )

            WalletMarketPriceCard(
                rootViewModel = rootViewModel,
                btcPriceText = btcPriceText ?: "Loading market...",
                isRefreshingPrice = isRefreshingPrice,
                onRefreshPrice = ::refreshPrice
            )
        }
    }

    when (activeOverlay) {
        HomeOverlay.BuyBitcoinInfo -> BuyBitcoinInfoOverlay(
            onDismiss = { activeOverlay = null }
        )

        HomeOverlay.CashAppAmount -> CashAppAmountOverlay(
            btcUsdRate = btcUsdPrice,
            isStarting = isLaunchingBuyBitcoin,
            onCancel = { activeOverlay = null },
            onStart = { amountSats ->
                activeOverlay = null
                onStartCashAppBuyBitcoin(amountSats)
            }
        )

        null -> Unit
    }
}

private enum class HomeOverlay {
    BuyBitcoinInfo,
    CashAppAmount
}

@Composable
private fun WalletPrimarySurface(
    fiatBalanceText: String,
    btcBalanceText: String,
    authStatusText: String?,
    authStatusIsError: Boolean,
    activeSpendWallet: SpendWalletSource,
    walletMenuItems: List<SpendWalletMenuItem>,
    isStartingTorForActiveWallet: Boolean,
    onSelectWalletMenuItem: (SpendWalletMenuItem) -> Unit,
    onTapQrScanner: () -> Unit,
    onTapSend: () -> Unit,
    onTapReceive: () -> Unit,
    onTapTransactions: () -> Unit,
    unseenTransactionCount: Int
) {
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.22f)
            )
            .background(
                color = CustomerIndexBlack,
                shape = shape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = shape
            )
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WalletBalanceHero(
                fiatBalanceText = fiatBalanceText,
                btcBalanceText = btcBalanceText,
                authStatusText = authStatusText,
                authStatusIsError = authStatusIsError,
                activeSpendWallet = activeSpendWallet,
                walletMenuItems = walletMenuItems,
                isStartingTorForActiveWallet = isStartingTorForActiveWallet,
                onSelectWalletMenuItem = onSelectWalletMenuItem
            )

            WalletActionRow(
                onTapQrScanner = onTapQrScanner,
                onTapSend = onTapSend,
                onTapReceive = onTapReceive,
                onTapTransactions = onTapTransactions,
                unseenTransactionCount = unseenTransactionCount
            )
        }
    }
}

@Composable
private fun WalletBalanceHero(
    fiatBalanceText: String,
    btcBalanceText: String,
    authStatusText: String?,
    authStatusIsError: Boolean,
    activeSpendWallet: SpendWalletSource,
    walletMenuItems: List<SpendWalletMenuItem>,
    isStartingTorForActiveWallet: Boolean,
    onSelectWalletMenuItem: (SpendWalletMenuItem) -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val context = LocalContext.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            WalletBalancePreferencesName,
            Context.MODE_PRIVATE
        )
    }
    var isWalletMenuExpanded by remember { mutableStateOf(false) }
    var isWalletBalanceHidden by remember {
        mutableStateOf(preferences.getBoolean(WalletBalanceHiddenKey, false))
    }
    val visibleFiatBalanceText = if (isWalletBalanceHidden) "******" else fiatBalanceText
    val visibleBtcBalanceText = if (isWalletBalanceHidden) "******" else btcBalanceText
    val activeWalletMenuItem = walletMenuItems.firstOrNull { it.isActive }
        ?: walletMenuItems.firstOrNull { it.source == activeSpendWallet }
        ?: walletMenuItems.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 22.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.20f)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = CustomerIndexBlack,
                    shape = shape
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.30f),
                    shape = shape
                )
        ) {
            val hasExternalWallet = walletMenuItems.any { it.source != SpendWalletSource.SPARK }
            if (hasExternalWallet && activeWalletMenuItem != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isStartingTorForActiveWallet) {
                            TorStartupStatus()
                        }

                        ActiveWalletBadge(
                            activeWalletMenuItem = activeWalletMenuItem,
                            onClick = { isWalletMenuExpanded = true }
                        )
                    }

                    DropdownMenu(
                        expanded = isWalletMenuExpanded,
                        onDismissRequest = { isWalletMenuExpanded = false }
                    ) {
                        walletMenuItems.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    WalletMenuItemText(item)
                                },
                                leadingIcon = {
                                    WalletMenuItemIcon(item)
                                },
                                onClick = {
                                    isWalletMenuExpanded = false
                                    onSelectWalletMenuItem(item)
                                }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasExternalWallet) {
                    Spacer(modifier = Modifier.height(20.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = visibleFiatBalanceText,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 42.sp,
                            lineHeight = 44.sp
                        ),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    IconButton(
                        onClick = {
                            val next = !isWalletBalanceHidden
                            isWalletBalanceHidden = next
                            preferences.edit().putBoolean(WalletBalanceHiddenKey, next).apply()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .padding(top = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = if (isWalletBalanceHidden) {
                                "Show balance"
                            } else {
                                "Hide balance"
                            },
                            tint = Color.White.copy(alpha = if (isWalletBalanceHidden) 0.96f else 0.82f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Text(
                    text = visibleBtcBalanceText,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.86f)
                )

                authStatusText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (authStatusIsError) {
                            Color.Red.copy(alpha = 0.95f)
                        } else {
                            Color.White.copy(alpha = 0.82f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TorStartupStatus() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Starting Tor",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = Color.White,
                strokeWidth = 1.5.dp
            )
        }
    }
}

@Composable
private fun ActiveWalletBadge(
    activeWalletMenuItem: SpendWalletMenuItem,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WalletMenuItemIcon(
                item = activeWalletMenuItem,
                modifier = Modifier.size(14.dp),
                tint = CustomerIndexBlack
            )
            Text(
                text = activeWalletMenuItem.title,
                modifier = Modifier.widthIn(max = 96.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = CustomerIndexBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WalletMenuItemText(item: SpendWalletMenuItem) {
    Column(modifier = Modifier.widthIn(min = 150.dp, max = 230.dp)) {
        Text(
            text = item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black.copy(alpha = 0.56f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WalletMenuItemIcon(
    item: SpendWalletMenuItem,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Black
) {
    when (item.source) {
        SpendWalletSource.SPARK -> {
            Image(
                painter = painterResource(id = R.drawable.token_logo),
                contentDescription = null,
                modifier = modifier
            )
        }
        SpendWalletSource.NWC -> {
            NwcSymbolIcon(modifier = modifier)
        }
        SpendWalletSource.LND,
        SpendWalletSource.CORE_LIGHTNING,
        SpendWalletSource.ECLAIR,
        SpendWalletSource.SPARK_SUBWALLET -> {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = tint,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun WalletIdentitySection(
    rootViewModel: SplitRootViewModel,
    walletState: WalletState.Ready,
    onShowBuyBitcoinInfo: () -> Unit,
    onTapBuyBitcoin: () -> Unit,
    isLaunchingBuyBitcoin: Boolean
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var lightningAddressInfo by remember { mutableStateOf<WalletLightningAddressInfo?>(null) }
    var profilePicUrl by remember { mutableStateOf<String?>(null) }
    var localProfilePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var isLoadingLightningAddress by remember { mutableStateOf(true) }
    var addressLoadError by remember { mutableStateOf<String?>(null) }
    var isUploadingProfilePic by remember { mutableStateOf(false) }
    var profilePicUploadError by remember { mutableStateOf<String?>(null) }
    var showIdentityShare by remember { mutableStateOf(false) }
    var showCreateLightningAddressDialog by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }

    suspend fun loadIdentity() {
        runCatching { rootViewModel.fetchProfilePicUrl() }
            .onSuccess { profilePicUrl = it }

        isLoadingLightningAddress = true
        addressLoadError = null

        runCatching { rootViewModel.currentLightningAddressInfo() }
            .onSuccess {
                lightningAddressInfo = it
            }
            .onFailure { error ->
                lightningAddressInfo = null
                addressLoadError = error.message ?: "Failed to load Lightning address."
            }

        isLoadingLightningAddress = false
    }

    suspend fun uploadProfilePhotoFromUri(uri: Uri) {
        isUploadingProfilePic = true
        profilePicUploadError = null

        runCatching {
            val selection = readProfilePhotoSelection(context, uri)
            val uploadedUrl = rootViewModel.uploadProfilePic(
                fileData = selection.data,
                fileName = selection.fileName,
                mimeType = selection.mimeType
            )

            localProfilePhotoUri = uri
            profilePicUrl = uploadedUrl ?: profilePicUrl
        }.onFailure { error ->
            profilePicUploadError = error.message ?: "Failed to upload profile picture."
        }

        isUploadingProfilePic = false
    }

    LaunchedEffect(walletState.sparkAddress) {
        loadIdentity()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            uploadProfilePhotoFromUri(uri)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        coroutineScope.launch {
            uploadProfilePhotoFromUri(uri)
        }
    }

    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .background(
                color = CustomerIndexBlack,
                shape = shape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = shape
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WalletIdentityAvatar(
                    imageModel = localProfilePhotoUri ?: profilePicUrl,
                    isUploading = isUploadingProfilePic,
                    onClick = { showPhotoSourceDialog = true }
                )

                WalletLightningAddressRow(
                    lightningAddressInfo = lightningAddressInfo,
                    isLoading = isLoadingLightningAddress,
                    errorMessage = addressLoadError,
                    onRetry = {
                        coroutineScope.launch {
                            loadIdentity()
                        }
                    },
                    onCreateLightningAddress = { showCreateLightningAddressDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (lightningAddressInfo == null) 0.dp else 34.dp)
                )
            }

            if (!profilePicUploadError.isNullOrBlank()) {
                Text(
                    text = profilePicUploadError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red.copy(alpha = 0.95f)
                )
            }

            if (lightningAddressInfo != null) {
                WalletLightningAddressControls(
                    onShowQr = { showIdentityShare = true },
                    onTapBuyBitcoin = onTapBuyBitcoin,
                    isLaunchingBuyBitcoin = isLaunchingBuyBitcoin
                )
            }
        }

        if (lightningAddressInfo != null) {
            HomeCornerCircleButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp),
                icon = Icons.Rounded.Info,
                onClick = onShowBuyBitcoinInfo,
                contentDescription = "Buying Bitcoin info"
            )
        }
    }

    if (showIdentityShare && lightningAddressInfo != null) {
        Dialog(
            onDismissRequest = { showIdentityShare = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            IdentityShareSheet(
                lightningAddress = lightningAddressInfo!!.lightningAddress,
                suggestedContactName = lightningAddressInfo!!.lightningAddress
                    .substringBefore("@")
                    .ifBlank { "Split User" },
                paymentQrString = lightningAddressInfo!!.lnurlBech32.trim().ifBlank {
                    "lightning:${lightningAddressInfo!!.lightningAddress}"
                },
                profilePicUrl = profilePicUrl,
                onDismiss = { showIdentityShare = false }
            )
        }
    }

    if (showCreateLightningAddressDialog) {
        CreateLightningAddressDialog(
            rootViewModel = rootViewModel,
            onDismiss = { showCreateLightningAddressDialog = false },
            onCreated = { created ->
                lightningAddressInfo = created
                addressLoadError = null
                showCreateLightningAddressDialog = false
            }
        )
    }

    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("Update Profile Photo") },
            text = {
                Text("Choose an image from your photo library or Files.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPhotoSourceDialog = false
                        photoPicker.launch("image/*")
                    }
                ) {
                    Text("Choose Photo")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            filePicker.launch(arrayOf("image/*"))
                        }
                    ) {
                        Text("Choose File")
                    }

                    TextButton(
                        onClick = { showPhotoSourceDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
private fun WalletIdentityAvatar(
    imageModel: Any?,
    isUploading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = "Profile photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "+ photo",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.90f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {}

        if (isUploading) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun WalletLightningAddressRow(
    lightningAddressInfo: WalletLightningAddressInfo?,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onCreateLightningAddress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        when {
            isLoading -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )

                    Text(
                        text = "Loading Lightning Address...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            !errorMessage.isNullOrBlank() -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Red.copy(alpha = 0.95f)
                    )

                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }

            lightningAddressInfo != null -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Lightning Address",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.56f)
                    )

                    Text(
                        text = lightningAddressInfo.lightningAddress,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Setup a Lightning Address for this wallet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.68f)
                    )

                    Button(
                        onClick = onCreateLightningAddress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create Lightning Address")
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletLightningAddressControls(
    onShowQr: () -> Unit,
    onTapBuyBitcoin: () -> Unit,
    isLaunchingBuyBitcoin: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WalletBuyBitcoinButton(
            isLaunchingBuyBitcoin = isLaunchingBuyBitcoin,
            onClick = onTapBuyBitcoin,
            modifier = Modifier.width(148.dp)
        )

        Surface(
            modifier = Modifier.size(46.dp),
            onClick = onShowQr,
            shape = RoundedCornerShape(15.dp),
            color = CustomerIndexBlue
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.QrCode2,
                    contentDescription = "Show Lightning Address QR code",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun WalletMarketPriceCard(
    rootViewModel: SplitRootViewModel,
    btcPriceText: String,
    isRefreshingPrice: Boolean,
    onRefreshPrice: () -> Unit
) {
    EmbeddedBitcoinPriceChartCard(
        rootViewModel = rootViewModel,
        fallbackPriceText = btcPriceText,
        isRefreshingPrice = isRefreshingPrice,
        onRefreshPrice = onRefreshPrice
    )
}

@Composable
private fun WalletBuyBitcoinButton(
    isLaunchingBuyBitcoin: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .height(46.dp)
            .background(
                color = Color.Transparent,
                shape = buttonShape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.22f),
                shape = buttonShape
            )
            .clickable(
                enabled = !isLaunchingBuyBitcoin,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLaunchingBuyBitcoin) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Text(
                text = buildAnnotatedString {
                    append("Buy ")
                    withStyle(
                        SpanStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = CustomerIndexPink
                        )
                    ) {
                        append("₿")
                    }
                    append("itcoin")
                },
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HomeCornerCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(enabled = !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {}

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = Color.White
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalletActionRow(
    onTapQrScanner: () -> Unit,
    onTapSend: () -> Unit,
    onTapReceive: () -> Unit,
    onTapTransactions: () -> Unit,
    unseenTransactionCount: Int
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(20.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color.White.copy(alpha = 0.04f),
                    shape = shape
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = shape
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WalletActionTile(
                modifier = Modifier.weight(1f),
                icon = SplitFeatureIcons.QrCodeScan,
                title = null,
                iconSize = 24.dp,
                background = CustomerIndexPink,
                onClick = onTapQrScanner
            )
            WalletActionTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ArrowUpward,
                title = "Send",
                background = CustomerIndexBlue,
                onClick = onTapSend
            )
            WalletActionTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ArrowDownward,
                title = "Receive",
                background = CustomerIndexBlue,
                onClick = onTapReceive
            )
            WalletActionTile(
                modifier = Modifier.weight(1f),
                icon = SplitFeatureIcons.Transactions,
                title = "activity",
                background = Color.White,
                contentColor = CustomerIndexBlack,
                onClick = onTapTransactions,
                highlightColor = CustomerIndexPink,
                highlightCount = unseenTransactionCount
            )
        }
    }
}

@Composable
private fun WalletActionTile(
    icon: ImageVector,
    title: String?,
    background: Color,
    contentColor: Color = Color.White,
    onClick: () -> Unit,
    iconSize: Dp = 16.dp,
    highlightColor: Color? = null,
    highlightCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val isHighlighted = highlightCount > 0
    val badgeText = if (highlightCount > 9) "9+" else highlightCount.toString()

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = background,
            border = BorderStroke(
                1.dp,
                if (isHighlighted) (highlightColor ?: background).copy(alpha = 0.80f)
                else Color.White.copy(alpha = 0.10f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (title == null) {
                    Arrangement.Center
                } else {
                    Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize)
                )
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (isHighlighted) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp),
                shape = RoundedCornerShape(999.dp),
                color = (highlightColor ?: background).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
            ) {
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun HomeCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(enabled = !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {}

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BuyBitcoinInfoOverlay(
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
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = SplitSurfaceAlt,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Buying Bitcoin",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )

                Text(
                    text = "Split has integrated with Cash App to provide a seamless buying experience deposited over the Lightning Network. Cash App is responsible for pricing, fees, execution, and any related liabilities. Your Bitcoin is sent directly to your Split wallet, and Split does not charge any fees for these purchases.",
                    style = MaterialTheme.typography.bodyLarge,
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

private fun walletAuthStatusText(authState: AuthState): String? {
    return when (authState) {
        AuthState.Authenticating -> "Verifying with server..."
        is AuthState.Failed -> "Server auth failed: ${authState.message}"
        else -> null
    }
}

private fun formatWalletFiat(
    balanceSats: Long,
    btcUsdPrice: Double?
): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.US)
    val usdBalance = if (btcUsdPrice != null) {
        (balanceSats.toDouble() / 100_000_000.0) * btcUsdPrice
    } else {
        0.0
    }
    return formatter.format(usdBalance)
}

@Composable
private fun WalletSparkAddressCard(
    sparkAddress: String,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = SplitSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Spark address",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Use this to receive Lightning payments into Split on Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }

                HomeCircleButton(
                    icon = Icons.Rounded.ContentCopy,
                    onClick = onCopy
                )
            }

            Text(
                text = sparkAddress,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = compactAddress(sparkAddress),
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandBlue
            )
        }
    }
}

@Composable
private fun WalletStatusCard(
    authState: AuthState,
    hasValidSession: Boolean,
    onRetryAuth: () -> Unit,
    onClearWallet: () -> Unit
) {
    val authIsHealthy = hasValidSession && authState == AuthState.Authenticated

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = SplitSurfaceAlt,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Wallet status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            WalletStatusRow(
                title = "Server session",
                value = authStatusLabel(authState, hasValidSession),
                accent = if (authIsHealthy) SplitBrandBlue else SplitBrandPink
            )
            WalletStatusRow(
                title = "Spark private mode",
                value = "Enabled",
                accent = SplitBrandBlue
            )
            WalletStatusRow(
                title = "Local seed storage",
                value = "Encrypted on device",
                accent = Color.White
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                if (!authIsHealthy) {
                    Button(
                        onClick = onRetryAuth,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry auth")
                    }
                }

                OutlinedButton(
                    onClick = onClearWallet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove wallet")
                }
            }
        }
    }
}

@Composable
private fun WalletStatusRow(
    title: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.74f)
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
    }
}

private fun authStatusLabel(
    authState: AuthState,
    hasValidSession: Boolean
): String {
    return when (authState) {
        AuthState.Authenticated -> "Server session active"
        AuthState.Authenticating -> "Verifying with server..."
        is AuthState.Failed -> authState.message.ifBlank { "Server auth failed" }
        AuthState.Idle -> if (hasValidSession) "Server session active" else "Wallet connected"
    }
}

private fun compactAddress(address: String): String {
    if (address.length <= 22) return address
    return address.take(12) + "..." + address.takeLast(8)
}

private fun formatSats(balanceSats: Long): String {
    val formatter = NumberFormat.getIntegerInstance(Locale.US)
    return "${formatter.format(balanceSats)} sats"
}

private fun formatBtc(balanceSats: Long): String {
    val btc = balanceSats.toDouble() / 100_000_000.0
    return String.format(Locale.US, "₿ %.8f", btc)
}

private fun formatUsdPrice(price: Double): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.US)
    return formatter.format(price)
}

private fun Modifier.weightlessActionTile(): Modifier {
    return this.fillMaxWidth(0.31f)
}
