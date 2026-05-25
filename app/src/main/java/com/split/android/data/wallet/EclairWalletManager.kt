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

sealed interface EclairConnectionState {
    data object Disconnected : EclairConnectionState
    data object Connecting : EclairConnectionState
    data object Ready : EclairConnectionState
    data class Error(val message: String) : EclairConnectionState
}

class EclairWalletManager(
    context: Context,
    private val credentialStore: EclairCredentialStore,
    private val onWalletActivity: () -> Unit
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<EclairConnectionState>(EclairConnectionState.Disconnected)
    val state: StateFlow<EclairConnectionState> = _state.asStateFlow()

    private val _connectedNode = MutableStateFlow<EclairNodeCredentials?>(null)
    val connectedNode: StateFlow<EclairNodeCredentials?> = _connectedNode.asStateFlow()

    private val _balanceSummary = MutableStateFlow<EclairBalanceSummary?>(null)
    val balanceSummary: StateFlow<EclairBalanceSummary?> = _balanceSummary.asStateFlow()

    private val _storedNodesVersion = MutableStateFlow(0L)
    val storedNodesVersion: StateFlow<Long> = _storedNodesVersion.asStateFlow()

    var lastErrorMessage: String? = null
        private set

    private var client: EclairRestClient? = null
    private val sentRows = MutableStateFlow<List<WalletTransactionRow>>(emptyList())

    val isConnected: Boolean
        get() = _state.value is EclairConnectionState.Ready

    suspend fun connect(
        scheme: String,
        host: String,
        port: Int,
        apiPassword: String,
        label: String? = null
    ): EclairNodeCredentials {
        return connect(EclairConnectParser.parse(scheme, host, port, apiPassword, label))
    }

    suspend fun connect(
        connectionString: String,
        label: String? = null
    ): EclairNodeCredentials {
        return connect(EclairConnectParser.parse(connectionString, label))
    }

    suspend fun restoreActiveNode() {
        lastErrorMessage = null

        val storedNode = credentialStore.activeNode() ?: run {
            _state.value = EclairConnectionState.Disconnected
            _connectedNode.value = null
            _balanceSummary.value = null
            client = null
            throw EclairWalletException.NoStoredNode
        }

        try {
            validateConnectionCandidate(storedNode)
        } catch (error: Throwable) {
            client = null
            _balanceSummary.value = null
            _connectedNode.value = storedNode
            val message = connectionErrorMessage(error, storedNode.host)
            lastErrorMessage = message
            _state.value = EclairConnectionState.Error(message)
            throw error
        }

        _state.value = EclairConnectionState.Connecting
        val restClient = EclairRestClient(storedNode)

        runCatching {
            val info = restClient.getInfo()
            val savedNode = credentialStore.saveNode(storedNode.verified(info), makeActive = false)
            client = EclairRestClient(savedNode)
            _connectedNode.value = savedNode
            _state.value = EclairConnectionState.Ready
            notifyStoredNodesChanged()
            runCatching { refreshBalance() }
        }.onFailure { error ->
            val message = connectionErrorMessage(error, storedNode.host)
            lastErrorMessage = message
            _connectedNode.value = storedNode
            client = restClient
            _state.value = EclairConnectionState.Error(message)
            throw error
        }
    }

    fun disconnectFromActiveNode() {
        client = null
        _connectedNode.value = null
        _balanceSummary.value = null
        lastErrorMessage = null
        _state.value = EclairConnectionState.Disconnected
    }

    fun forgetNode(id: String) {
        if (_connectedNode.value?.id == id) {
            disconnectFromActiveNode()
        }
        credentialStore.deleteNode(id)
        sentRows.value = sentRows.value.filterNot { it.id.contains(id) }
        notifyStoredNodesChanged()
        onWalletActivity()
    }

    fun storedNodes(): List<EclairNodeCredentials> = credentialStore.loadNodes()

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

    suspend fun refreshNodeInfo(): EclairGetInfoResponse {
        ensureTorForActiveNodeIfNeeded()
        val info = requireClient().getInfo()
        _connectedNode.value?.let { current ->
            val saved = credentialStore.saveNode(current.verified(info), makeActive = false)
            _connectedNode.value = saved
            notifyStoredNodesChanged()
        }
        _state.value = EclairConnectionState.Ready
        return info
    }

    suspend fun refreshBalance(): EclairBalanceSummary = coroutineScope {
        ensureTorForActiveNodeIfNeeded()
        val restClient = requireClient()
        val channelBalancesDeferred = async { restClient.channelBalances() }
        val onChainBalanceDeferred = async { restClient.onChainBalance() }

        val channels = channelBalancesDeferred.await()
        val onChain = onChainBalanceDeferred.await()
        var channelSats = 0L
        for (index in 0 until channels.length()) {
            val channel = channels.optJSONObject(index) ?: continue
            channelSats += EclairMilliSatoshi.parse(channel.opt("canSend"))?.satsRoundedDown ?: 0L
        }
        val onChainSats = onChain.eclairOptNullableLong("confirmed") ?: 0L
        val summary = EclairBalanceSummary(channelBalanceSats = channelSats, onChainBalanceSats = onChainSats)
        _balanceSummary.value = summary
        _state.value = EclairConnectionState.Ready
        summary
    }

    suspend fun decodeInvoice(bolt11: String): EclairParseInvoiceResponse {
        ensureTorForActiveNodeIfNeeded()
        return requireClient().parseInvoice(bolt11)
    }

    suspend fun getSentInfo(paymentHash: String): List<EclairSentPaymentInfo> {
        ensureTorForActiveNodeIfNeeded()
        return requireClient().getSentInfo(paymentHash)
    }

    suspend fun payInvoice(
        bolt11: String,
        amountSats: Long?
    ): EclairPayResponse {
        ensureTorForActiveNodeIfNeeded()
        val decoded = runCatching { decodeInvoice(bolt11) }.getOrNull()
        val response = requireClient().payInvoice(bolt11 = bolt11, amountSats = amountSats)
        cacheSentRow(response, decoded)
        runCatching { refreshBalance() }.onFailure { error ->
            lastErrorMessage = error.message
            Log.w("EclairWalletManager", "Failed to refresh Eclair balance after payInvoice. ${error.localizedMessage}", error)
        }
        return response
    }

    suspend fun createInvoice(
        amountSats: Long?,
        memo: String?,
        expirySecs: Long = 3_600L
    ): EclairInvoiceResponse {
        ensureTorForActiveNodeIfNeeded()
        return requireClient().createInvoice(amountSats, memo, expirySecs)
    }

    fun walletScopeIdentifier(): String? {
        val nodeId = activeNodeIdentifier() ?: return null
        return "eclair:$nodeId"
    }

    fun activeNodeIdentifier(): String? {
        return _connectedNode.value?.id ?: credentialStore.activeNode()?.id
    }

    suspend fun prepareOutgoingPayment(
        paymentRequest: String,
        amountSats: Long?,
        feesIncluded: Boolean,
        comment: String?
    ): PreparedOutgoingPayment.Eclair {
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

        return PreparedOutgoingPayment.Eclair(
            preview = PaymentPreview(
                backend = WalletBackend.ECLAIR,
                destination = invoice,
                amountSats = resolvedAmountSats,
                feeSats = null,
                feesIncluded = feesIncluded,
                methodLabel = decoded.description?.trim()?.ifBlank { null } ?: "Lightning Invoice",
                lndAmountOverrideSats = amountOverrideSats,
                destinationPubkey = decoded.nodeId?.trim()?.ifBlank { null },
                paymentHash = decoded.paymentHash?.trim()?.ifBlank { null }
            )
        )
    }

    suspend fun fetchTransactionRows(maxCount: Int = 100): List<WalletTransactionRow> {
        ensureTorForActiveNodeIfNeeded()
        val received = requireClient().listReceivedPayments(maxCount).mapNotNull { it.toWalletTransactionRowOrNull() }
        return (sentRows.value + received)
            .distinctBy { it.id }
            .sortedByDescending { it.transactionTimestampMillis }
            .take(maxCount)
    }

    private suspend fun connect(parsedCredentials: EclairNodeCredentials): EclairNodeCredentials {
        lastErrorMessage = null
        _state.value = EclairConnectionState.Connecting

        return runCatching {
            val (workingCredentials, info) = firstWorkingClient(parsedCredentials.restConnectionCandidates)
            val savedCredentials = credentialStore.saveNode(workingCredentials.verified(info), makeActive = true)
            client = EclairRestClient(savedCredentials)
            _connectedNode.value = savedCredentials
            _state.value = EclairConnectionState.Ready
            notifyStoredNodesChanged()
            runCatching { refreshBalance() }
            savedCredentials
        }.getOrElse { error ->
            val message = connectionErrorMessage(error, parsedCredentials.host)
            lastErrorMessage = message
            _state.value = EclairConnectionState.Error(message)
            throw error
        }
    }

    private fun requireClient(): EclairRestClient {
        return client ?: throw EclairWalletException.NodeNotConnected
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
        candidates: List<EclairNodeCredentials>
    ): Pair<EclairNodeCredentials, EclairGetInfoResponse> {
        var lastError: Throwable? = null

        for (candidate in candidates) {
            try {
                validateConnectionCandidate(candidate)
                val connectTimeoutMillis = if (candidate.usesTor) 20_000 else 6_000
                val readTimeoutMillis = if (candidate.usesTor) 75_000 else 12_000
                val restClient = EclairRestClient(candidate, connectTimeoutMillis, readTimeoutMillis)
                return candidate to restClient.getInfo()
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw lastError ?: EclairWalletException.InvalidResponse
    }

    private suspend fun validateConnectionCandidate(candidate: EclairNodeCredentials) {
        EclairHostAccessPolicy.validateHost(candidate.host)
        if (candidate.usesTor) {
            RemoteNodeTorTransport.ensureStarted(appContext)
            return
        }
        EclairHostAccessPolicy.validateResolvedAddresses(resolveHost(candidate.host))
    }

    private suspend fun resolveHost(host: String): List<EclairResolvedAddress> = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) throw EclairWalletException.MissingNodeHost
        val resolvedAddresses = InetAddress.getAllByName(trimmedHost)
            .mapNotNull { address ->
                when (address) {
                    is Inet4Address -> EclairResolvedAddress.Ipv4(address)
                    is Inet6Address -> EclairResolvedAddress.Ipv6(address)
                    else -> null
                }
            }
            .distinctBy { address ->
                when (address) {
                    is EclairResolvedAddress.Ipv4 -> "4:${address.address.hostAddress}"
                    is EclairResolvedAddress.Ipv6 -> "6:${address.address.hostAddress?.substringBefore('%').orEmpty()}"
                }
            }
        if (resolvedAddresses.isEmpty()) throw UnknownHostException(trimmedHost)
        resolvedAddresses
    }

    private fun connectionErrorMessage(error: Throwable, host: String?): String {
        val normalizedHost = host?.trim()?.lowercase(Locale.US)
        return when (error) {
            is UnknownHostException -> {
                if (normalizedHost?.endsWith(".onion") == true) {
                    "Split could not reach this Tor Eclair address. Make sure Tor is running and the onion service address is correct."
                } else if (normalizedHost?.endsWith(".local") == true) {
                    "Split could not resolve this .local hostname. Make sure your phone is on the same local network as the node."
                } else {
                    "Split could not resolve this Eclair host. Make sure the node is reachable from this phone over your private network, Tailscale, or Tor."
                }
            }
            is ConnectException, is SocketTimeoutException -> {
                if (normalizedHost?.endsWith(".onion") == true) {
                    "Split could not reach this Tor Eclair node. Make sure Tor is running, the onion service is online, and the REST port is reachable."
                } else {
                    "Split could not reach this Eclair node. Make sure the node is online and reachable from this phone over your private network, Tailscale, or Tor."
                }
            }
            is EclairWalletException -> error.message ?: "Unable to connect to Eclair node."
            else -> error.message ?: "Unable to connect to Eclair node."
        }
    }

    private fun cacheSentRow(response: EclairPayResponse, decoded: EclairParseInvoiceResponse?) {
        val amountSats = max(response.recipientAmount?.satsRoundedDown ?: response.amount?.satsRoundedDown ?: decoded?.amountSats ?: 0L, 0L)
        val feeSats = max(response.feesPaid?.satsRoundedUp ?: 0L, 0L)
        val paymentHash = response.paymentHash ?: decoded?.paymentHash
        val timestampMillis = System.currentTimeMillis()
        val row = WalletTransactionRow(
            id = "eclair-payment-${paymentHash ?: response.paymentId ?: timestampMillis}",
            transactionTimestampMillis = timestampMillis,
            direction = "sent",
            btcAmount = amountSats.eclairBtcString(),
            feeBtcAmount = feeSats.eclairBtcString(),
            network = "lightning",
            status = "Completed",
            dateString = Date(timestampMillis).eclairDisplayString(),
            note = decoded?.description.orEmpty(),
            amountSats = amountSats,
            feeSats = feeSats,
            method = "Eclair",
            destinationPubkey = decoded?.nodeId,
            invoice = decoded?.serialized,
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = paymentHash,
            preimage = response.paymentPreimage,
            expiryDateString = null,
            txReferenceLabel = paymentHash?.let { "Payment Hash" },
            txReference = paymentHash,
            hasConversion = false
        )
        sentRows.value = (listOf(row) + sentRows.value).distinctBy { it.id }.take(100)
    }
}

private val EclairNodeCredentials.restConnectionCandidates: List<EclairNodeCredentials>
    get() {
        val trimmedHost = host.trim()
        val candidates = mutableListOf(this)
        if (trimmedHost.lowercase(Locale.US).endsWith(".local")) {
            val fallback = trimmedHost.dropLast(".local".length)
            if (fallback.isNotBlank()) candidates.add(withHost(fallback))
        }
        return candidates.distinctBy { "${it.scheme.lowercase(Locale.US)}://${it.host.lowercase(Locale.US)}:${it.port}" }
    }

private fun EclairReceivedPayment.toWalletTransactionRowOrNull(): WalletTransactionRow? {
    val amountSats = max(receivedAmount?.satsRoundedDown ?: amount?.satsRoundedDown ?: invoice?.amountSats ?: 0L, 0L)
    if (amountSats <= 0L) return null
    val timestampMillis = ((receivedAt ?: createdAt)?.value ?: (System.currentTimeMillis() / 1_000L)) * 1_000L
    val paymentHash = paymentHash ?: invoice?.paymentHash

    return WalletTransactionRow(
        id = "eclair-invoice-$id",
        transactionTimestampMillis = timestampMillis,
        direction = "received",
        btcAmount = amountSats.eclairBtcString(),
        feeBtcAmount = 0L.eclairBtcString(),
        network = "lightning",
        status = paymentStatus(statusType),
        dateString = Date(timestampMillis).eclairDisplayString(),
        note = invoice?.description.orEmpty(),
        amountSats = amountSats,
        feeSats = 0L,
        method = "Eclair",
        destinationPubkey = null,
        invoice = invoice?.serialized,
        lnAddress = null,
        lnurlDomain = null,
        lnurlComment = null,
        senderComment = null,
        paymentHash = paymentHash,
        preimage = null,
        expiryDateString = invoice?.expiry?.takeIf { it > 0L }?.let { Date(it * 1_000L).eclairDisplayString() },
        txReferenceLabel = paymentHash?.let { "Payment Hash" },
        txReference = paymentHash,
        hasConversion = false
    )
}

private fun paymentStatus(status: String?): String {
    return when (status?.trim()?.lowercase(Locale.US)) {
        "sent", "received", "complete", "completed", "paid", "succeeded", "success" -> "Completed"
        "pending", "in_flight", "pendingonchain", "pending_on_chain" -> "Pending"
        "failed", "failure" -> "Failed"
        else -> status?.trim()?.ifBlank { null } ?: "Completed"
    }
}

private fun Long.eclairBtcString(): String {
    return String.format(Locale.US, "%.8f", max(this, 0L).toDouble() / 100_000_000.0)
}

private fun Date.eclairDisplayString(): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(this)
}
