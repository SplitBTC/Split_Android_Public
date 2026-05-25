package com.split.android.data.wallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private const val CORE_LIGHTNING_WALLET_LOG_TAG = "SplitCoreLightningWallet"

sealed interface CoreLightningConnectionState {
    data object Disconnected : CoreLightningConnectionState
    data object Connecting : CoreLightningConnectionState
    data object Ready : CoreLightningConnectionState
    data class Error(val message: String) : CoreLightningConnectionState
}

class CoreLightningWalletManager(
    context: Context,
    private val credentialStore: CoreLightningCredentialStore,
    private val onWalletActivity: () -> Unit
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<CoreLightningConnectionState>(CoreLightningConnectionState.Disconnected)
    val state: StateFlow<CoreLightningConnectionState> = _state.asStateFlow()

    private val _connectedNode = MutableStateFlow<CoreLightningNodeCredentials?>(null)
    val connectedNode: StateFlow<CoreLightningNodeCredentials?> = _connectedNode.asStateFlow()

    private val _balanceSummary = MutableStateFlow<CoreLightningBalanceSummary?>(null)
    val balanceSummary: StateFlow<CoreLightningBalanceSummary?> = _balanceSummary.asStateFlow()

    private val _storedNodesVersion = MutableStateFlow(0L)
    val storedNodesVersion: StateFlow<Long> = _storedNodesVersion.asStateFlow()

    var lastErrorMessage: String? = null
        private set

    private var client: CoreLightningRestClient? = null

    val isConnected: Boolean
        get() = _state.value is CoreLightningConnectionState.Ready

    fun displayName(): String = _connectedNode.value?.displayName ?: "Core Lightning Node"

    suspend fun connect(
        connectionString: String,
        label: String? = null
    ): CoreLightningNodeCredentials {
        Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning manager connect started")
        lastErrorMessage = null
        _state.value = CoreLightningConnectionState.Connecting

        return runCatching {
            val parsedCredentials = CoreLightningConnectParser.parse(connectionString).withLabel(label)
            Log.w(
                CORE_LIGHTNING_WALLET_LOG_TAG,
                "Core Lightning connection string parsed usesTor=${parsedCredentials.usesTor} " +
                    "hasCert=${parsedCredentials.tlsCertificateDerBase64 != null} " +
                    "candidateCount=${parsedCredentials.restConnectionCandidates.size}"
            )
            val (workingCredentials, info) = firstWorkingClient(parsedCredentials.restConnectionCandidates)
            Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning working client found")
            val verifiedCredentials = workingCredentials.verified(
                nodeId = info.id,
                nodeAlias = info.alias
            )
            val savedCredentials = credentialStore.saveNode(verifiedCredentials, makeActive = true)

            client = CoreLightningRestClient(savedCredentials)
            _connectedNode.value = savedCredentials
            _state.value = CoreLightningConnectionState.Ready
            notifyStoredNodesChanged()
            runCatching { refreshBalance() }
            savedCredentials
        }.getOrElse { error ->
            Log.w(
                CORE_LIGHTNING_WALLET_LOG_TAG,
                "Core Lightning manager connect failed: ${error.javaClass.simpleName}"
            )
            val host = runCatching { CoreLightningConnectParser.parse(connectionString).host }.getOrNull()
            val message = connectionErrorMessage(error, host)
            lastErrorMessage = message
            _state.value = CoreLightningConnectionState.Error(message)
            throw error
        }
    }

    suspend fun restoreActiveNode() {
        lastErrorMessage = null

        val storedNode = credentialStore.activeNode() ?: run {
            _state.value = CoreLightningConnectionState.Disconnected
            _connectedNode.value = null
            _balanceSummary.value = null
            client = null
            throw CoreLightningWalletException.NoStoredNode
        }

        try {
            validateConnectionCandidate(storedNode)
        } catch (error: Throwable) {
            client = null
            _balanceSummary.value = null
            _connectedNode.value = storedNode
            val message = connectionErrorMessage(error, storedNode.host)
            lastErrorMessage = message
            _state.value = CoreLightningConnectionState.Error(message)
            throw error
        }

        _state.value = CoreLightningConnectionState.Connecting
        val restClient = CoreLightningRestClient(storedNode)

        runCatching {
            val info = restClient.getInfo()
            val verifiedNode = storedNode
                .verified(nodeId = info.id, nodeAlias = info.alias)
                .withTlsCertificateDerBase64(restClient.observedServerCertificateDerBase64)
            val savedNode = credentialStore.saveNode(verifiedNode, makeActive = false)

            client = CoreLightningRestClient(savedNode)
            _connectedNode.value = savedNode
            _state.value = CoreLightningConnectionState.Ready
            notifyStoredNodesChanged()
            runCatching { refreshBalance() }
        }.onFailure { error ->
            val message = connectionErrorMessage(error, storedNode.host)
            lastErrorMessage = message
            _connectedNode.value = storedNode
            client = restClient
            _state.value = CoreLightningConnectionState.Error(message)
            throw error
        }
    }

    fun disconnectFromActiveNode() {
        client = null
        _connectedNode.value = null
        _balanceSummary.value = null
        lastErrorMessage = null
        _state.value = CoreLightningConnectionState.Disconnected
    }

    fun forgetNode(id: String) {
        if (_connectedNode.value?.id == id) {
            disconnectFromActiveNode()
        }
        credentialStore.deleteNode(id)
        notifyStoredNodesChanged()
        onWalletActivity()
    }

    fun storedNodes(): List<CoreLightningNodeCredentials> {
        return credentialStore.loadNodes()
    }

    suspend fun setActiveStoredNode(id: String) {
        credentialStore.setActiveNode(id)
        restoreActiveNode()
        notifyStoredNodesChanged()
    }

    fun renameNode(id: String, label: String) {
        credentialStore.renameNode(id, label)
        _connectedNode.value?.let { current ->
            if (current.id == id) {
                _connectedNode.value = current.withLabel(label)
            }
        }
        notifyStoredNodesChanged()
        onWalletActivity()
    }

    suspend fun refreshNodeInfo(): CoreLightningGetInfoResponse {
        ensureTorForActiveNodeIfNeeded()
        val restClient = requireClient()
        val info = restClient.getInfo()
        _connectedNode.value?.let { current ->
            val verified = current
                .verified(nodeId = info.id, nodeAlias = info.alias)
                .withTlsCertificateDerBase64(restClient.observedServerCertificateDerBase64)
            val saved = credentialStore.saveNode(verified, makeActive = false)
            _connectedNode.value = saved
            notifyStoredNodesChanged()
        }
        _state.value = CoreLightningConnectionState.Ready
        return info
    }

    suspend fun refreshBalance(): CoreLightningBalanceSummary {
        ensureTorForActiveNodeIfNeeded()
        val funds = requireClient().listFunds()
        val summary = CoreLightningBalanceSummary(
            channelBalanceSats = funds.spendableChannelBalanceSats,
            onChainBalanceSats = funds.onChainBalanceSats
        )
        _balanceSummary.value = summary
        _state.value = CoreLightningConnectionState.Ready
        return summary
    }

    suspend fun decodeInvoice(bolt11: String): CoreLightningDecodeResponse {
        ensureTorForActiveNodeIfNeeded()
        return requireClient().decodeInvoice(bolt11)
    }

    suspend fun payInvoice(
        bolt11: String,
        amountSats: Long?
    ): CoreLightningPayResponse {
        ensureTorForActiveNodeIfNeeded()
        val response = requireClient().payInvoice(
            bolt11 = bolt11,
            amountSats = amountSats
        )
        runCatching { refreshBalance() }.onFailure { error ->
            lastErrorMessage = error.message
            Log.w(
                "CoreLightningWalletManager",
                "Failed to refresh Core Lightning balance after payInvoice. ${error.localizedMessage}",
                error
            )
        }
        return response
    }

    suspend fun createInvoice(
        amountSats: Long?,
        memo: String?,
        expirySecs: Long = 3_600L
    ): CoreLightningInvoiceResponse {
        ensureTorForActiveNodeIfNeeded()
        return requireClient().createInvoice(amountSats, memo, expirySecs)
    }

    fun walletScopeIdentifier(): String? {
        val nodeId = activeNodeIdentifier() ?: return null
        return "core-lightning:$nodeId"
    }

    fun activeNodeIdentifier(): String? {
        return _connectedNode.value?.id ?: credentialStore.activeNode()?.id
    }

    suspend fun prepareOutgoingPayment(
        paymentRequest: String,
        amountSats: Long?,
        feesIncluded: Boolean,
        comment: String?
    ): PreparedOutgoingPayment.CoreLightning {
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
        val (resolvedAmountSats, amountOverrideSats) = when {
            invoiceAmountSats > 0L -> invoiceAmountSats to null
            amountSats != null && amountSats > 0L -> amountSats to amountSats
            else -> throw IllegalArgumentException("Enter an amount for this invoice.")
        }

        return PreparedOutgoingPayment.CoreLightning(
            preview = PaymentPreview(
                backend = WalletBackend.CORE_LIGHTNING,
                destination = invoice,
                amountSats = resolvedAmountSats,
                feeSats = null,
                feesIncluded = feesIncluded,
                methodLabel = decoded.description?.trim()?.ifBlank { null } ?: "Lightning Invoice",
                lndAmountOverrideSats = amountOverrideSats,
                destinationPubkey = decoded.payee?.trim()?.ifBlank { null },
                paymentHash = decoded.paymentHash?.trim()?.ifBlank { null }
            )
        )
    }

    suspend fun fetchTransactionRows(maxCount: Int = 100): List<WalletTransactionRow> = coroutineScope {
        ensureTorForActiveNodeIfNeeded()
        val restClient = requireClient()
        val paysDeferred = async { restClient.listPays(maxCount) }
        val invoicesDeferred = async { restClient.listInvoices(maxCount) }

        val rows = paysDeferred.await().map { it.toWalletTransactionRow() } +
            invoicesDeferred.await().mapNotNull { it.toWalletTransactionRowOrNull() }

        rows.sortedByDescending { it.transactionTimestampMillis }
    }

    private fun requireClient(): CoreLightningRestClient {
        return client ?: throw CoreLightningWalletException.NodeNotConnected
    }

    private fun notifyStoredNodesChanged() {
        _storedNodesVersion.value += 1
    }

    private suspend fun ensureTorForActiveNodeIfNeeded() {
        val node = _connectedNode.value ?: credentialStore.activeNode()
        if (node?.usesTor == true) {
            RemoteNodeTorTransport.ensureStarted(appContext)
        }
    }

    private suspend fun firstWorkingClient(
        candidates: List<CoreLightningNodeCredentials>
    ): Pair<CoreLightningNodeCredentials, CoreLightningGetInfoResponse> {
        var lastError: Throwable? = null

        for (candidate in candidates) {
            try {
                Log.w(
                    CORE_LIGHTNING_WALLET_LOG_TAG,
                    "Core Lightning candidate attempt started usesTor=${candidate.usesTor} " +
                        "hasCert=${candidate.tlsCertificateDerBase64 != null}"
                )
                validateConnectionCandidate(candidate)
                Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning candidate validation passed")
                val connectTimeoutMillis = if (candidate.usesTor) 20_000 else 6_000
                val readTimeoutMillis = if (candidate.usesTor) 75_000 else 12_000
                val restClient = CoreLightningRestClient(
                    credentials = candidate,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis
                )
                Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning getinfo request started")
                val info = restClient.getInfo()
                Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning getinfo request succeeded")
                val verifiedCandidate = candidate.withTlsCertificateDerBase64(
                    restClient.observedServerCertificateDerBase64
                )
                return verifiedCandidate to info
            } catch (error: Throwable) {
                Log.w(
                    CORE_LIGHTNING_WALLET_LOG_TAG,
                    "Core Lightning candidate failed: ${error.javaClass.simpleName}"
                )
                lastError = error
            }
        }

        throw lastError ?: CoreLightningWalletException.InvalidResponse
    }

    private suspend fun validateConnectionCandidate(candidate: CoreLightningNodeCredentials) {
        CoreLightningHostAccessPolicy.validateHost(candidate.host)
        if (candidate.usesTor) {
            Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning Tor candidate ensuring Tor started")
            RemoteNodeTorTransport.ensureStarted(appContext)
            Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning Tor ready for candidate")
            return
        }
        Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning direct candidate resolving host")
        val resolvedAddresses = resolveHost(candidate.host)
        CoreLightningHostAccessPolicy.validateResolvedAddresses(resolvedAddresses)
        Log.w(CORE_LIGHTNING_WALLET_LOG_TAG, "Core Lightning resolved address validation passed")
    }

    private suspend fun resolveHost(host: String): List<CoreLightningResolvedAddress> = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            throw CoreLightningWalletException.MissingNodeHost
        }

        val resolvedAddresses = InetAddress.getAllByName(trimmedHost)
            .mapNotNull { address ->
                when (address) {
                    is Inet4Address -> CoreLightningResolvedAddress.Ipv4(address)
                    is Inet6Address -> CoreLightningResolvedAddress.Ipv6(address)
                    else -> null
                }
            }
            .distinctBy { address ->
                when (address) {
                    is CoreLightningResolvedAddress.Ipv4 -> "4:${address.address.hostAddress}"
                    is CoreLightningResolvedAddress.Ipv6 -> {
                        val normalizedHost = address.address.hostAddress?.substringBefore('%').orEmpty()
                        "6:$normalizedHost"
                    }
                }
            }

        if (resolvedAddresses.isEmpty()) {
            throw UnknownHostException(trimmedHost)
        }

        resolvedAddresses
    }

    private fun connectionErrorMessage(error: Throwable, host: String?): String {
        val normalizedHost = host?.trim()?.lowercase(Locale.US)

        return when (error) {
            is UnknownHostException -> {
                if (normalizedHost?.endsWith(".onion") == true) {
                    "Split could not reach this Tor onion Core Lightning node. Make sure Tor is running and the onion service address is correct."
                } else if (normalizedHost?.endsWith(".local") == true) {
                    "Split could not resolve this .local hostname. Make sure your phone is on the same local network as the node."
                } else {
                    "Split could not resolve this Core Lightning host. Make sure the node is reachable from this phone over your private network, Tailscale, or Tor."
                }
            }

            is ConnectException, is SocketTimeoutException -> {
                if (normalizedHost?.endsWith(".onion") == true) {
                    "Split could not reach this Tor onion Core Lightning node. Make sure Tor is running, the onion service is online, and the REST port is reachable."
                } else {
                    "Split could not reach this Core Lightning node. Make sure the node is online and reachable from this phone over your private network, Tailscale, or Tor."
                }
            }

            is CoreLightningWalletException -> {
                error.message ?: "Unable to connect to Core Lightning node."
            }

            else -> error.message ?: "Unable to connect to Core Lightning node."
        }
    }
}

private val CoreLightningNodeCredentials.restConnectionCandidates: List<CoreLightningNodeCredentials>
    get() {
        val trimmedHost = host.trim()
        val candidates = mutableListOf(this)
        if (trimmedHost.lowercase(Locale.US).endsWith(".local")) {
            val fallback = trimmedHost.dropLast(".local".length)
            if (fallback.isNotBlank()) {
                candidates.add(withHost(fallback))
            }
        }
        return candidates.distinctBy { "${it.scheme.lowercase(Locale.US)}://${it.host.lowercase(Locale.US)}:${it.port}" }
    }

private fun CoreLightningPay.toWalletTransactionRow(): WalletTransactionRow {
    val amountSats = max(amountMsat?.satsRoundedDown ?: 0L, 0L)
    val feeSats = max((amountSentMsat?.satsRoundedUp ?: amountSats) - amountSats, 0L)
    val timestampMillis = ((completedAt ?: createdAt)?.value ?: (System.currentTimeMillis() / 1_000L)) * 1_000L
    val normalizedStatus = paymentStatus(status)

    return WalletTransactionRow(
        id = "core-lightning-payment-$id",
        transactionTimestampMillis = timestampMillis,
        direction = "sent",
        btcAmount = amountSats.coreLightningBtcString(),
        feeBtcAmount = feeSats.coreLightningBtcString(),
        network = "lightning",
        status = normalizedStatus,
        dateString = Date(timestampMillis).coreLightningDisplayString(),
        note = description.orEmpty(),
        amountSats = amountSats,
        feeSats = feeSats,
        method = "Core Lightning",
        destinationPubkey = destination,
        invoice = bolt11,
        lnAddress = null,
        lnurlDomain = null,
        lnurlComment = null,
        senderComment = null,
        paymentHash = paymentHash,
        preimage = paymentPreimage,
        expiryDateString = null,
        txReferenceLabel = paymentHash?.let { "Payment Hash" },
        txReference = paymentHash,
        hasConversion = false
    )
}

private fun CoreLightningInvoice.toWalletTransactionRowOrNull(): WalletTransactionRow? {
    if (!isPaid) return null
    val amountSats = max(amountReceivedMsat?.satsRoundedDown ?: amountMsat?.satsRoundedDown ?: 0L, 0L)
    if (amountSats <= 0L) return null
    val timestampMillis = (paidAt?.value ?: (System.currentTimeMillis() / 1_000L)) * 1_000L

    return WalletTransactionRow(
        id = "core-lightning-invoice-$id",
        transactionTimestampMillis = timestampMillis,
        direction = "received",
        btcAmount = amountSats.coreLightningBtcString(),
        feeBtcAmount = 0L.coreLightningBtcString(),
        network = "lightning",
        status = "Completed",
        dateString = Date(timestampMillis).coreLightningDisplayString(),
        note = description.orEmpty(),
        amountSats = amountSats,
        feeSats = 0L,
        method = "Core Lightning",
        destinationPubkey = null,
        invoice = bolt11,
        lnAddress = null,
        lnurlDomain = null,
        lnurlComment = null,
        senderComment = null,
        paymentHash = paymentHash,
        preimage = paymentPreimage,
        expiryDateString = expiresAt?.value?.takeIf { it > 0L }?.let { Date(it * 1_000L).coreLightningDisplayString() },
        txReferenceLabel = paymentHash?.let { "Payment Hash" },
        txReference = paymentHash,
        hasConversion = false
    )
}

private fun paymentStatus(status: String?): String {
    return when (status?.trim()?.lowercase(Locale.US)) {
        "complete", "completed", "paid", "succeeded", "success" -> "Completed"
        "pending", "in_flight" -> "Pending"
        "failed", "failure" -> "Failed"
        else -> status?.trim()?.ifBlank { null } ?: "Pending"
    }
}

private fun Long.coreLightningBtcString(): String {
    return String.format(Locale.US, "%.8f", max(this, 0L).toDouble() / 100_000_000.0)
}

private fun Date.coreLightningDisplayString(): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(this)
}
