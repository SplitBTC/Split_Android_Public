package com.split.android.data.wallet

import android.content.Context
import android.os.Build
import android.util.Log
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentType
import breez_sdk_spark.SdkException
import breez_sdk_spark.SdkEvent
import com.split.android.data.auth.AuthManager
import com.split.android.data.pricing.BtcPriceRepository
import com.split.android.data.rewards.RewardsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.roundToInt

sealed interface WalletState {
    data object Loading : WalletState
    data object NoWallet : WalletState
    data object Connecting : WalletState
    data class Ready(
        val balanceSats: Long,
        val sparkAddress: String
    ) : WalletState
    data class Error(
        val message: String,
        val preferRestoreFlow: Boolean = false
    ) : WalletState
}

class WalletManager(
    private val appContext: Context,
    private val seedStore: SeedStore,
    private val breezApiRepository: BreezApiRepository,
    private val sparkWalletClient: SparkWalletClient,
    private val mnemonicGenerator: MnemonicGenerator,
    private val authManager: AuthManager,
    private val rewardsRepository: RewardsRepository
) {
    companion object {
        private const val STARTUP_SEED_READ_ATTEMPTS = 3
        private const val STARTUP_SEED_READ_DELAY_MS = 350L
        private const val STARTUP_BOOTSTRAP_ATTEMPTS = 3
        private const val STARTUP_BOOTSTRAP_RETRY_DELAY_MS = 650L

        private val lightningUsernameAllowedCharacters =
            "abcdefghijklmnopqrstuvwxyz0123456789._-".toSet()
    }

    private val _state = MutableStateFlow<WalletState>(WalletState.Loading)
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private val _isStoredWalletRecoveryFlowActive = MutableStateFlow(false)
    val isStoredWalletRecoveryFlowActive: StateFlow<Boolean> =
        _isStoredWalletRecoveryFlowActive.asStateFlow()

    private val _pendingSeedWords = MutableStateFlow<List<String>>(emptyList())
    val pendingSeedWords: StateFlow<List<String>> = _pendingSeedWords.asStateFlow()

    private val _walletEventVersion = MutableStateFlow(0L)
    val walletEventVersion: StateFlow<Long> = _walletEventVersion.asStateFlow()

    private var pendingSeedPhrase: String? = null
    private var authProvider: WalletAuthProvider? = null
    private val btcPriceRepository = BtcPriceRepository()
    private val paymentUsdSnapshotStore = PaymentUsdSnapshotStore(appContext)
    private val usdSnapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val walletRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val usdSnapshotSyncMutex = Mutex()
    private var usdSnapshotSyncJob: Job? = null
    private val processedPaymentIds = mutableSetOf<String>()
    private val suppressedOutgoingSuccessPaymentIds = mutableSetOf<String>()
    private val suppressedOutgoingFailurePaymentIds = mutableSetOf<String>()
    private var didAttemptSilentWalletRepairInCurrentStartupFlow = false

    private sealed class WalletBootstrapFailure(
        message: String?,
        cause: Throwable
    ) : Exception(message, cause) {
        class PreConnect(val error: Throwable) : WalletBootstrapFailure(
            error.message,
            error
        )

        class Connect(
            val error: Throwable,
            val hadExistingStorage: Boolean
        ) : WalletBootstrapFailure(
            error.message,
            error
        )
    }

    suspend fun configure() {
        didAttemptSilentWalletRepairInCurrentStartupFlow = false
        val localSeedResult = readLocalSeedForStartup()
        val localSeed = localSeedResult.getOrElse { error ->
            authProvider = null
            activateStoredWalletRecoveryFlow()
            _state.value = WalletState.Error(
                message = walletSecureStorageFailureMessage(error)
            )
            return
        }

        if (localSeed.isNullOrBlank()) {
            authProvider = null
            clearStoredWalletRecoveryFlow()
            _state.value = WalletState.NoWallet
            return
        }

        connectWithSeed(
            seedPhrase = localSeed,
            persistSeedOnSuccess = false,
            source = WalletConnectionSource.CONFIGURE
        )
    }

    fun createWallet() {
        runCatching {
            val words = mnemonicGenerator.generateWords()
            pendingSeedPhrase = words.joinToString(" ")
            _pendingSeedWords.value = words
        }.onFailure { error ->
            _state.value = WalletState.Error(
                "Failed to generate seed phrase: ${error.message ?: "Unknown error"}"
            )
        }
    }

    suspend fun confirmPendingWalletCreation() {
        val phrase = pendingSeedPhrase?.trim().orEmpty()
        if (phrase.isEmpty()) {
            _state.value = WalletState.Error("No pending seed phrase to confirm.")
            return
        }

        pendingSeedPhrase = null
        _pendingSeedWords.value = emptyList()
        clearStoredWalletRecoveryFlow()

        connectWithSeed(
            seedPhrase = phrase,
            persistSeedOnSuccess = true,
            source = WalletConnectionSource.CREATE
        )
    }

    fun cancelPendingWalletCreation() {
        pendingSeedPhrase = null
        _pendingSeedWords.value = emptyList()
    }

    suspend fun restoreWallet(seedPhrase: String) {
        val normalizedWords = normalizeRecoveryPhraseWords(seedPhrase)
        val validationMessage = validateRecoveryPhraseWords(words = normalizedWords)

        if (validationMessage != null) {
            _state.value = WalletState.Error(
                message = validationMessage,
                preferRestoreFlow = true
            )
            return
        }

        connectWithSeed(
            seedPhrase = normalizedWords.joinToString(" "),
            persistSeedOnSuccess = true,
            source = WalletConnectionSource.RESTORE
        )
    }

    suspend fun removeWalletFromDevice() {
        usdSnapshotSyncJob?.cancel()
        usdSnapshotSyncJob = null
        processedPaymentIds.clear()
        suppressedOutgoingSuccessPaymentIds.clear()
        suppressedOutgoingFailurePaymentIds.clear()
        sparkWalletClient.disconnect()
        seedStore.clearSeed()
        paymentUsdSnapshotStore.clearAll()
        pendingSeedPhrase = null
        _pendingSeedWords.value = emptyList()
        authProvider = null
        clearStoredWalletRecoveryFlow()
        _state.value = WalletState.NoWallet
    }

    suspend fun shutdown() {
        usdSnapshotSyncJob?.cancel()
        usdSnapshotSyncJob = null
        processedPaymentIds.clear()
        suppressedOutgoingSuccessPaymentIds.clear()
        suppressedOutgoingFailurePaymentIds.clear()
        sparkWalletClient.disconnect()
        authProvider = null
    }

    fun currentAuthProvider(): WalletAuthProvider? = authProvider

    suspend fun signAuthMessage(message: String): SignedMessage {
        val provider = authProvider ?: throw IllegalStateException("Wallet is not ready for authentication.")
        return provider.signMessage(message)
    }

    suspend fun currentWalletPubkey(): String {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.currentWalletPubkey()
    }

    suspend fun transactionReportableStates(paymentIds: List<String>): Map<String, Boolean> {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return paymentUsdSnapshotStore.reportableStates(
            walletPubkey = sparkWalletClient.currentWalletPubkey(),
            paymentIds = paymentIds
        )
    }

    suspend fun setTransactionReportable(
        paymentId: String,
        direction: String,
        isReportable: Boolean
    ) {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        paymentUsdSnapshotStore.setReportable(
            walletPubkey = sparkWalletClient.currentWalletPubkey(),
            paymentId = paymentId,
            paymentType = if (direction == "received") "received" else "sent",
            isReportable = isReportable
        )
    }

    suspend fun setTransactionUserLog(
        paymentId: String,
        direction: String,
        userLog: String?
    ) {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        paymentUsdSnapshotStore.setUserLog(
            walletPubkey = sparkWalletClient.currentWalletPubkey(),
            paymentId = paymentId,
            paymentType = if (direction == "received") "received" else "sent",
            userLog = userLog
        )
    }

    suspend fun currentLightningAddress(): String? {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.currentLightningAddress()
    }

    suspend fun currentLightningAddressInfo(): WalletLightningAddressInfo? {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.currentLightningAddressInfo()
    }

    fun normalizedLightningUsername(input: String): String {
        val username = input.trim().lowercase()
        require(username.isNotBlank()) { "Please enter a Lightning address name." }
        require(username.all { it in lightningUsernameAllowedCharacters }) {
            "Lightning address names can only use letters, numbers, underscores, hyphens, and periods."
        }
        return username
    }

    suspend fun isLightningAddressAvailable(username: String): Boolean {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.isLightningAddressAvailable(
            normalizedLightningUsername(username)
        )
    }

    suspend fun createLightningAddress(
        username: String,
        description: String? = null
    ): WalletLightningAddressInfo {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        val normalized = normalizedLightningUsername(username)
        val available = sparkWalletClient.isLightningAddressAvailable(normalized)
        require(available) { "That Lightning address is already taken." }

        return sparkWalletClient.createLightningAddress(
            username = normalized,
            description = description
        )
    }

    suspend fun listContacts(): List<WalletContact> {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.listContacts()
    }

    suspend fun contactForPaymentIdentifier(paymentIdentifier: String): WalletContact? {
        val normalized = paymentIdentifier.trim().lowercase()
        return listContacts().firstOrNull {
            it.paymentIdentifier.trim().lowercase() == normalized
        }
    }

    suspend fun addContact(
        name: String,
        paymentIdentifier: String
    ): WalletContact {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        val normalizedPaymentIdentifier = paymentIdentifier.trim().lowercase()
        require(normalizedPaymentIdentifier.isNotBlank()) { "Payment identifier is required." }
        require(name.trim().isNotBlank()) { "Name is required." }

        val existing = contactForPaymentIdentifier(normalizedPaymentIdentifier)
        require(existing == null) { "A contact with this Lightning Address already exists." }

        return sparkWalletClient.addContact(
            name = name.trim(),
            paymentIdentifier = normalizedPaymentIdentifier
        )
    }

    suspend fun updateContact(
        id: String,
        name: String,
        paymentIdentifier: String
    ): WalletContact {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        val trimmedId = id.trim()
        val normalizedPaymentIdentifier = paymentIdentifier.trim().lowercase()
        require(trimmedId.isNotBlank()) { "Contact id is required." }
        require(normalizedPaymentIdentifier.isNotBlank()) { "Payment identifier is required." }
        require(name.trim().isNotBlank()) { "Name is required." }

        val existing = listContacts().firstOrNull {
            it.id != trimmedId &&
                it.paymentIdentifier.trim().lowercase() == normalizedPaymentIdentifier
        }
        require(existing == null) { "A contact with this Lightning Address already exists." }

        return sparkWalletClient.updateContact(
            id = trimmedId,
            name = name.trim(),
            paymentIdentifier = normalizedPaymentIdentifier
        )
    }

    suspend fun deleteContact(id: String) {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        require(id.trim().isNotBlank()) { "Contact id is required." }
        sparkWalletClient.deleteContact(id.trim())
    }

    suspend fun refreshWalletState() {
        val existingState = _state.value
        if (existingState !is WalletState.Ready) return

        runCatching {
            val snapshot = sparkWalletClient.refreshWalletSnapshot()
            _state.value = existingReadyState(snapshot)
        }.onFailure { error ->
            _state.value = WalletState.Error(
                error.message ?: "Failed to refresh wallet state."
            )
        }
    }

    suspend fun prepareOutgoingPayment(
        destination: String,
        amountSats: Long?,
        feesIncluded: Boolean = false,
        comment: String? = null
    ): PreparedOutgoingPayment {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        return sparkWalletClient.prepareOutgoingPayment(
            destination = destination,
            amountSats = amountSats,
            feesIncluded = feesIncluded,
            comment = comment
        )
    }

    suspend fun presetAmountSats(destination: String): Long? {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.presetAmountSats(destination)
    }

    suspend fun decodeBolt11InvoiceMetadata(invoice: String): Bolt11InvoiceMetadata? {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        return sparkWalletClient.decodeBolt11InvoiceMetadata(invoice)
    }

    suspend fun sendPreparedPayment(
        preparedPayment: PreparedOutgoingPayment
    ): PreparedOutgoingPaymentSendResult {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        val result = sparkWalletClient.sendPreparedPayment(preparedPayment)
        walletRefreshScope.launch {
            runCatching {
                refreshWalletState()
            }.onFailure { error ->
                Log.w(
                    "WalletManager",
                    "Failed to refresh wallet state after send. ${error.localizedMessage}",
                    error
                )
            }
        }
        return result
    }

    fun showOutgoingPaymentPending() {
        WalletToastManager.showOutgoingPaymentPending()
    }

    fun showOutgoingPaymentSuccess() {
        WalletToastManager.showOutgoingPaymentSuccess()
    }

    fun showIncomingPaymentSuccess() {
        WalletToastManager.showIncomingPaymentSuccess()
    }

    fun showOutgoingPaymentFailure(subtitle: String? = null) {
        WalletToastManager.showOutgoingPaymentFailure(subtitle = subtitle)
    }

    fun showIncomingPaymentFailure(subtitle: String? = null) {
        WalletToastManager.showIncomingPaymentFailure(subtitle = subtitle)
    }

    fun suppressOutgoingSuccessToastForPayment(paymentId: String) {
        suppressOutgoingSuccessToast(paymentId)
    }

    fun suppressOutgoingFailureToastForPayment(paymentId: String) {
        suppressOutgoingFailureToast(paymentId)
    }

    fun showOutgoingPaymentResult() {
        showOutgoingPaymentSuccess()
    }

    fun showIncomingPaymentResult() {
        showIncomingPaymentSuccess()
    }

    suspend fun createBolt11Invoice(
        amountSats: Long?,
        description: String?
    ): ReceiveInvoice {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        return sparkWalletClient.createBolt11Invoice(
            amountSats = amountSats,
            description = description
        )
    }

    suspend fun createCashAppBuyUrl(amountSats: Long?): String {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        return sparkWalletClient.createCashAppBuyUrl(amountSats = amountSats)
    }

    suspend fun listUnclaimedBitcoinDeposits(): List<UnclaimedBitcoinDeposit> {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        return sparkWalletClient.listUnclaimedBitcoinDeposits()
    }

    suspend fun claimDepositWithRate(
        txid: String,
        vout: Int,
        satPerVbyte: Long
    ) {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }

        sparkWalletClient.claimDepositWithRate(
            txid = txid,
            vout = vout,
            satPerVbyte = satPerVbyte
        )
        refreshWalletState()
    }

    suspend fun fetchTransactionRows(): List<WalletTransactionRow> {
        val existingState = _state.value
        require(existingState is WalletState.Ready) { "Wallet is not ready yet." }
        val rows = sparkWalletClient.listTransactions()
        val userLogs = paymentUsdSnapshotStore.userLogs(
            walletPubkey = sparkWalletClient.currentWalletPubkey(),
            paymentIds = rows.map { it.id }
        )
        val destinationMetadata = paymentUsdSnapshotStore.destinationMetadata(
            walletPubkey = sparkWalletClient.currentWalletPubkey(),
            paymentIds = rows.map { it.id }
        )
        captureDestinationMetadata(rows, sparkWalletClient.currentWalletPubkey())
        return rows.map { row ->
            val metadata = destinationMetadata[row.id]
            row.withUserLog(userLogs[row.id])
                .withDestinationMetadata(
                    destinationPubkey = metadata?.destinationPubkey,
                    paymentHash = metadata?.paymentHash
                )
        }.also { rowsWithUserLogs ->
            scheduleUsdSnapshotBackfill(rowsWithUserLogs)
        }
    }

    private fun scheduleUsdSnapshotBackfill(rows: List<WalletTransactionRow>) {
        if (usdSnapshotSyncJob?.isActive == true) {
            return
        }

        usdSnapshotSyncJob = usdSnapshotScope.launch {
            try {
                ensureUsdSnapshots(rows)
            } finally {
                usdSnapshotSyncJob = null
            }
        }
    }

    suspend fun ensureUsdSnapshots(rows: List<WalletTransactionRow>) {
        usdSnapshotSyncMutex.withLock {
            val walletPubkey = runCatching {
                sparkWalletClient.currentWalletPubkey()
            }.getOrNull() ?: return

            val completedRows = rows
                .filter { row ->
                    row.status == "Completed" &&
                        (row.direction == "sent" || row.direction == "received") &&
                        row.amountSats > 0
                }
                .sortedBy { it.transactionTimestampMillis }

            for (row in completedRows) {
                if (!currentCoroutineContext().isActive) return
                captureDestinationMetadata(row, walletPubkey)
                persistUsdSnapshotIfNeeded(
                    row = row,
                    walletPubkey = walletPubkey
                )
            }
        }
    }

    private fun captureDestinationMetadata(rows: List<WalletTransactionRow>, walletPubkey: String) {
        rows
            .filter { it.direction == "sent" }
            .forEach { row -> captureDestinationMetadata(row, walletPubkey) }
    }

    private fun captureDestinationMetadata(row: WalletTransactionRow, walletPubkey: String) {
        paymentUsdSnapshotStore.setDestinationMetadata(
            walletPubkey = walletPubkey,
            paymentId = row.id,
            paymentType = if (row.direction == "received") "received" else "sent",
            destinationPubkey = row.destinationPubkey,
            paymentHash = row.paymentHash
        )
    }

    private suspend fun persistUsdSnapshotIfNeeded(
        row: WalletTransactionRow,
        walletPubkey: String
    ) {
        if (paymentUsdSnapshotStore.containsSnapshot(walletPubkey, row.id)) {
            return
        }

        val rate = runCatching {
            btcPriceRepository.fetchUsdPriceAt(row.transactionTimestampMillis)
        }.getOrElse { error ->
            println("WalletManager: failed to capture BTC/USD snapshot for ${row.id}. ${error.localizedMessage}")
            return
        }

        if (rate <= 0.0) return

        paymentUsdSnapshotStore.upsert(
            PaymentUsdSnapshot(
                walletPubkey = walletPubkey,
                paymentId = row.id,
                paymentType = if (row.direction == "sent") "sent" else "received",
                usdValueAtTransaction = (row.amountSats.toDouble() / 100_000_000.0) * rate,
                btcUsdRateAtTransaction = rate
            )
        )
    }

    private suspend fun connectWithSeed(
        seedPhrase: String,
        persistSeedOnSuccess: Boolean,
        source: WalletConnectionSource
    ) {
        _state.value = WalletState.Connecting
        processedPaymentIds.clear()
        suppressedOutgoingSuccessPaymentIds.clear()
        suppressedOutgoingFailurePaymentIds.clear()

        val result = if (source == WalletConnectionSource.CONFIGURE) {
            bootstrapWalletForStartup(
                seedPhrase = seedPhrase,
                persistSeedOnSuccess = persistSeedOnSuccess
            )
        } else {
            bootstrapWallet(
                seedPhrase = seedPhrase,
                persistSeedOnSuccess = persistSeedOnSuccess
            )
        }

        if (result.isSuccess) {
            val connection = result.getOrThrow()
            authProvider = connection.authProvider
            clearStoredWalletRecoveryFlow()
            _state.value = existingReadyState(connection.snapshot)

            sparkWalletClient.attachEventListener { event ->
                when (event) {
                    is SdkEvent.PaymentSucceeded -> {
                        handleSucceededPaymentEvent(event.payment)
                        publishPaymentSuccessIfNeeded(event.payment)
                    }

                    is SdkEvent.PaymentFailed -> {
                        publishPaymentFailureIfNeeded(event.payment)
                    }

                    else -> Unit
                }
                refreshWalletState()
                _walletEventVersion.value = _walletEventVersion.value + 1L
            }
            return
        }

        val failure = result.exceptionOrNull()
        val walletFailure = failure as? WalletBootstrapFailure
        val rootError = when (walletFailure) {
            is WalletBootstrapFailure.PreConnect -> walletFailure.error
            is WalletBootstrapFailure.Connect -> walletFailure.error
            else -> failure ?: IllegalStateException("Unknown wallet bootstrap failure.")
        }

        Log.e(
            "WalletManager",
            "Failed to connect wallet during $source. " +
                "abis=${Build.SUPPORTED_ABIS.joinToString()} " +
                "detail=${walletFailureDebugSummary(rootError)}",
            rootError
        )

        authProvider = null

        when {
            walletFailure is WalletBootstrapFailure.Connect &&
                source == WalletConnectionSource.CONFIGURE -> {
                val shouldAttemptRepair =
                    !didAttemptSilentWalletRepairInCurrentStartupFlow &&
                        shouldAttemptSilentWalletRepair(
                            error = rootError,
                            hadExistingStorage = walletFailure.hadExistingStorage
                        )

                if (shouldAttemptRepair) {
                    didAttemptSilentWalletRepairInCurrentStartupFlow = true
                    val repairSucceeded = repairLocalWallet(
                        seedPhrase = seedPhrase,
                        persistSeedOnSuccess = persistSeedOnSuccess
                    )

                    if (repairSucceeded) {
                        return
                    }
                }

                activateStoredWalletRecoveryFlow()
                _state.value = WalletState.Error(message = walletRecoveryFailureMessage(rootError))
            }
            walletFailure is WalletBootstrapFailure.PreConnect &&
                source == WalletConnectionSource.CONFIGURE -> {
                activateStoredWalletRecoveryFlow()
                _state.value = WalletState.Error(
                    message = walletConnectionFailureMessage(rootError, source)
                )
            }
            else -> {
                _state.value = WalletState.Error(
                    message = walletConnectionFailureMessage(rootError, source),
                    preferRestoreFlow = source == WalletConnectionSource.RESTORE
                )
            }
        }
    }

    private suspend fun readLocalSeedForStartup(): Result<String?> {
        var lastFailure: Throwable? = null

        for (attempt in 1..STARTUP_SEED_READ_ATTEMPTS) {
            try {
                val seed = seedStore.readSeed()
                if (!seed.isNullOrBlank() || attempt == STARTUP_SEED_READ_ATTEMPTS) {
                    return Result.success(seed)
                }
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt == STARTUP_SEED_READ_ATTEMPTS) {
                    return Result.failure(error)
                }
            }

            delay(STARTUP_SEED_READ_DELAY_MS)
        }

        return lastFailure?.let { Result.failure(it) } ?: Result.success(null)
    }

    private suspend fun bootstrapWalletForStartup(
        seedPhrase: String,
        persistSeedOnSuccess: Boolean
    ): Result<WalletConnection> {
        var lastResult: Result<WalletConnection>? = null

        for (attempt in 1..STARTUP_BOOTSTRAP_ATTEMPTS) {
            val result = bootstrapWallet(
                seedPhrase = seedPhrase,
                persistSeedOnSuccess = persistSeedOnSuccess
            )

            if (result.isSuccess || attempt == STARTUP_BOOTSTRAP_ATTEMPTS) {
                return result
            }

            lastResult = result
            Log.w(
                "WalletManager",
                "Wallet startup attempt $attempt failed; retrying before showing recovery UI.",
                result.exceptionOrNull()
            )
            sparkWalletClient.disconnect()
            delay(STARTUP_BOOTSTRAP_RETRY_DELAY_MS * attempt)
        }

        return lastResult ?: Result.failure(IllegalStateException("Wallet startup did not run."))
    }

    private suspend fun bootstrapWallet(
        seedPhrase: String,
        persistSeedOnSuccess: Boolean
    ): Result<WalletConnection> {
        return runCatching {
            val hadExistingStorage = hasExistingBreezStorageDirectory()

            val apiKey = try {
                breezApiRepository.getApiKey()
            } catch (error: Throwable) {
                throw WalletBootstrapFailure.PreConnect(error)
            }

            val storageDir = try {
                makeStorageDir()
            } catch (error: Throwable) {
                throw WalletBootstrapFailure.PreConnect(error)
            }

            val connection = try {
                sparkWalletClient.connectWithSeed(
                    mnemonic = seedPhrase,
                    apiKey = apiKey,
                    storageDir = storageDir
                )
            } catch (error: Throwable) {
                throw WalletBootstrapFailure.Connect(
                    error = error,
                    hadExistingStorage = hadExistingStorage
                )
            }

            if (persistSeedOnSuccess) {
                seedStore.saveSeed(seedPhrase)
            }

            connection
        }
    }

    private suspend fun handleSucceededPaymentEvent(payment: Payment) {
        val paymentId = payment.id.trim()
        if (paymentId.isBlank() || processedPaymentIds.contains(paymentId)) {
            return
        }
        processedPaymentIds.add(paymentId)

        val row = payment.toWalletTransactionRow()
        val walletPubkey = runCatching {
            sparkWalletClient.currentWalletPubkey()
        }.getOrNull()

        if (walletPubkey != null) {
            runCatching {
                persistUsdSnapshotIfNeeded(
                    row = row,
                    walletPubkey = walletPubkey
                )
            }
        }

        val usdAmountCents = walletPubkey
            ?.let { paymentUsdSnapshotStore.snapshot(it, paymentId) }
            ?.usdValueAtTransaction
            ?.let { usdValue -> (usdValue * 100.0).roundToInt() }
            ?: fallbackUsdAmountCents(row.amountSats)

        runCatching {
            rewardsRepository.postRewardSpend(
                direction = paymentDirection(payment),
                usdAmountCents = usdAmountCents,
                btcAmountSats = row.amountSats,
                destinationPubkey = (payment.details as? PaymentDetails.Lightning)
                    ?.destinationPubkey
                    ?.trim()
                    ?.ifBlank { null },
                network = rewardSpendNetwork(payment.method),
                status = "Completed",
                paymentHash = row.paymentHash?.trim()?.ifBlank { null },
                authManager = authManager,
                walletManager = this
            )
        }.onFailure { error ->
            println(
                "WalletManager: failed to post reward spend for $paymentId. ${error.localizedMessage}"
            )
        }
    }

    private suspend fun fallbackUsdAmountCents(amountSats: Long): Int {
        val rate = runCatching {
            btcPriceRepository.fetchSpotUsdPrice()
        }.getOrElse { error ->
            println("WalletManager: failed to fetch fallback BTC/USD price. ${error.localizedMessage}")
            return 0
        }

        if (rate <= 0.0) return 0

        val usd = (amountSats.toDouble() / 100_000_000.0) * rate
        return (usd * 100.0).roundToInt()
    }

    private fun paymentDirection(payment: Payment): String {
        return when (payment.paymentType) {
            PaymentType.SEND -> "sent"
            PaymentType.RECEIVE -> "received"
        }
    }

    private fun paymentResultDirection(payment: Payment): WalletPaymentDirection {
        return when (payment.paymentType) {
            PaymentType.SEND -> WalletPaymentDirection.SENT
            PaymentType.RECEIVE -> WalletPaymentDirection.RECEIVED
        }
    }

    private fun rewardSpendNetwork(method: PaymentMethod): String {
        return when (method) {
            PaymentMethod.DEPOSIT,
            PaymentMethod.WITHDRAW -> "onchain"

            PaymentMethod.LIGHTNING,
            PaymentMethod.SPARK,
            PaymentMethod.TOKEN,
            PaymentMethod.UNKNOWN -> "lightning"
        }
    }

    private fun makeStorageDir(): String {
        val storageDir = File(appContext.filesDir, "breez-spark")
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            throw IllegalStateException("Unable to create local Breez storage directory.")
        }
        return storageDir.absolutePath
    }

    private fun hasExistingBreezStorageDirectory(): Boolean {
        val storageDir = File(appContext.filesDir, "breez-spark")
        return storageDir.exists()
    }

    private suspend fun repairLocalWallet(
        seedPhrase: String,
        persistSeedOnSuccess: Boolean
    ): Boolean {
        sparkWalletClient.disconnect()
        clearBreezStorage()

        val result = bootstrapWallet(
            seedPhrase = seedPhrase,
            persistSeedOnSuccess = persistSeedOnSuccess
        )

        return result.onSuccess { connection ->
            authProvider = connection.authProvider
            clearStoredWalletRecoveryFlow()
            _state.value = existingReadyState(connection.snapshot)

            sparkWalletClient.attachEventListener { event ->
                when (event) {
                    is SdkEvent.PaymentSucceeded -> {
                        handleSucceededPaymentEvent(event.payment)
                        publishPaymentSuccessIfNeeded(event.payment)
                    }

                    is SdkEvent.PaymentFailed -> {
                        publishPaymentFailureIfNeeded(event.payment)
                    }

                    else -> Unit
                }
                refreshWalletState()
                _walletEventVersion.value = _walletEventVersion.value + 1L
            }
        }.isSuccess
    }

    private fun clearBreezStorage() {
        val storageDir = File(appContext.filesDir, "breez-spark")
        storageDir.deleteRecursively()
    }

    private fun shouldAttemptSilentWalletRepair(
        error: Throwable,
        hadExistingStorage: Boolean
    ): Boolean {
        if (!hadExistingStorage) {
            return false
        }

        return unwrapWalletFailure(error) is SdkException.StorageException
    }

    private fun existingReadyState(snapshot: WalletSnapshot): WalletState.Ready {
        return WalletState.Ready(
            balanceSats = snapshot.balanceSats,
            sparkAddress = snapshot.sparkAddress
        )
    }

    private fun activateStoredWalletRecoveryFlow() {
        _isStoredWalletRecoveryFlowActive.value = true
    }

    private fun clearStoredWalletRecoveryFlow() {
        _isStoredWalletRecoveryFlowActive.value = false
    }

    private fun suppressOutgoingSuccessToast(paymentId: String) {
        val normalized = paymentId.trim()
        if (normalized.isNotEmpty()) {
            suppressedOutgoingSuccessPaymentIds.add(normalized)
        }
    }

    private fun suppressOutgoingFailureToast(paymentId: String) {
        val normalized = paymentId.trim()
        if (normalized.isNotEmpty()) {
            suppressedOutgoingFailurePaymentIds.add(normalized)
        }
    }

    private fun consumeSuppressedOutgoingSuccessToastIfNeeded(paymentId: String): Boolean {
        val normalized = paymentId.trim()
        if (normalized.isEmpty()) return false
        return suppressedOutgoingSuccessPaymentIds.remove(normalized)
    }

    private fun consumeSuppressedOutgoingFailureToastIfNeeded(paymentId: String): Boolean {
        val normalized = paymentId.trim()
        if (normalized.isEmpty()) return false
        return suppressedOutgoingFailurePaymentIds.remove(normalized)
    }

    private fun publishPaymentSuccessIfNeeded(payment: Payment) {
        val direction = paymentResultDirection(payment)
        if (direction == WalletPaymentDirection.SENT &&
            consumeSuppressedOutgoingSuccessToastIfNeeded(payment.id)
        ) {
            return
        }

        when (direction) {
            WalletPaymentDirection.SENT -> WalletToastManager.showOutgoingPaymentSuccess()
            WalletPaymentDirection.RECEIVED -> WalletToastManager.showIncomingPaymentSuccess()
        }
    }

    private fun publishPaymentFailureIfNeeded(payment: Payment) {
        val direction = paymentResultDirection(payment)
        if (direction == WalletPaymentDirection.SENT &&
            consumeSuppressedOutgoingFailureToastIfNeeded(payment.id)
        ) {
            return
        }

        when (direction) {
            WalletPaymentDirection.SENT -> WalletToastManager.showOutgoingPaymentFailure()
            WalletPaymentDirection.RECEIVED -> WalletToastManager.showIncomingPaymentFailure()
        }
    }

}
