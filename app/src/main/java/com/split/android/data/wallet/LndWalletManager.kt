package com.split.android.data.wallet

import android.content.Context
import android.util.Log
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
import java.util.Locale
import kotlin.math.max

sealed interface LndConnectionState {
    data object Disconnected : LndConnectionState
    data object Connecting : LndConnectionState
    data object Ready : LndConnectionState
    data class Error(val message: String) : LndConnectionState
}

class LndWalletManager(
    context: Context,
    private val credentialStore: LndCredentialStore,
    private val onPaymentReceived: () -> Unit,
    private val onWalletActivity: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cursorPreferences = appContext.getSharedPreferences(
        "split_lnd_invoice_listener",
        Context.MODE_PRIVATE
    )

    private val _state = MutableStateFlow<LndConnectionState>(LndConnectionState.Disconnected)
    val state: StateFlow<LndConnectionState> = _state.asStateFlow()

    private val _connectedNode = MutableStateFlow<LndNodeCredentials?>(null)
    val connectedNode: StateFlow<LndNodeCredentials?> = _connectedNode.asStateFlow()

    private val _balanceSummary = MutableStateFlow<LndBalanceSummary?>(null)
    val balanceSummary: StateFlow<LndBalanceSummary?> = _balanceSummary.asStateFlow()

    private val _walletEventVersion = MutableStateFlow(0L)
    val walletEventVersion: StateFlow<Long> = _walletEventVersion.asStateFlow()

    var lastErrorMessage: String? = null
        private set

    private var client: LndRestClient? = null
    private var invoiceListenerJob: Job? = null
    private var invoiceRefreshJob: Job? = null
    private var invoiceListenerNodeId: String? = null
    private var shouldKeepInvoiceListenerRunning = false
    private val processedSettledInvoiceKeys = mutableSetOf<String>()

    val isConnected: Boolean
        get() = _state.value is LndConnectionState.Ready

    fun displayName(): String {
        return _connectedNode.value?.displayName ?: "LND Node"
    }

    suspend fun connect(lndConnectString: String): LndNodeCredentials {
        lastErrorMessage = null
        _state.value = LndConnectionState.Connecting

        return runCatching {
            val parsedCredentials = LndConnectParser.parse(lndConnectString)
            val (workingCredentials, info) = firstWorkingClient(parsedCredentials.restConnectionCandidates)
            val verifiedCredentials = workingCredentials.verified(info)

            credentialStore.saveNode(verifiedCredentials, makeActive = true)
            client = LndRestClient(verifiedCredentials)
            _connectedNode.value = verifiedCredentials
            _state.value = LndConnectionState.Ready

            runCatching { refreshBalance() }
            startInvoiceEventListenerIfPossible()
            verifiedCredentials
        }.getOrElse { error ->
            val message = error.message ?: "Unable to connect to LND node."
            lastErrorMessage = message
            _state.value = LndConnectionState.Error(message)
            throw error
        }
    }

    suspend fun restoreActiveNode() {
        lastErrorMessage = null

        val storedNode = credentialStore.activeNode() ?: run {
            _state.value = LndConnectionState.Disconnected
            _connectedNode.value = null
            client = null
            throw LndWalletException.NoStoredNode
        }

        _state.value = LndConnectionState.Connecting
        val restClient = LndRestClient(storedNode)

        runCatching {
            val info = restClient.getInfo()
            val verifiedNode = storedNode.verified(info)
            credentialStore.saveNode(verifiedNode, makeActive = true)

            client = LndRestClient(verifiedNode)
            _connectedNode.value = verifiedNode
            _state.value = LndConnectionState.Ready

            runCatching { refreshBalance() }
            startInvoiceEventListenerIfPossible()
        }.onFailure { error ->
            val message = error.message ?: "Unable to restore LND node."
            lastErrorMessage = message
            _connectedNode.value = storedNode
            client = restClient
            _state.value = LndConnectionState.Error(message)
            throw error
        }
    }

    fun disconnectFromActiveNode() {
        stopInvoiceEventListener()
        client = null
        _connectedNode.value = null
        _balanceSummary.value = null
        lastErrorMessage = null
        _state.value = LndConnectionState.Disconnected
    }

    fun forgetNode(id: String) {
        if (_connectedNode.value?.id == id) {
            disconnectFromActiveNode()
        }

        cursorPreferences.edit()
            .remove(cursorKey(id))
            .apply()
        credentialStore.deleteNode(id)
        onWalletActivity()
    }

    fun storedNodes(): List<LndNodeCredentials> {
        return credentialStore.loadNodes()
    }

    suspend fun setActiveStoredNode(id: String) {
        credentialStore.setActiveNode(id)
        restoreActiveNode()
    }

    suspend fun refreshNodeInfo(): LndGetInfoResponse {
        val restClient = requireClient()
        val info = restClient.getInfo()
        _connectedNode.value?.let { current ->
            val verified = current.verified(info)
            credentialStore.saveNode(verified, makeActive = true)
            _connectedNode.value = verified
        }
        _state.value = LndConnectionState.Ready
        return info
    }

    suspend fun refreshBalance(): LndBalanceSummary {
        val restClient = requireClient()
        val channelBalance = restClient.channelBalance()
        val walletBalance = restClient.walletBalance()
        val summary = LndBalanceSummary(
            channelBalanceSats = channelBalance,
            onChainBalanceSats = walletBalance
        )
        _balanceSummary.value = summary
        _state.value = LndConnectionState.Ready
        return summary
    }

    suspend fun decodeInvoice(bolt11: String): LndDecodePayReqResponse {
        return requireClient().decodePayReq(bolt11)
    }

    suspend fun payInvoice(
        bolt11: String,
        amountSats: Long?
    ): LndPayInvoiceResponse {
        val response = requireClient().payInvoice(
            bolt11 = bolt11,
            amountSats = amountSats
        )
        runCatching { refreshBalance() }.onFailure { error ->
            lastErrorMessage = error.message
            Log.w(
                "LndWalletManager",
                "Failed to refresh LND balance after payInvoice. ${error.localizedMessage}",
                error
            )
        }
        return response
    }

    suspend fun estimateRouteFee(
        destinationPubkey: String,
        amountSats: Long
    ): Long {
        return requireClient().estimateRouteFee(
            destinationPubkey = destinationPubkey,
            amountSats = amountSats
        )
    }

    suspend fun createInvoice(
        amountSats: Long?,
        memo: String?,
        expirySecs: Long = 3_600L
    ): LndAddInvoiceResponse {
        return requireClient().addInvoice(
            amountSats = amountSats,
            memo = memo,
            expirySecs = expirySecs
        )
    }

    suspend fun signNodeMessage(message: String): LndSignMessageResponse {
        return requireClient().signMessage(message)
    }

    suspend fun listPayments(maxPayments: Int = 50): List<LndPayment> {
        return requireClient().listPayments(maxPayments)
    }

    suspend fun listInvoices(maxInvoices: Int = 50): List<LndInvoice> {
        return requireClient().listInvoices(maxInvoices)
    }

    fun walletScopeIdentifier(): String? {
        val nodeId = activeNodeIdentifier() ?: return null
        return "lnd:$nodeId"
    }

    fun activeNodeIdentifier(): String? {
        return _connectedNode.value?.id ?: credentialStore.activeNode()?.id
    }

    suspend fun prepareOutgoingPayment(
        paymentRequest: String,
        amountSats: Long?,
        feesIncluded: Boolean,
        comment: String?
    ): PreparedOutgoingPayment.Lnd {
        if (!isConnected) {
            restoreActiveNode()
        }

        val trimmedRequest = paymentRequest.trim()
        val invoice = if (LndLightningPaymentResolver.isBolt11(trimmedRequest)) {
            trimmedRequest
        } else {
            val sats = amountSats?.takeIf { it > 0L }
                ?: throw IllegalArgumentException("Enter a valid amount.")
            LndLightningPaymentResolver.resolveInvoice(
                paymentRequest = trimmedRequest,
                amountSats = sats,
                comment = comment
            ).invoice
        }

        val decoded = decodeInvoice(invoice)
        val invoiceAmountSats = decoded.amountSats ?: 0L
        val (resolvedAmountSats, lndAmountOverrideSats) = when {
            invoiceAmountSats > 0L -> invoiceAmountSats to null
            amountSats != null && amountSats > 0L -> amountSats to amountSats
            else -> throw IllegalArgumentException("Enter an amount for this invoice.")
        }

        val estimatedFee = decoded.destination
            ?.trim()
            ?.ifBlank { null }
            ?.let { destination ->
                runCatching {
                    estimateRouteFee(
                        destinationPubkey = destination,
                        amountSats = resolvedAmountSats
                    )
                }.getOrNull()
            }

        return PreparedOutgoingPayment.Lnd(
            preview = PaymentPreview(
                backend = WalletBackend.LND,
                destination = invoice,
                amountSats = resolvedAmountSats,
                feeSats = estimatedFee,
                feesIncluded = feesIncluded,
                methodLabel = decoded.description?.trim()?.ifBlank { null } ?: "Lightning Invoice",
                lndAmountOverrideSats = lndAmountOverrideSats,
                destinationPubkey = decoded.destination?.trim()?.ifBlank { null },
                paymentHash = decoded.paymentHash?.trim()?.ifBlank { null }
            )
        )
    }

    suspend fun fetchTransactionRows(maxCount: Int = 100): List<WalletTransactionRow> = coroutineScope {
        val restClient = requireClient()
        val paymentsDeferred = async { restClient.listPayments(maxCount) }
        val invoicesDeferred = async { restClient.listInvoices(maxCount) }

        val rows = paymentsDeferred.await().map { it.toWalletTransactionRow() } +
            invoicesDeferred.await().mapNotNull { it.toWalletTransactionRowOrNull() }

        rows.sortedByDescending { it.transactionTimestampMillis }
    }

    fun setInvoiceListenerActive(active: Boolean) {
        shouldKeepInvoiceListenerRunning = active
        if (active) {
            startInvoiceEventListenerIfPossible()
        } else {
            stopInvoiceEventListener()
        }
    }

    fun startInvoiceEventListenerIfPossible() {
        if (!shouldKeepInvoiceListenerRunning) return
        if (!isConnected) return

        val node = _connectedNode.value ?: return
        val nodeId = node.id
        if (invoiceListenerNodeId == nodeId && invoiceListenerJob?.isActive == true) {
            return
        }

        stopInvoiceListenerJob()
        invoiceListenerNodeId = nodeId
        invoiceListenerJob = scope.launch {
            val startSettleIndex = invoiceListenerStartSettleIndex(nodeId)
            runInvoiceEventListener(
                credentials = node,
                nodeId = nodeId,
                startSettleIndex = startSettleIndex
            )
        }
    }

    fun stopInvoiceEventListener() {
        stopInvoiceListenerJob()
        invoiceRefreshJob?.cancel()
        invoiceRefreshJob = null
    }

    private fun requireClient(): LndRestClient {
        return client ?: throw LndWalletException.NodeNotConnected
    }

    private suspend fun firstWorkingClient(
        candidates: List<LndNodeCredentials>
    ): Pair<LndNodeCredentials, LndGetInfoResponse> {
        var lastError: Throwable? = null

        for (candidate in candidates) {
            try {
                val restClient = LndRestClient(
                    credentials = candidate,
                    connectTimeoutMillis = 6_000,
                    readTimeoutMillis = 10_000
                )
                val info = restClient.getInfo()
                return candidate to info
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw lastError ?: LndWalletException.InvalidResponse
    }

    private fun stopInvoiceListenerJob() {
        invoiceListenerJob?.cancel()
        invoiceListenerJob = null
        invoiceListenerNodeId = null
        processedSettledInvoiceKeys.clear()
    }

    private suspend fun invoiceListenerStartSettleIndex(nodeId: String): Long? {
        lastSettleIndex(nodeId)?.let { return it }

        return runCatching {
            var maxSettleIndex: Long? = null
            listInvoices(maxInvoices = 100).forEach { invoice ->
                if (invoice.isSettledInvoice) {
                    processedSettledInvoiceKeys.add(settledInvoiceKey(invoice, nodeId))
                    invoice.settleIndex?.let { settleIndex ->
                        maxSettleIndex = max(maxSettleIndex ?: settleIndex, settleIndex)
                    }
                }
            }

            maxSettleIndex?.let { setLastSettleIndex(nodeId, it) }
            maxSettleIndex
        }.getOrElse { error ->
            println("LndWalletManager: failed to seed invoice listener cursor. ${error.message}")
            null
        }
    }

    private suspend fun runInvoiceEventListener(
        credentials: LndNodeCredentials,
        nodeId: String,
        startSettleIndex: Long?
    ) {
        var currentSettleIndex = startSettleIndex
        var reconnectDelayMillis = 2_000L

        while (scope.coroutineContext.isActive && shouldKeepInvoiceListenerRunning) {
            val listenerClient = LndRestClient(
                credentials = credentials,
                connectTimeoutMillis = 20_000,
                readTimeoutMillis = 86_400_000
            )

            runCatching {
                listenerClient.subscribeInvoices(currentSettleIndex) { invoice ->
                    handleInvoiceEvent(invoice, nodeId)?.let { updatedIndex ->
                        currentSettleIndex = updatedIndex
                    } ?: run {
                        currentSettleIndex = lastSettleIndex(nodeId) ?: currentSettleIndex
                    }
                    reconnectDelayMillis = 2_000L
                }
            }.onFailure { error ->
                if (!shouldKeepInvoiceListenerRunning) return
                println("LndWalletManager: invoice listener disconnected. ${error.message}")
            }

            delay(reconnectDelayMillis)
            reconnectDelayMillis = (reconnectDelayMillis * 2L).coerceAtMost(30_000L)
            currentSettleIndex = lastSettleIndex(nodeId) ?: currentSettleIndex
        }
    }

    private fun handleInvoiceEvent(invoice: LndInvoice, nodeId: String): Long? {
        if (!invoice.isSettledInvoice) return null

        val currentSettleIndex = updateStoredSettleIndex(invoice, nodeId)
        val invoiceKey = settledInvoiceKey(invoice, nodeId)

        if (!processedSettledInvoiceKeys.add(invoiceKey)) {
            return currentSettleIndex
        }

        onPaymentReceived()
        scheduleInvoiceEventRefresh()
        return currentSettleIndex
    }

    private fun scheduleInvoiceEventRefresh() {
        if (invoiceRefreshJob?.isActive == true) return

        invoiceRefreshJob = scope.launch {
            delay(300L)

            runCatching {
                refreshBalance()
                fetchTransactionRows()
            }.onFailure { error ->
                lastErrorMessage = error.message
                println("LndWalletManager: failed to refresh after invoice event. ${error.message}")
            }

            _walletEventVersion.value = _walletEventVersion.value + 1L
            onWalletActivity()
            invoiceRefreshJob = null
        }
    }

    private fun updateStoredSettleIndex(invoice: LndInvoice, nodeId: String): Long? {
        val settleIndex = invoice.settleIndex ?: return lastSettleIndex(nodeId)
        val nextSettleIndex = max(lastSettleIndex(nodeId) ?: settleIndex, settleIndex)
        setLastSettleIndex(nodeId, nextSettleIndex)
        return nextSettleIndex
    }

    private fun settledInvoiceKey(invoice: LndInvoice, nodeId: String): String {
        invoice.rHash?.trim()?.ifBlank { null }?.let { return "$nodeId:rhash:$it" }
        invoice.paymentRequest?.trim()?.ifBlank { null }?.let { return "$nodeId:invoice:$it" }
        invoice.settleIndex?.let { return "$nodeId:settle:$it" }
        return "$nodeId:fallback:${invoice.id}"
    }

    private fun lastSettleIndex(nodeId: String): Long? {
        if (!cursorPreferences.contains(cursorKey(nodeId))) return null
        return cursorPreferences.getLong(cursorKey(nodeId), 0L)
    }

    private fun setLastSettleIndex(nodeId: String, settleIndex: Long) {
        cursorPreferences.edit()
            .putLong(cursorKey(nodeId), settleIndex)
            .apply()
    }

    private fun cursorKey(nodeId: String): String {
        return "split.lnd.invoiceListener.lastSettleIndex.${nodeId.lowercase(Locale.US)}"
    }
}
