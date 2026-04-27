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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.split.android.core.AppConfig
import com.split.android.data.wallet.LndConnectionState
import com.split.android.data.wallet.LndConnectParser
import com.split.android.data.wallet.LndNodeCredentials
import com.split.android.data.wallet.WalletLightningAddressInfo
import com.split.android.data.wallet.WalletState
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.events.BitcoinEventsScreen
import com.split.android.ui.home.ClaimBitcoinScreen
import com.split.android.ui.qr.IdentityShareSheet
import com.split.android.ui.qr.SplitQrScannerView
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
import java.util.Locale

private val supportLightningAddress: String
    get() = AppConfig.supportLightningAddress

private enum class ProfileRoute {
    HOME,
    LIGHTNING_NODE,
    ADD_MERCHANT,
    CLAIM_BITCOIN,
    BITCOIN_EVENTS,
    REWARDS_INFO,
    SUPPORT,
    CONTENT_MODERATION,
    LEGAL,
    WALLET_MANAGEMENT
}

private data class ProfileEntry(
    val icon: ImageVector,
    val iconColor: Color,
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
    onClose: (() -> Unit)? = null,
    onOpenSupportChat: (SupportChatRequest) -> Unit
) {
    var route by remember { mutableStateOf(ProfileRoute.HOME) }
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()

    when (route) {
        ProfileRoute.HOME -> ProfileHomeScreen(
            rootViewModel = rootViewModel,
            modifier = modifier,
            onClose = onClose,
            onSelectRoute = { route = it }
        )

        ProfileRoute.LIGHTNING_NODE -> LightningNodeSettingsScreen(
            rootViewModel = rootViewModel,
            onBack = { route = ProfileRoute.HOME }
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

@Composable
private fun ProfileHomeScreen(
    rootViewModel: SplitRootViewModel,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)?,
    onSelectRoute: (ProfileRoute) -> Unit
) {
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val hasConnectedLightningNode = connectedLndNode != null || rootViewModel.hasStoredLndNode()
    val entries = listOf(
        ProfileEntry(
            Icons.Rounded.Bolt,
            alternatingProfileColor(7),
            "Lightning Node",
            if (hasConnectedLightningNode) "Manage your connection" else "Connect a lightning node to Split",
            ProfileRoute.LIGHTNING_NODE
        ),
        ProfileEntry(
            SplitFeatureIcons.Store,
            alternatingProfileColor(0),
            "Add a Merchant",
            "Add any BTC business to our rewards program.",
            ProfileRoute.ADD_MERCHANT
        ),
        ProfileEntry(SplitFeatureIcons.Rewards, alternatingProfileColor(1), "Rewards Explained", "How it works.", ProfileRoute.REWARDS_INFO),
        ProfileEntry(Icons.Rounded.CurrencyBitcoin, alternatingProfileColor(2), "Claim Your Bitcoin", "On-chain deposits.", ProfileRoute.CLAIM_BITCOIN),
        ProfileEntry(Icons.Rounded.ChatBubble, alternatingProfileColor(3), "Contact / Support", "Questions, feedback, and product ideas.", ProfileRoute.SUPPORT),
        ProfileEntry(Icons.Rounded.Flag, alternatingProfileColor(4), "Content Moderation", "Blocking and user safety.", ProfileRoute.CONTENT_MODERATION),
        ProfileEntry(Icons.Rounded.Description, alternatingProfileColor(5), "Legal", "Documents and agreements.", ProfileRoute.LEGAL),
        ProfileEntry(Icons.Rounded.Lock, alternatingProfileColor(6), "Wallet Management", "Wallet device access.", ProfileRoute.WALLET_MANAGEMENT)
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
            entries.forEach { entry ->
                ProfileNavCard(
                    entry = entry,
                    onClick = { onSelectRoute(entry.route) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
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
                    tint = entry.iconColor
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
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lndState by rootViewModel.lndConnectionState.collectAsStateWithLifecycle()
    val connectedNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val balanceSummary by rootViewModel.lndBalanceSummary.collectAsStateWithLifecycle()

    var lndConnectString by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isScanningQr by remember { mutableStateOf(false) }

    val activeNode = connectedNode ?: rootViewModel.activeLndNodeCredentials()
    val hasStoredNode = activeNode != null || rootViewModel.hasStoredLndNode()
    val canConnect = !isConnecting && lndConnectString.trim().isNotEmpty()

    fun connectNode(rawValue: String) {
        val normalized = LndConnectParser.normalize(rawValue)?.trim().orEmpty()
        if (normalized.isEmpty()) {
            statusMessage = "Enter an LND Connect string."
            return
        }

        isConnecting = true
        statusMessage = "Connecting..."
        scope.launch {
            runCatching {
                rootViewModel.connectLndNode(normalized)
            }.onSuccess {
                lndConnectString = ""
                statusMessage = "Connected"
            }.onFailure { error ->
                statusMessage = error.message ?: "Unable to connect to LND node."
            }
            isConnecting = false
        }
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
        connectNode(normalized)
    }

    LaunchedEffect(Unit) {
        if (rootViewModel.hasStoredLndNode()) {
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
            onEnterManually = { isScanningQr = false },
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
            title = "Lightning Node",
            subtitle = if (hasStoredNode) "Manage your node connection." else "Connect your LND node to Split.",
            onBack = onBack
        )

        if (hasStoredNode) {
            LightningNodeSummaryCard(
                node = activeNode,
                state = lndState,
                channelBalanceSats = balanceSummary?.spendableSats,
                onChainBalanceSats = balanceSummary?.onChainBalanceSats
            )
        } else {
            LightningNodeCompatibilityCard()
            LightningNodeConnectCard(
                lndConnectString = lndConnectString,
                onLndConnectStringChange = {
                    lndConnectString = it
                    statusMessage = null
                },
                isConnecting = isConnecting,
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
                    statusMessage == "Connecting..."
                ) {
                    SplitBrandBlue
                } else {
                    SplitBrandPink
                }
            )
        }

        if (hasStoredNode) {
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

            Button(
                onClick = {
                    activeNode?.let { node ->
                        rootViewModel.forgetLndNode(node.id)
                        statusMessage = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = activeNode != null && !isRefreshing && !isConnecting
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Forget Node")
            }
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
                text = "Your node must be reachable from this phone over LND REST."
            )
            LightningNodeCompatibilityRow(
                icon = Icons.Rounded.Lock,
                text = "For remote access, we recommend Tailscale or another private VPN."
            )
            LightningNodeCompatibilityRow(
                icon = Icons.Rounded.Info,
                text = "Tor and Core Lightning are not supported yet."
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
private fun LightningNodeConnectCard(
    lndConnectString: String,
    onLndConnectStringChange: (String) -> Unit,
    isConnecting: Boolean,
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

            OutlinedButton(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Scan QR Code")
            }

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
                    Text("Connecting")
                } else {
                    Text("Connect Node")
                }
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
    onEnterManually: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileHeaderActionButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Close scanner",
                onClick = onClose
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Lightning Node",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.90f)
            )
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.size(42.dp))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Scan LND Connect",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text(
                text = "Align the node QR code inside the frame.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.62f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            SplitQrScannerView(
                modifier = Modifier.fillMaxSize(),
                onCodeScanned = onCodeScanned
            )

            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {}
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCode2,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.90f),
                modifier = Modifier.size(38.dp)
            )
            Text(
                text = "Scan your lndconnect QR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "The macaroon stays on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.62f)
            )
        }

        Button(
            onClick = onEnterManually,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enter Manually")
        }
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF101013),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
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
                        onClick = onDismiss,
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
                            title = "Support",
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
            Text(if (existingSupportThread == null) "Message Support" else "Open Support Chat")
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
        if (AppConfig.usesPublicPlaceholderConfig) {
            InfoCard(
                title = "Placeholder Legal Configuration",
                paragraphs = listOf(
                    "This public snapshot intentionally uses placeholder legal and support configuration.",
                    "Hosted legal documents are not bundled in this repository. Before shipping a derived app, point the app at your own published terms and privacy policy."
                )
            )
            return@ScrollSubscreen
        }

        LegalLinkCard(
            title = "User Agreement",
            subtitle = "Review the app terms and messaging rules.",
            onClick = { activeDocument = LegalDocument.UserAgreement }
        )
        LegalLinkCard(
            title = "Privacy Policy",
            subtitle = "Review how the app handles messaging and wallet data.",
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
        subtitle = "Review the app terms and messaging rules.",
        url = "${AppConfig.baseUrl}/User-agreement"
    ),
    PrivacyPolicy(
        title = "Privacy Policy",
        subtitle = "Review how the app handles messaging and wallet data.",
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
    var isRemoving by remember { mutableStateOf(false) }
    var removalError by remember { mutableStateOf<String?>(null) }

    ScrollSubscreen(
        title = "Wallet Management",
        subtitle = "Wallet device access.",
        onBack = onBack
    ) {
        InfoCard(
            title = "Remove Wallet",
            paragraphs = listOf(
                "This removes the wallet from this device. You will need your recovery phrase to restore it later."
            )
        )

        OutlinedButton(
            onClick = {
                removalError = null
                confirmRemove = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRemoving
        ) {
            Text("Remove Wallet From Device")
        }

        if (!removalError.isNullOrBlank()) {
            Text(
                text = removalError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink
            )
        }
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
