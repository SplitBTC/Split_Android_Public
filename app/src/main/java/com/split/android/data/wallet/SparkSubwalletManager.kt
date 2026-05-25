package com.split.android.data.wallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed class SparkSubwalletException(message: String) : IllegalStateException(message) {
    data object InvalidSeedPhrase : SparkSubwalletException("Invalid seed phrase.")
    data object MissingSeed : SparkSubwalletException("Spark sub-wallet seed is missing from this device.")
    data object NoStoredWallet : SparkSubwalletException("No Spark sub-wallet is stored on this device.")
    data object WalletNotConnected : SparkSubwalletException("Spark sub-wallet is not connected.")
}

sealed interface SparkSubwalletConnectionState {
    data object Disconnected : SparkSubwalletConnectionState
    data object Connecting : SparkSubwalletConnectionState
    data object Ready : SparkSubwalletConnectionState
    data class Error(val message: String) : SparkSubwalletConnectionState
}

data class SparkSubwalletBalanceSummary(
    val spendableSats: Long
)

class SparkSubwalletManager(
    context: Context,
    private val credentialStore: SparkSubwalletCredentialStore,
    private val store: SparkSubwalletStore,
    private val breezApiRepository: BreezApiRepository,
    private val sparkWalletClient: SparkWalletClient,
    private val mnemonicGenerator: MnemonicGenerator,
    private val onWalletActivity: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<SparkSubwalletConnectionState>(SparkSubwalletConnectionState.Disconnected)
    val state: StateFlow<SparkSubwalletConnectionState> = _state.asStateFlow()

    private val _connectedWallet = MutableStateFlow<SparkSubwalletCredentials?>(null)
    val connectedWallet: StateFlow<SparkSubwalletCredentials?> = _connectedWallet.asStateFlow()

    private val _balanceSummary = MutableStateFlow<SparkSubwalletBalanceSummary?>(null)
    val balanceSummary: StateFlow<SparkSubwalletBalanceSummary?> = _balanceSummary.asStateFlow()

    private val _pendingSeedWords = MutableStateFlow<List<String>>(emptyList())
    val pendingSeedWords: StateFlow<List<String>> = _pendingSeedWords.asStateFlow()

    private val _storedWalletsVersion = MutableStateFlow(0L)
    val storedWalletsVersion: StateFlow<Long> = _storedWalletsVersion.asStateFlow()

    var lastErrorMessage: String? = null
        private set

    private var pendingSeedPhrase: String? = null
    private var refreshJob: Job? = null

    val isConnected: Boolean
        get() = _state.value is SparkSubwalletConnectionState.Ready

    fun displayName(): String = _connectedWallet.value?.displayName ?: "Spark Wallet"

    fun createPendingWalletSeed() {
        val words = mnemonicGenerator.generateWords()
        pendingSeedPhrase = words.joinToString(" ")
        _pendingSeedWords.value = words
    }

    fun cancelPendingWalletSeed() {
        pendingSeedPhrase = null
        _pendingSeedWords.value = emptyList()
    }

    suspend fun createWallet(label: String): SparkSubwalletCredentials {
        val seedPhrase = pendingSeedPhrase?.trim()?.ifBlank { null }
            ?: throw SparkSubwalletException.InvalidSeedPhrase
        val wallet = connectAndSave(seedPhrase = seedPhrase, label = label)
        pendingSeedPhrase = null
        _pendingSeedWords.value = emptyList()
        return wallet
    }

    suspend fun restoreWallet(
        seedPhrase: String,
        label: String
    ): SparkSubwalletCredentials {
        return connectAndSave(seedPhrase = normalizedSeedPhrase(seedPhrase), label = label)
    }

    suspend fun restoreWalletIfNeeded(id: String? = null) {
        val wallet = id?.let(credentialStore::loadWallet) ?: credentialStore.activeWallet()
        if (wallet == null) {
            _state.value = SparkSubwalletConnectionState.Disconnected
            _connectedWallet.value = null
            _balanceSummary.value = null
            throw SparkSubwalletException.NoStoredWallet
        }

        connectStoredWallet(wallet)
    }

    suspend fun disconnectActiveWallet() {
        refreshJob?.cancel()
        refreshJob = null
        runCatching { sparkWalletClient.disconnect() }
        _connectedWallet.value = null
        _balanceSummary.value = null
        lastErrorMessage = null
        _state.value = SparkSubwalletConnectionState.Disconnected
    }

    suspend fun forgetWallet(id: String) {
        val wallet = credentialStore.loadWallet(id)
        if (_connectedWallet.value?.id == id) {
            disconnectActiveWallet()
        }

        wallet?.let {
            store.deleteSeed(it)
            store.deleteStorageDirectory(it)
        }
        credentialStore.deleteWallet(id)
        notifyStoredWalletsChanged()
        onWalletActivity()
    }

    fun storedWallets(): List<SparkSubwalletCredentials> = credentialStore.loadWallets()

    suspend fun setActiveStoredWallet(id: String) {
        val wallet = credentialStore.loadWallet(id) ?: throw SparkSubwalletException.NoStoredWallet
        connectStoredWallet(wallet)
        credentialStore.setActiveWallet(id)
        notifyStoredWalletsChanged()
    }

    fun renameWallet(id: String, label: String) {
        credentialStore.renameWallet(id, label)
        _connectedWallet.value?.let { current ->
            if (current.id == id) {
                _connectedWallet.value = current.withLabel(label)
            }
        }
        notifyStoredWalletsChanged()
        onWalletActivity()
    }

    suspend fun refreshBalance(): SparkSubwalletBalanceSummary {
        val snapshot = sparkWalletClient.refreshWalletSnapshot()
        val summary = SparkSubwalletBalanceSummary(spendableSats = snapshot.balanceSats)
        _balanceSummary.value = summary
        _state.value = SparkSubwalletConnectionState.Ready
        return summary
    }

    suspend fun createInvoice(
        amountSats: Long?,
        memo: String?
    ): ReceiveInvoice {
        requireConnectedWallet()
        return sparkWalletClient.createBolt11Invoice(
            amountSats = amountSats,
            description = memo
        )
    }

    suspend fun prepareOutgoingPayment(
        destination: String,
        amountSats: Long?,
        feesIncluded: Boolean = false,
        comment: String? = null
    ): PreparedOutgoingPayment {
        requireConnectedWallet()
        val prepared = sparkWalletClient.prepareOutgoingPayment(
            destination = destination,
            amountSats = amountSats,
            feesIncluded = feesIncluded,
            comment = comment
        )
        return prepared.withBackend(WalletBackend.SPARK_SUBWALLET)
    }

    suspend fun presetAmountSats(destination: String): Long? {
        requireConnectedWallet()
        return sparkWalletClient.presetAmountSats(destination)
    }

    suspend fun decodeBolt11InvoiceMetadata(invoice: String): Bolt11InvoiceMetadata? {
        requireConnectedWallet()
        return sparkWalletClient.decodeBolt11InvoiceMetadata(invoice)
    }

    suspend fun sendPreparedPayment(
        preparedPayment: PreparedOutgoingPayment
    ): PreparedOutgoingPaymentSendResult {
        requireConnectedWallet()
        val result = sparkWalletClient.sendPreparedPayment(preparedPayment)
        scheduleRefresh()
        return result
    }

    suspend fun fetchTransactionRows(maxCount: Int = 100): List<WalletTransactionRow> {
        requireConnectedWallet()
        return sparkWalletClient.listTransactions().take(maxCount)
    }

    fun walletScopeIdentifier(): String? {
        val walletId = activeWalletIdentifier() ?: return null
        return "spark-subwallet:$walletId"
    }

    fun activeWalletIdentifier(): String? {
        return _connectedWallet.value?.id ?: credentialStore.activeWallet()?.id
    }

    private suspend fun connectAndSave(
        seedPhrase: String,
        label: String
    ): SparkSubwalletCredentials {
        val normalizedLabel = label.trim()
        if (normalizedLabel.isEmpty()) {
            throw SparkSubwalletException.NoStoredWallet
        }

        val normalizedSeed = normalizedSeedPhrase(seedPhrase)
        validateSeedPhrase(normalizedSeed)

        val storageDirectoryName = store.temporaryStorageDirectoryName()
        val connection = connectWithSeed(
            seedPhrase = normalizedSeed,
            storageDirectoryName = storageDirectoryName
        )
        val walletPubkey = sparkWalletClient.currentWalletPubkey()
            .trim()
            .lowercase(Locale.US)
        val seedKey = store.seedKey(walletPubkey)
        val sparkAddress = runCatching { connection.authProvider.getSparkAddress() }.getOrNull()
        val existing = credentialStore.loadWallet(walletPubkey)

        val wallet = SparkSubwalletCredentials(
            id = walletPubkey,
            label = normalizedLabel,
            seedStorageKey = seedKey,
            storageDirectoryName = existing?.storageDirectoryName ?: storageDirectoryName,
            sparkAddress = sparkAddress,
            connectedAtMillis = existing?.connectedAtMillis ?: System.currentTimeMillis(),
            lastVerifiedAtMillis = System.currentTimeMillis()
        )

        if (existing != null && existing.storageDirectoryName != storageDirectoryName) {
            sparkWalletClient.disconnect()
            store.deleteStorageDirectory(storageDirectoryName)
            store.saveSeed(normalizedSeed, wallet)
            credentialStore.saveWallet(wallet, makeActive = true)
            connectStoredWallet(wallet.withLabel(normalizedLabel))
            notifyStoredWalletsChanged()
            return wallet
        } else {
            _connectedWallet.value = wallet
            _balanceSummary.value = SparkSubwalletBalanceSummary(connection.snapshot.balanceSats)
            _state.value = SparkSubwalletConnectionState.Ready
            attachEventListener()
        }

        store.saveSeed(normalizedSeed, wallet)
        credentialStore.saveWallet(wallet, makeActive = true)
        notifyStoredWalletsChanged()
        return wallet
    }

    private suspend fun connectStoredWallet(wallet: SparkSubwalletCredentials) {
        val seed = store.readSeed(wallet) ?: run {
            _state.value = SparkSubwalletConnectionState.Error(SparkSubwalletException.MissingSeed.message.orEmpty())
            throw SparkSubwalletException.MissingSeed
        }

        if (_connectedWallet.value?.id == wallet.id && isConnected) {
            refreshBalance()
            return
        }

        disconnectActiveWallet()
        _state.value = SparkSubwalletConnectionState.Connecting

        runCatching {
            val connection = connectWithSeed(
                seedPhrase = seed,
                storageDirectoryName = wallet.storageDirectoryName
            )
            val sparkAddress = runCatching { connection.authProvider.getSparkAddress() }.getOrNull()
            val verified = wallet.verified(sparkAddress)
            _connectedWallet.value = verified
            _balanceSummary.value = SparkSubwalletBalanceSummary(connection.snapshot.balanceSats)
            _state.value = SparkSubwalletConnectionState.Ready
            credentialStore.saveWallet(verified, makeActive = false)
            notifyStoredWalletsChanged()
            attachEventListener()
        }.onFailure { error ->
            val message = error.message ?: "Unable to connect Spark sub-wallet."
            lastErrorMessage = message
            _state.value = SparkSubwalletConnectionState.Error(message)
            throw error
        }
    }

    private suspend fun connectWithSeed(
        seedPhrase: String,
        storageDirectoryName: String
    ): WalletConnection {
        val apiKey = breezApiRepository.getApiKey()
        val storageDir = store.createStorageDirectory(storageDirectoryName).absolutePath
        return sparkWalletClient.connectWithSeed(
            mnemonic = seedPhrase,
            apiKey = apiKey,
            storageDir = storageDir
        )
    }

    private suspend fun attachEventListener() {
        sparkWalletClient.attachEventListener { event ->
            when (event) {
                is breez_sdk_spark.SdkEvent.PaymentSucceeded -> {
                    scheduleRefresh()
                }
                is breez_sdk_spark.SdkEvent.PaymentFailed,
                is breez_sdk_spark.SdkEvent.PaymentPending,
                is breez_sdk_spark.SdkEvent.Synced,
                is breez_sdk_spark.SdkEvent.LightningAddressChanged -> scheduleRefresh()
                else -> Unit
            }
        }
    }

    private fun scheduleRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(300L)
            runCatching { refreshBalance() }.onFailure { error ->
                Log.w(
                    "SparkSubwalletManager",
                    "Failed to refresh Spark sub-wallet. ${error.localizedMessage}",
                    error
                )
            }
            onWalletActivity()
            refreshJob = null
        }
    }

    private fun requireConnectedWallet(): SparkSubwalletCredentials {
        return _connectedWallet.value ?: throw SparkSubwalletException.WalletNotConnected
    }

    private fun notifyStoredWalletsChanged() {
        _storedWalletsVersion.value += 1
    }

    private fun normalizedSeedPhrase(phrase: String): String {
        return phrase
            .lowercase(Locale.US)
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun validateSeedPhrase(seedPhrase: String) {
        val words = seedPhrase.split(" ").filter { it.isNotBlank() }
        val validWords = mnemonicGenerator.validWordSet()
        if (words.size != 12 || words.any { it !in validWords }) {
            throw SparkSubwalletException.InvalidSeedPhrase
        }
    }
}

private fun PreparedOutgoingPayment.withBackend(
    backend: WalletBackend
): PreparedOutgoingPayment {
    fun PaymentPreview.updated(): PaymentPreview = copy(backend = backend)

    return when (this) {
        is PreparedOutgoingPayment.Standard -> copy(preview = preview.updated())
        is PreparedOutgoingPayment.Lnurl -> copy(preview = preview.updated())
        is PreparedOutgoingPayment.Lnd,
        is PreparedOutgoingPayment.Nwc,
        is PreparedOutgoingPayment.CoreLightning,
        is PreparedOutgoingPayment.Eclair -> this
    }
}
