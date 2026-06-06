package com.split.android.ui.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.split.android.core.AppConfig
import com.split.android.data.wallet.CoreLightningNodeCredentials
import com.split.android.data.wallet.EclairConnectParser
import com.split.android.data.wallet.EclairConnectionState
import com.split.android.data.wallet.EclairNodeCredentials
import com.split.android.data.wallet.ExternalWalletKind
import com.split.android.data.wallet.ExternalWalletRecord
import com.split.android.data.wallet.LndConnectionState
import com.split.android.data.wallet.LndConnectParser
import com.split.android.data.wallet.LndNodeCredentials
import com.split.android.data.wallet.NwcConnectionState
import com.split.android.data.wallet.NwcWalletCredentials
import com.split.android.data.wallet.SparkSubwalletCredentials
import com.split.android.data.wallet.WalletLightningAddressInfo
import com.split.android.data.wallet.WalletState
import com.split.android.data.wallet.usesTor
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.events.BitcoinEventsScreen
import com.split.android.ui.home.ClaimBitcoinScreen
import com.split.android.ui.qr.IdentityShareSheet
import com.split.android.ui.qr.SplitFullScreenQrScanner
import com.split.android.ui.rewards.RewardsHowItWorksParagraphs
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import java.util.Locale

private val supportLightningAddress = AppConfig.supportLightningAddress
private const val CORE_LIGHTNING_QR_LOG_TAG = "SplitCoreLightningQR"

private enum class ProfileRoute {
    HOME,
    LIGHTNING_CONNECTIONS,
    LIGHTNING_WALLET_DETAIL,
    ADD_LIGHTNING_WALLET,
    LIGHTNING_NODE,
    NWC_WALLET,
    CORE_LIGHTNING_NODE,
    ECLAIR_NODE,
    SPARK_SUBWALLET,
    ADD_MERCHANT,
    CLAIM_BITCOIN,
    BITCOIN_EVENTS,
    REWARDS_INFO,
    SUPPORT,
    CONTENT_MODERATION,
    LEGAL,
    WALLET_MANAGEMENT
}

enum class ProfileStartRoute {
    HOME,
    ADD_LIGHTNING_WALLET
}

private enum class SparkSubwalletSetupMode {
    OVERVIEW,
    CREATE,
    RESTORE
}

private data class ProfileEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val route: ProfileRoute
)

private fun alternatingProfileColor(index: Int): Color {
    return if (index % 2 == 0) SplitBrandBlue else SplitBrandPink
}

sealed interface SupportChatRequest {
    data class Compose(val lightningAddress: String) : SupportChatRequest
    data class OpenThread(
        val conversationId: String,
        val title: String,
        val lightningAddress: String
    ) : SupportChatRequest
}

@Composable
fun ProfileScreen(
    rootViewModel: SplitRootViewModel,
    walletState: WalletState.Ready,
    modifier: Modifier = Modifier,
    initialRoute: ProfileStartRoute = ProfileStartRoute.HOME,
    onClose: (() -> Unit)? = null,
    onOpenSupportChat: (SupportChatRequest) -> Unit
) {
    var route by remember(initialRoute) { mutableStateOf(initialRoute.toProfileRoute()) }
    var selectedLightningWallet by remember { mutableStateOf<ExternalWalletRecord?>(null) }
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()

    when (route) {
        ProfileRoute.HOME -> ProfileHomeScreen(
            rootViewModel = rootViewModel,
            modifier = modifier,
            onClose = onClose,
            onSelectRoute = { route = it }
        )

        ProfileRoute.LIGHTNING_CONNECTIONS -> LightningConnectionsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.HOME },
            onAddWallet = { route = ProfileRoute.ADD_LIGHTNING_WALLET },
            onSelectWallet = { wallet ->
                selectedLightningWallet = wallet
                route = ProfileRoute.LIGHTNING_WALLET_DETAIL
            }
        )

        ProfileRoute.LIGHTNING_WALLET_DETAIL -> {
            val wallet = selectedLightningWallet
            if (wallet == null) {
                route = ProfileRoute.LIGHTNING_CONNECTIONS
            } else {
                LightningWalletDetailScreen(
                    rootViewModel = rootViewModel,
                    wallet = wallet,
                    onBack = { route = ProfileRoute.LIGHTNING_CONNECTIONS }
                )
            }
        }

        ProfileRoute.ADD_LIGHTNING_WALLET -> AddLightningWalletScreen(
            onBack = {
                route = if (rootViewModel.storedLightningWallets().isEmpty()) {
                    ProfileRoute.HOME
                } else {
                    ProfileRoute.LIGHTNING_CONNECTIONS
                }
            },
            onSelectRoute = { route = it }
        )

        ProfileRoute.LIGHTNING_NODE -> LightningNodeSettingsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.ADD_LIGHTNING_WALLET },
            showsSavedWallets = false,
            onWalletAdded = { route = ProfileRoute.LIGHTNING_CONNECTIONS }
        )

        ProfileRoute.NWC_WALLET -> NwcWalletSettingsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.ADD_LIGHTNING_WALLET },
            showsSavedWallets = false,
            onWalletAdded = { route = ProfileRoute.LIGHTNING_CONNECTIONS }
        )

        ProfileRoute.CORE_LIGHTNING_NODE -> CoreLightningSettingsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.ADD_LIGHTNING_WALLET },
            onWalletAdded = { route = ProfileRoute.LIGHTNING_CONNECTIONS }
        )

        ProfileRoute.ECLAIR_NODE -> EclairSettingsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.ADD_LIGHTNING_WALLET },
            onWalletAdded = { route = ProfileRoute.LIGHTNING_CONNECTIONS }
        )

        ProfileRoute.SPARK_SUBWALLET -> SparkSubwalletSetupScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.ADD_LIGHTNING_WALLET },
            onWalletAdded = { route = ProfileRoute.LIGHTNING_CONNECTIONS }
        )

        ProfileRoute.ADD_MERCHANT -> AddMerchantInfoScreen(
            onBack = { route = ProfileRoute.HOME }
        )

        ProfileRoute.CLAIM_BITCOIN -> ClaimBitcoinScreen(
            rootViewModel = rootViewModel,
            onDismiss = { route = ProfileRoute.HOME }
        )

        ProfileRoute.BITCOIN_EVENTS -> BitcoinEventsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.HOME }
        )

        ProfileRoute.REWARDS_INFO -> RewardsExplainedScreen(
            onBack = { route = ProfileRoute.HOME }
        )

        ProfileRoute.SUPPORT -> SupportScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.HOME },
            onOpenSupportChat = onOpenSupportChat
        )

        ProfileRoute.CONTENT_MODERATION -> ContentModerationScreen(
            onBack = { route = ProfileRoute.HOME }
        )

        ProfileRoute.LEGAL -> LegalScreen(
            onBack = { route = ProfileRoute.HOME }
        )

        ProfileRoute.WALLET_MANAGEMENT -> WalletManagementScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.HOME }
        )
    }
}

private fun ProfileStartRoute.toProfileRoute(): ProfileRoute {
    return when (this) {
        ProfileStartRoute.HOME -> ProfileRoute.HOME
        ProfileStartRoute.ADD_LIGHTNING_WALLET -> ProfileRoute.ADD_LIGHTNING_WALLET
    }
}

@Composable
private fun ProfileHomeScreen(
    rootViewModel: SplitRootViewModel,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)?,
    onSelectRoute: (ProfileRoute) -> Unit
) {
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val hasConnectedLightningNode = connectedLndNode != null || rootViewModel.hasStoredLndNode()
    val connectedNwcWallet by rootViewModel.connectedNwcWallet.collectAsStateWithLifecycle()
    val hasConnectedNwcWallet = connectedNwcWallet != null || rootViewModel.hasStoredNwcWallet()
    val connectedCoreLightningNode by rootViewModel.connectedCoreLightningNode.collectAsStateWithLifecycle()
    val hasConnectedCoreLightningNode = connectedCoreLightningNode != null || rootViewModel.hasStoredCoreLightningNode()
    val connectedEclairNode by rootViewModel.connectedEclairNode.collectAsStateWithLifecycle()
    val hasConnectedEclairNode = connectedEclairNode != null || rootViewModel.hasStoredEclairNode()
    val connectedSparkSubwallet by rootViewModel.connectedSparkSubwallet.collectAsStateWithLifecycle()
    val hasConnectedSparkSubwallet = connectedSparkSubwallet != null || rootViewModel.hasStoredSparkSubwallet()
    val hasConnectedLightningWallet =
        hasConnectedLightningNode ||
            hasConnectedNwcWallet ||
            hasConnectedCoreLightningNode ||
            hasConnectedEclairNode ||
            hasConnectedSparkSubwallet
    val entries = listOf(
        ProfileEntry(
            Icons.Rounded.Bolt,
            "Lightning Wallets",
            lightningConnectionsSubtitle(
                hasConnectedLightningNode = hasConnectedLightningNode,
                hasConnectedNwcWallet = hasConnectedNwcWallet,
                hasConnectedCoreLightningNode = hasConnectedCoreLightningNode,
                hasConnectedEclairNode = hasConnectedEclairNode,
                hasConnectedSparkSubwallet = hasConnectedSparkSubwallet
            ),
            if (hasConnectedLightningWallet) {
                ProfileRoute.LIGHTNING_CONNECTIONS
            } else {
                ProfileRoute.ADD_LIGHTNING_WALLET
            }
        ),
        ProfileEntry(
            SplitFeatureIcons.Store,
            "Add a Merchant",
            "Add any BTC business to our rewards program.",
            ProfileRoute.ADD_MERCHANT
        ),
        ProfileEntry(SplitFeatureIcons.Rewards, "Rewards Explained", "How it works.", ProfileRoute.REWARDS_INFO),
        ProfileEntry(Icons.Rounded.CurrencyBitcoin, "Claim Your Bitcoin", "On-chain deposits.", ProfileRoute.CLAIM_BITCOIN),
        ProfileEntry(Icons.Rounded.ChatBubble, "Contact / Support", "Questions, feedback, and product ideas.", ProfileRoute.SUPPORT),
        ProfileEntry(Icons.Rounded.Flag, "Content Moderation", "Blocking and user safety.", ProfileRoute.CONTENT_MODERATION),
        ProfileEntry(Icons.Rounded.Description, "Legal", "Documents and agreements.", ProfileRoute.LEGAL),
        ProfileEntry(Icons.Rounded.Lock, "Wallet Management", "Wallet device access.", ProfileRoute.WALLET_MANAGEMENT)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SplitBlack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.96f),
                            Color(0xFF121217).copy(alpha = 0.98f)
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp)
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(SplitBrandPink.copy(alpha = 0.12f))
                    .alpha(0.9f)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 8.dp)
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(SplitBrandBlue.copy(alpha = 0.10f))
                    .alpha(0.9f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                if (onClose != null) {
                    ProfileHeaderActionButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Close profile",
                        onClick = onClose
                    )
                }

                Column(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "App settings and wallet management",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.58f)
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.06f))
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            entries.forEachIndexed { index, entry ->
                ProfileNavCard(
                    entry = entry,
                    iconColor = alternatingProfileColor(index),
                    onClick = { onSelectRoute(entry.route) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

private fun lightningConnectionsSubtitle(
    hasConnectedLightningNode: Boolean,
    hasConnectedNwcWallet: Boolean,
    hasConnectedCoreLightningNode: Boolean,
    hasConnectedEclairNode: Boolean,
    hasConnectedSparkSubwallet: Boolean
): String {
    val connectedCount = listOf(
        hasConnectedLightningNode,
        hasConnectedNwcWallet,
        hasConnectedCoreLightningNode,
        hasConnectedEclairNode,
        hasConnectedSparkSubwallet
    ).count { it }
    return when {
        connectedCount > 1 -> "Manage your Lightning wallets"
        connectedCount == 1 -> "Manage your Lightning wallet"
        else -> "Connect any Lightning node or wallet"
    }
}

@Composable
private fun LightningConnectionsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    onAddWallet: () -> Unit,
    onSelectWallet: (ExternalWalletRecord) -> Unit
) {
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val connectedNwcWallet by rootViewModel.connectedNwcWallet.collectAsStateWithLifecycle()
    val connectedCoreLightningNode by rootViewModel.connectedCoreLightningNode.collectAsStateWithLifecycle()
    val connectedEclairNode by rootViewModel.connectedEclairNode.collectAsStateWithLifecycle()
    val connectedSparkSubwallet by rootViewModel.connectedSparkSubwallet.collectAsStateWithLifecycle()
    val lndVersion by rootViewModel.storedLndNodesVersion.collectAsStateWithLifecycle()
    val nwcVersion by rootViewModel.storedNwcWalletsVersion.collectAsStateWithLifecycle()
    val coreVersion by rootViewModel.storedCoreLightningNodesVersion.collectAsStateWithLifecycle()
    val eclairVersion by rootViewModel.storedEclairNodesVersion.collectAsStateWithLifecycle()
    val sparkVersion by rootViewModel.storedSparkSubwalletsVersion.collectAsStateWithLifecycle()
    val wallets = remember(
        lndVersion,
        nwcVersion,
        coreVersion,
        eclairVersion,
        sparkVersion,
        connectedLndNode,
        connectedNwcWallet,
        connectedCoreLightningNode,
        connectedEclairNode,
        connectedSparkSubwallet
    ) {
        rootViewModel.storedLightningWallets()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SubscreenHeader(
            title = "Lightning Wallets",
            subtitle = "Manage external Lightning wallets and nodes.",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (wallets.isEmpty()) {
                LightningWalletEmptyCard()
            } else {
                wallets.forEach { wallet ->
                    LightningWalletListCard(
                        wallet = wallet,
                        onClick = { onSelectWallet(wallet) }
                    )
                }
            }
        }

        AddLightningWalletButton(onClick = onAddWallet)
    }
}

@Composable
private fun AddLightningWalletScreen(
    onBack: () -> Unit,
    onSelectRoute: (ProfileRoute) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SubscreenHeader(
            title = "Add Wallet",
            subtitle = "Choose how you want to connect a Lightning wallet or node.",
            onBack = onBack
        )

        LightningConnectionOptionCard(
            iconContent = {
                NwcSymbolIcon(modifier = Modifier.size(32.dp))
            },
            title = "NWC Wallet or Node",
            subtitle = "Connect an NWC-compatible Lightning wallet or node",
            onClick = { onSelectRoute(ProfileRoute.NWC_WALLET) }
        )
        LightningConnectionOptionCard(
            iconContent = {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = SplitBrandBlue,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = "LND Lightning Node",
            subtitle = "Connect an LND lightning node",
            onClick = { onSelectRoute(ProfileRoute.LIGHTNING_NODE) }
        )
        LightningConnectionOptionCard(
            iconContent = {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = SplitBrandBlue,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = "Core Lightning Node",
            subtitle = "Connect a Core Lightning node using REST",
            onClick = { onSelectRoute(ProfileRoute.CORE_LIGHTNING_NODE) }
        )
        LightningConnectionOptionCard(
            iconContent = {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = SplitBrandBlue,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = "Eclair Node",
            subtitle = "Connect an Eclair node using its API",
            onClick = { onSelectRoute(ProfileRoute.ECLAIR_NODE) }
        )
        LightningConnectionOptionCard(
            iconContent = {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = SplitBrandBlue,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = "Spark Wallet",
            subtitle = "Create a new Spark wallet",
            onClick = { onSelectRoute(ProfileRoute.SPARK_SUBWALLET) }
        )
    }
}

@Composable
private fun LightningWalletEmptyCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "Connect any Lightning node or wallet.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AddLightningWalletButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Add Wallet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Icon(
                imageVector = Icons.Rounded.AddCircle,
                contentDescription = null,
                tint = SplitBrandBlue,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LightningWalletListCard(
    wallet: ExternalWalletRecord,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LightningWalletIcon(wallet.kind)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = wallet.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = wallet.kind.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.60f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun LightningConnectionOptionCard(
    iconContent: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                iconContent()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.60f)
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun NwcSymbolIcon(
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFFFA81A),
    secondaryColor: Color = Color(0xFF8573FF),
    foregroundColor: Color = Color.Black
) {
    Canvas(modifier = modifier) {
        val symbolSize = min(size.width, size.height)
        val left = (size.width - symbolSize) / 2f
        val top = (size.height - symbolSize) / 2f
        val center = Offset(left + symbolSize / 2f, top + symbolSize / 2f)
        val corner = CornerRadius(symbolSize * 0.085f, symbolSize * 0.085f)

        drawCircle(
            color = Color.Black,
            radius = symbolSize / 2f,
            center = center
        )

        val secondarySize = Size(symbolSize * 0.34f, symbolSize * 0.58f)
        val secondaryCenter = Offset(
            center.x + symbolSize * 0.20f,
            center.y - symbolSize * 0.16f
        )
        rotate(degrees = -35f, pivot = secondaryCenter) {
            drawRoundRect(
                color = secondaryColor,
                topLeft = Offset(
                    secondaryCenter.x - secondarySize.width / 2f,
                    secondaryCenter.y - secondarySize.height / 2f
                ),
                size = secondarySize,
                cornerRadius = corner
            )
        }

        val accentSize = Size(symbolSize * 0.55f, symbolSize * 0.55f)
        val accentTopLeft = Offset(
            center.x - accentSize.width / 2f,
            center.y - accentSize.height / 2f
        )
        rotate(degrees = -45f, pivot = center) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(accentColor, Color(0xFFFF8C14)),
                    start = accentTopLeft,
                    end = Offset(
                        accentTopLeft.x + accentSize.width,
                        accentTopLeft.y + accentSize.height
                    )
                ),
                topLeft = accentTopLeft,
                size = accentSize,
                cornerRadius = corner
            )
        }

        val plugSize = Size(symbolSize * 0.39f, symbolSize * 0.38f)
        val plugCenter = Offset(
            center.x - symbolSize * 0.01f,
            center.y + symbolSize * 0.07f
        )
        val plugTopLeft = Offset(
            plugCenter.x - plugSize.width / 2f,
            plugCenter.y - plugSize.height / 2f
        )
        rotate(degrees = -45f, pivot = plugCenter) {
            drawPath(
                path = nwcPlugPath(
                    left = plugTopLeft.x,
                    top = plugTopLeft.y,
                    width = plugSize.width,
                    height = plugSize.height
                ),
                color = foregroundColor
            )
        }
    }
}

private fun nwcPlugPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float
): Path {
    fun x(value: Float) = left + width * value
    fun y(value: Float) = top + height * value

    return Path().apply {
        moveTo(x(0.08f), y(0.36f))
        lineTo(x(0.24f), y(0.36f))
        lineTo(x(0.24f), y(0.18f))
        quadraticTo(x(0.275f), y(0.11f), x(0.31f), y(0.18f))
        lineTo(x(0.31f), y(0.36f))
        lineTo(x(0.42f), y(0.36f))
        lineTo(x(0.42f), y(0.11f))
        quadraticTo(x(0.46f), y(0.04f), x(0.50f), y(0.11f))
        lineTo(x(0.50f), y(0.36f))
        lineTo(x(0.62f), y(0.36f))
        lineTo(x(0.62f), y(0.18f))
        quadraticTo(x(0.66f), y(0.11f), x(0.70f), y(0.18f))
        lineTo(x(0.70f), y(0.38f))
        quadraticTo(x(0.84f), y(0.42f), x(0.90f), y(0.59f))
        lineTo(x(0.90f), y(0.72f))
        lineTo(x(0.72f), y(0.72f))
        quadraticTo(x(0.63f), y(0.92f), x(0.39f), y(0.92f))
        quadraticTo(x(0.18f), y(0.90f), x(0.12f), y(0.62f))
        lineTo(x(0.08f), y(0.62f))
        quadraticTo(x(-0.02f), y(0.49f), x(0.08f), y(0.36f))
        close()
    }
}

@Composable
private fun LightningWalletIcon(kind: ExternalWalletKind) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (kind == ExternalWalletKind.NWC) {
            NwcSymbolIcon(modifier = Modifier.size(32.dp))
        } else {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = SplitBrandBlue,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun LegacyLightningConnectionsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    onSelectRoute: (ProfileRoute) -> Unit
) {
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val hasConnectedLightningNode = connectedLndNode != null || rootViewModel.hasStoredLndNode()
    val connectedNwcWallet by rootViewModel.connectedNwcWallet.collectAsStateWithLifecycle()
    val hasConnectedNwcWallet = connectedNwcWallet != null || rootViewModel.hasStoredNwcWallet()
    val entries = listOf(
        ProfileEntry(
            Icons.Rounded.Bolt,
            "LND Lightning Node",
            if (hasConnectedLightningNode) {
                "Manage your LND lightning node connection"
            } else {
                "Connect an LND lightning node"
            },
            ProfileRoute.LIGHTNING_NODE
        ),
        ProfileEntry(
            Icons.Rounded.QrCode2,
            "NWC Wallet or Node",
            if (hasConnectedNwcWallet) {
                "Manage your NWC connected Lightning wallet or node"
            } else {
                "Connect an NWC-compatible Lightning wallet or node"
            },
            ProfileRoute.NWC_WALLET
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SubscreenHeader(
            title = "Lightning Connections",
            subtitle = "Connect or manage an external Lightning wallet or node.",
            onBack = onBack
        )

        entries.forEachIndexed { index, entry ->
            ProfileNavCard(
                entry = entry,
                iconColor = alternatingProfileColor(index),
                onClick = { onSelectRoute(entry.route) }
            )
        }
    }
}

@Composable
private fun LightningWalletDetailScreen(
    rootViewModel: SplitRootViewModel,
    wallet: ExternalWalletRecord,
    onBack: () -> Unit
) {
    var name by remember(wallet.id, wallet.updatedAtMillis) { mutableStateOf(wallet.label) }
    var draftName by remember(wallet.id, wallet.updatedAtMillis) { mutableStateOf(wallet.label) }
    var isEditingName by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val details = remember(wallet) { lightningWalletDetails(wallet) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isEditingName) {
                        TextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Wallet name") }
                        )
                    } else {
                        Text(
                            text = name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    ProfileHeaderActionButton(
                        icon = if (isEditingName) Icons.Rounded.Check else Icons.Rounded.Edit,
                        contentDescription = if (isEditingName) "Save wallet name" else "Edit wallet name",
                        onClick = {
                            if (isEditingName) {
                                val normalized = draftName.trim()
                                if (normalized.isNotEmpty()) {
                                    renameLightningWallet(rootViewModel, wallet, normalized)
                                    name = normalized
                                    isEditingName = false
                                }
                            } else {
                                draftName = name
                                isEditingName = true
                            }
                        },
                        iconTint = Color.White
                    )
                }

                if (isEditingName) {
                    TextButton(
                        onClick = {
                            draftName = name
                            isEditingName = false
                        }
                    ) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.76f))
                    }
                }

                Text(
                    text = wallet.kind.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.60f),
                    fontWeight = FontWeight.SemiBold
                )
            }

            ProfileHeaderActionButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Close wallet details",
                onClick = onBack
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF151519),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LightningNodeDetailRow("Wallet Name", name)
                LightningNodeDetailRow(details.pubkeyTitle, abbreviatedLightningNodeValue(details.pubkey))
                LightningNodeDetailRow(details.hostTitle, details.host)
                LightningNodeDetailRow("Connection", details.connectionMethod)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { showDeleteConfirmation = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Delete Wallet")
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Wallet?") },
            text = {
                Text(
                    if (wallet.kind == ExternalWalletKind.SPARK_SUBWALLET) {
                        "This removes $name, its local seed, and its local wallet data from this device. You must have the recovery phrase to restore funds."
                    } else {
                        "This removes $name from this device."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteLightningWallet(rootViewModel, wallet)
                        showDeleteConfirmation = false
                        onBack()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private data class LightningWalletDetails(
    val pubkeyTitle: String,
    val pubkey: String?,
    val hostTitle: String,
    val host: String,
    val connectionMethod: String
)

private fun lightningWalletDetails(wallet: ExternalWalletRecord): LightningWalletDetails {
    return when (wallet.kind) {
        ExternalWalletKind.LND -> {
            val node = runCatching { LndNodeCredentials.fromJson(wallet.payload) }.getOrNull()
            LightningWalletDetails(
                pubkeyTitle = "Node Pubkey",
                pubkey = node?.nodePubkey,
                hostTitle = "Host",
                host = node?.let { "${it.host}:${it.port}" } ?: "Not available",
                connectionMethod = node?.connectionMethodLabel() ?: "Not available"
            )
        }
        ExternalWalletKind.NWC -> {
            val nwcWallet = runCatching { NwcWalletCredentials.fromJson(wallet.payload) }.getOrNull()
            LightningWalletDetails(
                pubkeyTitle = "Wallet Pubkey",
                pubkey = nwcWallet?.walletPubkey,
                hostTitle = "Relay",
                host = nwcWallet?.primaryRelayHost ?: "Not available",
                connectionMethod = nwcWallet?.connectionMethodLabel() ?: "Not available"
            )
        }
        ExternalWalletKind.CORE_LIGHTNING -> {
            val node = runCatching { CoreLightningNodeCredentials.fromJson(wallet.payload) }.getOrNull()
            LightningWalletDetails(
                pubkeyTitle = "Node Pubkey",
                pubkey = node?.nodeId,
                hostTitle = "Host",
                host = node?.let { "${it.scheme}://${it.host}:${it.port}" } ?: "Not available",
                connectionMethod = node?.connectionMethodLabel() ?: "Not available"
            )
        }
        ExternalWalletKind.ECLAIR -> {
            val node = runCatching { EclairNodeCredentials.fromJson(wallet.payload) }.getOrNull()
            LightningWalletDetails(
                pubkeyTitle = "Node Pubkey",
                pubkey = node?.nodeId,
                hostTitle = "Host",
                host = node?.let { "${it.scheme}://${it.host}:${it.port}" } ?: "Not available",
                connectionMethod = node?.connectionMethodLabel() ?: "Not available"
            )
        }
        ExternalWalletKind.SPARK_SUBWALLET -> {
            val subwallet = runCatching { SparkSubwalletCredentials.fromJson(wallet.payload) }.getOrNull()
            LightningWalletDetails(
                pubkeyTitle = "Node Pubkey",
                pubkey = subwallet?.identityPubkey,
                hostTitle = "Address",
                host = subwallet?.sparkAddress ?: "Not available",
                connectionMethod = "Direct"
            )
        }
    }
}

private fun renameLightningWallet(
    rootViewModel: SplitRootViewModel,
    wallet: ExternalWalletRecord,
    label: String
) {
    when (wallet.kind) {
        ExternalWalletKind.LND -> rootViewModel.renameLndNode(wallet.id, label)
        ExternalWalletKind.NWC -> rootViewModel.renameNwcWallet(wallet.id, label)
        ExternalWalletKind.CORE_LIGHTNING -> rootViewModel.renameCoreLightningNode(wallet.id, label)
        ExternalWalletKind.ECLAIR -> rootViewModel.renameEclairNode(wallet.id, label)
        ExternalWalletKind.SPARK_SUBWALLET -> rootViewModel.renameSparkSubwallet(wallet.id, label)
    }
}

private fun deleteLightningWallet(
    rootViewModel: SplitRootViewModel,
    wallet: ExternalWalletRecord
) {
    when (wallet.kind) {
        ExternalWalletKind.LND -> rootViewModel.forgetLndNode(wallet.id)
        ExternalWalletKind.NWC -> rootViewModel.forgetNwcWallet(wallet.id)
        ExternalWalletKind.CORE_LIGHTNING -> rootViewModel.forgetCoreLightningNode(wallet.id)
        ExternalWalletKind.ECLAIR -> rootViewModel.forgetEclairNode(wallet.id)
        ExternalWalletKind.SPARK_SUBWALLET -> rootViewModel.forgetSparkSubwallet(wallet.id)
    }
}

@Composable
private fun ProfileAvatar(
    imageModel: Any?,
    isUploading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = "Profile photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Text(
                text = "+ add photo",
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
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun ProfileNavCard(
    entry: ProfileEntry,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = iconColor
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.68f)
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun LightningNodeSettingsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    showsSavedWallets: Boolean = true,
    onWalletAdded: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val lndState by rootViewModel.lndConnectionState.collectAsStateWithLifecycle()
    val connectedNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val balanceSummary by rootViewModel.lndBalanceSummary.collectAsStateWithLifecycle()
    val storedNodesVersion by rootViewModel.storedLndNodesVersion.collectAsStateWithLifecycle()

    var lndConnectString by rememberSaveable { mutableStateOf("") }
    var walletName by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isScanningQr by remember { mutableStateOf(false) }
    var renamingNode by remember { mutableStateOf<LndNodeCredentials?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var connectedNodeConfirmation by remember { mutableStateOf<LndNodeCredentials?>(null) }

    val activeNode = connectedNode ?: rootViewModel.activeLndNodeCredentials()
    val storedNodes = remember(storedNodesVersion, connectedNode) {
        rootViewModel.storedLndNodes()
    }
    val hasStoredNode = storedNodes.isNotEmpty() || activeNode != null || rootViewModel.hasStoredLndNode()
    val canConnect = !isConnecting &&
        lndConnectString.trim().isNotEmpty() &&
        walletName.trim().isNotEmpty()

    fun connectNode(rawValue: String) {
        val normalizedName = walletName.trim()
        if (normalizedName.isEmpty()) {
            statusMessage = "Enter a wallet name."
            return
        }
        val normalized = LndConnectParser.normalize(rawValue)?.trim().orEmpty()
        if (normalized.isEmpty()) {
            statusMessage = "Enter an LND Connect string."
            return
        }

        isConnecting = true
        statusMessage = if (normalized.contains(".onion", ignoreCase = true)) {
            "Starting Tor..."
        } else {
            "Checking LND node..."
        }
        scope.launch {
            runCatching {
                rootViewModel.connectLndNode(normalized, normalizedName)
            }.onSuccess { node ->
                lndConnectString = ""
                walletName = ""
                statusMessage = null
                connectedNodeConfirmation = node
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to connect to LND node."
            }
            isConnecting = false
        }
    }

    fun selectNode(node: LndNodeCredentials) {
        if (rootViewModel.setLndSpendWallet(node.id)) {
            statusMessage = "Selected ${node.displayName}"
        } else {
            statusMessage = "Unable to select this node."
        }
    }

    fun renameNode(node: LndNodeCredentials) {
        val normalized = renameText.trim()
        if (normalized.isBlank()) {
            statusMessage = "Enter a wallet name."
            return
        }

        rootViewModel.renameLndNode(node.id, normalized)
        renamingNode = null
        renameText = ""
        statusMessage = "Renamed wallet"
    }

    fun refreshConnection() {
        if (isRefreshing) return

        isRefreshing = true
        statusMessage = null
        scope.launch {
            runCatching {
                rootViewModel.refreshLndConnection()
            }.onSuccess {
                statusMessage = "Connection refreshed"
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to refresh this node."
            }
            isRefreshing = false
        }
    }

    fun handleScannedCode(code: String) {
        val normalized = LndConnectParser.normalize(code)?.trim().orEmpty()
        if (!normalized.startsWith("lndconnect://", ignoreCase = true)) {
            isScanningQr = false
            statusMessage = "Scan an LND Connect QR code."
            return
        }

        lndConnectString = normalized
        isScanningQr = false

        if (walletName.trim().isEmpty()) {
            statusMessage = "Name this wallet, then connect."
        } else {
            connectNode(normalized)
        }
    }

    LaunchedEffect(showsSavedWallets) {
        if (showsSavedWallets && rootViewModel.hasStoredLndNode()) {
            runCatching {
                rootViewModel.restoreLndNode()
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to restore this node."
            }
        }
    }

    if (isScanningQr) {
        LightningNodeScannerScreen(
            onClose = { isScanningQr = false },
            onCodeScanned = ::handleScannedCode
        )
        return
    }

    renamingNode?.let { node ->
        AlertDialog(
            onDismissRequest = {
                renamingNode = null
                renameText = ""
            },
            title = { Text("Rename Wallet") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Wallet name") }
                )
            },
            confirmButton = {
                Button(onClick = { renameNode(node) }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renamingNode = null
                        renameText = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SubscreenHeader(
            title = "Lightning Node",
            subtitle = if (showsSavedWallets && hasStoredNode) {
                "Manage your node connections."
            } else {
                "Connect your LND node to Split."
            },
            onBack = onBack
        )

        if (showsSavedWallets && hasStoredNode) {
            LightningSavedNodesSection(
                nodes = storedNodes,
                activeNode = activeNode,
                state = lndState,
                channelBalanceSats = balanceSummary?.spendableSats,
                onChainBalanceSats = balanceSummary?.onChainBalanceSats,
                onSelectNode = ::selectNode,
                onRenameNode = { node ->
                    renameText = node.displayName
                    renamingNode = node
                },
                onDeleteNode = { node ->
                    rootViewModel.forgetLndNode(node.id)
                    statusMessage = null
                }
            )
        }

        connectedNodeConfirmation?.takeIf { !showsSavedWallets }?.let { node ->
            ExternalWalletConnectedCard(
                title = "LND node connected",
                name = node.displayName,
                rows = listOf(
                    "Node Pubkey" to abbreviatedLightningNodeValue(node.nodePubkey),
                    "Host" to "${node.host}:${node.port}",
                    "Connection" to node.connectionMethodLabel()
                ),
                onDone = { onWalletAdded?.invoke() },
                onConnectAnother = {
                    connectedNodeConfirmation = null
                    statusMessage = null
                }
            )
        } ?: run {
            LightningNodeCompatibilityCard()
            LightningNodeConnectCard(
                walletName = walletName,
                onWalletNameChange = {
                    walletName = it
                    statusMessage = null
                },
                lndConnectString = lndConnectString,
                onLndConnectStringChange = {
                    lndConnectString = it
                    statusMessage = null
                },
                isConnecting = isConnecting,
                connectingText = if (lndConnectString.contains(".onion", ignoreCase = true)) "Starting Tor" else "Connecting",
                canConnect = canConnect,
                onScanQr = {
                    statusMessage = null
                    isScanningQr = true
                },
                onConnect = {
                    connectNode(lndConnectString)
                }
            )
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (statusMessage == "Connected" ||
                    statusMessage == "Connection refreshed" ||
                    statusMessage == "Connecting..." ||
                    statusMessage == "Starting Tor..." ||
                    statusMessage?.startsWith("Checking ") == true
                ) {
                    SplitBrandBlue
                } else {
                    SplitBrandPink
                }
            )
        }

        if (showsSavedWallets && hasStoredNode) {
            OutlinedButton(
                onClick = ::refreshConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Refresh Connection")
                }
            }
        }
    }
}

@Composable
private fun LightningSavedNodesSection(
    nodes: List<LndNodeCredentials>,
    activeNode: LndNodeCredentials?,
    state: LndConnectionState,
    channelBalanceSats: Long?,
    onChainBalanceSats: Long?,
    onSelectNode: (LndNodeCredentials) -> Unit,
    onRenameNode: (LndNodeCredentials) -> Unit,
    onDeleteNode: (LndNodeCredentials) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Saved Nodes",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        nodes.forEach { node ->
            val isActive = activeNode?.id == node.id
            LightningNodeRowCard(
                node = node,
                isActive = isActive,
                state = state,
                channelBalanceSats = if (isActive) channelBalanceSats else null,
                onChainBalanceSats = if (isActive) onChainBalanceSats else null,
                onSelectNode = { onSelectNode(node) },
                onRenameNode = { onRenameNode(node) },
                onDeleteNode = { onDeleteNode(node) }
            )
        }
    }
}

@Composable
private fun LightningNodeCompatibilityCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = SplitBrandBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Before You Connect",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Text(
                text = "Split currently supports LND nodes using an LND Connect QR or lndconnect:// string.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.82f)
            )

            LightningNodeCompatibilityRow(
                icon = Icons.Rounded.Bolt,
                text = "Use a private LAN or VPN hostname/IP, a .local hostname, a Tailscale .ts.net host, or a Tor .onion service."
            )
            LightningNodeCompatibilityRow(
                icon = Icons.Rounded.Lock,
                text = "Clearnet nodes must resolve privately. Tor .onion nodes are routed through Tor."
            )
            LightningNodeCompatibilityRow(
                icon = Icons.Rounded.Info,
                text = "Core Lightning has its own connection option."
            )
        }
    }
}

@Composable
private fun LightningNodeCompatibilityRow(
    icon: ImageVector,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.66f)
        )
    }
}

@Composable
private fun ScanQrConnectionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = SplitBrandPink,
            contentColor = Color.White,
            disabledContainerColor = SplitBrandPink.copy(alpha = 0.36f),
            disabledContentColor = Color.White.copy(alpha = 0.58f)
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.QrCode2,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text("Scan QR Code")
    }
}

@Composable
private fun LightningNodeConnectCard(
    walletName: String,
    onWalletNameChange: (String) -> Unit,
    lndConnectString: String,
    onLndConnectStringChange: (String) -> Unit,
    isConnecting: Boolean,
    connectingText: String,
    canConnect: Boolean,
    onScanQr: () -> Unit,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = SplitBrandBlue,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "LND Connect",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Scan your node QR or paste the lndconnect string.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
            }

            TextField(
                value = walletName,
                onValueChange = onWalletNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Wallet name") },
                enabled = !isConnecting
            )

            ScanQrConnectionButton(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting
            )

            TextField(
                value = lndConnectString,
                onValueChange = onLndConnectStringChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("lndconnect://") },
                minLines = 3,
                maxLines = 5,
                enabled = !isConnecting
            )

            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = canConnect
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(connectingText)
                } else {
                    Text("Connect Node")
                }
            }
        }
    }
}

@Composable
private fun ExternalWalletConnectedCard(
    title: String,
    name: String,
    rows: List<Pair<String, String>>,
    onDone: () -> Unit,
    onConnectAnother: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = SplitBrandPink,
                    modifier = Modifier.size(28.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.64f))
                }
            }

            rows.forEach { (label, value) ->
                LightningNodeDetailRow(label, value)
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            TextButton(onClick = onConnectAnother, modifier = Modifier.fillMaxWidth()) {
                Text("Connect another", color = Color.White.copy(alpha = 0.82f))
            }
        }
    }
}

@Composable
private fun LightningNodeSummaryCard(
    node: LndNodeCredentials?,
    state: LndConnectionState,
    channelBalanceSats: Long?,
    onChainBalanceSats: Long?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = SplitBrandBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = node?.displayName ?: "Lightning Node",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = lightningNodeStatusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.60f)
                    )
                }
            }

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            LightningNodeDetailRow("Node Pubkey", abbreviatedLightningNodeValue(node?.nodePubkey))
            LightningNodeDetailRow(
                "Host",
                node?.let { "${it.host}:${it.port}" } ?: "Not available"
            )
            LightningNodeDetailRow("Connection", node?.connectionMethodLabel() ?: "Not available")
            if (channelBalanceSats != null) {
                LightningNodeDetailRow("Channel Balance", lightningSatsText(channelBalanceSats))
            }
            if (onChainBalanceSats != null) {
                LightningNodeDetailRow("On-chain Balance", lightningSatsText(onChainBalanceSats))
            }
        }
    }
}

@Composable
private fun LightningNodeRowCard(
    node: LndNodeCredentials,
    isActive: Boolean,
    state: LndConnectionState,
    channelBalanceSats: Long?,
    onChainBalanceSats: Long?,
    onSelectNode: () -> Unit,
    onRenameNode: () -> Unit,
    onDeleteNode: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = SplitBrandBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isActive) lightningNodeStatusText(state) else "Saved on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.60f)
                    )
                }

                if (isActive) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = SplitBrandPink,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            LightningNodeDetailRow("Node Pubkey", abbreviatedLightningNodeValue(node.nodePubkey))
            LightningNodeDetailRow("Host", "${node.host}:${node.port}")
            LightningNodeDetailRow("Connection", node.connectionMethodLabel())
            if (channelBalanceSats != null) {
                LightningNodeDetailRow("Channel Balance", lightningSatsText(channelBalanceSats))
            }
            if (onChainBalanceSats != null) {
                LightningNodeDetailRow("On-chain Balance", lightningSatsText(onChainBalanceSats))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSelectNode,
                    modifier = Modifier.weight(1f),
                    enabled = !isActive
                ) {
                    Text(if (isActive) "Selected" else "Use")
                }

                OutlinedButton(
                    onClick = onRenameNode,
                    modifier = Modifier.size(width = 52.dp, height = 42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Rename",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Button(
                    onClick = onDeleteNode,
                    modifier = Modifier.size(width = 52.dp, height = 42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Forget",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LightningNodeDetailRow(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(0.46f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.58f)
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.54f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LightningNodeScannerScreen(
    onClose: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    SplitFullScreenQrScanner(
        onClose = onClose,
        onCodeScanned = onCodeScanned
    )
}

@Composable
private fun NwcScannerScreen(
    onClose: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    SplitFullScreenQrScanner(
        onClose = onClose,
        onCodeScanned = onCodeScanned
    )
}

@Composable
private fun CoreLightningScannerScreen(
    onClose: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    SplitFullScreenQrScanner(
        onClose = onClose,
        onCodeScanned = onCodeScanned
    )
}

@Composable
private fun EclairScannerScreen(
    onClose: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    SplitFullScreenQrScanner(
        onClose = onClose,
        onCodeScanned = onCodeScanned
    )
}

@Composable
private fun CoreLightningSettingsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    onWalletAdded: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var walletName by rememberSaveable { mutableStateOf("") }
    var connectionString by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isScanningQr by remember { mutableStateOf(false) }
    var connectedNodeConfirmation by remember { mutableStateOf<CoreLightningNodeCredentials?>(null) }
    val canConnect = !isConnecting &&
        walletName.trim().isNotEmpty() &&
        connectionString.trim().isNotEmpty()

    fun connectNode(rawValue: String = connectionString) {
        Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning connect requested from settings screen")
        val normalizedName = walletName.trim()
        val normalizedConnection = rawValue.trim()
        when {
            normalizedName.isEmpty() -> {
                Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning connect blocked: missing wallet name")
                statusMessage = "Enter a wallet name."
                return
            }
            normalizedConnection.isEmpty() -> {
                Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning connect blocked: missing connection string")
                statusMessage = "Enter a Core Lightning REST connection string."
                return
            }
        }

        isConnecting = true
        statusMessage = if (normalizedConnection.contains(".onion", ignoreCase = true)) {
            "Starting Tor..."
        } else {
            "Checking Core Lightning node..."
        }
        scope.launch {
            Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning connect coroutine started")
            runCatching {
                rootViewModel.connectCoreLightningNode(normalizedConnection, normalizedName)
            }.onSuccess { node ->
                Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning connect succeeded")
                walletName = ""
                connectionString = ""
                statusMessage = null
                connectedNodeConfirmation = node
            }.onFailure { error ->
                Log.w(
                    CORE_LIGHTNING_QR_LOG_TAG,
                    "Core Lightning connect failed: ${error.javaClass.simpleName}"
                )
                statusMessage = error.message ?: "Unable to connect this Core Lightning node."
            }
            isConnecting = false
        }
    }

    fun handleScannedCode(code: String) {
        Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning scan callback received QR data")
        val normalized = code.trim()
        connectionString = normalized
        isScanningQr = false

        if (walletName.trim().isEmpty()) {
            Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning scan stored connection string; waiting for wallet name")
            statusMessage = "Name this wallet, then connect."
        } else {
            Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning scan triggering connect")
            connectNode(normalized)
        }
    }

    if (isScanningQr) {
        CoreLightningScannerScreen(
            onClose = {
                Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning scanner closed by user")
                isScanningQr = false
            },
            onCodeScanned = ::handleScannedCode
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SubscreenHeader(
            title = "Core Lightning Node",
            subtitle = "Connect a Core Lightning node using REST.",
            onBack = onBack
        )

        val confirmedNode = connectedNodeConfirmation
        if (confirmedNode != null) {
            ExternalWalletConnectedCard(
                title = "Core Lightning node connected",
                name = confirmedNode.displayName,
                rows = listOf(
                    "Node ID" to abbreviatedLightningNodeValue(confirmedNode.nodeId),
                    "Host" to "${confirmedNode.host}:${confirmedNode.port}",
                    "Connection" to confirmedNode.connectionMethodLabel()
                ),
                onDone = { onWalletAdded?.invoke() },
                onConnectAnother = {
                    connectedNodeConfirmation = null
                    statusMessage = null
                }
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF151519),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = SplitBrandBlue, modifier = Modifier.size(20.dp))
                        Text("Before You Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text(
                        text = "Use a private LAN, .local, Tailscale, or Tor .onion Core Lightning REST connection. Public clearnet hosts are blocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF151519),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Core Lightning REST", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    TextField(
                        value = walletName,
                        onValueChange = {
                            walletName = it
                            statusMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Wallet name") },
                        enabled = !isConnecting
                    )
                    ScanQrConnectionButton(
                        onClick = {
                            Log.w(CORE_LIGHTNING_QR_LOG_TAG, "Core Lightning scanner opened")
                            statusMessage = null
                            isScanningQr = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isConnecting
                    )
                    TextField(
                        value = connectionString,
                        onValueChange = {
                            connectionString = it
                            statusMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("clnrest:// or JSON") },
                        minLines = 3,
                        maxLines = 5,
                        enabled = !isConnecting
                    )
                    Button(onClick = { connectNode() }, modifier = Modifier.fillMaxWidth(), enabled = canConnect) {
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(if (connectionString.contains(".onion", ignoreCase = true)) "Starting Tor" else "Connecting")
                        } else {
                            Text("Connect Node")
                        }
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (statusMessage == "Connected" ||
                        statusMessage == "Connecting..." ||
                        statusMessage == "Starting Tor..." ||
                        statusMessage?.startsWith("Checking ") == true
                    ) SplitBrandBlue else SplitBrandPink
                )
            }
        }
    }
}

@Composable
private fun EclairSettingsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    onWalletAdded: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val eclairState by rootViewModel.eclairConnectionState.collectAsStateWithLifecycle()
    var walletName by rememberSaveable { mutableStateOf("") }
    var scheme by rememberSaveable { mutableStateOf("http") }
    var host by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable { mutableStateOf("8080") }
    var apiPassword by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isScanningQr by remember { mutableStateOf(false) }
    var connectedNodeConfirmation by remember { mutableStateOf<EclairNodeCredentials?>(null) }
    val canConnect = !isConnecting &&
        walletName.trim().isNotEmpty() &&
        host.trim().isNotEmpty() &&
        apiPassword.trim().isNotEmpty() &&
        portText.trim().toIntOrNull() != null

    fun connectNode() {
        val normalizedName = walletName.trim()
        val normalizedHost = host.trim()
        val normalizedPort = portText.trim().toIntOrNull()
        val normalizedPassword = apiPassword.trim()
        when {
            normalizedName.isEmpty() -> {
                statusMessage = "Enter a wallet name."
                return
            }
            normalizedHost.isEmpty() -> {
                statusMessage = "Enter an Eclair host."
                return
            }
            normalizedPort == null -> {
                statusMessage = "Enter a valid port."
                return
            }
            normalizedPassword.isEmpty() -> {
                statusMessage = "Enter the Eclair API password."
                return
            }
        }

        isConnecting = true
        statusMessage = if (normalizedHost.contains(".onion", ignoreCase = true)) {
            "Starting Tor..."
        } else {
            "Checking Eclair node..."
        }
        scope.launch {
            runCatching {
                rootViewModel.connectEclairNode(
                    scheme = scheme,
                    host = normalizedHost,
                    port = normalizedPort,
                    apiPassword = normalizedPassword,
                    label = normalizedName
                )
            }.onSuccess { node ->
                walletName = ""
                host = ""
                portText = "8080"
                apiPassword = ""
                statusMessage = null
                connectedNodeConfirmation = node
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to connect this Eclair node."
            }
            isConnecting = false
        }
    }

    fun applyScannedCredentials(credentials: EclairNodeCredentials, fallbackLabel: String? = null) {
        scheme = credentials.scheme
        host = credentials.host
        portText = credentials.port.toString()
        apiPassword = credentials.apiPassword
        val suggestedLabel = fallbackLabel?.trim()?.ifBlank { null } ?: credentials.label
        if (walletName.trim().isEmpty() && !suggestedLabel.isNullOrBlank()) {
            walletName = suggestedLabel
        }
    }

    fun connectScannedNode(rawValue: String, label: String) {
        isConnecting = true
        statusMessage = if (rawValue.contains(".onion", ignoreCase = true)) {
            "Starting Tor..."
        } else {
            "Checking Eclair node..."
        }
        scope.launch {
            runCatching {
                rootViewModel.connectEclairNode(rawValue, label)
            }.onSuccess { node ->
                walletName = ""
                host = ""
                portText = "8080"
                apiPassword = ""
                statusMessage = null
                connectedNodeConfirmation = node
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to connect this Eclair node."
            }
            isConnecting = false
        }
    }

    fun handleScannedCode(code: String) {
        val normalized = code.trim()
        val parsed = runCatching {
            EclairConnectParser.parse(normalized)
        }.getOrElse {
            isScanningQr = false
            statusMessage = "Scan an Eclair connection QR code."
            return
        }

        isScanningQr = false
        val label = walletName.trim().ifBlank {
            parsed.label?.trim().orEmpty()
        }
        applyScannedCredentials(parsed)

        if (label.isBlank()) {
            statusMessage = "Name this wallet, then connect."
            return
        }

        walletName = label
        connectScannedNode(normalized, label)
    }

    if (isScanningQr) {
        EclairScannerScreen(
            onClose = { isScanningQr = false },
            onCodeScanned = ::handleScannedCode
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SubscreenHeader(
            title = "Eclair Node",
            subtitle = "Connect an Eclair node using its API.",
            onBack = onBack
        )

        connectedNodeConfirmation?.let { node ->
            ExternalWalletConnectedCard(
                title = "Eclair node connected",
                name = node.displayName,
                rows = listOf(
                    "Node ID" to abbreviatedLightningNodeValue(node.nodeId),
                    "Host" to "${node.scheme}://${node.host}:${node.port}",
                    "Connection" to if (node.usesTor) "Tor" else "Direct"
                ),
                onDone = { onWalletAdded?.invoke() },
                onConnectAnother = {
                    connectedNodeConfirmation = null
                    statusMessage = null
                }
            )
        } ?: Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF151519),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = SplitBrandBlue, modifier = Modifier.size(20.dp))
                    Text("Before You Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(
                    text = "Use a private LAN, .local, Tailscale, or Tor .onion Eclair API connection. Public clearnet hosts are blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF151519),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Eclair API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                TextField(
                    value = walletName,
                    onValueChange = {
                        walletName = it
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Wallet name") },
                    enabled = !isConnecting
                )
                ScanQrConnectionButton(
                    onClick = {
                        statusMessage = null
                        isScanningQr = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { scheme = "http" },
                        enabled = !isConnecting,
                        border = BorderStroke(1.dp, if (scheme == "http") SplitBrandBlue else Color.White.copy(alpha = 0.24f))
                    ) {
                        Text("HTTP")
                    }
                    OutlinedButton(
                        onClick = { scheme = "https" },
                        enabled = !isConnecting,
                        border = BorderStroke(1.dp, if (scheme == "https") SplitBrandBlue else Color.White.copy(alpha = 0.24f))
                    ) {
                        Text("HTTPS")
                    }
                }
                TextField(
                    value = host,
                    onValueChange = {
                        host = it
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Host or .onion address") },
                    enabled = !isConnecting
                )
                TextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter(Char::isDigit).take(5)
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Port") },
                    enabled = !isConnecting
                )
                TextField(
                    value = apiPassword,
                    onValueChange = {
                        apiPassword = it
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API password") },
                    enabled = !isConnecting
                )
                Button(onClick = ::connectNode, modifier = Modifier.fillMaxWidth(), enabled = canConnect) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(if (host.contains(".onion", ignoreCase = true)) "Starting Tor" else "Connecting")
                    } else {
                        Text("Connect Node")
                    }
                }
            }
        }

        if (connectedNodeConfirmation == null) {
            Text(
                text = statusMessage ?: eclairStatusText(eclairState),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (statusMessage == null ||
                    statusMessage == "Connected" ||
                    statusMessage == "Connecting..." ||
                    statusMessage == "Starting Tor..." ||
                    statusMessage?.startsWith("Checking ") == true
                ) SplitBrandBlue else SplitBrandPink
            )
        }
    }
}

@Composable
private fun SparkSubwalletSetupScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    onWalletAdded: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pendingSeedWords by rootViewModel.sparkSubwalletPendingSeedWords.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(SparkSubwalletSetupMode.OVERVIEW) }
    var walletName by rememberSaveable { mutableStateOf("") }
    var restorePhrase by rememberSaveable { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSeedPhrase by remember { mutableStateOf(false) }

    fun canSubmitName(): Boolean = walletName.trim().isNotEmpty()

    if (showSeedPhrase) {
        SparkSubwalletSeedBackupScreen(
            words = pendingSeedWords,
            onConfirm = {
                if (!canSubmitName()) {
                    errorMessage = "Enter a wallet name."
                    return@SparkSubwalletSeedBackupScreen
                }
                isWorking = true
                scope.launch {
                    runCatching {
                        rootViewModel.createSparkSubwallet(walletName.trim())
                    }.onSuccess {
                        isWorking = false
                        showSeedPhrase = false
                        onWalletAdded()
                    }.onFailure { error ->
                        isWorking = false
                        errorMessage = error.message ?: "Unable to create this Spark wallet."
                        showSeedPhrase = false
                    }
                }
            },
            onCancel = {
                rootViewModel.cancelPendingSparkSubwalletSeed()
                showSeedPhrase = false
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SubscreenHeader(
            title = when (mode) {
                SparkSubwalletSetupMode.OVERVIEW -> "Spark Wallet"
                SparkSubwalletSetupMode.CREATE -> "Name Wallet"
                SparkSubwalletSetupMode.RESTORE -> "Restore Wallet"
            },
            subtitle = when (mode) {
                SparkSubwalletSetupMode.OVERVIEW -> "Add a Spark wallet as a subwallet under this Split account."
                SparkSubwalletSetupMode.CREATE -> "Choose a name before Split creates a new Spark subwallet."
                SparkSubwalletSetupMode.RESTORE -> "Enter a name and the recovery phrase for the Spark wallet."
            },
            onBack = {
                if (mode == SparkSubwalletSetupMode.OVERVIEW) {
                    onBack()
                } else {
                    mode = SparkSubwalletSetupMode.OVERVIEW
                    errorMessage = null
                }
            }
        )

        when (mode) {
            SparkSubwalletSetupMode.OVERVIEW -> {
                InfoCard(
                    title = "Important",
                    paragraphs = listOf(
                        "You can add an existing Spark wallet or create a new one. Your original Spark wallet remains the root wallet for this Split account.",
                        "A Spark wallet created as a subwallet can later be used as a root wallet through restore when the app starts, linking it with a new user account.",
                        "Spark wallets associated with one root account can also be added as subwallets under another root account. Most Split account data is stored on this device."
                    )
                )
                Button(
                    onClick = {
                        mode = SparkSubwalletSetupMode.CREATE
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create New Spark Wallet")
                }
                OutlinedButton(
                    onClick = {
                        mode = SparkSubwalletSetupMode.RESTORE
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore Existing Spark Wallet")
                }
            }

            SparkSubwalletSetupMode.CREATE -> {
                SparkSubwalletNameField(
                    walletName = walletName,
                    onWalletNameChange = {
                        walletName = it
                        errorMessage = null
                    },
                    enabled = !isWorking
                )
                Button(
                    onClick = {
                        if (!canSubmitName()) {
                            errorMessage = "Enter a wallet name."
                            return@Button
                        }
                        rootViewModel.createPendingSparkSubwalletSeed()
                        showSeedPhrase = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSubmitName() && !isWorking
                ) {
                    Text("Continue")
                }
            }

            SparkSubwalletSetupMode.RESTORE -> {
                SparkSubwalletNameField(
                    walletName = walletName,
                    onWalletNameChange = {
                        walletName = it
                        errorMessage = null
                    },
                    enabled = !isWorking
                )
                TextField(
                    value = restorePhrase,
                    onValueChange = {
                        restorePhrase = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Recovery phrase") },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !isWorking
                )
                Button(
                    onClick = {
                        if (!canSubmitName()) {
                            errorMessage = "Enter a wallet name."
                            return@Button
                        }
                        isWorking = true
                        scope.launch {
                            runCatching {
                                rootViewModel.restoreSparkSubwallet(
                                    seedPhrase = restorePhrase,
                                    label = walletName.trim()
                                )
                            }.onSuccess {
                                isWorking = false
                                onWalletAdded()
                            }.onFailure { error ->
                                isWorking = false
                                errorMessage = error.message ?: "Unable to restore this Spark wallet."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSubmitName() && restorePhrase.trim().isNotEmpty() && !isWorking
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Restoring")
                    } else {
                        Text("Restore Spark Wallet")
                    }
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage ?: "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = SplitBrandPink
            )
        }

        if (isWorking && mode != SparkSubwalletSetupMode.RESTORE) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun SparkSubwalletNameField(
    walletName: String,
    onWalletNameChange: (String) -> Unit,
    enabled: Boolean
) {
    TextField(
        value = walletName,
        onValueChange = onWalletNameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Wallet name") },
        enabled = enabled
    )
}

@Composable
private fun SparkSubwalletSeedBackupScreen(
    words: List<String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var hasAcknowledged by rememberSaveable { mutableStateOf(false) }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        showDiscardConfirmation = true
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard this subwallet?") },
            text = { Text("This subwallet has not been saved yet. If you cancel, this recovery phrase and setup will be discarded.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onCancel()
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("Keep Viewing")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Subwallet Recovery Phrase",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { showDiscardConfirmation = true }) {
                Text("Cancel", color = Color.White)
            }
        }

        Text(
            text = "These 12 words back up this Spark subwallet only. Your root Split wallet is separate.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = SplitBrandPink.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, SplitBrandPink.copy(alpha = 0.40f))
        ) {
            Text(
                text = "This is the only time Split will show this subwallet recovery phrase. Save it before continuing.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF151519),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                words.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
                        Text(word, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { hasAcknowledged = !hasAcknowledged },
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = 0.06f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = hasAcknowledged,
                    onCheckedChange = { hasAcknowledged = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SplitBrandPink,
                        uncheckedColor = Color.White.copy(alpha = 0.70f),
                        checkmarkColor = Color.White
                    )
                )
                Text(
                    text = "I understand this subwallet needs this recovery phrase to restore funds.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = hasAcknowledged
        ) {
            Text("I saved this phrase")
        }
    }
}

@Composable
private fun NwcWalletSettingsScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    showsSavedWallets: Boolean = true,
    onWalletAdded: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val nwcState by rootViewModel.nwcConnectionState.collectAsStateWithLifecycle()
    val connectedWallet by rootViewModel.connectedNwcWallet.collectAsStateWithLifecycle()
    val balanceSummary by rootViewModel.nwcBalanceSummary.collectAsStateWithLifecycle()
    val storedWalletsVersion by rootViewModel.storedNwcWalletsVersion.collectAsStateWithLifecycle()

    var connectionString by rememberSaveable { mutableStateOf("") }
    var walletName by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isScanningQr by remember { mutableStateOf(false) }
    var renamingWallet by remember { mutableStateOf<NwcWalletCredentials?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var connectedWalletConfirmation by remember { mutableStateOf<NwcWalletCredentials?>(null) }

    val activeWallet = connectedWallet ?: rootViewModel.activeNwcWalletCredentials()
    val storedWallets = remember(storedWalletsVersion, connectedWallet) {
        rootViewModel.storedNwcWallets()
    }
    val hasStoredWallet = storedWallets.isNotEmpty() || activeWallet != null || rootViewModel.hasStoredNwcWallet()
    val canConnect = !isConnecting &&
        connectionString.trim().isNotEmpty() &&
        walletName.trim().isNotEmpty()

    fun connectWallet(rawValue: String = connectionString) {
        val normalizedName = walletName.trim()
        if (normalizedName.isEmpty()) {
            statusMessage = "Enter a wallet name."
            return
        }
        val normalized = rawValue.trim()
        if (normalized.isEmpty()) {
            statusMessage = "Enter a NWC connection string."
            return
        }
        isConnecting = true
        statusMessage = if (normalized.contains(".onion", ignoreCase = true)) {
            "Starting Tor..."
        } else {
            "Checking NWC wallet..."
        }
        scope.launch {
            runCatching {
                rootViewModel.connectNwcWallet(normalized, normalizedName)
            }.onSuccess { wallet ->
                connectionString = ""
                walletName = ""
                statusMessage = null
                connectedWalletConfirmation = wallet
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to connect this NWC wallet."
            }
            isConnecting = false
        }
    }

    fun handleScannedCode(code: String) {
        val normalized = code.trim()

        if (!normalized.startsWith("nostr+walletconnect://", ignoreCase = true)) {
            isScanningQr = false
            statusMessage = "Scan an NWC QR code from a Lightning wallet or node."
            return
        }

        connectionString = normalized
        isScanningQr = false

        if (walletName.trim().isEmpty()) {
            statusMessage = "Name this wallet, then connect."
        } else {
            connectWallet(normalized)
        }
    }

    fun refreshConnection() {
        if (isRefreshing) return
        isRefreshing = true
        statusMessage = null
        scope.launch {
            runCatching {
                rootViewModel.refreshNwcConnection()
            }.onSuccess {
                statusMessage = "Connection refreshed"
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to refresh this wallet."
            }
            isRefreshing = false
        }
    }

    fun selectWallet(wallet: NwcWalletCredentials) {
        if (rootViewModel.setNwcSpendWallet(wallet.id)) {
            statusMessage = "Selected ${wallet.displayName}"
        } else {
            statusMessage = "Unable to select this wallet."
        }
    }

    fun renameWallet(wallet: NwcWalletCredentials) {
        val normalized = renameText.trim()
        if (normalized.isBlank()) {
            statusMessage = "Enter a wallet name."
            return
        }
        rootViewModel.renameNwcWallet(wallet.id, normalized)
        renamingWallet = null
        renameText = ""
        statusMessage = "Renamed wallet"
    }

    LaunchedEffect(showsSavedWallets) {
        if (showsSavedWallets && rootViewModel.hasStoredNwcWallet()) {
            runCatching {
                rootViewModel.restoreNwcWallet()
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to restore this wallet."
            }
        }
    }

    if (isScanningQr) {
        NwcScannerScreen(
            onClose = { isScanningQr = false },
            onCodeScanned = ::handleScannedCode
        )
        return
    }

    renamingWallet?.let { wallet ->
        AlertDialog(
            onDismissRequest = {
                renamingWallet = null
                renameText = ""
            },
            title = { Text("Rename Wallet") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Wallet name") }
                )
            },
            confirmButton = {
                Button(onClick = { renameWallet(wallet) }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    renamingWallet = null
                    renameText = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SubscreenHeader(
            title = "NWC Wallet or Node",
            subtitle = if (showsSavedWallets && hasStoredWallet) {
                "Manage your NWC connections."
            } else {
                "Connect an NWC-compatible Lightning wallet or node."
            },
            onBack = onBack
        )

        if (showsSavedWallets && hasStoredWallet) {
            NwcSavedWalletsSection(
                wallets = storedWallets,
                activeWallet = activeWallet,
                state = nwcState,
                balanceSats = balanceSummary?.spendableSats,
                onSelectWallet = ::selectWallet,
                onRenameWallet = { wallet ->
                    renameText = wallet.displayName
                    renamingWallet = wallet
                },
                onDeleteWallet = { wallet ->
                    rootViewModel.forgetNwcWallet(wallet.id)
                    statusMessage = null
                }
            )
        }

        connectedWalletConfirmation?.takeIf { !showsSavedWallets }?.let { wallet ->
            ExternalWalletConnectedCard(
                title = "NWC wallet connected",
                name = wallet.displayName,
                rows = listOf(
                    "Wallet Pubkey" to abbreviatedLightningNodeValue(wallet.walletPubkey),
                    "Relay" to wallet.primaryRelayHost,
                    "Connection" to wallet.connectionMethodLabel()
                ),
                onDone = { onWalletAdded?.invoke() },
                onConnectAnother = {
                    connectedWalletConfirmation = null
                    statusMessage = null
                }
            )
        } ?: run {
            NwcCompatibilityCard()
            NwcConnectCard(
                walletName = walletName,
                onWalletNameChange = {
                    walletName = it
                    statusMessage = null
                },
                connectionString = connectionString,
                onConnectionStringChange = {
                    connectionString = it
                    statusMessage = null
                },
                isConnecting = isConnecting,
                connectingText = if (connectionString.contains(".onion", ignoreCase = true)) "Starting Tor" else "Connecting",
                canConnect = canConnect,
                onScanQr = {
                    statusMessage = null
                    isScanningQr = true
                },
                onConnect = { connectWallet() }
            )
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (statusMessage == "Connected" ||
                    statusMessage == "Connection refreshed" ||
                    statusMessage == "Connecting..." ||
                    statusMessage == "Starting Tor..." ||
                    statusMessage?.startsWith("Checking ") == true
                ) SplitBrandBlue else SplitBrandPink
            )
        }

        if (showsSavedWallets && hasStoredWallet) {
            OutlinedButton(
                onClick = ::refreshConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Refresh Connection")
                }
            }
        }
    }
}

@Composable
private fun NwcSavedWalletsSection(
    wallets: List<NwcWalletCredentials>,
    activeWallet: NwcWalletCredentials?,
    state: NwcConnectionState,
    balanceSats: Long?,
    onSelectWallet: (NwcWalletCredentials) -> Unit,
    onRenameWallet: (NwcWalletCredentials) -> Unit,
    onDeleteWallet: (NwcWalletCredentials) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Saved Wallets",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        wallets.forEach { wallet ->
            val isActive = activeWallet?.id == wallet.id
            NwcWalletRowCard(
                wallet = wallet,
                isActive = isActive,
                state = state,
                balanceSats = if (isActive) balanceSats else null,
                onSelectWallet = { onSelectWallet(wallet) },
                onRenameWallet = { onRenameWallet(wallet) },
                onDeleteWallet = { onDeleteWallet(wallet) }
            )
        }
    }
}

@Composable
private fun NwcCompatibilityCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = SplitBrandBlue, modifier = Modifier.size(20.dp))
                Text("Before You Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                text = "Split supports NWC connection strings from wallets or nodes that can pay invoices, create invoices, show balance, look up invoices, and list transactions.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.82f)
            )
            LightningNodeCompatibilityRow(Icons.Rounded.Lock, "NWC secrets stay encrypted on this device.")
            LightningNodeCompatibilityRow(Icons.Rounded.Info, "Production connections require secure wss:// relays, or ws:// for Tor .onion relays.")
        }
    }
}

@Composable
private fun NwcConnectCard(
    walletName: String,
    onWalletNameChange: (String) -> Unit,
    connectionString: String,
    onConnectionStringChange: (String) -> Unit,
    isConnecting: Boolean,
    connectingText: String,
    canConnect: Boolean,
    onScanQr: () -> Unit,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Nostr Wallet Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            TextField(
                value = walletName,
                onValueChange = onWalletNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Wallet name") },
                enabled = !isConnecting
            )
            ScanQrConnectionButton(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting
            )
            TextField(
                value = connectionString,
                onValueChange = onConnectionStringChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("nostr+walletconnect://") },
                minLines = 3,
                maxLines = 5,
                enabled = !isConnecting
            )
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), enabled = canConnect) {
                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(connectingText)
                } else {
                    Text("Connect Wallet")
                }
            }
        }
    }
}

@Composable
private fun NwcWalletRowCard(
    wallet: NwcWalletCredentials,
    isActive: Boolean,
    state: NwcConnectionState,
    balanceSats: Long?,
    onSelectWallet: () -> Unit,
    onRenameWallet: () -> Unit,
    onDeleteWallet: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151519),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    NwcSymbolIcon(modifier = Modifier.size(32.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(wallet.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (isActive) nwcStatusText(state) else "Saved on this device", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.60f))
                }
                if (isActive) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = SplitBrandPink, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            LightningNodeDetailRow("Wallet Pubkey", abbreviatedLightningNodeValue(wallet.walletPubkey))
            LightningNodeDetailRow("Relay", wallet.primaryRelayHost)
            LightningNodeDetailRow("Connection", wallet.connectionMethodLabel())
            if (balanceSats != null) {
                LightningNodeDetailRow("Balance", lightningSatsText(balanceSats))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSelectWallet, modifier = Modifier.weight(1f), enabled = !isActive) {
                    Text(if (isActive) "Selected" else "Use")
                }
                OutlinedButton(onClick = onRenameWallet, modifier = Modifier.size(width = 52.dp, height = 42.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                }
                Button(onClick = onDeleteWallet, modifier = Modifier.size(width = 52.dp, height = 42.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Forget", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun nwcStatusText(state: NwcConnectionState): String {
    return when (state) {
        NwcConnectionState.Ready -> "Connected"
        NwcConnectionState.Connecting -> "Connecting"
        is NwcConnectionState.Error -> "Needs attention"
        NwcConnectionState.Disconnected -> "Saved on this device"
    }
}

private fun eclairStatusText(state: EclairConnectionState): String {
    return when (state) {
        EclairConnectionState.Ready -> "Connected"
        EclairConnectionState.Connecting -> "Connecting"
        EclairConnectionState.Disconnected -> "Saved on this device"
        is EclairConnectionState.Error -> "Needs attention"
    }
}

private fun lightningNodeStatusText(state: LndConnectionState): String {
    return when (state) {
        LndConnectionState.Ready -> "Connected"
        LndConnectionState.Connecting -> "Connecting"
        LndConnectionState.Disconnected -> "Saved on this device"
        is LndConnectionState.Error -> "Needs attention"
    }
}

private fun LndNodeCredentials.connectionMethodLabel(): String {
    return if (usesTor) "Tor" else "Direct"
}

private fun CoreLightningNodeCredentials.connectionMethodLabel(): String {
    return if (usesTor) "Tor" else "Direct"
}

private fun NwcWalletCredentials.connectionMethodLabel(): String {
    return if (usesTor) "Tor" else "Direct"
}

private fun EclairNodeCredentials.connectionMethodLabel(): String {
    return if (usesTor) "Tor" else "Direct"
}

private fun abbreviatedLightningNodeValue(value: String?): String {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return "Not available"
    if (normalized.length <= 14) return normalized
    return "${normalized.take(8)}...${normalized.takeLast(6)}"
}

private fun lightningSatsText(sats: Long): String {
    return String.format(Locale.US, "%,d sats", sats)
}

data class SelectedProfilePhoto(
    val data: ByteArray,
    val fileName: String,
    val mimeType: String
)

@Composable
fun CreateLightningAddressDialog(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit,
    onCreated: (WalletLightningAddressInfo) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var username by rememberSaveable { mutableStateOf("") }
    var checkedUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var isUsernameAvailable by rememberSaveable { mutableStateOf(false) }
    var isCheckingAvailability by remember { mutableStateOf(false) }
    var isCreatingAddress by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val normalizedPreview = username.trim().lowercase()
    val canCheckAvailability = normalizedPreview.isNotBlank() && !isCheckingAvailability && !isCreatingAddress
    val canCreateAddress = checkedUsername == normalizedPreview &&
        isUsernameAvailable &&
        !isCheckingAvailability &&
        !isCreatingAddress

    Dialog(
        onDismissRequest = {
            if (!isCheckingAvailability && !isCreatingAddress) {
                onDismiss()
            }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF101013),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Create Address",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )

                Text(
                    text = "Choose a username for your Lightning Address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.66f)
                )

                TextField(
                    value = username,
                    onValueChange = {
                        username = it
                        checkedUsername = null
                        isUsernameAvailable = false
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Username") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                        }
                    ),
                    supportingText = {
                        Text("Allowed: letters, numbers, periods, underscores, and hyphens.")
                    }
                )

                if (normalizedPreview.isNotBlank()) {
                    Text(
                        text = "$normalizedPreview@${AppConfig.lightningAddressDomain}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = SplitBrandPink
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            coroutineScope.launch {
                                runCatching {
                                    val normalized = rootViewModel.normalizedLightningUsername(username)
                                    isCheckingAvailability = true
                                    val available = rootViewModel.isLightningAddressAvailable(normalized)
                                    checkedUsername = normalized
                                    isUsernameAvailable = available
                                    errorMessage = if (available) null else "That Lightning address is already taken."
                                }.onFailure { error ->
                                    checkedUsername = null
                                    isUsernameAvailable = false
                                    errorMessage = error.message ?: "Failed to check availability."
                                }
                                isCheckingAvailability = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canCheckAvailability
                    ) {
                        if (isCheckingAvailability) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Check Availability")
                        }
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            coroutineScope.launch {
                                isCreatingAddress = true
                                runCatching {
                                    rootViewModel.createLightningAddress(username)
                                }.onSuccess { created ->
                                    onCreated(created)
                                }.onFailure { error ->
                                    errorMessage = error.message ?: "Failed to create Lightning Address."
                                }
                                isCreatingAddress = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canCreateAddress
                    ) {
                        if (isCreatingAddress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Create Lightning Address")
                        }
                    }

                    TextButton(
                        onClick = {
                            focusManager.clearFocus()
                            onDismiss()
                        },
                        enabled = !isCheckingAvailability && !isCreatingAddress,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.82f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RewardsExplainedScreen(
    onBack: () -> Unit
) {
    ScrollSubscreen(
        title = "Rewards Explained",
        subtitle = "How it works.",
        onBack = onBack
    ) {
        InfoCard(
            title = "How Rewards Work",
            paragraphs = RewardsHowItWorksParagraphs
        )
    }
}

@Composable
private fun AddMerchantInfoScreen(
    onBack: () -> Unit
) {
    ScrollSubscreen(
        title = "Add a Merchant",
        subtitle = "You can add any bitcoin-accepting business to our rewards program.",
        onBack = onBack
    ) {
        AddMerchantIntroCard()
        AddMerchantStepsCard()
        InfoCard(
            title = "Good to know",
            paragraphs = listOf(
                "This works for any business that accepts Bitcoin. Once approved, your spend with that merchant will start earning Bitcoin rewards."
            )
        )
    }
}

@Composable
private fun AddMerchantIntroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(SplitBrandBlue, SplitBrandPink)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SplitFeatureIcons.Store,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "See a business missing from rewards?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "You can submit it right from the payment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }
            }

            Text(
                text = "After you pay, open the transaction and tap the merchant button below to send us the business details.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.76f)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tap this icon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SplitFeatureIcons.Store,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddMerchantStepsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AddMerchantStepRow(
                number = "1",
                title = "Make a normal bitcoin payment",
                detail = "Pay the merchant with Split like a normal transaction."
            )
            AddMerchantStepRow(
                number = "2",
                title = "Open the transaction in Split",
                detail = "Find that payment to the merchant in your Transactions list."
            )
            AddMerchantIconStepRow()
            AddMerchantStepRow(
                number = "4",
                title = "Submit the merchant form",
                detail = "Enter the merchant name and address. The merchant does not need to participate. We’ll verify the business and add them as soon as possible."
            )
        }
    }
}

@Composable
private fun AddMerchantStepRow(
    number: String,
    title: String,
    detail: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(SplitBrandBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f)
            )
        }
    }
}

@Composable
private fun AddMerchantIconStepRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(SplitBrandBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "3",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Tap the merchant icon",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Inside the transaction detail, tap the merchant button to open the submission form.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SplitFeatureIcons.Store,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun SupportScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    onOpenSupportChat: (SupportChatRequest) -> Unit
) {
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val existingSupportThread = remember(storedMessages) {
        rootViewModel.conversationPreviews().firstOrNull {
            it.lightningAddress?.trim()?.lowercase() == supportLightningAddress
        }
    }

    ScrollSubscreen(
        title = "Support",
        subtitle = "Questions, feedback, and product ideas.",
        onBack = onBack
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(SplitBrandBlue, SplitBrandPink)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(22.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Support",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Thank you for using Split. Please do not hesitate to reach out at any time with any suggestions, comments, questions, concerns, bugs, feedback, or product ideas.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        InfoCard(
            title = "Lightning Address",
            paragraphs = listOf(supportLightningAddress)
        )

        Button(
            onClick = {
                val existing = existingSupportThread
                if (existing != null) {
                    onOpenSupportChat(
                        SupportChatRequest.OpenThread(
                            conversationId = existing.id,
                            title = "Taylor",
                            lightningAddress = supportLightningAddress
                        )
                    )
                } else {
                    onOpenSupportChat(
                        SupportChatRequest.Compose(supportLightningAddress)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (existingSupportThread == null) "Message Taylor" else "Open Support Chat")
        }
    }
}

@Composable
private fun LegalScreen(
    onBack: () -> Unit
) {
    var activeDocument by remember { mutableStateOf<LegalDocument?>(null) }

    if (activeDocument != null) {
        LegalDocumentScreen(
            document = activeDocument!!,
            onBack = { activeDocument = null }
        )
        return
    }

    ScrollSubscreen(
        title = "Legal",
        subtitle = "Documents and agreements.",
        onBack = onBack
    ) {
        LegalLinkCard(
            title = "User Agreement",
            subtitle = "Review the Split terms and messaging rules.",
            onClick = { activeDocument = LegalDocument.UserAgreement }
        )
        LegalLinkCard(
            title = "Privacy Policy",
            subtitle = "Review how Split handles messaging and wallet data.",
            onClick = { activeDocument = LegalDocument.PrivacyPolicy }
        )
    }
}

private enum class LegalDocument(
    val title: String,
    val subtitle: String,
    val url: String
) {
    UserAgreement(
        title = "User Agreement",
        subtitle = "Review the Split terms and messaging rules.",
        url = "${AppConfig.baseUrl}/User-agreement"
    ),
    PrivacyPolicy(
        title = "Privacy Policy",
        subtitle = "Review how Split handles messaging and wallet data.",
        url = "${AppConfig.baseUrl}/Privacy-policy"
    )
}

@Composable
private fun LegalDocumentScreen(
    document: LegalDocument,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SubscreenHeader(
            title = document.title,
            subtitle = document.subtitle,
            onBack = onBack
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF111114),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(document.url)
                    }
                },
                update = { webView ->
                    if (webView.url != document.url) {
                        webView.loadUrl(document.url)
                    }
                }
            )
        }
    }
}

@Composable
private fun WalletManagementScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    var isRemoving by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var removalError by remember { mutableStateOf<String?>(null) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }
    var isRevealingSeed by remember { mutableStateOf(false) }
    var revealError by remember { mutableStateOf<String?>(null) }
    var revealedSeedWords by remember { mutableStateOf<List<String>>(emptyList()) }

    ScrollSubscreen(
        title = "Account Management",
        subtitle = "Wallet-backed identity.",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Your Split Account",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Your wallet is your Split account. Keep your recovery phrase saved somewhere private and durable. Split cannot recover your account, wallet, or funds if your device is lost and you do not have that phrase.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WalletManagementSectionTitle("Account Security")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF111217),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column {
                    WalletManagementInfoRow(
                        icon = Icons.Rounded.Lock,
                        iconTint = SplitBrandBlue,
                        title = "Self-custodial wallet",
                        subtitle = "The recovery phrase controls access to this Split account."
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.06f))
                    )
                    WalletManagementInfoRow(
                        icon = Icons.Rounded.Info,
                        iconTint = SplitBrandPink,
                        title = "No server recovery",
                        subtitle = "Split does not store your recovery phrase or private keys."
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WalletManagementSectionTitle("Recovery Phrase")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF111217),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Use this only when you need to back up or restore your wallet. Never share these words with anyone, including Split support.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.70f)
                    )

                    Button(
                        onClick = {
                            val activity = context.findActivity()
                            if (activity == null) {
                                revealError = "Biometric authentication is not available on this device."
                                return@Button
                            }

                            scope.launch {
                                isRevealingSeed = true
                                revealError = null

                                runCatching {
                                    val confirmed = confirmRecoveryPhraseRevealWithBiometrics(activity)
                                    if (!confirmed) {
                                        null
                                    } else {
                                        rootViewModel.readSavedRecoveryPhrase()
                                            ?.split(Regex("\\s+"))
                                            ?.map { it.trim() }
                                            ?.filter { it.isNotEmpty() }
                                            .orEmpty()
                                    }
                                }.onSuccess { words ->
                                    if (words == null) {
                                        return@onSuccess
                                    }

                                    if (words.size >= 12) {
                                        revealedSeedWords = words
                                    } else {
                                        revealError = "No recovery phrase is saved on this device."
                                    }
                                }.onFailure { error ->
                                    revealError = error.message
                                        ?: "Could not confirm with device biometrics."
                                }

                                isRevealingSeed = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRevealingSeed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SplitBrandPink,
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isRevealingSeed) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isRevealingSeed) "Checking Identity" else "Reveal Recovery Phrase",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Requires biometric authentication",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.68f)
                                )
                            }

                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.70f)
                            )
                        }
                    }

                    if (!revealError.isNullOrBlank()) {
                        Text(
                            text = revealError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = SplitBrandPink,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WalletManagementSectionTitle("Device Access")
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !isRemoving,
                        onClick = {
                            removalError = null
                            confirmRemove = true
                        }
                    ),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF101013),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.24f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remove Wallet From This Device",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "You will need your recovery phrase to restore access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.48f)
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !isDeletingAccount,
                        onClick = {
                            deleteAccountError = null
                            confirmDeleteAccount = true
                        }
                    ),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF101013),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.30f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.90f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Delete Rewards Account",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "This will delete your rewards account. This data cannot be restored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.48f)
                    )
                }
            }

            if (!removalError.isNullOrBlank()) {
                Text(
                    text = removalError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SplitBrandPink
                )
            }

            if (!deleteAccountError.isNullOrBlank()) {
                Text(
                    text = deleteAccountError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SplitBrandPink
                )
            }
        }
    }

    if (revealedSeedWords.isNotEmpty()) {
        RecoveryPhraseReviewDialog(
            words = revealedSeedWords,
            onDismiss = { revealedSeedWords = emptyList() }
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = {
                if (!isRemoving) {
                    confirmRemove = false
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activity = context.findActivity()
                        if (activity == null) {
                            removalError = "Biometric authentication is not available on this device."
                            confirmRemove = false
                            return@Button
                        }

                        scope.launch {
                            isRemoving = true
                            removalError = null

                            runCatching {
                                confirmWalletRemovalWithBiometrics(activity)
                            }.onSuccess { confirmed ->
                                if (confirmed) {
                                    rootViewModel.clearWallet()
                                }
                                confirmRemove = false
                            }.onFailure { error ->
                                removalError = error.message
                                    ?: "Could not confirm with device biometrics."
                                confirmRemove = false
                            }

                            isRemoving = false
                        }
                    },
                    enabled = !isRemoving
                ) {
                    if (isRemoving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Remove")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmRemove = false },
                    enabled = !isRemoving
                ) {
                    Text("Cancel")
                }
            },
            title = { Text("Remove Wallet?") },
            text = {
                Text(
                    "This will remove the wallet from this device. Authenticate with your device biometrics to continue."
                )
            }
        )
    }

    if (confirmDeleteAccount) {
        AlertDialog(
            onDismissRequest = {
                if (!isDeletingAccount) {
                    confirmDeleteAccount = false
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activity = context.findActivity()
                        if (activity == null) {
                            deleteAccountError = "Biometric authentication is not available on this device."
                            confirmDeleteAccount = false
                            return@Button
                        }

                        scope.launch {
                            isDeletingAccount = true
                            deleteAccountError = null

                            runCatching {
                                val confirmed = confirmWalletRemovalWithBiometrics(activity)
                                if (confirmed) {
                                    rootViewModel.deleteRewardsAccount()
                                }
                            }.onSuccess {
                                confirmDeleteAccount = false
                            }.onFailure { error ->
                                deleteAccountError = error.message
                                    ?: "Could not delete your rewards account right now."
                                confirmDeleteAccount = false
                            }

                            isDeletingAccount = false
                        }
                    },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        contentColor = Color.White
                    )
                ) {
                    if (isDeletingAccount) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmDeleteAccount = false },
                    enabled = !isDeletingAccount
                ) {
                    Text("Cancel")
                }
            },
            title = { Text("Delete Rewards Account?") },
            text = {
                Text(
                    "This will delete your rewards account. This data cannot be restored. Authenticate with your device biometrics to continue."
                )
            }
        )
    }
}

@Composable
private fun WalletManagementSectionTitle(title: String) {
    Text(
        text = title.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.52f),
        fontWeight = FontWeight.Black
    )
}

@Composable
private fun WalletManagementInfoRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.64f)
            )
        }
    }
}

@Composable
private fun RecoveryPhraseReviewDialog(
    words: List<String>,
    onDismiss: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(onBack = onDismiss)

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                onDismiss()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SplitBlack)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Recovery Phrase",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )

                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color.White)
                }
            }

            Text(
                text = "Write these words down exactly, in order. Anyone with this phrase can access your wallet and Split account.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f)
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SplitBrandPink.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, SplitBrandPink.copy(alpha = 0.42f))
            ) {
                Text(
                    text = "Screenshots and screen recordings are blocked on this screen. Store the phrase somewhere private and offline, or in a trusted password manager.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0F1014),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2
                ) {
                    words.forEachIndexed { index, word ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.48f),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF16171D)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = word,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollSubscreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = {
            SubscreenHeader(
                title = title,
                subtitle = subtitle,
                onBack = onBack
            )
            content()
        }
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Suppress("DEPRECATION")
private suspend fun confirmRecoveryPhraseRevealWithBiometrics(
    activity: Activity
): Boolean = suspendCancellableCoroutine { continuation ->
    val biometricManager = activity.getSystemService(BiometricManager::class.java)
    if (biometricManager == null ||
        biometricManager.canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS
    ) {
        continuation.resumeWithException(
            IllegalStateException("Biometric authentication is not available on this device.")
        )
        return@suspendCancellableCoroutine
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val cancellationSignal = CancellationSignal()

    continuation.invokeOnCancellation {
        cancellationSignal.cancel()
    }

    val prompt = BiometricPrompt.Builder(activity)
        .setTitle("Reveal Recovery Phrase")
        .setSubtitle("Confirm your identity")
        .setNegativeButton(
            "Cancel",
            executor
        ) { _, _ ->
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        .build()

    prompt.authenticate(
        cancellationSignal,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence?
            ) {
                if (!continuation.isActive) return

                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                ) {
                    continuation.resume(false)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException(
                            errString?.toString()
                                ?: "Could not confirm with device biometrics."
                        )
                    )
                }
            }

            override fun onAuthenticationFailed() {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Could not confirm with device biometrics.")
                    )
                }
            }
        }
    )
}

@Suppress("DEPRECATION")
private suspend fun confirmWalletRemovalWithBiometrics(
    activity: Activity
): Boolean = suspendCancellableCoroutine { continuation ->
    val biometricManager = activity.getSystemService(BiometricManager::class.java)
    if (biometricManager == null ||
        biometricManager.canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS
    ) {
        continuation.resumeWithException(
            IllegalStateException("Biometric authentication is not available on this device.")
        )
        return@suspendCancellableCoroutine
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val cancellationSignal = CancellationSignal()

    continuation.invokeOnCancellation {
        cancellationSignal.cancel()
    }

    val prompt = BiometricPrompt.Builder(activity)
        .setTitle("Remove Wallet")
        .setSubtitle("Confirm wallet removal")
        .setNegativeButton(
            "Cancel",
            executor
        ) { _, _ ->
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        .build()

    prompt.authenticate(
        cancellationSignal,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence?
            ) {
                if (!continuation.isActive) return

                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                ) {
                    continuation.resume(false)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException(
                            errString?.toString()
                                ?: "Could not confirm with device biometrics."
                        )
                    )
                }
            }

            override fun onAuthenticationFailed() {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Could not confirm with device biometrics.")
                    )
                }
            }
        }
    )
}

@Composable
private fun SubscreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileHeaderActionButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            onClick = onBack
        )

        Column(
            modifier = Modifier.padding(start = 10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f)
            )
        }
    }
}

@Composable
private fun ProfileHeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color = Color.White
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {}

        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    paragraphs: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun LegalLinkCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF111114),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f)
            )
        }
    }
}

private fun openUrl(
    context: Context,
    url: String
) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

suspend fun readProfilePhotoSelection(
    context: Context,
    uri: Uri
): SelectedProfilePhoto = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver
    val data = contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes()
    } ?: throw IllegalStateException("Failed to read selected photo.")

    val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
        ?.trim()
        ?.ifBlank { null }
        ?: "profile-photo.jpg"

    SelectedProfilePhoto(
        data = data,
        fileName = fileName,
        mimeType = contentResolver.getType(uri)?.trim().orEmpty().ifBlank { "image/jpeg" }
    )
}
