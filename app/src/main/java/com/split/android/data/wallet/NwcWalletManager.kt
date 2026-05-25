package com.split.android.data.wallet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

sealed interface NwcConnectionState {
    data object Disconnected : NwcConnectionState
    data object Connecting : NwcConnectionState
    data object Ready : NwcConnectionState
    data class Error(val message: String) : NwcConnectionState
}

data class NwcBalanceSummary(
    val spendableSats: Long
)

class NwcWalletManager(
    context: Context,
    private val credentialStore: NwcCredentialStore,
    private val onPaymentReceived: () -> Unit,
    private val onWalletActivity: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<NwcConnectionState>(NwcConnectionState.Disconnected)
    val state: StateFlow<NwcConnectionState> = _state.asStateFlow()

    private val _connectedWallet = MutableStateFlow<NwcWalletCredentials?>(null)
    val connectedWallet: StateFlow<NwcWalletCredentials?> = _connectedWallet.asStateFlow()

    private val _balanceSummary = MutableStateFlow<NwcBalanceSummary?>(null)
    val balanceSummary: StateFlow<NwcBalanceSummary?> = _balanceSummary.asStateFlow()

    private val _storedWalletsVersion = MutableStateFlow(0L)
    val storedWalletsVersion: StateFlow<Long> = _storedWalletsVersion.asStateFlow()

    private var client: NwcCommandClient? = null
    private var notificationListenerJob: Job? = null
    private var notificationRefreshJob: Job? = null
    private var notificationListenerWalletId: String? = null
    private var shouldKeepNotificationListenerRunning = false
    private val processedNotificationIds = mutableSetOf<String>()

    val isConnected: Boolean
        get() = _state.value is NwcConnectionState.Ready

    fun displayName(): String = _connectedWallet.value?.displayName ?: "NWC Wallet"

    suspend fun connect(
        nwcConnectionString: String,
        label: String? = null
    ): NwcWalletCredentials {
        _state.value = NwcConnectionState.Connecting
        return runCatching {
            val parsed = NwcConnectParser.parse(nwcConnectionString)
            val capabilities = fetchCapabilities(parsed)
            if (!capabilities.supportsCompatibleEncryption) {
                throw NwcWalletException.UnsupportedEncryption
            }
            if (!capabilities.supportsSplitBaseline) {
                throw NwcWalletException.InsufficientCapabilities
            }
            val labeled = parsed
                .verified(capabilities)
                .copy(label = label?.trim()?.ifBlank { null } ?: parsed.label)
            val saved = credentialStore.saveWallet(labeled, makeActive = true)
            _connectedWallet.value = saved
            client = NwcCommandClient(saved)
            _state.value = NwcConnectionState.Ready
            notifyStoredWalletsChanged()
            runCatching { refreshBalance() }
            startNotificationListenerIfPossible()
            saved
        }.getOrElse { error ->
            val message = connectionErrorMessage(error)
            _state.value = NwcConnectionState.Error(message)
            throw error
        }
    }

    suspend fun restoreActiveWallet() {
        val storedWallet = credentialStore.activeWallet() ?: run {
            _state.value = NwcConnectionState.Disconnected
            _connectedWallet.value = null
            _balanceSummary.value = null
            client = null
            throw NwcWalletException.NoStoredConnection
        }

        _state.value = NwcConnectionState.Connecting
        runCatching {
            val capabilities = fetchCapabilities(storedWallet)
            val saved = credentialStore.saveWallet(storedWallet.verified(capabilities), makeActive = false)
            _connectedWallet.value = saved
            client = NwcCommandClient(saved)
            _state.value = NwcConnectionState.Ready
            notifyStoredWalletsChanged()
            runCatching { refreshBalance() }
            startNotificationListenerIfPossible()
        }.onFailure { error ->
            _connectedWallet.value = storedWallet
            client = NwcCommandClient(storedWallet)
            _state.value = NwcConnectionState.Error(connectionErrorMessage(error))
            throw error
        }
    }

    fun disconnectFromActiveWallet() {
        stopNotificationListener()
        _connectedWallet.value = null
        _balanceSummary.value = null
        client = null
        _state.value = NwcConnectionState.Disconnected
    }

    fun storedWallets(): List<NwcWalletCredentials> = credentialStore.loadWallets()

    suspend fun setActiveStoredWallet(id: String) {
        credentialStore.setActiveWallet(id)
        restoreActiveWallet()
        notifyStoredWalletsChanged()
    }

    fun forgetWallet(id: String) {
        if (_connectedWallet.value?.id == id) {
            disconnectFromActiveWallet()
        }
        credentialStore.deleteWallet(id)
        notifyStoredWalletsChanged()
        onWalletActivity()
    }

    fun renameWallet(id: String, label: String) {
        credentialStore.renameWallet(id, label)
        if (_connectedWallet.value?.id == id) {
            credentialStore.loadWallets().firstOrNull { it.id == id }?.let { wallet ->
                _connectedWallet.value = wallet
                client = NwcCommandClient(wallet)
            }
        }
        notifyStoredWalletsChanged()
        onWalletActivity()
    }

    suspend fun refreshWalletInfo(): NwcWalletCapabilities {
        val wallet = requireWallet()
        val capabilities = fetchCapabilities(wallet)
        val saved = credentialStore.saveWallet(wallet.verified(capabilities), makeActive = false)
        _connectedWallet.value = saved
        client = NwcCommandClient(saved)
        _state.value = NwcConnectionState.Ready
        notifyStoredWalletsChanged()
        startNotificationListenerIfPossible()
        return capabilities
    }

    suspend fun refreshBalance(): NwcBalanceSummary {
        ensureTorIfNeeded(requireWallet())
        val result = requireClient().getBalance()
        val summary = NwcBalanceSummary(spendableSats = result.balanceSats)
        _balanceSummary.value = summary
        _state.value = NwcConnectionState.Ready
        return summary
    }

    suspend fun createInvoice(amountSats: Long?, memo: String?, expirySecs: Long = 3_600L): NwcTransactionResult {
        ensureTorIfNeeded(requireWallet())
        return requireClient().makeInvoice(amountSats, memo, expirySecs)
    }

    suspend fun payInvoice(bolt11: String, amountSats: Long? = null): NwcPayInvoiceResult {
        ensureTorIfNeeded(requireWallet())
        val result = requireClient().payInvoice(bolt11, amountSats)
        runCatching { refreshBalance() }
        return result
    }

    suspend fun lookupInvoice(bolt11: String): NwcTransactionResult {
        ensureTorIfNeeded(requireWallet())
        return requireClient().lookupInvoice(bolt11)
    }

    suspend fun lookupInvoiceByPaymentHash(paymentHash: String): NwcTransactionResult {
        ensureTorIfNeeded(requireWallet())
        return requireClient().lookupInvoiceByPaymentHash(paymentHash)
    }

    suspend fun fetchTransactionRows(maxCount: Int = 50): List<WalletTransactionRow> {
        ensureTorIfNeeded(requireWallet())
        return requireClient().listTransactions(limit = maxCount)
            .mapNotNull(::toWalletTransactionRow)
            .sortedByDescending { it.transactionTimestampMillis }
    }

    fun setNotificationListenerActive(active: Boolean) {
        shouldKeepNotificationListenerRunning = active
        if (active) {
            startNotificationListenerIfPossible()
        } else {
            stopNotificationListener()
        }
    }

    fun startNotificationListenerIfPossible() {
        if (!shouldKeepNotificationListenerRunning) return
        if (!isConnected) return

        val wallet = _connectedWallet.value ?: return
        if (wallet.capabilities?.supportsPaymentNotifications == false) return

        val walletId = wallet.id
        if (notificationListenerWalletId == walletId && notificationListenerJob?.isActive == true) {
            return
        }

        stopNotificationListenerJob()
        notificationListenerWalletId = walletId
        notificationListenerJob = scope.launch {
            runNotificationListener(wallet)
        }
    }

    fun walletScopeIdentifier(): String? {
        val walletId = _connectedWallet.value?.id ?: credentialStore.activeWallet()?.id ?: return null
        return "nwc:$walletId"
    }

    private suspend fun fetchCapabilities(wallet: NwcWalletCredentials): NwcWalletCapabilities {
        ensureTorIfNeeded(wallet)
        var lastError: Throwable? = null
        val relayClient = NwcRelayClient()
        for (relayUrl in wallet.relayUrls) {
            try {
                val event = relayClient.fetchWalletInfo(relayUrl, wallet.walletPubkey)
                if (!event.pubkey.equals(wallet.walletPubkey, ignoreCase = true)) {
                    throw NwcWalletException.InvalidRelayResponse
                }
                return event.capabilities
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: NwcWalletException.WalletInfoUnavailable
    }

    private fun requireWallet(): NwcWalletCredentials {
        return _connectedWallet.value ?: credentialStore.activeWallet() ?: throw NwcWalletException.WalletNotConnected
    }

    private fun requireClient(): NwcCommandClient {
        client?.let { return it }
        val wallet = requireWallet()
        return NwcCommandClient(wallet).also { client = it }
    }

    private fun stopNotificationListener() {
        stopNotificationListenerJob()
        notificationRefreshJob?.cancel()
        notificationRefreshJob = null
    }

    private fun stopNotificationListenerJob() {
        notificationListenerJob?.cancel()
        notificationListenerJob = null
        notificationListenerWalletId = null
        processedNotificationIds.clear()
    }

    private suspend fun runNotificationListener(wallet: NwcWalletCredentials) {
        ensureTorIfNeeded(wallet)
        var reconnectDelayMillis = 2_000L
        val clientPubkey = NwcNostrCryptography.publicKeyHex(wallet.secret)

        while (scope.coroutineContext.isActive && shouldKeepNotificationListenerRunning) {
            runCatching {
                coroutineScope {
                    wallet.relayUrls.map { relayUrl ->
                        async {
                            NwcRelayClient().subscribeNotifications(
                                relayUrl = relayUrl,
                                wallet = wallet,
                                clientPubkey = clientPubkey,
                                onNotification = ::handleNotificationEvent
                            )
                        }
                    }.forEach { it.await() }
                }
            }.onFailure { error ->
                if (!shouldKeepNotificationListenerRunning) return
                println("NwcWalletManager: notification listener disconnected. ${error.message}")
            }

            delay(reconnectDelayMillis)
            reconnectDelayMillis = (reconnectDelayMillis * 2L).coerceAtMost(30_000L)
        }
    }

    private fun handleNotificationEvent(notification: NwcNotificationEvent) {
        if (!processedNotificationIds.add(notification.id)) return

        if (notification.isPaymentReceived) {
            onPaymentReceived()
        }

        if (notification.isPaymentReceived || notification.isPaymentSent) {
            scheduleNotificationRefresh()
        }
    }

    private fun scheduleNotificationRefresh() {
        if (notificationRefreshJob?.isActive == true) return

        notificationRefreshJob = scope.launch {
            delay(300L)

            runCatching {
                refreshBalance()
                fetchTransactionRows()
            }.onFailure { error ->
                println("NwcWalletManager: failed to refresh after notification. ${error.message}")
            }

            onWalletActivity()
            notificationRefreshJob = null
        }
    }

    private fun notifyStoredWalletsChanged() {
        _storedWalletsVersion.value += 1
    }

    private fun connectionErrorMessage(error: Throwable): String {
        return error.message ?: "Unable to connect to NWC wallet."
    }

    private suspend fun ensureTorIfNeeded(wallet: NwcWalletCredentials) {
        if (wallet.usesTor) {
            RemoteNodeTorTransport.ensureStarted(appContext)
        }
    }

    private fun toWalletTransactionRow(transaction: NwcTransactionResult): WalletTransactionRow? {
        val amountSats = max(transaction.amountSats ?: 0L, 0L)
        if (amountSats <= 0L) return null
        val direction = if (transaction.type?.trim()?.lowercase() == "incoming") "received" else "sent"
        val status = when (transaction.state?.trim()?.lowercase()) {
            "settled", "succeeded", "success", "completed", "paid" -> "Completed"
            "failed", "failure", "expired" -> "Failed"
            else -> "Pending"
        }
        val timestampMillis = (transaction.createdAt ?: (System.currentTimeMillis() / 1_000L)) * 1_000L
        val idSeed = transaction.paymentHash
            ?: transaction.preimage
            ?: transaction.invoice
            ?: "$timestampMillis-$amountSats-$direction"
        return WalletTransactionRow(
            id = "nwc-$idSeed",
            transactionTimestampMillis = timestampMillis,
            direction = direction,
            btcAmount = amountSats.nwcBtcString(),
            feeBtcAmount = (transaction.feesPaidSats ?: 0L).nwcBtcString(),
            network = "lightning",
            status = status,
            dateString = Date(timestampMillis).nwcDisplayString(),
            note = transaction.description.orEmpty(),
            amountSats = amountSats,
            feeSats = transaction.feesPaidSats ?: 0L,
            method = "NWC",
            destinationPubkey = null,
            invoice = transaction.invoice,
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = transaction.paymentHash,
            preimage = transaction.preimage,
            expiryDateString = transaction.expiresAt?.takeIf { it > 0L }?.let { Date(it * 1_000L).nwcDisplayString() },
            txReferenceLabel = transaction.paymentHash?.let { "Payment Hash" },
            txReference = transaction.paymentHash,
            hasConversion = false
        )
    }
}

private fun Long.nwcBtcString(): String {
    return String.format(Locale.US, "%.8f", max(this, 0L).toDouble() / 100_000_000.0)
}

private fun Date.nwcDisplayString(): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(this)
}
