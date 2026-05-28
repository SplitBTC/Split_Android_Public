package com.split.android.data.wallet

import android.content.Context
import android.util.Log
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentType
import com.split.android.data.auth.AuthManager
import com.split.android.data.pricing.BtcPriceRepository
import com.split.android.data.rewards.RewardTrace
import com.split.android.data.rewards.RewardsRepository
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
import kotlin.math.roundToInt

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
    private val rewardsRepository: RewardsRepository,
    private val authManager: AuthManager,
    private val walletManager: WalletManager,
    private val onWalletActivity: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val btcPriceRepository = BtcPriceRepository()

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
    private val processedRewardPaymentIds = mutableSetOf<String>()

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
        RewardTrace.i(
            "subwallet sendPrepared start backend=${preparedPayment.preview.backend} " +
                "rewardEligible=${preparedPayment.preview.rewardEligible} " +
                "merchantHashFp=${RewardTrace.fp(preparedPayment.preview.merchantPubkeyHash)} " +
                "dest=${RewardTrace.present(preparedPayment.preview.destinationPubkey)} " +
                "destFp=${RewardTrace.fp(preparedPayment.preview.destinationPubkey)} " +
                "paymentHash=${RewardTrace.present(preparedPayment.preview.paymentHash)} " +
                "paymentHashFp=${RewardTrace.fp(preparedPayment.preview.paymentHash)}"
        )
        val result = sparkWalletClient.sendPreparedPayment(preparedPayment)
        RewardTrace.i("subwallet sendPrepared result=${result.javaClass.simpleName}")
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
                    RewardTrace.i(
                        "subwallet event PaymentSucceeded id=${RewardTrace.id(event.payment.id)} " +
                            "status=${event.payment.status} type=${event.payment.paymentType} " +
                            "method=${event.payment.method} " +
                            "details=${event.payment.details?.javaClass?.simpleName ?: "none"}"
                    )
                    handleSucceededPaymentEvent(event.payment)
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

    private suspend fun handleSucceededPaymentEvent(payment: Payment) {
        val paymentId = payment.id.trim()
        val duplicate = processedRewardPaymentIds.contains(paymentId)
        if (paymentId.isBlank() || duplicate) {
            RewardTrace.i(
                "subwallet handleSucceeded skip duplicate-or-blank id=${RewardTrace.id(payment.id)} " +
                    "blank=${paymentId.isBlank()} duplicate=$duplicate"
            )
            return
        }
        processedRewardPaymentIds.add(paymentId)

        if (payment.paymentType != PaymentType.SEND) {
            RewardTrace.i(
                "subwallet reward skip non-send id=${RewardTrace.id(payment.id)} " +
                    "type=${payment.paymentType}"
            )
            return
        }

        val row = payment.toWalletTransactionRow()
        RewardTrace.i(
            "subwallet handleSucceeded start id=${RewardTrace.id(paymentId)} " +
                "rowNetwork=${row.network} rowAmountSats=${row.amountSats} " +
                "rowInvoice=${RewardTrace.present(row.invoice)} " +
                "rowPaymentHash=${RewardTrace.present(row.paymentHash)} " +
                "rowPreimage=${RewardTrace.present(row.preimage)} " +
                "rowDest=${RewardTrace.present(row.destinationPubkey)}"
        )
        val proof = payment.rewardClaimProof(row)
        if (proof == null) {
            RewardTrace.i(
                "subwallet reward skip no-proof id=${RewardTrace.id(payment.id)} " +
                    "details=${payment.details?.javaClass?.simpleName ?: "none"}"
            )
            return
        }
        RewardTrace.i(
            "subwallet reward proof id=${RewardTrace.id(payment.id)} " +
                "dest=${RewardTrace.present(proof.destinationPubkey)} " +
                "destFp=${RewardTrace.fp(proof.destinationPubkey)} " +
                "invoice=${RewardTrace.present(proof.invoice)} " +
                "invoiceLen=${proof.invoice?.length ?: 0} " +
                "paymentHash=${RewardTrace.present(proof.paymentHash)} " +
                "paymentHashFp=${RewardTrace.fp(proof.paymentHash)} " +
                "preimage=${RewardTrace.present(proof.preimage)}"
        )
        val destinationPubkey = proof.destinationPubkey ?: run {
            RewardTrace.i("subwallet reward skip missing-destination id=${RewardTrace.id(payment.id)}")
            return
        }
        val invoice = proof.invoice ?: run {
            RewardTrace.i("subwallet reward skip missing-invoice id=${RewardTrace.id(payment.id)}")
            return
        }
        val paymentHash = proof.paymentHash ?: run {
            RewardTrace.i("subwallet reward skip missing-payment-hash id=${RewardTrace.id(payment.id)}")
            return
        }
        val preimage = proof.preimage ?: run {
            RewardTrace.i("subwallet reward skip missing-preimage id=${RewardTrace.id(payment.id)}")
            return
        }

        val rewardsCheck = runCatching {
            rewardsRepository.localRewardsCheck(destinationPubkey)
        }.getOrElse { error ->
            RewardTrace.w(
                "subwallet reward eligibility failed id=${RewardTrace.id(payment.id)} " +
                    "error=${error.localizedMessage.orEmpty()}",
                error
            )
            Log.w(
                "SparkSubwalletManager",
                "Failed to check Spark sub-wallet reward eligibility. ${error.localizedMessage}",
                error
            )
            return
        }
        RewardTrace.i(
            "subwallet reward eligibility id=${RewardTrace.id(payment.id)} " +
                "eligible=${rewardsCheck.rewardEligible} " +
                "merchantHashFp=${RewardTrace.fp(rewardsCheck.merchantPubkeyHash)}"
        )
        if (!rewardsCheck.rewardEligible) {
            RewardTrace.i("subwallet reward skip not-eligible id=${RewardTrace.id(payment.id)}")
            return
        }

        val amountSats = row.amountSats
        val usdAmountCents = fallbackUsdAmountCents(amountSats)
        runCatching {
            RewardTrace.i("subwallet reward post start id=${RewardTrace.id(payment.id)}")
            val response = rewardsRepository.postEncryptedRewardSpendClaim(
                merchantPubkeyHash = rewardsCheck.merchantPubkeyHash,
                paymentHash = paymentHash,
                preimage = preimage,
                btcAmountSats = amountSats,
                usdAmountCents = usdAmountCents,
                invoice = invoice,
                authManager = authManager,
                walletManager = walletManager
            )
            RewardTrace.i(
                "subwallet reward post result id=${RewardTrace.id(payment.id)} " +
                    "ok=${response.ok} applied=${response.rewardSpendApplied}"
            )
        }.onFailure { error ->
            RewardTrace.w(
                "subwallet reward post failed id=${RewardTrace.id(payment.id)} " +
                    "error=${error.localizedMessage.orEmpty()}",
                error
            )
            Log.w(
                "SparkSubwalletManager",
                "Failed to post Spark sub-wallet encrypted reward claim. ${error.localizedMessage}",
                error
            )
        }
    }

    private suspend fun fallbackUsdAmountCents(amountSats: Long): Int {
        val rate = runCatching {
            btcPriceRepository.fetchSpotUsdPrice()
        }.getOrElse { error ->
            Log.w(
                "SparkSubwalletManager",
                "Failed to fetch fallback BTC/USD price. ${error.localizedMessage}",
                error
            )
            return 0
        }

        if (rate <= 0.0) return 0

        val usd = (amountSats.toDouble() / 100_000_000.0) * rate
        return (usd * 100.0).roundToInt()
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
