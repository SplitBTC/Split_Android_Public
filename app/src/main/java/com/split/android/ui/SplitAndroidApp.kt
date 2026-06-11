package com.split.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.split.android.data.auth.AuthState
import com.split.android.data.messages.MessageNotificationManager
import com.split.android.data.messages.MessageNotificationRouter
import com.split.android.data.messages.MessagePushEvents
import com.split.android.data.messages.MessageThreadPresenceTracker
import com.split.android.data.wallet.TransactionActivityTracker
import com.split.android.data.wallet.RemoteNodeTorTransport
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.TorBootstrapState
import com.split.android.data.wallet.WalletState
import com.split.android.data.wallet.WalletToastManager
import com.split.android.data.wallet.usesTor
import com.split.android.data.wallet.isReasonableRecoveryPhraseWord
import com.split.android.data.wallet.normalizeRecoveryPhraseWord
import com.split.android.data.wallet.normalizeRecoveryPhraseWords
import com.split.android.ui.coupons.NearbyCouponsScreen
import com.split.android.ui.home.WalletHomeScreen
import com.split.android.ui.home.SpendWalletMenuItem
import com.split.android.ui.home.WalletReceiveScreen
import com.split.android.ui.home.WalletSendScreen
import com.split.android.ui.home.WalletTransactionsScreen
import com.split.android.ui.map.BtcMerchantMapScreen
import com.split.android.ui.messages.MessagesScreen
import com.split.android.ui.profile.ProfileScreen
import com.split.android.ui.profile.ProfileStartRoute
import com.split.android.ui.profile.SupportChatRequest
import com.split.android.ui.rewards.WalletRewardsScreen
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SplitDestination(
    val label: String,
    val icon: ImageVector
) {
    WALLET("Wallet", SplitFeatureIcons.Wallet),
    REWARDS("Rewards", SplitFeatureIcons.Rewards),
    PROMOS("Promos", SplitFeatureIcons.Tag),
    MESSAGES("Messages", Icons.Rounded.ChatBubble)
}

private enum class WalletOverlay {
    RECEIVE,
    TRANSACTIONS,
    MERCHANT_MAP,
    PROFILE
}

data class SendOverlayConfig(
    val initialDestination: String? = null,
    val initialComment: String? = null,
    val autoPrepareOnLaunch: Boolean = false,
    val startInScanMode: Boolean = false
)

private data class RequestedMessageThread(
    val conversationId: String,
    val title: String? = null,
    val lightningAddress: String? = null
)

private enum class WalletBootstrapRoute {
    HOME,
    RESTORE
}

@Composable
fun SplitAndroidApp(
    rootViewModel: SplitRootViewModel = viewModel()
) {
    val walletState by rootViewModel.walletState.collectAsStateWithLifecycle()
    val isStoredWalletRecoveryFlowActive by rootViewModel
        .isStoredWalletRecoveryFlowActive
        .collectAsStateWithLifecycle()
    val authState by rootViewModel.authState.collectAsStateWithLifecycle()
    val hasValidSession by rootViewModel.hasValidSession.collectAsStateWithLifecycle()
    val authError by rootViewModel.lastAuthError.collectAsStateWithLifecycle()
    val pendingSeedWords by rootViewModel.pendingSeedWords.collectAsStateWithLifecycle()

    LaunchedEffect(walletState) {
        if (walletState is WalletState.Ready && authState == AuthState.Idle) {
            rootViewModel.retryAuth()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (pendingSeedWords.isNotEmpty()) {
            WalletSeedBackupScreen(
                words = pendingSeedWords,
                onConfirm = rootViewModel::confirmPendingWalletCreation,
                onCancel = rootViewModel::cancelPendingWalletCreation
            )
        } else {
            when (val state = walletState) {
                WalletState.Loading,
                WalletState.Connecting -> WalletBootstrapLoadingScreen()
                WalletState.NoWallet -> WalletBootstrapScreen(
                    errorMessage = authError,
                    preferRestoreFlow = false,
                    onCreateWallet = rootViewModel::createWallet,
                    onRestoreWallet = rootViewModel::restoreWallet
                )
                is WalletState.Error -> if (isStoredWalletRecoveryFlowActive) {
                    WalletRecoveryScreen(
                        errorMessage = state.message,
                        startOnRestore = state.preferRestoreFlow,
                        onRetry = rootViewModel::bootstrap,
                        onCreateWallet = rootViewModel::createWallet,
                        onRestoreWallet = rootViewModel::restoreWallet
                    )
                } else {
                    WalletBootstrapScreen(
                        errorMessage = state.message,
                        preferRestoreFlow = state.preferRestoreFlow,
                        onCreateWallet = rootViewModel::createWallet,
                        onRestoreWallet = rootViewModel::restoreWallet
                    )
                }
                is WalletState.Ready -> MainWalletShell(
                    rootViewModel = rootViewModel,
                    walletState = state,
                    authState = authState,
                    hasValidSession = hasValidSession,
                    onRetryAuth = rootViewModel::retryAuth,
                    onClearWallet = rootViewModel::clearWallet
                )
            }
        }
    }
}

@Composable
private fun WalletBootstrapLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF0F1014),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = "Loading wallet...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.74f)
                )
            }
        }
    }
}

@Composable
private fun WalletRecoveryScreen(
    errorMessage: String,
    startOnRestore: Boolean,
    onRetry: () -> Unit,
    onCreateWallet: () -> Unit,
    onRestoreWallet: (String) -> Unit
) {
    var route by rememberSaveable {
        mutableStateOf(
            if (startOnRestore) {
                WalletBootstrapRoute.RESTORE
            } else {
                WalletBootstrapRoute.HOME
            }
        )
    }
    var showCreateWalletConfirmation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(startOnRestore) {
        route = if (startOnRestore) {
            WalletBootstrapRoute.RESTORE
        } else {
            WalletBootstrapRoute.HOME
        }
    }

    if (showCreateWalletConfirmation) {
        AlertDialog(
            onDismissRequest = { showCreateWalletConfirmation = false },
            title = { Text("Replace Saved Wallet?") },
            text = {
                Text(
                    "Creating a new wallet will wipe the seedphrase currently saved on this device making recovery without your own backup impossible. Only continue if you want a brand-new wallet on this device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateWalletConfirmation = false
                        onCreateWallet()
                    }
                ) {
                    Text("Create New Wallet")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateWalletConfirmation = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    when (route) {
        WalletBootstrapRoute.HOME -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Color(0xFF0F1014),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Wallet Couldn't Open",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "We found a wallet seed on this device, but Split couldn't reopen the wallet automatically. Try restoring the existing wallet first. If you create a new wallet instead, the saved recovery phrase on this device will be replaced.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.74f)
                    )

                    WalletBootstrapErrorBox(errorMessage)

                    WalletBootstrapActionButton(
                        text = "Retry",
                        gradientColors = listOf(Color(0xFF4B58E8), SplitBrandBlue),
                        onClick = onRetry,
                    )

                    WalletBootstrapActionButton(
                        text = "Restore Wallet",
                        gradientColors = listOf(Color(0xFFE1468E), SplitBrandPink),
                        onClick = { route = WalletBootstrapRoute.RESTORE }
                    )

                    OutlinedButton(
                        onClick = { showCreateWalletConfirmation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create New Wallet")
                    }
                }
            }
        }

        WalletBootstrapRoute.RESTORE -> WalletRestoreScreen(
            errorMessage = errorMessage,
            onBack = { route = WalletBootstrapRoute.HOME },
            onRestoreWallet = onRestoreWallet
        )
    }
}

@Composable
private fun WalletBootstrapScreen(
    errorMessage: String?,
    preferRestoreFlow: Boolean,
    onCreateWallet: () -> Unit,
    onRestoreWallet: (String) -> Unit
) {
    var route by rememberSaveable {
        mutableStateOf(
            if (preferRestoreFlow) {
                WalletBootstrapRoute.RESTORE
            } else {
                WalletBootstrapRoute.HOME
            }
        )
    }

    LaunchedEffect(preferRestoreFlow) {
        route = if (preferRestoreFlow) {
            WalletBootstrapRoute.RESTORE
        } else {
            WalletBootstrapRoute.HOME
        }
    }

    when (route) {
        WalletBootstrapRoute.HOME -> WalletBootstrapHomeScreen(
            errorMessage = errorMessage,
            onCreateWallet = onCreateWallet,
            onOpenRestore = { route = WalletBootstrapRoute.RESTORE }
        )
        WalletBootstrapRoute.RESTORE -> WalletRestoreScreen(
            errorMessage = errorMessage,
            onBack = { route = WalletBootstrapRoute.HOME },
            onRestoreWallet = onRestoreWallet
        )
    }
}

@Composable
private fun WalletBootstrapHomeScreen(
    errorMessage: String?,
    onCreateWallet: () -> Unit,
    onOpenRestore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color.Black,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Set Up Your Wallet",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "You can link an existing wallet with a seed phrase, or create a brand-new non-custodial wallet on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f)
                )

                WalletBootstrapActionButton(
                    text = "Link Existing Wallet (Restore)",
                    gradientColors = listOf(Color(0xFF4B58E8), SplitBrandBlue),
                    onClick = onOpenRestore
                )

                WalletBootstrapActionButton(
                    text = "Create New Wallet",
                    gradientColors = listOf(Color(0xFFE1468E), SplitBrandPink),
                    onClick = onCreateWallet
                )

                if (!errorMessage.isNullOrBlank()) {
                    WalletBootstrapErrorBox(errorMessage)
                }
            }
        }
    }
}

@Composable
private fun WalletRestoreScreen(
    errorMessage: String?,
    onBack: () -> Unit,
    onRestoreWallet: (String) -> Unit
) {
    var restoreWordCount by rememberSaveable { mutableStateOf(12) }
    var phraseText by rememberSaveable { mutableStateOf("") }

    val normalizedWords = remember(phraseText) {
        parseRecoveryPhraseWords(phraseText)
    }
    val validationMessage = remember(normalizedWords, restoreWordCount) {
        when {
            normalizedWords.isEmpty() -> ""
            normalizedWords.size < restoreWordCount -> "Incomplete"
            normalizedWords.size > restoreWordCount -> "Too many words"
            normalizedWords.any { !isReasonableSeedWord(it) } -> "Check spelling"
            else -> ""
        }
    }
    val isPhraseValid = normalizedWords.size == restoreWordCount &&
        normalizedWords.all(::isReasonableSeedWord)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Text(
                text = "Restore Wallet",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Paste or type your recovery phrase. Use a single space between each word.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WalletWordCountButton(
                modifier = Modifier.fillMaxWidth(0.48f),
                label = "12 words",
                selected = restoreWordCount == 12,
                onClick = { restoreWordCount = 12 }
            )
            WalletWordCountButton(
                modifier = Modifier.fillMaxWidth(0.48f),
                label = "24 words",
                selected = restoreWordCount == 24,
                onClick = { restoreWordCount = 24 }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${normalizedWords.size}/$restoreWordCount words",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.56f)
            )

            if (validationMessage.isNotEmpty()) {
                Text(
                    text = validationMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = SplitBrandPink
                )
            }
        }

        TextField(
            value = phraseText,
            onValueChange = {
                phraseText = it
                    .replace('\t', ' ')
                    .replace('\n', ' ')
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 172.dp),
            minLines = 6,
            label = { Text("Recovery phrase") },
            supportingText = {
                Text("Paste or type the full phrase in a single field.")
            }
        )

        if (!errorMessage.isNullOrBlank()) {
            WalletBootstrapErrorBox(errorMessage)
        }

        WalletBootstrapActionButton(
            text = "Restore",
            gradientColors = listOf(Color(0xFFE1468E), SplitBrandPink),
            onClick = {
                onRestoreWallet(
                    normalizedWords
                        .take(restoreWordCount)
                        .joinToString(" ")
                )
            },
            enabled = isPhraseValid
        )

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back", color = Color.White.copy(alpha = 0.82f))
        }
    }
}

@Composable
private fun WalletSeedBackupScreen(
    words: List<String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recoveryPhrase = remember(words) { words.joinToString(" ") }
    var hasAcknowledgedOneTimeDisplay by rememberSaveable { mutableStateOf(false) }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
    var showCopyConfirmation by rememberSaveable { mutableStateOf(false) }
    var didCopyRecoveryPhrase by rememberSaveable { mutableStateOf(false) }

    fun copyRecoveryPhrase() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Split recovery phrase", recoveryPhrase)
        )
        didCopyRecoveryPhrase = true

        scope.launch {
            delay(60_000L)
            val current = clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
            if (current == recoveryPhrase) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            didCopyRecoveryPhrase = false
        }
    }

    BackHandler {
        showDiscardConfirmation = true
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard this wallet?") },
            text = {
                Text(
                    "This recovery phrase has not been saved yet. If you cancel, this wallet setup will be discarded and you will need to create a new wallet."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onCancel()
                    }
                ) {
                    Text("Discard Wallet")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardConfirmation = false }
                ) {
                    Text("Keep Viewing")
                }
            }
        )
    }

    if (showCopyConfirmation) {
        AlertDialog(
            onDismissRequest = { showCopyConfirmation = false },
            title = { Text("Copy recovery phrase?") },
            text = {
                Text(
                    "Only paste this into a trusted password manager. Your clipboard may be visible to other apps or synced to nearby devices. Split will try to clear it after 60 seconds."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCopyConfirmation = false
                        copyRecoveryPhrase()
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCopyConfirmation = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Your Recovery Phrase",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            TextButton(onClick = { showDiscardConfirmation = true }) {
                Text("Cancel", color = Color.White)
            }
        }

        Text(
            text = "These 12 words are the backup for your wallet and Split account.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = SplitBrandPink.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, SplitBrandPink.copy(alpha = 0.40f))
        ) {
            Text(
                text = "Save this recovery phrase before continuing. If this device is lost, replaced, or app data is erased, Split cannot recover it for you.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
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
                        shape = MaterialTheme.shapes.medium,
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
                                color = Color.White.copy(alpha = 0.55f)
                            )
                            Text(
                                text = word,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCopyConfirmation = true },
            shape = MaterialTheme.shapes.large,
            color = Color.White.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (didCopyRecoveryPhrase) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = Color.White
                )
                Text(
                    text = if (didCopyRecoveryPhrase) "Copied" else "Copy recovery phrase",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF101013),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Anyone with these words can access your wallet funds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Split cannot recover this phrase for you if this device is lost, replaced, or app data is erased.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.68f)
                )
                Text(
                    text = "Do not share it with anyone. Do not store it in screenshots, email, cloud notes, or messages. If copying, paste it only into a trusted password manager.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.68f)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    hasAcknowledgedOneTimeDisplay = !hasAcknowledgedOneTimeDisplay
                },
            shape = MaterialTheme.shapes.large,
            color = Color.White.copy(alpha = 0.06f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = hasAcknowledgedOneTimeDisplay,
                    onCheckedChange = { hasAcknowledgedOneTimeDisplay = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SplitBrandPink,
                        uncheckedColor = Color.White.copy(alpha = 0.70f),
                        checkmarkColor = Color.White
                    )
                )
                Text(
                    text = "I saved my recovery phrase somewhere safe.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        WalletBootstrapActionButton(
            text = "I saved my recovery phrase",
            gradientColors = listOf(SplitBrandBlue, SplitBrandPink),
            onClick = onConfirm,
            enabled = hasAcknowledgedOneTimeDisplay
        )
    }
}

@Composable
private fun WalletBootstrapActionButton(
    text: String,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                brush = Brush.linearGradient(
                    if (enabled) {
                        gradientColors
                    } else {
                        gradientColors.map { it.copy(alpha = 0.38f) }
                    }
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (enabled) 0.16f else 0.08f),
                shape = MaterialTheme.shapes.medium
            )
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.58f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WalletBootstrapErrorBox(
    message: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFF241116),
        border = BorderStroke(1.dp, SplitBrandPink.copy(alpha = 0.35f))
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
private fun WalletWordCountButton(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) SplitBrandBlue else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 0.20f else 0.10f))
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun parseRecoveryPhraseWords(raw: String): List<String> {
    return normalizeRecoveryPhraseWords(raw)
}

private fun normalizeSeedWord(word: String): String {
    return normalizeRecoveryPhraseWord(word)
}

private fun isReasonableSeedWord(word: String): Boolean {
    return isReasonableRecoveryPhraseWord(word)
}

@Composable
private fun MainWalletShell(
    rootViewModel: SplitRootViewModel,
    walletState: WalletState.Ready,
    authState: AuthState,
    hasValidSession: Boolean,
    onRetryAuth: () -> Unit,
    onClearWallet: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val walletEventVersion by rootViewModel.walletEventVersion.collectAsStateWithLifecycle()
    val activeSpendWallet by rootViewModel.activeSpendWallet.collectAsStateWithLifecycle()
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val lndBalanceSummary by rootViewModel.lndBalanceSummary.collectAsStateWithLifecycle()
    val connectedNwcWallet by rootViewModel.connectedNwcWallet.collectAsStateWithLifecycle()
    val nwcBalanceSummary by rootViewModel.nwcBalanceSummary.collectAsStateWithLifecycle()
    val connectedCoreLightningNode by rootViewModel.connectedCoreLightningNode.collectAsStateWithLifecycle()
    val coreLightningBalanceSummary by rootViewModel.coreLightningBalanceSummary.collectAsStateWithLifecycle()
    val connectedEclairNode by rootViewModel.connectedEclairNode.collectAsStateWithLifecycle()
    val eclairBalanceSummary by rootViewModel.eclairBalanceSummary.collectAsStateWithLifecycle()
    val connectedSparkSubwallet by rootViewModel.connectedSparkSubwallet.collectAsStateWithLifecycle()
    val sparkSubwalletBalanceSummary by rootViewModel.sparkSubwalletBalanceSummary.collectAsStateWithLifecycle()
    val storedLndNodesVersion by rootViewModel.storedLndNodesVersion.collectAsStateWithLifecycle()
    val storedNwcWalletsVersion by rootViewModel.storedNwcWalletsVersion.collectAsStateWithLifecycle()
    val storedCoreLightningNodesVersion by rootViewModel.storedCoreLightningNodesVersion.collectAsStateWithLifecycle()
    val storedEclairNodesVersion by rootViewModel.storedEclairNodesVersion.collectAsStateWithLifecycle()
    val storedSparkSubwalletsVersion by rootViewModel.storedSparkSubwalletsVersion.collectAsStateWithLifecycle()
    val torBootstrapState by RemoteNodeTorTransport.bootstrapState.collectAsStateWithLifecycle()
    val pendingNotificationConversationId by MessageNotificationRouter.pendingConversationId.collectAsStateWithLifecycle()
    val transactionActivityTracker = remember(context) {
        TransactionActivityTracker.getInstance(context)
    }
    val unseenTransactionIds by transactionActivityTracker.unseenTransactionIds.collectAsStateWithLifecycle()
    val isTransactionBadgeHeld by WalletToastManager.isTransactionBadgeHeld.collectAsStateWithLifecycle()
    var destination by remember {
        mutableStateOf(
            if (pendingNotificationConversationId?.trim().orEmpty().isNotEmpty()) {
                SplitDestination.MESSAGES
            } else {
                SplitDestination.WALLET
            }
        )
    }
    var activeOverlay by remember { mutableStateOf<WalletOverlay?>(null) }
    var sendOverlayConfig by remember { mutableStateOf<SendOverlayConfig?>(null) }
    var requestedMessageComposeLightningAddress by remember { mutableStateOf<String?>(null) }
    var requestedMessageThread by remember { mutableStateOf<RequestedMessageThread?>(null) }
    var requestedOpenContacts by remember { mutableStateOf(false) }
    var profileStartRoute by rememberSaveable { mutableStateOf(ProfileStartRoute.HOME) }
    var isLaunchingBuyBitcoin by remember { mutableStateOf(false) }
    var isAppActive by remember { mutableStateOf(true) }
    val unreadMessageCount = remember(storedMessages) {
        storedMessages.count { it.isIncoming && !it.isRead }
    }
    val unseenTransactionCount = unseenTransactionIds.size
    var displayedUnseenTransactionCount by remember { mutableIntStateOf(unseenTransactionCount) }
    val activeLndNode = connectedLndNode ?: rootViewModel.activeLndNodeCredentials()
    val activeNwcWallet = connectedNwcWallet ?: rootViewModel.activeNwcWalletCredentials()
    val activeCoreLightningNode = connectedCoreLightningNode ?: rootViewModel.activeCoreLightningNodeCredentials()
    val activeEclairNode = connectedEclairNode ?: rootViewModel.activeEclairNodeCredentials()
    val activeSparkSubwallet = connectedSparkSubwallet ?: rootViewModel.activeSparkSubwalletCredentials()
    val storedLndNodes = remember(storedLndNodesVersion, connectedLndNode) {
        rootViewModel.storedLndNodes()
    }
    val storedNwcWallets = remember(storedNwcWalletsVersion, connectedNwcWallet) {
        rootViewModel.storedNwcWallets()
    }
    val storedCoreLightningNodes = remember(storedCoreLightningNodesVersion, connectedCoreLightningNode) {
        rootViewModel.storedCoreLightningNodes()
    }
    val storedEclairNodes = remember(storedEclairNodesVersion, connectedEclairNode) {
        rootViewModel.storedEclairNodes()
    }
    val storedSparkSubwallets = remember(storedSparkSubwalletsVersion, connectedSparkSubwallet) {
        rootViewModel.storedSparkSubwallets()
    }
    val walletMenuItems = remember(
        activeSpendWallet,
        activeLndNode,
        activeNwcWallet,
        activeCoreLightningNode,
        activeEclairNode,
        activeSparkSubwallet,
        storedLndNodes,
        storedNwcWallets,
        storedCoreLightningNodes,
        storedEclairNodes,
        storedSparkSubwallets
    ) {
        val activeLndNodeId = activeLndNode?.id
        val activeNwcWalletId = activeNwcWallet?.id
        val activeCoreLightningNodeId = activeCoreLightningNode?.id
        val activeEclairNodeId = activeEclairNode?.id
        val activeSparkSubwalletId = activeSparkSubwallet?.id

        buildList {
            add(
                SpendWalletMenuItem(
                    source = SpendWalletSource.SPARK,
                    walletId = null,
                    title = "Split",
                    subtitle = "Root Spark wallet",
                    isActive = activeSpendWallet == SpendWalletSource.SPARK
                )
            )

            (listOfNotNull(activeLndNode) + storedLndNodes)
                .distinctBy { it.id }
                .forEach { node ->
                    add(
                        SpendWalletMenuItem(
                            source = SpendWalletSource.LND,
                            walletId = node.id,
                            title = node.displayName,
                            subtitle = "LND Lightning Node",
                            isActive = activeSpendWallet == SpendWalletSource.LND &&
                                node.id == activeLndNodeId
                        )
                    )
                }

            (listOfNotNull(activeNwcWallet) + storedNwcWallets)
                .distinctBy { it.id }
                .forEach { wallet ->
                    add(
                        SpendWalletMenuItem(
                            source = SpendWalletSource.NWC,
                            walletId = wallet.id,
                            title = wallet.displayName,
                            subtitle = "NWC Wallet or Node",
                            isActive = activeSpendWallet == SpendWalletSource.NWC &&
                                wallet.id == activeNwcWalletId
                        )
                    )
                }

            (listOfNotNull(activeCoreLightningNode) + storedCoreLightningNodes)
                .distinctBy { it.id }
                .forEach { node ->
                    add(
                        SpendWalletMenuItem(
                            source = SpendWalletSource.CORE_LIGHTNING,
                            walletId = node.id,
                            title = node.displayName,
                            subtitle = "Core Lightning Node",
                            isActive = activeSpendWallet == SpendWalletSource.CORE_LIGHTNING &&
                                node.id == activeCoreLightningNodeId
                        )
                    )
                }

            (listOfNotNull(activeEclairNode) + storedEclairNodes)
                .distinctBy { it.id }
                .forEach { node ->
                    add(
                        SpendWalletMenuItem(
                            source = SpendWalletSource.ECLAIR,
                            walletId = node.id,
                            title = node.displayName,
                            subtitle = "Eclair Node",
                            isActive = activeSpendWallet == SpendWalletSource.ECLAIR &&
                                node.id == activeEclairNodeId
                        )
                    )
                }

            (listOfNotNull(activeSparkSubwallet) + storedSparkSubwallets)
                .distinctBy { it.id }
                .forEach { wallet ->
                    add(
                        SpendWalletMenuItem(
                            source = SpendWalletSource.SPARK_SUBWALLET,
                            walletId = wallet.id,
                            title = wallet.displayName,
                            subtitle = "Spark Wallet",
                            isActive = activeSpendWallet == SpendWalletSource.SPARK_SUBWALLET &&
                                wallet.id == activeSparkSubwalletId
                        )
                    )
                }
        }
    }
    val activeSpendWalletUsesTor = when (activeSpendWallet) {
        SpendWalletSource.LND -> activeLndNode?.usesTor == true
        SpendWalletSource.NWC -> activeNwcWallet?.usesTor == true
        SpendWalletSource.CORE_LIGHTNING -> activeCoreLightningNode?.usesTor == true
        SpendWalletSource.ECLAIR -> activeEclairNode?.usesTor == true
        SpendWalletSource.SPARK,
        SpendWalletSource.SPARK_SUBWALLET -> false
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                isAppActive = true
            }

            override fun onPause(owner: LifecycleOwner) {
                isAppActive = false
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isAppActive) {
        MessageThreadPresenceTracker.setAppForeground(isAppActive)
        rootViewModel.setLndInvoiceListenerActive(isAppActive)
        rootViewModel.setNwcNotificationListenerActive(isAppActive)
    }

    LaunchedEffect(Unit) {
        MessagePushEvents.incomingMessagePushes.collect {
            rootViewModel.syncMessages(force = true)
        }
    }

    LaunchedEffect(
        isAppActive,
        activeSpendWallet,
        storedLndNodesVersion,
        storedNwcWalletsVersion,
        storedCoreLightningNodesVersion,
        storedEclairNodesVersion
    ) {
        if (isAppActive) {
            rootViewModel.warmTorForActiveWalletIfNeeded()
        }
    }

    LaunchedEffect(isAppActive, hasValidSession, walletState.sparkAddress) {
        if (!isAppActive || !hasValidSession) {
            return@LaunchedEffect
        }

        rootViewModel.syncMessages(force = true)
        rootViewModel.syncOutgoingStatuses(force = true)
    }

    LaunchedEffect(isAppActive, walletState.sparkAddress, walletEventVersion, activeSpendWallet) {
        if (!isAppActive) return@LaunchedEffect

        runCatching {
            rootViewModel.fetchTransactionRows(source = activeSpendWallet)
        }.onSuccess { rows ->
            transactionActivityTracker.reconcile(
                rows = rows,
                scope = rootViewModel.transactionActivityScope(activeSpendWallet)
            )
        }
    }

    LaunchedEffect(unseenTransactionCount, isTransactionBadgeHeld) {
        if (!isTransactionBadgeHeld) {
            displayedUnseenTransactionCount = unseenTransactionCount
        }
    }

    LaunchedEffect(Unit) {
        MessageNotificationManager.ensureMessageChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(pendingNotificationConversationId) {
        val conversationId = pendingNotificationConversationId?.trim().orEmpty()
        if (conversationId.isNotEmpty()) {
            requestedMessageThread = RequestedMessageThread(conversationId = conversationId)
            requestedMessageComposeLightningAddress = null
            requestedOpenContacts = false
            activeOverlay = null
            destination = SplitDestination.MESSAGES
            rootViewModel.syncMessages(force = true)
        }
    }

    val openMainTabContacts = {
        requestedOpenContacts = true
        destination = SplitDestination.MESSAGES
    }

    val openMainTabProfile = {
        profileStartRoute = ProfileStartRoute.HOME
        activeOverlay = WalletOverlay.PROFILE
    }

    val openMainTabAddWallet = {
        profileStartRoute = ProfileStartRoute.ADD_LIGHTNING_WALLET
        activeOverlay = WalletOverlay.PROFILE
    }

    val openSendOverlay: (SendOverlayConfig) -> Unit = openSendOverlay@{ config ->
        if (sendOverlayConfig != null) return@openSendOverlay
        sendOverlayConfig = config
    }

    val openMainTabQrScanner = openMainTabQrScanner@{
        if (activeSpendWalletUsesTor && torBootstrapState !is TorBootstrapState.Ready) {
            RemoteNodeTorTransport.warmUp(context.applicationContext, scope)
            return@openMainTabQrScanner
        }
        openSendOverlay(SendOverlayConfig(startInScanMode = true))
    }

    val openMainTabMerchantMap = {
        activeOverlay = WalletOverlay.MERCHANT_MAP
    }
    val openMainTabCoupons = {
        destination = SplitDestination.PROMOS
        if (activeOverlay == WalletOverlay.PROFILE) {
            activeOverlay = null
        }
    }
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Scaffold(
            containerColor = Color.Black,
            bottomBar = {
                if (sendOverlayConfig == null &&
                    !(destination == SplitDestination.MESSAGES && isKeyboardVisible)
                ) {
                    NavigationBar(
                        containerColor = Color(0xFF090909),
                        tonalElevation = 0.dp
                    ) {
                        SplitDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = {
                                    destination = item
                                    if (activeOverlay == WalletOverlay.PROFILE) {
                                        activeOverlay = null
                                    }
                                },
                                icon = {
                                    if (item == SplitDestination.MESSAGES && unreadMessageCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge {
                                                    Text(
                                                        text = unreadMessageCount.coerceAtMost(99).toString()
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = item.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label
                                        )
                                    }
                                },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            when (destination) {
                SplitDestination.WALLET -> WalletHomeScreen(
                    rootViewModel = rootViewModel,
                    walletState = walletState,
                    authState = authState,
                    hasValidSession = hasValidSession,
                    activeSpendWallet = activeSpendWallet,
                    walletMenuItems = walletMenuItems,
                    lndBalanceSummary = lndBalanceSummary,
                    nwcBalanceSummary = nwcBalanceSummary,
                    coreLightningBalanceSummary = coreLightningBalanceSummary,
                    eclairBalanceSummary = eclairBalanceSummary,
                    sparkSubwalletBalanceSummary = sparkSubwalletBalanceSummary,
                    isStartingTorForActiveWallet = activeSpendWalletUsesTor &&
                        torBootstrapState is TorBootstrapState.Starting,
                    onSelectWalletMenuItem = { item ->
                        when (item.source) {
                            SpendWalletSource.SPARK -> rootViewModel.setSparkSpendWallet()
                            SpendWalletSource.LND -> item.walletId?.let(rootViewModel::setLndSpendWallet)
                            SpendWalletSource.NWC -> item.walletId?.let(rootViewModel::setNwcSpendWallet)
                            SpendWalletSource.CORE_LIGHTNING -> item.walletId?.let(rootViewModel::setCoreLightningSpendWallet)
                            SpendWalletSource.ECLAIR -> item.walletId?.let(rootViewModel::setEclairSpendWallet)
                            SpendWalletSource.SPARK_SUBWALLET -> item.walletId?.let(rootViewModel::setSparkSubwalletSpendWallet)
                        }
                    },
                    onAddWallet = openMainTabAddWallet,
                    onOpenContacts = openMainTabContacts,
                    onOpenProfile = openMainTabProfile,
                    onOpenQrScanner = openMainTabQrScanner,
                    onOpenMerchantMap = openMainTabMerchantMap,
                    loadBtcUsdPrice = { rootViewModel.fetchBtcUsdPrice() },
                    onStartCashAppBuyBitcoin = { amountSats ->
                        if (isLaunchingBuyBitcoin) return@WalletHomeScreen

                        scope.launch {
                            isLaunchingBuyBitcoin = true
                            runCatching {
                                rootViewModel.createCashAppBuyUrl(amountSats)
                            }.onSuccess { url ->
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(browserIntent)
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "Failed to start Cash App.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isLaunchingBuyBitcoin = false
                        }
                    },
                    onTapSend = { openSendOverlay(SendOverlayConfig()) },
                    onTapReceive = { activeOverlay = WalletOverlay.RECEIVE },
                    onTapTransactions = { activeOverlay = WalletOverlay.TRANSACTIONS },
                    unseenTransactionCount = displayedUnseenTransactionCount,
                    onRetryAuth = onRetryAuth,
                    onClearWallet = onClearWallet,
                    isLaunchingBuyBitcoin = isLaunchingBuyBitcoin,
                    modifier = Modifier.padding(paddingValues)
                )
                SplitDestination.REWARDS -> WalletRewardsScreen(
                    rootViewModel = rootViewModel,
                    onOpenContacts = openMainTabContacts,
                    onOpenProfile = openMainTabProfile,
                    onOpenMerchantMap = openMainTabMerchantMap,
                    modifier = Modifier.padding(paddingValues)
                )
                SplitDestination.PROMOS -> NearbyCouponsScreen(
                    rootViewModel = rootViewModel,
                    onDismiss = { destination = SplitDestination.WALLET },
                    showBackButton = false,
                    modifier = Modifier.padding(paddingValues)
                )
                SplitDestination.MESSAGES -> MessagesScreen(
                    rootViewModel = rootViewModel,
                    onOpenContacts = openMainTabContacts,
                    onOpenProfile = openMainTabProfile,
                    onOpenMerchantMap = openMainTabMerchantMap,
                    onOpenSendOverlay = openSendOverlay,
                    bottomInset = paddingValues.calculateBottomPadding(),
                    requestedComposeLightningAddress = requestedMessageComposeLightningAddress,
                    onConsumeRequestedCompose = { requestedMessageComposeLightningAddress = null },
                    requestedConversationId = requestedMessageThread?.conversationId,
                    requestedConversationTitle = requestedMessageThread?.title,
                    requestedConversationLightningAddress = requestedMessageThread?.lightningAddress,
                    onConsumeRequestedConversation = {
                        MessageNotificationRouter.consumeConversation(requestedMessageThread?.conversationId)
                        requestedMessageThread = null
                    },
                    requestedOpenContacts = requestedOpenContacts,
                    onConsumeRequestedOpenContacts = { requestedOpenContacts = false },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        sendOverlayConfig?.let { config ->
            WalletSendScreen(
                rootViewModel = rootViewModel,
                initialDestination = config.initialDestination,
                initialComment = config.initialComment,
                autoPrepareOnLaunch = config.autoPrepareOnLaunch,
                startInScanMode = config.startInScanMode,
                onDismiss = { sendOverlayConfig = null }
            )
        }

        when (activeOverlay) {
            WalletOverlay.RECEIVE -> WalletReceiveScreen(
                rootViewModel = rootViewModel,
                onDismiss = { activeOverlay = null }
            )

            WalletOverlay.TRANSACTIONS -> WalletTransactionsScreen(
                rootViewModel = rootViewModel,
                walletMenuItems = walletMenuItems,
                onSelectWalletMenuItem = { item ->
                    when (item.source) {
                        SpendWalletSource.SPARK -> rootViewModel.setSparkSpendWallet()
                        SpendWalletSource.LND -> item.walletId?.let(rootViewModel::setLndSpendWallet)
                        SpendWalletSource.NWC -> item.walletId?.let(rootViewModel::setNwcSpendWallet)
                        SpendWalletSource.CORE_LIGHTNING -> item.walletId?.let(rootViewModel::setCoreLightningSpendWallet)
                        SpendWalletSource.ECLAIR -> item.walletId?.let(rootViewModel::setEclairSpendWallet)
                        SpendWalletSource.SPARK_SUBWALLET -> item.walletId?.let(rootViewModel::setSparkSubwalletSpendWallet)
                    }
                },
                onDismiss = { activeOverlay = null }
            )

            WalletOverlay.MERCHANT_MAP -> BtcMerchantMapScreen(
                rootViewModel = rootViewModel,
                onDismiss = { activeOverlay = null }
            )

            WalletOverlay.PROFILE -> ProfileScreen(
                rootViewModel = rootViewModel,
                walletState = walletState,
                modifier = Modifier.fillMaxSize(),
                initialRoute = profileStartRoute,
                onClose = {
                    activeOverlay = null
                    profileStartRoute = ProfileStartRoute.HOME
                }
            ) { request ->
                activeOverlay = null
                profileStartRoute = ProfileStartRoute.HOME

                when (request) {
                    is SupportChatRequest.Compose -> {
                        requestedMessageComposeLightningAddress = request.lightningAddress
                        requestedMessageThread = null
                    }

                    is SupportChatRequest.OpenThread -> {
                        requestedMessageThread = RequestedMessageThread(
                            conversationId = request.conversationId,
                            title = request.title,
                            lightningAddress = request.lightningAddress
                        )
                        requestedMessageComposeLightningAddress = null
                    }
                }

                destination = SplitDestination.MESSAGES
            }

            null -> Unit
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    subtitle: String,
    summary: String,
    highlights: List<String>,
    accent: Color,
    modifier: Modifier = Modifier,
    authState: AuthState? = null,
    onRetryAuth: (() -> Unit)? = null,
    onClearWallet: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wallet,
                    contentDescription = null,
                    tint = accent
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF0D0D0F),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    highlights.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.84f)
                            )
                        }
                    }
                }
            }
        }

        if (authState != null && onRetryAuth != null && onClearWallet != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Color(0xFF111114)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Session Auth",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = when (authState) {
                            AuthState.Idle -> "Waiting to establish the wallet-backed cookie session."
                            AuthState.Authenticating -> "Authenticating against the Split backend."
                            AuthState.Authenticated -> "Session is valid and ready for authenticated API calls."
                            is AuthState.Failed -> authState.message
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (authState is AuthState.Failed) SplitBrandPink else Color.White.copy(alpha = 0.82f)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(onClick = onClearWallet) {
                            Text("Clear Wallet")
                        }

                        Button(onClick = onRetryAuth) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Retry Auth",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
