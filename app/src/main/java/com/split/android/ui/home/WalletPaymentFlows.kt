package com.split.android.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.split.android.data.wallet.PreparedOutgoingPayment
import com.split.android.data.wallet.ReceiveInvoice
import com.split.android.data.wallet.RemoteNodeTorTransport
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.TorBootstrapState
import com.split.android.data.wallet.WalletContact
import com.split.android.data.wallet.WalletState
import com.split.android.data.wallet.usesTor
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.qr.QrImageDecodeResult
import com.split.android.ui.qr.QrCodeCard
import com.split.android.ui.qr.SplitFullScreenQrScanner
import com.split.android.ui.qr.SplitContactPayload
import com.split.android.ui.qr.isAmountlessBolt11PaymentRequest
import com.split.android.ui.qr.normalizePaymentRequest
import com.split.android.ui.qr.readClipboardPaymentText
import com.split.android.ui.qr.readSingleQrCodeFromImageUri
import com.split.android.ui.qr.shouldOpenEntryFirstSendFlow
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

internal enum class SendAmountUnit {
    USD,
    SATS
}

private enum class WalletSendStage {
    Scan,
    Entry
}

@Composable
private fun SendContactPickerDialog(
    contacts: List<WalletContact>,
    onDismiss: () -> Unit,
    onSelect: (WalletContact) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101013),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.72f),
        title = { Text("Contacts") },
        text = {
            if (contacts.isEmpty()) {
                Text(
                    text = "No saved contacts yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    contacts.forEach { contact ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            onClick = { onSelect(contact) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(34.dp),
                                    shape = CircleShape,
                                    color = SplitBrandPink.copy(alpha = 0.24f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = contact.name.firstOrNull()?.uppercase().orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = contact.paymentIdentifier,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.58f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SplitBrandPink)
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
fun WalletSendScreen(
    rootViewModel: SplitRootViewModel,
    initialDestination: String? = null,
    initialComment: String? = null,
    autoPrepareOnLaunch: Boolean = false,
    startInScanMode: Boolean = false,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val walletState by rootViewModel.walletState.collectAsStateWithLifecycle()
    val activeSpendWallet by rootViewModel.activeSpendWallet.collectAsStateWithLifecycle()
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val connectedNwcWallet by rootViewModel.connectedNwcWallet.collectAsStateWithLifecycle()
    val connectedCoreLightningNode by rootViewModel.connectedCoreLightningNode.collectAsStateWithLifecycle()
    val connectedEclairNode by rootViewModel.connectedEclairNode.collectAsStateWithLifecycle()
    val torBootstrapState by RemoteNodeTorTransport.bootstrapState.collectAsStateWithLifecycle()
    val lndBalanceSummary by rootViewModel.lndBalanceSummary.collectAsStateWithLifecycle()
    val nwcBalanceSummary by rootViewModel.nwcBalanceSummary.collectAsStateWithLifecycle()
    val coreLightningBalanceSummary by rootViewModel.coreLightningBalanceSummary.collectAsStateWithLifecycle()
    val eclairBalanceSummary by rootViewModel.eclairBalanceSummary.collectAsStateWithLifecycle()
    val sparkSubwalletBalanceSummary by rootViewModel.sparkSubwalletBalanceSummary.collectAsStateWithLifecycle()
    val contactsByPaymentIdentifier by rootViewModel.contactsByPaymentIdentifier.collectAsStateWithLifecycle()
    val sparkBalanceSats = (walletState as? WalletState.Ready)?.balanceSats ?: 0L
    val walletBalanceSats = when (activeSpendWallet) {
        SpendWalletSource.SPARK -> sparkBalanceSats
        SpendWalletSource.LND -> lndBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.NWC -> nwcBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.CORE_LIGHTNING -> coreLightningBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.ECLAIR -> eclairBalanceSummary?.spendableSats ?: 0L
        SpendWalletSource.SPARK_SUBWALLET -> sparkSubwalletBalanceSummary?.spendableSats ?: 0L
    }
    val activeSpendWalletUsesTor = when (activeSpendWallet) {
        SpendWalletSource.LND -> connectedLndNode?.usesTor == true
        SpendWalletSource.NWC -> connectedNwcWallet?.usesTor == true
        SpendWalletSource.CORE_LIGHTNING -> connectedCoreLightningNode?.usesTor == true
        SpendWalletSource.ECLAIR -> connectedEclairNode?.usesTor == true
        SpendWalletSource.SPARK,
        SpendWalletSource.SPARK_SUBWALLET -> false
    }

    var destination by rememberSaveable(initialDestination) { mutableStateOf(initialDestination.orEmpty()) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var amountUnit by rememberSaveable { mutableStateOf(SendAmountUnit.USD) }
    var isSendMaxAmount by rememberSaveable { mutableStateOf(false) }
    var commentText by rememberSaveable(initialComment) { mutableStateOf(initialComment.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var preparedPayment by remember { mutableStateOf<PreparedOutgoingPayment?>(null) }
    var didAutoPrepare by rememberSaveable(initialDestination, autoPrepareOnLaunch) { mutableStateOf(false) }
    var stage by rememberSaveable(startInScanMode) {
        mutableStateOf(if (startInScanMode) WalletSendStage.Scan else WalletSendStage.Entry)
    }
    var shouldReturnToEntryAfterScan by rememberSaveable(startInScanMode) { mutableStateOf(false) }
    var scanStatusMessage by remember { mutableStateOf<String?>(null) }
    var scannedContactPayload by remember { mutableStateOf<SplitContactPayload?>(null) }
    var scannedContactName by rememberSaveable { mutableStateOf("") }
    var scannedContactError by remember { mutableStateOf<String?>(null) }
    var showContactPicker by remember { mutableStateOf(false) }
    var presetAmountSats by remember { mutableStateOf<Long?>(null) }
    var btcUsdRate by remember { mutableStateOf<Double?>(null) }
    var isLoadingBtcPrice by remember { mutableStateOf(true) }

    fun enteredAmountSatsOrNull(): Long? {
        return if (presetAmountSats != null) {
            null
        } else if (isSendMaxAmount) {
            walletBalanceSats.takeIf { it > 0L }
        } else {
            SendAmountCalculator.parseEnteredAmountSats(
                amountUnit = amountUnit,
                amountText = amountText,
                btcUsdRate = btcUsdRate
            )
        }
    }

    fun showEntryStage(withDestination: String? = null, resetAmount: Boolean = false) {
        withDestination?.let { destination = it }
        if (resetAmount) {
            amountText = ""
            presetAmountSats = null
            isSendMaxAmount = false
        }
        stage = WalletSendStage.Entry
    }

    fun launchPrepare(
        destinationOverride: String = destination,
        feesIncluded: Boolean = false,
        allowEntryFallback: Boolean = false
    ) {
        scope.launch {
            isPreparing = true
            errorMessage = null
            scanStatusMessage = null
            val amountSats = enteredAmountSatsOrNull()

            runCatching {
                rootViewModel.prepareOutgoingPayment(
                    destination = destinationOverride,
                    amountSats = amountSats,
                    feesIncluded = feesIncluded,
                    comment = resolvedCommentText(commentText)
                )
            }.onSuccess { prepared ->
                preparedPayment = rootViewModel.checkPreparedPaymentRewards(prepared)
            }.onFailure { error ->
                val message = sendFlowPreparationErrorMessage(error.message)
                if (allowEntryFallback && shouldFallbackToAmountEntry(message)) {
                    showEntryStage(withDestination = destinationOverride, resetAmount = true)
                } else if (stage == WalletSendStage.Scan) {
                    scanStatusMessage = message
                } else {
                    errorMessage = message
                }
            }
            isPreparing = false
        }
    }

    fun handleScanClose() {
        if (shouldReturnToEntryAfterScan) {
            scanStatusMessage = null
            shouldReturnToEntryAfterScan = false
            stage = WalletSendStage.Entry
        } else {
            onDismiss()
        }
    }

    fun openScannerFromEntry() {
        if (activeSpendWalletUsesTor && torBootstrapState !is TorBootstrapState.Ready) {
            RemoteNodeTorTransport.warmUp(context.applicationContext, scope)
            errorMessage = "Starting Tor..."
            return
        }
        scanStatusMessage = null
        errorMessage = null
        shouldReturnToEntryAfterScan = true
        stage = WalletSendStage.Scan
    }

    fun openScannedPaymentRequest(normalized: String) {
        scanStatusMessage = null
        errorMessage = null
        preparedPayment = null
        shouldReturnToEntryAfterScan = false
        destination = normalized

        if (shouldOpenEntryFirstSendFlow(normalized)) {
            showEntryStage(withDestination = normalized, resetAmount = true)
        } else {
            launchPrepare(
                destinationOverride = normalized,
                allowEntryFallback = true
            )
        }
    }

    fun showScannedContact(payload: SplitContactPayload) {
        scanStatusMessage = null
        scannedContactPayload = payload
        scannedContactName = payload.suggestedName
        scannedContactError = null
        shouldReturnToEntryAfterScan = false
        showEntryStage()
    }

    fun applySendMaxAmount(preferredUnit: SendAmountUnit = amountUnit) {
        if (walletBalanceSats <= 0L) {
            isSendMaxAmount = false
            amountText = ""
            errorMessage = "No spendable balance available."
            return
        }

        val currentBtcUsdRate = btcUsdRate
        val displayUnit = if (preferredUnit == SendAmountUnit.USD && (currentBtcUsdRate == null || currentBtcUsdRate <= 0.0)) {
            SendAmountUnit.SATS
        } else {
            preferredUnit
        }

        amountUnit = displayUnit
        amountText = SendAmountCalculator.sendMaxAmountDisplayText(
            amountUnit = displayUnit,
            balanceSats = walletBalanceSats,
            btcUsdRate = currentBtcUsdRate
        )
        isSendMaxAmount = true
        errorMessage = null
    }

    fun handleScannerInput(
        raw: String,
        invalidMessage: String,
        showInvalidInEntry: Boolean = false
    ) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return

        SplitContactPayload.parse(trimmed)?.let { payload ->
            showScannedContact(payload)
            return
        }

        val normalized = normalizePaymentRequest(trimmed)
        if (normalized.isNullOrBlank()) {
            if (showInvalidInEntry) {
                errorMessage = invalidMessage
            } else {
                scanStatusMessage = invalidMessage
            }
            return
        }

        openScannedPaymentRequest(normalized)
    }

    fun pasteFromClipboard() {
        errorMessage = null
        scanStatusMessage = null

        val clipboardText = readClipboardPaymentText(context)
        if (clipboardText.isNullOrBlank()) {
            errorMessage = "Clipboard is empty or doesn’t contain text."
            return
        }

        handleScannerInput(
            raw = clipboardText,
            invalidMessage = "Clipboard text doesn’t contain a supported payment or contact code.",
            showInvalidInEntry = true
        )
    }

    fun handleSelectedQrImage(uri: Uri) {
        scope.launch {
            errorMessage = null
            scanStatusMessage = null
            isPreparing = true

            val result = runCatching {
                readSingleQrCodeFromImageUri(context, uri)
            }.getOrElse {
                QrImageDecodeResult.ImageUnreadable
            }
            isPreparing = false

            when (result) {
                is QrImageDecodeResult.Success -> {
                    handleScannerInput(
                        raw = result.value,
                        invalidMessage = "Selected QR code image doesn’t contain a supported payment or contact code."
                    )
                }

                QrImageDecodeResult.NoQrCode -> {
                    scanStatusMessage = "No QR code found in this image."
                }

                QrImageDecodeResult.MultipleQrCodes -> {
                    scanStatusMessage = "Multiple QR codes found. Choose an image with one QR code."
                }

                QrImageDecodeResult.ImageUnreadable -> {
                    scanStatusMessage = "Couldn’t read the selected image."
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoadingBtcPrice = true
        btcUsdRate = runCatching { rootViewModel.fetchBtcUsdPrice() }
            .getOrNull()
            ?.takeIf { it > 0.0 }
        isLoadingBtcPrice = false
    }

    LaunchedEffect(destination) {
        val trimmedDestination = destination.trim()
        if (trimmedDestination.isEmpty()) {
            presetAmountSats = null
            return@LaunchedEffect
        }

        val preset = runCatching {
            rootViewModel.presetSendAmountSats(trimmedDestination)
        }.getOrNull()

        val hadLockedAmount = presetAmountSats != null
        presetAmountSats = preset?.takeIf { it > 0L }
        if (presetAmountSats != null) {
            isSendMaxAmount = false
            amountText = SendAmountCalculator.lockedAmountDisplayText(
                amountUnit = amountUnit,
                lockedAmountSats = presetAmountSats!!,
                btcUsdRate = btcUsdRate
            )
        } else if (hadLockedAmount) {
            isSendMaxAmount = false
            amountText = ""
        }
    }

    LaunchedEffect(Unit) {
        rootViewModel.loadContacts()
    }

    LaunchedEffect(amountUnit) {
        amountText = when {
            presetAmountSats != null -> {
                SendAmountCalculator.lockedAmountDisplayText(
                    amountUnit = amountUnit,
                    lockedAmountSats = presetAmountSats!!,
                    btcUsdRate = btcUsdRate
                )
            }

            isSendMaxAmount && walletBalanceSats > 0L -> {
                SendAmountCalculator.sendMaxAmountDisplayText(
                    amountUnit = amountUnit,
                    balanceSats = walletBalanceSats,
                    btcUsdRate = btcUsdRate
                )
            }

            else -> ""
        }
    }

    LaunchedEffect(presetAmountSats, btcUsdRate, walletBalanceSats, isSendMaxAmount) {
        if (presetAmountSats != null) {
            amountText = SendAmountCalculator.lockedAmountDisplayText(
                amountUnit = amountUnit,
                lockedAmountSats = presetAmountSats!!,
                btcUsdRate = btcUsdRate
            )
        } else if (isSendMaxAmount && walletBalanceSats > 0L) {
            amountText = SendAmountCalculator.sendMaxAmountDisplayText(
                amountUnit = amountUnit,
                balanceSats = walletBalanceSats,
                btcUsdRate = btcUsdRate
            )
        }
    }

    LaunchedEffect(initialDestination, autoPrepareOnLaunch, didAutoPrepare) {
        if (!autoPrepareOnLaunch ||
            didAutoPrepare ||
            destination.trim().isEmpty() ||
            preparedPayment != null
        ) {
            return@LaunchedEffect
        }

        didAutoPrepare = true
        isPreparing = true
        errorMessage = null
        val amountSats = enteredAmountSatsOrNull()

        runCatching {
            rootViewModel.prepareOutgoingPayment(
                destination = destination,
                amountSats = amountSats,
                feesIncluded = false,
                comment = resolvedCommentText(commentText)
            )
        }.onSuccess { prepared ->
            preparedPayment = rootViewModel.checkPreparedPaymentRewards(prepared)
        }.onFailure { error ->
            errorMessage = sendFlowPreparationErrorMessage(error.message)
        }

        isPreparing = false
    }

    val showingScanStage = preparedPayment == null &&
        stage == WalletSendStage.Scan
    val showingEntryStage = preparedPayment == null &&
        stage == WalletSendStage.Entry
    val showingReviewStage = preparedPayment != null
    val isAmountlessInvoiceEntry = isAmountlessBolt11PaymentRequest(destination)

    fun continueFromEntry() {
        errorMessage = null
        val trimmedDestination = destination.trim()
        if (trimmedDestination.isEmpty()) {
            errorMessage = "Enter a destination to send to."
            return
        }

        if (presetAmountSats == null && isSendMaxAmount) {
            if (walletBalanceSats <= 0L) {
                errorMessage = "No spendable balance available."
                return
            }

            launchPrepare(
                destinationOverride = trimmedDestination,
                feesIncluded = true
            )
            return
        }

        if (presetAmountSats == null) {
            val trimmedAmount = amountText.trim()
            if (trimmedAmount.isEmpty()) {
                errorMessage = "Enter an amount."
                return
            }

            val amountSats = enteredAmountSatsOrNull()
            if (amountSats == null || amountSats <= 0L) {
                errorMessage = if (amountUnit == SendAmountUnit.USD) {
                    "Couldn't convert USD to sats. Try again."
                } else {
                    "Enter a valid sats amount."
                }
                return
            }
        }

        launchPrepare(destinationOverride = trimmedDestination)
    }

    when {
        showingEntryStage -> {
            WalletSendEntryStage(
                destination = destination,
                onDestinationChange = {
                    destination = it
                    errorMessage = null
                },
                amountText = amountText,
                onAmountChange = { updated ->
                    isSendMaxAmount = false
                    amountText = SendAmountCalculator.sanitizeAmountInput(
                        amountUnit = amountUnit,
                        raw = updated
                    )
                    errorMessage = null
                },
                amountUnit = amountUnit,
                onSelectAmountUnit = { selectedUnit ->
                    if (isSendMaxAmount) {
                        applySendMaxAmount(selectedUnit)
                    } else {
                        amountUnit = selectedUnit
                    }
                },
                isSendMaxAmount = isSendMaxAmount,
                onSelectSendMax = { applySendMaxAmount() },
                isAmountLocked = presetAmountSats != null,
                isAmountlessInvoice = isAmountlessInvoiceEntry,
                isLoadingBtcPrice = isLoadingBtcPrice,
                btcUsdRate = btcUsdRate,
                errorMessage = errorMessage,
                isPreparing = isPreparing,
                onDismiss = onDismiss,
                onOpenScanner = ::openScannerFromEntry,
                onOpenContacts = {
                    rootViewModel.loadContacts()
                    showContactPicker = true
                },
                onPasteClipboard = ::pasteFromClipboard,
                onContinue = ::continueFromEntry
            )
        }

        showingScanStage -> WalletSendScanStage(
            isPreparing = isPreparing,
            scanStatusMessage = scanStatusMessage,
            onDismiss = ::handleScanClose,
            onCodeScanned = { raw ->
                handleScannerInput(
                    raw = raw,
                    invalidMessage = "Couldn't read a supported payment or contact QR."
                )
            },
            onImageSelected = ::handleSelectedQrImage
        )

        else -> FullScreenWalletFlowShell(
            title = when {
                showingReviewStage -> "Confirm Payment"
                else -> "Send Bitcoin"
            },
            subtitle = when {
                showingReviewStage -> ""
                else -> "Spark address, Lightning address, or invoice"
            },
            onDismiss = onDismiss,
            scrollable = !showingReviewStage
        ) {
            when {
                preparedPayment != null -> {
                    val preview = preparedPayment!!.preview

                    WalletSendReviewStage(
                        preview = preview,
                        btcUsdRate = btcUsdRate,
                        errorMessage = errorMessage,
                        isSending = isSending,
                        onSend = {
                            errorMessage = null
                            isSending = true
                            rootViewModel.submitPreparedPayment(preparedPayment!!)
                            onDismiss()
                        }
                    )
                }

                stage == WalletSendStage.Scan -> Unit
            }
        }
    }

    if (scannedContactPayload != null) {
        val payload = scannedContactPayload!!
        AlertDialog(
            onDismissRequest = {
                scannedContactPayload = null
                scannedContactError = null
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                rootViewModel.addWalletContact(
                                    name = scannedContactName,
                                    paymentIdentifier = payload.lightningAddress
                                )
                            }.onSuccess {
                                destination = payload.lightningAddress
                                stage = WalletSendStage.Entry
                                scannedContactPayload = null
                                scannedContactError = null
                                Toast.makeText(context, "Contact added.", Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                scannedContactError = error.message ?: "Failed to add contact."
                            }
                        }
                    },
                    enabled = scannedContactName.trim().isNotEmpty()
                ) {
                    Text("Add Contact")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        destination = payload.lightningAddress
                        stage = WalletSendStage.Entry
                        scannedContactPayload = null
                        scannedContactError = null
                    }
                ) {
                    Text("Use Address")
                }
            },
            title = { Text("Add Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(
                        value = scannedContactName,
                        onValueChange = { scannedContactName = it },
                        label = { Text("Name") }
                    )
                    Text(
                        text = payload.lightningAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    if (!scannedContactError.isNullOrBlank()) {
                        Text(
                            text = scannedContactError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = SplitBrandPink
                        )
                    }
                }
            }
        )
    }

    if (showContactPicker) {
        SendContactPickerDialog(
            contacts = contactsByPaymentIdentifier.values
                .sortedBy { it.name.lowercase(Locale.US) },
            onDismiss = { showContactPicker = false },
            onSelect = { contact ->
                destination = contact.paymentIdentifier
                errorMessage = null
                showContactPicker = false
            }
        )
    }
}

private fun resolvedCommentText(currentCommentText: String): String? {
    return currentCommentText.trim().ifBlank { null }
}

private fun sendFlowPreparationErrorMessage(rawMessage: String?): String {
    return rawMessage?.trim()?.ifBlank { null } ?: "Unable to prepare payment."
}

@Composable
private fun WalletSendEntryStage(
    destination: String,
    onDestinationChange: (String) -> Unit,
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountUnit: SendAmountUnit,
    onSelectAmountUnit: (SendAmountUnit) -> Unit,
    isSendMaxAmount: Boolean,
    onSelectSendMax: () -> Unit,
    isAmountLocked: Boolean,
    isAmountlessInvoice: Boolean,
    isLoadingBtcPrice: Boolean,
    btcUsdRate: Double?,
    errorMessage: String?,
    isPreparing: Boolean,
    onDismiss: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenContacts: () -> Unit,
    onPasteClipboard: () -> Unit,
    onContinue: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val canContinue = !isPreparing && destination.trim().isNotEmpty()
    val quickAmounts = remember(amountUnit) { SendAmountCalculator.quickAmounts(amountUnit) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                focusManager.clearFocus()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .padding(bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Send Bitcoin",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WalletSendOverlayCircleButton(
                        icon = SplitFeatureIcons.QrCodeScan,
                        contentDescription = "Scan QR",
                        onClick = onOpenScanner
                    )

                    WalletSendOverlayCircleButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Close send flow",
                        onClick = onDismiss
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Recipient address",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f)
                )

                Box {
                    TextField(
                        value = destination,
                        onValueChange = onDestinationChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(22.dp)
                            ),
                        placeholder = {
                            Text(
                                text = "Lightning address, invoice, Bitcoin address, or QR code image",
                                color = Color.White.copy(alpha = 0.24f)
                            )
                        },
                        minLines = 4,
                        maxLines = 7,
                        colors = walletSendOverlayFieldColors(),
                        shape = RoundedCornerShape(22.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 10.dp, bottom = 10.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onOpenContacts()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = SplitFeatureIcons.Contacts,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.84f),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Contacts",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.84f)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 10.dp, bottom = 10.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onPasteClipboard()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.84f),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Paste",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.84f)
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Amount",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (isAmountLocked) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = "Locked",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                TextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(22.dp)
                        ),
                    singleLine = true,
                    enabled = !isAmountLocked,
                    placeholder = {
                        Text(
                            text = if (amountUnit == SendAmountUnit.USD) "0.00" else "1000",
                            color = Color.White.copy(alpha = 0.20f)
                        )
                    },
                    prefix = if (amountUnit == SendAmountUnit.USD) {
                        {
                            Text(
                                text = "$",
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        null
                    },
                    suffix = if (amountUnit == SendAmountUnit.SATS) {
                        {
                            Text(
                                text = "sats",
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (amountUnit == SendAmountUnit.USD) {
                            KeyboardType.Decimal
                        } else {
                            KeyboardType.Number
                        }
                    ),
                    colors = walletSendOverlayFieldColors(),
                    shape = RoundedCornerShape(22.dp),
                    textStyle = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SendAmountUnitButton(
                        modifier = Modifier.weight(1f),
                        label = "USD",
                        selected = amountUnit == SendAmountUnit.USD,
                        onClick = { onSelectAmountUnit(SendAmountUnit.USD) },
                        enabled = !isAmountLocked
                    )
                    SendAmountUnitButton(
                        modifier = Modifier.weight(1f),
                        label = "Sats",
                        selected = amountUnit == SendAmountUnit.SATS,
                        onClick = { onSelectAmountUnit(SendAmountUnit.SATS) },
                        enabled = !isAmountLocked
                    )
                }

                if (!isAmountLocked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        quickAmounts.forEach { option ->
                            WalletSendQuickAmountChip(
                                modifier = Modifier.weight(1f),
                                label = option.label,
                                onClick = { onAmountChange(option.rawValue) }
                            )
                        }

                        WalletSendQuickAmountChip(
                            modifier = Modifier.weight(1f),
                            label = "Max",
                            selected = isSendMaxAmount,
                            accented = true,
                            enabled = !isPreparing,
                            onClick = onSelectSendMax
                        )
                    }
                }

                Text(
                    text = when {
                        isAmountLocked -> "This request includes a fixed amount."
                        isAmountlessInvoice -> "This blank Lightning invoice needs an amount before you can pay it."
                        isSendMaxAmount -> "Max uses your available balance. Fees are deducted from that amount."
                        amountUnit == SendAmountUnit.USD && isLoadingBtcPrice ->
                            "Loading the current BTC/USD rate..."
                        amountUnit == SendAmountUnit.USD && btcUsdRate == null ->
                            "Unable to load the current BTC/USD rate. Please try again."
                        else -> "Some recipients require you to enter an amount."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !isSendMaxAmount && amountUnit == SendAmountUnit.USD && !isLoadingBtcPrice && btcUsdRate == null ->
                            SplitBrandPink
                        else -> Color.White.copy(alpha = 0.62f)
                    }
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = SplitBrandPink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black)
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.06f))
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onContinue()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canContinue,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SplitBrandBlue,
                        contentColor = Color.White,
                        disabledContainerColor = SplitBrandBlue.copy(alpha = 0.38f),
                        disabledContentColor = Color.White.copy(alpha = 0.74f)
                    )
                ) {
                    if (isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Continue")
                        }
                    }
                }

                Text(
                    text = "Cancel",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(enabled = !isPreparing, onClick = onDismiss),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }

        if (isPreparing) {
            WalletPrepareLoadingOverlay()
        }
    }
}

@Composable
private fun WalletSendEntrySection(
    title: String,
    trailingContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            trailingContent?.invoke()
        }

        content()
    }
}

@Composable
private fun WalletSendFieldSurface(
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF17171B)
    ) {
        content()
    }
}

@Composable
private fun WalletSendHeaderCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    surfaceColor: Color = Color.Black.copy(alpha = 0.90f)
) {
    Surface(
        modifier = Modifier.size(36.dp),
        onClick = onClick,
        shape = CircleShape,
        color = surfaceColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun WalletSendOverlayCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp)
            )
            .background(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun walletSendFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = Color.White,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.78f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.35f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.35f),
    disabledPlaceholderColor = Color.White.copy(alpha = 0.25f)
)

@Composable
private fun walletSendOverlayFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.06f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
    disabledContainerColor = Color.White.copy(alpha = 0.06f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.60f),
    cursorColor = Color.White,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    focusedPlaceholderColor = Color.White.copy(alpha = 0.24f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.24f),
    disabledPlaceholderColor = Color.White.copy(alpha = 0.18f)
)

@Composable
private fun WalletSendQuickAmountChip(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean = false,
    accented: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = when {
                    selected -> Color.Transparent
                    accented -> SplitBrandBlue.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .background(
                color = if (selected) SplitBrandBlue else Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (accented && !selected) SplitBrandBlue else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WalletSendScanStage(
    isPreparing: Boolean,
    scanStatusMessage: String?,
    onCodeScanned: (String) -> Unit,
    onImageSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onImageSelected(uri)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SplitFullScreenQrScanner(
            onClose = onDismiss,
            onCodeScanned = onCodeScanned,
            isProcessing = isPreparing,
            onChooseImage = { imagePicker.launch("image/*") }
        )

        if (!scanStatusMessage.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Text(
                    text = scanStatusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun WalletSendReviewStage(
    preview: com.split.android.data.wallet.PaymentPreview,
    btcUsdRate: Double?,
    errorMessage: String?,
    isSending: Boolean,
    onSend: () -> Unit
) {
    val amountBtc = preview.amountSats.toDouble() / 100_000_000.0
    val amountUsd = satsToUsd(preview.amountSats, btcUsdRate)
    val feeUsd = preview.feeSats?.let { satsToUsd(it, btcUsdRate) }
    val totalSats = preview.amountSats + (preview.feeSats ?: 0L)
    val totalBtc = totalSats.toDouble() / 100_000_000.0
    val totalUsd = satsToUsd(totalSats, btcUsdRate)
    val recipientTitle = preview.methodLabel
    val recipientSubtitle = previewRecipientSubtitle(preview)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        WalletReviewInfoBlock {
            Text(
                text = "To",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f)
            )

            Text(
                text = recipientTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            recipientSubtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        WalletReviewInfoBlock {
            Text(
                text = "Amount",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.fillMaxWidth()
            )

            if (preview.amountSats > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = amountUsd?.let(::formatUsdCurrency) ?: "${formatBtc(amountBtc)} BTC",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = "${formatBtc(amountBtc)} BTC",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Amount set in invoice",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )

                    Text(
                        text = "The Lightning invoice you scanned includes the amount.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }
            }
        }

        WalletReviewInfoBlock {
            Text(
                text = "Details",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f)
            )

            WalletReviewDetailRow(
                label = "Payment",
                primaryValue = when {
                    amountUsd != null -> formatUsdCurrency(amountUsd)
                    preview.amountSats > 0 -> "${formatBtc(amountBtc)} BTC"
                    else -> "—"
                }
            )

            WalletReviewDetailRow(
                label = if (preview.feesIncluded) "Network fee (included)" else "Network fee",
                primaryValue = when {
                    feeUsd != null -> formatUsdCurrency(feeUsd)
                    preview.feeSats != null -> "Fee calculated on send"
                    else -> "—"
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f))
            )

            WalletReviewDetailRow(
                label = "Total",
                primaryValue = when {
                    totalUsd != null -> formatUsdCurrency(totalUsd)
                    totalSats > 0L -> "${formatBtc(totalBtc)} BTC"
                    else -> "—"
                },
                secondaryValue = if (totalSats > 0L) "${formatBtc(totalBtc)} BTC" else null,
                emphasized = true
            )
        }

        RewardsEligibilityPill(
            rewardEligible = preview.rewardEligible == true,
            modifier = Modifier.align(Alignment.Start)
        )

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSending
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Send")
            }
        }

        Text(
            text = "Payments can't be reversed. Double-check the recipient and amount before sending.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RewardsEligibilityPill(
    rewardEligible: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (rewardEligible) SplitBrandPink else Color.Black,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Text(
            text = if (rewardEligible) {
                "Reward payment."
            } else {
                "Payment not eligible for rewards."
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun WalletReviewInfoBlock(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun WalletReviewDetailRow(
    label: String,
    primaryValue: String,
    secondaryValue: String? = null,
    emphasized: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = primaryValue,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White
            )

            secondaryValue?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }
        }
    }
}

@Composable
private fun WalletPrepareLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )

                Text(
                    text = "Loading payment details...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun WalletReceiveScreen(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usdAmountText by rememberSaveable { mutableStateOf("") }
    var satsAmountText by rememberSaveable { mutableStateOf("") }
    var descriptionText by rememberSaveable { mutableStateOf("") }
    var isAmountlessInvoice by rememberSaveable { mutableStateOf(false) }
    var invoiceInfo by remember { mutableStateOf<ReceiveInvoicePresentation?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var btcUsdRate by remember { mutableStateOf<Double?>(null) }
    var isLoadingBtcPrice by remember { mutableStateOf(true) }
    var isProgrammaticAmountUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoadingBtcPrice = true
        btcUsdRate = runCatching { rootViewModel.fetchBtcUsdPrice() }
            .getOrNull()
            ?.takeIf { it > 0.0 }
        isLoadingBtcPrice = false
    }

    FullScreenWalletFlowShell(
        title = if (invoiceInfo != null) "Receive Bitcoin" else "Request Bitcoin",
        subtitle = if (invoiceInfo != null) "" else "Generate a Lightning invoice",
        onDismiss = onDismiss,
        scrollable = invoiceInfo == null
    ) {
        if (invoiceInfo != null) {
            WalletReceiveInvoiceStage(
                invoiceInfo = invoiceInfo!!,
                onCopyInvoice = {
                    copyPlainText(
                        context = context,
                        label = "Lightning Invoice",
                        value = invoiceInfo!!.invoice.invoice,
                        toast = "Invoice copied."
                    )
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                    if (!isAmountlessInvoice) {
                        ReceiveFieldLabel("Amount in USD")

                        ReceiveRoundedTextField(
                            value = usdAmountText,
                            onValueChange = { updated ->
                                val sanitized = ReceiveAmountCalculator.sanitizeUsdInput(updated)
                                usdAmountText = sanitized
                                if (isProgrammaticAmountUpdate) return@ReceiveRoundedTextField

                                isProgrammaticAmountUpdate = true
                                satsAmountText = ReceiveAmountCalculator.satsTextFromUsdInput(
                                    usdInput = sanitized,
                                    btcUsdRate = btcUsdRate
                                ).orEmpty()
                                isProgrammaticAmountUpdate = false
                            },
                            placeholder = "0.00",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            ),
                            prefix = {
                                Text(
                                    text = "$",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReceiveFieldLabel("Sats amount")

                            ReceiveRoundedTextField(
                                value = satsAmountText,
                                onValueChange = { updated ->
                                    val sanitized = ReceiveAmountCalculator.sanitizeSatsInput(updated)
                                    satsAmountText = sanitized
                                    if (isProgrammaticAmountUpdate) return@ReceiveRoundedTextField

                                    isProgrammaticAmountUpdate = true
                                    usdAmountText = ReceiveAmountCalculator.usdTextFromSatsInput(
                                        satsInput = sanitized,
                                        btcUsdRate = btcUsdRate
                                    ).orEmpty()
                                    isProgrammaticAmountUpdate = false
                                },
                                placeholder = "1000",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }

                        if (isLoadingBtcPrice) {
                            Text(
                                text = "Loading the current BTC/USD rate...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.64f)
                            )
                        } else if (btcUsdRate == null) {
                            Text(
                                text = "Unable to load the current BTC/USD rate. Please try again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SplitBrandPink
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReceiveFieldLabel("Description (optional)")

                        ReceiveRoundedTextField(
                            value = descriptionText,
                            onValueChange = { descriptionText = it.take(80) },
                            placeholder = "Optional",
                            keyboardOptions = KeyboardOptions.Default,
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Text(
                            text = "Keep it short. This text is embedded in the invoice and may be visible to the sender.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.62f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(10.dp)
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        onClick = {
                            isAmountlessInvoice = !isAmountlessInvoice
                            if (isAmountlessInvoice) {
                                usdAmountText = ""
                                satsAmountText = ""
                                errorMessage = null
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Create a blank Lightning invoice",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isAmountlessInvoice) {
                                            SplitBrandBlue
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = if (isAmountlessInvoice) {
                                            SplitBrandBlue
                                        } else {
                                            Color.White.copy(alpha = 0.45f)
                                        },
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isAmountlessInvoice) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = SplitBrandPink,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isGenerating = true
                                errorMessage = null
                                val amountSats = if (isAmountlessInvoice) {
                                    null
                                } else {
                                    ReceiveAmountCalculator.parseAmountSats(satsAmountText)
                                }
                                val amountUsd = if (isAmountlessInvoice) {
                                    null
                                } else {
                                    ReceiveAmountCalculator.parseAmountUsd(usdAmountText)
                                }
                                runCatching {
                                    rootViewModel.createBolt11Invoice(
                                        amountSats = amountSats,
                                        description = descriptionText
                                            .trim()
                                            .ifBlank { "Split payment" }
                                    )
                                }.onSuccess { invoice ->
                                    invoiceInfo = ReceiveInvoicePresentation(
                                        invoice = invoice,
                                        amountUsd = amountUsd,
                                        amountSats = amountSats ?: 0L
                                    )
                                }.onFailure { error ->
                                    errorMessage = error.message ?: "Unable to generate invoice."
                                }
                                isGenerating = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = if (isAmountlessInvoice) {
                            !isGenerating
                        } else {
                            !isGenerating &&
                            !isLoadingBtcPrice &&
                            btcUsdRate != null &&
                            (ReceiveAmountCalculator.parseAmountUsd(usdAmountText) ?: 0.0) > 0.0 &&
                            (ReceiveAmountCalculator.parseAmountSats(satsAmountText) ?: 0L) > 0L
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            disabledContainerColor = Color.White.copy(alpha = 0.30f),
                            disabledContentColor = Color.Black.copy(alpha = 0.52f)
                        )
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Confirm")
                        }
                    }
                }
        }
    }
}

@Composable
private fun ReceiveFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.62f)
    )
}

@Composable
private fun ReceiveRoundedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Color.White,
        fontWeight = FontWeight.Medium
    ),
    prefix: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(14.dp)
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = shape
            ),
        placeholder = {
            Text(
                text = placeholder,
                color = Color.White.copy(alpha = 0.28f)
            )
        },
        prefix = prefix,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        colors = receiveFieldColors(),
        shape = shape,
        textStyle = textStyle
    )
}

@Composable
private fun receiveFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.06f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
    disabledContainerColor = Color.White.copy(alpha = 0.06f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.62f),
    cursorColor = Color.White,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    focusedPlaceholderColor = Color.White.copy(alpha = 0.28f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.28f),
    disabledPlaceholderColor = Color.White.copy(alpha = 0.20f),
    focusedPrefixColor = Color.White,
    unfocusedPrefixColor = Color.White,
    disabledPrefixColor = Color.White.copy(alpha = 0.62f)
)

private data class ReceiveInvoicePresentation(
    val invoice: ReceiveInvoice,
    val amountUsd: Double?,
    val amountSats: Long
)

@Composable
private fun WalletReceiveInvoiceStage(
    invoiceInfo: ReceiveInvoicePresentation,
    onCopyInvoice: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(16.dp)
        ) {
            QrCodeCard(
                qrString = invoiceInfo.invoice.invoice,
                size = 220
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WalletReceiveDetailRow(
                    label = "Amount",
                    value = invoiceInfo.amountUsd?.let(ReceiveAmountCalculator::formatUsdDisplay)
                        ?: "Payer chooses"
                )
                WalletReceiveDetailRow(
                    label = "Sats",
                    value = invoiceInfo.amountSats.takeIf { it > 0L }?.let(::formatSats)
                        ?: "Payer chooses"
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onCopyInvoice,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Invoice")
        }
    }
}

@Composable
private fun WalletReceiveDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f)
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

@Composable
private fun SendAmountUnitButton(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = when {
            selected && enabled -> Color.White
            selected -> Color.White.copy(alpha = 0.34f)
            else -> Color.White.copy(alpha = 0.04f)
        },
        border = BorderStroke(
            1.dp,
            if (selected && enabled) Color.White else Color.White.copy(alpha = 0.14f)
        )
    ) {
        Box(
            modifier = Modifier.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected && enabled) Color.Black else Color.White.copy(alpha = if (enabled) 0.95f else 0.60f),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun FullScreenWalletFlowShell(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        WalletFlowBackgroundAtmosphere()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .then(
                    if (scrollable) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            WalletFlowHeader(
                title = title,
                subtitle = subtitle,
                onDismiss = onDismiss
            )

            content()
        }
    }
}

@Composable
private fun WalletFlowCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun PaymentReviewRow(
    title: String,
    value: String,
    emphasize: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f)
        )
        Text(
            text = value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = Color.White,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun WalletFlowHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            onClick = onDismiss
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Close flow",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Column(
            modifier = Modifier.padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.70f)
                )
            }
        }
    }
}

@Composable
private fun WalletFlowBackgroundAtmosphere() {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            SplitBrandPink.copy(alpha = 0.16f),
                            SplitBrandBlue.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

private fun formatSats(amountSats: Long): String {
    val formatter = NumberFormat.getIntegerInstance(Locale.US)
    return "${formatter.format(amountSats)} sats"
}

private fun shouldFallbackToAmountEntry(message: String): Boolean {
    return message.contains("Enter an amount", ignoreCase = true)
}

private fun previewRecipientSubtitle(
    preview: com.split.android.data.wallet.PaymentPreview
): String? {
    val destination = preview.destination.trim().takeIf { it.isNotEmpty() } ?: return null
    return when (preview.methodLabel) {
        "Lightning invoice",
        "Spark invoice" -> null
        else -> destination
    }
}

private fun satsToUsd(
    amountSats: Long,
    btcUsdRate: Double?
): Double? {
    if (amountSats <= 0L || btcUsdRate == null || btcUsdRate <= 0.0) {
        return null
    }

    return (amountSats.toDouble() / 100_000_000.0) * btcUsdRate
}

private fun formatUsdCurrency(value: Double): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.US)
    formatter.maximumFractionDigits = 2
    formatter.minimumFractionDigits = 2
    return formatter.format(value)
}

private fun formatBtc(amountBtc: Double): String {
    return String.format(Locale.US, "%.8f", amountBtc)
}

internal object ReceiveAmountCalculator {

    fun sanitizeUsdInput(raw: String): String = sanitizeDecimalInput(raw, maxFractionDigits = 2)

    fun sanitizeBtcInput(raw: String): String = sanitizeDecimalInput(raw, maxFractionDigits = 8)

    fun sanitizeSatsInput(raw: String): String = raw.filter { it.isDigit() }.trimStart('0')

    fun parseAmountUsd(raw: String): Double? = raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun parseAmountBtc(raw: String): Double? = raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun parseAmountSats(raw: String): Long? {
        return raw
            .trim()
            .replace(",", "")
            .takeIf { it.isNotEmpty() }
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun satsTextFromUsdInput(
        usdInput: String,
        btcUsdRate: Double?
    ): String? {
        val usdAmount = parseAmountUsd(usdInput) ?: return null
        val amountSats = satsFromUsd(usdAmount, btcUsdRate) ?: return null
        return amountSats.toString()
    }

    fun usdTextFromSatsInput(
        satsInput: String,
        btcUsdRate: Double?
    ): String? {
        val amountSats = parseAmountSats(satsInput) ?: return null
        val usdAmount = usdFromSats(amountSats, btcUsdRate) ?: return null
        return formatUsdInput(usdAmount)
    }

    fun satsFromUsdInput(
        usdInput: String,
        btcUsdRate: Double?
    ): Long? {
        val usdAmount = parseAmountUsd(usdInput) ?: return null
        return satsFromUsd(usdAmount, btcUsdRate)
    }

    fun btcTextFromUsdInput(
        usdInput: String,
        btcUsdRate: Double?
    ): String? {
        val usdAmount = parseAmountUsd(usdInput) ?: return null
        val btcAmount = btcFromUsd(usdAmount, btcUsdRate) ?: return null
        return formatBtcDisplay(btcAmount)
    }

    fun usdTextFromBtcInput(
        btcInput: String,
        btcUsdRate: Double?
    ): String? {
        val btcAmount = parseAmountBtc(btcInput) ?: return null
        val usdAmount = usdFromBtc(btcAmount, btcUsdRate) ?: return null
        return formatUsdInput(usdAmount)
    }

    fun satsFromBtcInput(btcInput: String): Long? {
        val btcAmount = parseAmountBtc(btcInput) ?: return null
        if (btcAmount <= 0.0) return null
        return (btcAmount * 100_000_000.0).roundToLong()
    }

    fun formatBtcDisplay(amountBtc: Double): String {
        return String.format(Locale.US, "%.8f", amountBtc)
    }

    fun formatUsdDisplay(amountUsd: Double): String {
        return String.format(Locale.US, "$%.2f", amountUsd)
    }

    private fun formatUsdInput(amountUsd: Double): String {
        return String.format(Locale.US, "%.2f", amountUsd)
    }

    private fun btcFromUsd(
        usdAmount: Double,
        btcUsdRate: Double?
    ): Double? {
        if (usdAmount <= 0.0 || btcUsdRate == null || btcUsdRate <= 0.0) {
            return null
        }
        return usdAmount / btcUsdRate
    }

    private fun usdFromBtc(
        btcAmount: Double,
        btcUsdRate: Double?
    ): Double? {
        if (btcAmount <= 0.0 || btcUsdRate == null || btcUsdRate <= 0.0) {
            return null
        }
        return btcAmount * btcUsdRate
    }

    private fun satsFromUsd(
        usdAmount: Double,
        btcUsdRate: Double?
    ): Long? {
        if (usdAmount <= 0.0 || btcUsdRate == null || btcUsdRate <= 0.0) {
            return null
        }

        return ((usdAmount / btcUsdRate) * 100_000_000.0)
            .roundToLong()
            .takeIf { it > 0L }
    }

    private fun usdFromSats(
        amountSats: Long,
        btcUsdRate: Double?
    ): Double? {
        if (amountSats <= 0L || btcUsdRate == null || btcUsdRate <= 0.0) {
            return null
        }

        return (amountSats.toDouble() / 100_000_000.0) * btcUsdRate
    }

    private fun sanitizeDecimalInput(
        raw: String,
        maxFractionDigits: Int
    ): String {
        val filtered = raw.filter { it.isDigit() || it == '.' }
        if (filtered.isBlank()) return ""

        val firstDecimalIndex = filtered.indexOf('.')
        if (firstDecimalIndex == -1) {
            return filtered
        }

        val integerPart = filtered.substring(0, firstDecimalIndex)
        val fractionalPart = filtered
            .substring(firstDecimalIndex + 1)
            .replace(".", "")
            .take(maxFractionDigits)

        return buildString {
            append(integerPart)
            append('.')
            append(fractionalPart)
        }
    }
}

internal object SendAmountCalculator {

    data class QuickAmount(
        val label: String,
        val rawValue: String
    )

    fun quickAmounts(amountUnit: SendAmountUnit): List<QuickAmount> {
        return when (amountUnit) {
            SendAmountUnit.USD -> listOf(
                QuickAmount(label = "$5", rawValue = "5"),
                QuickAmount(label = "$10", rawValue = "10"),
                QuickAmount(label = "$25", rawValue = "25")
            )

            SendAmountUnit.SATS -> listOf(
                QuickAmount(label = "1k sats", rawValue = "1000"),
                QuickAmount(label = "10k sats", rawValue = "10000"),
                QuickAmount(label = "50k sats", rawValue = "50000")
            )
        }
    }

    fun sanitizeAmountInput(
        amountUnit: SendAmountUnit,
        raw: String
    ): String {
        return when (amountUnit) {
            SendAmountUnit.USD -> ReceiveAmountCalculator.sanitizeUsdInput(raw)
            SendAmountUnit.SATS -> raw.filter { it.isDigit() }.trimStart('0')
        }
    }

    fun parseEnteredAmountSats(
        amountUnit: SendAmountUnit,
        amountText: String,
        btcUsdRate: Double?
    ): Long? {
        return when (amountUnit) {
            SendAmountUnit.SATS -> amountText
                .trim()
                .replace(",", "")
                .toLongOrNull()
                ?.takeIf { it > 0L }

            SendAmountUnit.USD -> ReceiveAmountCalculator.satsFromUsdInput(
                usdInput = amountText,
                btcUsdRate = btcUsdRate
            )
        }
    }

    fun lockedAmountDisplayText(
        amountUnit: SendAmountUnit,
        lockedAmountSats: Long,
        btcUsdRate: Double?
    ): String {
        val btcAmount = lockedAmountSats.toDouble() / 100_000_000.0
        return when (amountUnit) {
            SendAmountUnit.SATS -> lockedAmountSats.toString()

            SendAmountUnit.USD -> {
                if (btcUsdRate == null || btcUsdRate <= 0.0) {
                    lockedAmountSats.toString()
                } else {
                    String.format(Locale.US, "%.2f", btcAmount * btcUsdRate)
                }
            }
        }
    }

    fun sendMaxAmountDisplayText(
        amountUnit: SendAmountUnit,
        balanceSats: Long,
        btcUsdRate: Double?
    ): String {
        val btcAmount = balanceSats.toDouble() / 100_000_000.0
        return when (amountUnit) {
            SendAmountUnit.SATS -> balanceSats.toString()

            SendAmountUnit.USD -> {
                if (btcUsdRate == null || btcUsdRate <= 0.0) {
                    ""
                } else {
                    String.format(Locale.US, "%.2f", btcAmount * btcUsdRate)
                }
            }
        }
    }
}

private fun copyPlainText(
    context: Context,
    label: String,
    value: String,
    toast: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
