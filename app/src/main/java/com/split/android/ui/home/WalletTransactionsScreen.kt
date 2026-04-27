package com.split.android.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.split.android.ui.SplitFeatureIcons
import com.split.android.data.wallet.TransactionActivityTracker
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.WalletTransactionRow
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WalletTransactionsScreen(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val transactionActivityTracker = remember(context) {
        TransactionActivityTracker.getInstance(context)
    }
    val walletEventVersion by rootViewModel.walletEventVersion.collectAsStateWithLifecycle()
    val activeSpendWallet by rootViewModel.activeSpendWallet.collectAsStateWithLifecycle()
    val connectedLndNode by rootViewModel.connectedLndNode.collectAsStateWithLifecycle()
    val hasStoredNode = connectedLndNode != null || rootViewModel.hasStoredLndNode()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var transactions by remember { mutableStateOf<List<WalletTransactionRow>>(emptyList()) }
    var selectedTransactionSource by remember { mutableStateOf(activeSpendWallet) }
    var displayedTransactionSource by remember { mutableStateOf(activeSpendWallet) }
    var didSeedInitialTransactionSource by remember { mutableStateOf(false) }
    var highlightedTransactionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTransaction by remember { mutableStateOf<WalletTransactionRow?>(null) }
    var merchantReportTransaction by remember { mutableStateOf<WalletTransactionRow?>(null) }
    var reportableTransaction by remember { mutableStateOf<WalletTransactionRow?>(null) }
    var reportableTransactionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isPresentingExport by remember { mutableStateOf(false) }
    var latestLoadRequestId by remember { mutableStateOf(0L) }

    LaunchedEffect(activeSpendWallet, hasStoredNode, didSeedInitialTransactionSource) {
        if (!didSeedInitialTransactionSource) {
            selectedTransactionSource = if (activeSpendWallet == SpendWalletSource.LND && hasStoredNode) {
                SpendWalletSource.LND
            } else {
                SpendWalletSource.SPARK
            }
            displayedTransactionSource = selectedTransactionSource
            didSeedInitialTransactionSource = true
        }
    }

    LaunchedEffect(walletEventVersion, selectedTransactionSource, hasStoredNode, didSeedInitialTransactionSource) {
        if (!didSeedInitialTransactionSource) return@LaunchedEffect
        if (selectedTransactionSource == SpendWalletSource.LND && !hasStoredNode) {
            selectedTransactionSource = SpendWalletSource.SPARK
            displayedTransactionSource = SpendWalletSource.SPARK
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null
        val requestedSource = selectedTransactionSource
        latestLoadRequestId += 1L
        val loadRequestId = latestLoadRequestId

        try {
            val rows = rootViewModel.fetchTransactionRows(source = requestedSource)
            if (latestLoadRequestId != loadRequestId) return@LaunchedEffect

            transactions = rows
            displayedTransactionSource = requestedSource
            val activityScope = rootViewModel.transactionActivityScope(requestedSource)
            transactionActivityTracker.reconcile(rows, scope = activityScope)
            highlightedTransactionIds = highlightedTransactionIds +
                transactionActivityTracker.captureVisibleUnseenAndMarkSeen(rows, scope = activityScope)

            runCatching {
                rootViewModel.transactionReportableStates(
                    paymentIds = rows.map { it.id },
                    source = requestedSource
                )
            }.onSuccess { reportableStates ->
                if (latestLoadRequestId == loadRequestId) {
                    reportableTransactionIds = reportableStates
                        .filterValues { it }
                        .keys
                        .toSet()
                }
            }.onFailure {
                if (latestLoadRequestId == loadRequestId) {
                    reportableTransactionIds = emptySet()
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (latestLoadRequestId == loadRequestId) {
                errorMessage = error.message ?: "Failed to load transactions."
            }
        } finally {
            if (latestLoadRequestId == loadRequestId) {
                isLoading = false
            }
        }
    }

    if (merchantReportTransaction != null) {
        MerchantReportScreen(
            rootViewModel = rootViewModel,
            transaction = merchantReportTransaction!!,
            onDismiss = { merchantReportTransaction = null },
            onSubmitted = {
                merchantReportTransaction = null
                Toast.makeText(context, "Merchant submitted.", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    val activeReportableTransaction = reportableTransaction
    if (activeReportableTransaction != null) {
        TransactionReportableScreen(
            rootViewModel = rootViewModel,
            transaction = activeReportableTransaction,
            transactionSource = displayedTransactionSource,
            isInitiallyReportable = activeReportableTransaction.id in reportableTransactionIds,
            onDismiss = { reportableTransaction = null },
            onStatusChanged = { isReportable ->
                reportableTransactionIds = if (isReportable) {
                    reportableTransactionIds + activeReportableTransaction.id
                } else {
                    reportableTransactionIds - activeReportableTransaction.id
                }
            }
        )
        return
    }

    if (selectedTransaction != null) {
        WalletTransactionDetailScreen(
            transaction = selectedTransaction!!,
            onBack = { selectedTransaction = null }
        )
        return
    }

    if (isPresentingExport) {
        TransactionExportScreen(
            rootViewModel = rootViewModel,
            transactions = transactions,
            transactionSource = displayedTransactionSource,
            onDismiss = { isPresentingExport = false }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TransactionsBackgroundAtmosphere()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TransactionScreenHeader(
                title = "Transactions",
                subtitle = "Tap a transaction to see more.",
                onBack = onDismiss,
                actions = {
                    TransactionExportButton(
                        enabled = transactions.isNotEmpty(),
                        onClick = { isPresentingExport = true }
                    )
                }
            )

            if (hasStoredNode) {
                TransactionWalletToggle(
                    selectedSource = selectedTransactionSource,
                    onSelectSource = { source ->
                        if (source == selectedTransactionSource) return@TransactionWalletToggle
                        if (source == SpendWalletSource.LND && !hasStoredNode) return@TransactionWalletToggle

                        selectedTransactionSource = source
                        transactions = emptyList()
                        highlightedTransactionIds = emptySet()
                        reportableTransactionIds = emptySet()
                        errorMessage = null
                    }
                )
            }

            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SplitBrandBlue)
                }

                !errorMessage.isNullOrBlank() -> WalletTransactionsMessageCard(
                    title = "Unable to load transactions",
                    body = errorMessage!!
                )

                transactions.isEmpty() -> WalletTransactionsMessageCard(
                    title = "No transactions yet",
                    body = if (displayedTransactionSource == SpendWalletSource.LND) {
                        "Once your connected node sends or receives payments, that transaction history will show up here."
                    } else {
                        "Once this Android wallet starts sending or receiving payments, your transaction history will show up here."
                    }
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(transactions, key = { it.id }) { transaction ->
                        WalletTransactionCard(
                            transaction = transaction,
                            isHighlighted = transaction.id in highlightedTransactionIds,
                            isReportable = transaction.id in reportableTransactionIds,
                            canManageReportableStatus = transaction.direction == "sent" &&
                                transaction.status == "Completed",
                            canReportMerchant = transaction.direction == "sent" &&
                                transaction.status == "Completed" &&
                                transaction.network.equals("lightning", ignoreCase = true) &&
                                !transaction.destinationPubkey.isNullOrBlank(),
                            onReportMerchant = { merchantReportTransaction = transaction },
                            onManageReportability = { reportableTransaction = transaction },
                            onOpenDetails = { selectedTransaction = transaction },
                            onSaveUserLog = { userLog ->
                                rootViewModel.setTransactionUserLog(
                                    paymentId = transaction.id,
                                    direction = transaction.direction,
                                    userLog = userLog,
                                    source = displayedTransactionSource
                                )
                                transactions = transactions.map { row ->
                                    if (row.id == transaction.id) row.withUserLog(userLog) else row
                                }
                                if (selectedTransaction?.id == transaction.id) {
                                    selectedTransaction = selectedTransaction?.withUserLog(userLog)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WalletTransactionsMessageCard(
    title: String,
    body: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun TransactionWalletToggle(
    selectedSource: SpendWalletSource,
    onSelectSource: (SpendWalletSource) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TransactionWalletToggleItem(
                label = "SPARK",
                selected = selectedSource == SpendWalletSource.SPARK,
                onClick = { onSelectSource(SpendWalletSource.SPARK) },
                modifier = Modifier.weight(1f)
            )
            TransactionWalletToggleItem(
                label = "Node",
                selected = selectedSource == SpendWalletSource.LND,
                onClick = { onSelectSource(SpendWalletSource.LND) },
                modifier = Modifier.weight(1f),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = if (selectedSource == SpendWalletSource.LND) Color.Black else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun TransactionWalletToggleItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color.White else Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = if (selected) Color.Black else Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WalletTransactionCard(
    transaction: WalletTransactionRow,
    isHighlighted: Boolean,
    isReportable: Boolean,
    canManageReportableStatus: Boolean,
    canReportMerchant: Boolean,
    onReportMerchant: () -> Unit,
    onManageReportability: () -> Unit,
    onOpenDetails: () -> Unit,
    onSaveUserLog: suspend (String?) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(
            1.dp,
            if (isHighlighted) SplitBrandBlue.copy(alpha = 0.78f)
            else Color.White.copy(alpha = 0.06f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.clickable(onClick = onOpenDetails),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = transaction.direction.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        sentLightningAddressLine(transaction)?.let { sentLine ->
                            Text(
                                text = sentLine,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = signedAmountText(transaction),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }

                    if (canReportMerchant || canManageReportableStatus) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (canReportMerchant) {
                                MerchantReportIconButton(
                                    onClick = onReportMerchant
                                )
                            }
                            if (canManageReportableStatus) {
                                ReportableStatusIconButton(
                                    isReportable = isReportable,
                                    onClick = onManageReportability
                                )
                            }
                        }
                    }
                }

                TransactionInfoRow("Status", transaction.status)
                TransactionInfoRow("Network", transaction.network.replaceFirstChar { it.uppercase() })
                TransactionInfoRow("Date", transaction.dateString)

                val cardNote = transactionCardNote(transaction)
                if (cardNote.isNotEmpty()) {
                    TransactionInfoRow("Memo", cardNote)
                }

                if (transaction.feeSats > 0) {
                    TransactionInfoRow("Fee (BTC)", transaction.feeBtcAmount)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.38f)
                    )
                }
            }

            TransactionUserLogField(
                userLog = transaction.userLog,
                onSaveUserLog = onSaveUserLog
            )
        }
    }
}

@Composable
private fun MerchantReportIconButton(
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .padding(1.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(9.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {}

            Icon(
                imageVector = SplitFeatureIcons.Store,
                contentDescription = "Add merchant for rewards",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TransactionUserLogField(
    userLog: String?,
    onSaveUserLog: suspend (String?) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var userLogText by rememberSaveable { mutableStateOf(normalizedUserLogText(userLog).orEmpty()) }
    var savedUserLog by remember { mutableStateOf(normalizedUserLogText(userLog)) }
    var userLogError by remember { mutableStateOf<String?>(null) }
    var isSavingUserLog by remember { mutableStateOf(false) }
    var isUserLogFocused by remember { mutableStateOf(false) }
    var isDismissingViaButton by remember { mutableStateOf(false) }

    val normalizedUserLog = normalizedUserLogText(userLogText)
    val canSaveUserLog = !isSavingUserLog && normalizedUserLog != savedUserLog
    val canTapUserLogButton = !isSavingUserLog && (canSaveUserLog || isUserLogFocused)

    fun persistUserLog(dismissField: Boolean) {
        if (!canSaveUserLog) {
            if (dismissField) {
                focusManager.clearFocus()
            }
            return
        }

        val nextUserLog = normalizedUserLog
        isSavingUserLog = true
        userLogError = null

        if (dismissField) {
            isDismissingViaButton = true
            focusManager.clearFocus()
        }

        scope.launch {
            runCatching {
                onSaveUserLog(nextUserLog)
            }.onSuccess {
                savedUserLog = nextUserLog
                userLogText = nextUserLog.orEmpty()
            }.onFailure { error ->
                userLogError = error.message ?: "Unable to save this log on device."
            }

            isSavingUserLog = false
            isDismissingViaButton = false
        }
    }

    LaunchedEffect(userLog) {
        val normalized = normalizedUserLogText(userLog)
        savedUserLog = normalized
        if (!isUserLogFocused) {
            userLogText = normalized.orEmpty()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = userLogText,
                onValueChange = {
                    userLogText = it
                    userLogError = null
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        val wasFocused = isUserLogFocused
                        isUserLogFocused = focusState.isFocused

                        if (wasFocused &&
                            !focusState.isFocused &&
                            !isDismissingViaButton &&
                            canSaveUserLog
                        ) {
                            persistUserLog(dismissField = false)
                        }
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        persistUserLog(dismissField = true)
                    }
                ),
                minLines = 1,
                maxLines = 3,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color.White.copy(alpha = 0.07f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (userLogText.isBlank() && !isUserLogFocused) {
                            Text(
                                text = "Logs are only stored on device.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.35f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        innerTextField()
                    }
                }
            )

            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clickable(
                        enabled = canTapUserLogButton,
                        onClick = {
                            persistUserLog(dismissField = true)
                        }
                    ),
                shape = RoundedCornerShape(10.dp),
                color = if (canTapUserLogButton) {
                    SplitBrandPink
                } else {
                    Color.White.copy(alpha = 0.08f)
                }
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    if (isSavingUserLog) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Save payment log",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (!userLogError.isNullOrBlank()) {
            Text(
                text = userLogError!!,
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink
            )
        }
    }
}

@Composable
private fun MerchantReportScreen(
    rootViewModel: SplitRootViewModel,
    transaction: WalletTransactionRow,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var merchantName by rememberSaveable(transaction.id) { mutableStateOf("") }
    var merchantAddress by rememberSaveable(transaction.id) { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TransactionScreenHeader(
            title = "Add Merchant",
            subtitle = "Share the merchant name and address. We’ll review the business and add them to rewards as quickly as possible.",
            onBack = onDismiss
        )

        TransactionSectionCard("Merchant Details") {
            TextField(
                value = merchantName,
                onValueChange = { merchantName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Merchant Name") },
                supportingText = { Text("Enter the business name") }
            )

            TextField(
                value = merchantAddress,
                onValueChange = { merchantAddress = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Merchant Address") },
                supportingText = { Text("Enter the business address") }
            )
        }

        TransactionSectionCard("This payment") {
            DetailRow("Amount", "${transaction.btcAmount} BTC")
            DetailRow("Date", transaction.dateString)
            if (transaction.note.isNotBlank()) {
                DetailRow("Memo", transaction.note)
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = SplitBrandPink
            )
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting
        ) {
            Text("Cancel")
        }

        Button(
            onClick = {
                scope.launch {
                    isSubmitting = true
                    errorMessage = null

                    runCatching {
                        rootViewModel.reportMerchant(
                            merchantName = merchantName.trim(),
                            merchantAddress = merchantAddress.trim(),
                            transaction = transaction
                        )
                    }.onSuccess {
                        onSubmitted()
                    }.onFailure { error ->
                        errorMessage = error.message ?: "Failed to add merchant."
                    }

                    isSubmitting = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting &&
                merchantName.trim().isNotEmpty() &&
                merchantAddress.trim().isNotEmpty()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Add Merchant")
            }
        }
    }
}

@Composable
private fun TransactionReportableScreen(
    rootViewModel: SplitRootViewModel,
    transaction: WalletTransactionRow,
    transactionSource: SpendWalletSource,
    isInitiallyReportable: Boolean,
    onDismiss: () -> Unit,
    onStatusChanged: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()

    var isReportable by rememberSaveable(transaction.id) { mutableStateOf(isInitiallyReportable) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isUpdating by remember { mutableStateOf(false) }
    var isRevertingState by remember { mutableStateOf(false) }

    fun persistReportableStatus(nextValue: Boolean) {
        if (isUpdating || isRevertingState || nextValue == isReportable) {
            return
        }

        val previousValue = isReportable
        isReportable = nextValue
        errorMessage = null
        onStatusChanged(nextValue)

        scope.launch {
            isUpdating = true

            runCatching {
                rootViewModel.setTransactionReportable(
                    paymentId = transaction.id,
                    direction = transaction.direction,
                    isReportable = nextValue,
                    source = transactionSource
                )
            }.onFailure { error ->
                isRevertingState = true
                isReportable = previousValue
                onStatusChanged(previousValue)
                errorMessage = error.message ?: "Unable to update the reportable setting on this device."
                isRevertingState = false
            }

            isUpdating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TransactionScreenHeader(
            title = "Reportable",
            subtitle = "Use this to decide whether this send should export as reportable in your CSV. Transactions stay non-reportable unless you turn this on.",
            onBack = onDismiss
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF101013),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isReportable) "Reportable" else "Non-reportable",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isReportable) {
                            "This transaction will export as Reportable."
                        } else {
                            "This transaction will export as Non-reportable."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }

                Switch(
                    checked = isReportable,
                    onCheckedChange = ::persistReportableStatus,
                    enabled = !isUpdating
                )
            }
        }

        TransactionSectionCard("This payment") {
            DetailRow("Amount", "${transaction.btcAmount} BTC")
            DetailRow("Date", transaction.dateString)
            DetailRow("Status", transaction.status)
            if (transaction.note.isNotBlank()) {
                DetailRow("Memo", transaction.note)
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = SplitBrandPink
            )
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isUpdating
        ) {
            Text("Done")
        }
    }
}

@Composable
private fun ReportableStatusIconButton(
    isReportable: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isReportable) {
            SplitBrandPink.copy(alpha = 0.18f)
        } else {
            Color.White.copy(alpha = 0.08f)
        }
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .padding(1.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(9.dp),
                color = Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    if (isReportable) SplitBrandPink.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f)
                )
            ) {}

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = if (isReportable) "Edit reportable status" else "Mark transaction as reportable",
                tint = if (isReportable) SplitBrandPink else Color.White.copy(alpha = 0.88f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TransactionInfoRow(
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(84.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.60f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.86f)
        )
    }
}

@Composable
private fun WalletTransactionDetailScreen(
    transaction: WalletTransactionRow,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TransactionScreenHeader(
            title = "Transaction",
            subtitle = "Expanded details for this payment.",
            onBack = onBack
        )

        TransactionSectionCard("Summary") {
            DetailRow("Direction", transaction.direction.replaceFirstChar { it.uppercase() })
            sentLightningAddress(transaction)?.let { lnAddress ->
                DetailRow("To", lnAddress)
            }
            DetailRow("Amount", signedAmountText(transaction))
            if (transaction.note.isNotBlank()) {
                DetailRow("Description", transaction.note)
            }
            DetailRow("Status", transaction.status)
            DetailRow("Date", transaction.dateString)
            DetailRow("Network", transaction.network.replaceFirstChar { it.uppercase() })
            DetailRow("Method", transaction.method)
            DetailRow("Fee (BTC)", transaction.feeBtcAmount)
            if (transaction.hasConversion) {
                DetailRow("Conversion", "Yes")
            }
        }

        val counterpartyRows = buildList {
            transaction.destinationPubkey?.let { add("Destination Pubkey" to it) }
            transaction.lnAddress?.let { add("Lightning Address" to it) }
            transaction.lnurlDomain?.let { add("LNURL Domain" to it) }
        }
        if (counterpartyRows.isNotEmpty()) {
            TransactionSectionCard("Counterparty") {
                counterpartyRows.forEach { (label, value) ->
                    CopyableDetailRow(
                        label = label,
                        value = value,
                        onCopy = {
                            copyPlainText(context, label, value, "$label copied.")
                        }
                    )
                }
            }
        }

        val referenceRows = buildList {
            transaction.invoice?.let { add("Invoice" to it) }
            transaction.paymentHash?.let { add("Payment Hash" to it) }
            transaction.preimage?.let { add("Preimage" to it) }
            transaction.expiryDateString?.let { add("HTLC Expiry" to it) }
            if (transaction.txReferenceLabel != null && transaction.txReference != null) {
                add(transaction.txReferenceLabel to transaction.txReference)
            }
        }
        if (referenceRows.isNotEmpty()) {
            TransactionSectionCard("Reference") {
                referenceRows.forEach { (label, value) ->
                    if (label == "HTLC Expiry") {
                        DetailRow(label, value)
                    } else {
                        CopyableDetailRow(
                            label = label,
                            value = value,
                            onCopy = {
                                copyPlainText(context, label, value, "$label copied.")
                            }
                        )
                    }
                }
            }
        }

        val noteRows = listOfNotNull(
            transaction.note.takeIf { it.isNotBlank() }?.let { "Memo" to it },
            transaction.lnurlComment
                ?.takeIf { it != transaction.note }
                ?.let { "LNURL Comment" to it },
            transaction.senderComment?.let { "Sender Comment" to it }
        )
        if (noteRows.isNotEmpty()) {
            TransactionSectionCard("Payment Memo") {
                noteRows.forEach { (label, value) ->
                    DetailRow(label, value)
                }
            }
        }
    }
}

@Composable
fun TransactionSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            content()
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.60f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TransactionScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransactionHeaderActionButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            onClick = onBack
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f)
            )
        }

        actions()
    }
}

@Composable
fun TransactionHeaderActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        onClick = onClick
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
private fun CopyableDetailRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.60f)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = SplitBrandBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun signedAmountText(transaction: WalletTransactionRow): String {
    val sign = if (transaction.direction == "sent") "-" else "+"
    return "$sign${transaction.btcAmount} BTC"
}

private fun transactionCardNote(transaction: WalletTransactionRow): String {
    val senderComment = transaction.senderComment?.trim().orEmpty()
    if (transaction.direction == "received" && senderComment.isNotEmpty()) {
        return senderComment
    }

    return transaction.note.trim()
}

private fun normalizedUserLogText(value: String?): String? {
    return value?.trim()?.ifBlank { null }
}

private fun sentLightningAddress(transaction: WalletTransactionRow): String? {
    if (transaction.direction != "sent") return null
    return transaction.lnAddress?.trim()?.ifBlank { null }
}

private fun sentLightningAddressLine(transaction: WalletTransactionRow): String? {
    val lnAddress = sentLightningAddress(transaction) ?: return null
    return "to $lnAddress"
}

@Composable
private fun TransactionsBackgroundAtmosphere() {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            SplitBrandPink.copy(alpha = 0.18f),
                            SplitBrandBlue.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.weight(1f))
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
