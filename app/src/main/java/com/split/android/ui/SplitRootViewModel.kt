package com.split.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.split.android.data.auth.AuthManager
import com.split.android.data.auth.AuthState
import com.split.android.data.events.BitcoinEventsRepository
import com.split.android.data.events.BitcoinEventsResponse
import com.split.android.data.coupons.MerchantCouponsRepository
import com.split.android.data.coupons.NearbyCouponsResponse
import com.split.android.data.coupons.RedeemNearbyCouponResponse
import com.split.android.data.map.BtcMerchantMapRepository
import com.split.android.data.map.BtcMerchantPlace
import com.split.android.data.messages.MessageConversationPreview
import com.split.android.data.messages.AttachmentMessagePayload
import com.split.android.data.merchants.MerchantReportRepository
import com.split.android.data.messages.MessageRecipientMetadata
import com.split.android.data.messages.MessageReactionKind
import com.split.android.data.messages.MessageStore
import com.split.android.data.messages.MessageAttachmentManager
import com.split.android.data.messages.MessagingBlockedUser
import com.split.android.data.messages.MessagingBlockRepository
import com.split.android.data.messages.MessageKeyManager
import com.split.android.data.messages.MessageSyncScheduler
import com.split.android.data.messages.MessagingDeviceTokenSyncScheduler
import com.split.android.data.messages.MessagingDeviceTokenManager
import com.split.android.data.messages.PaymentRequestMessagePayload
import com.split.android.data.messages.MessageSendResult
import com.split.android.data.messages.MessagingRepository
import com.split.android.data.messages.MessagingUiState
import com.split.android.data.messages.StoredMessage
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.pricing.BtcPriceRepository
import com.split.android.data.pricing.BitcoinChartRange
import com.split.android.data.pricing.BitcoinPricePoint
import com.split.android.data.profile.ProfileIdentityRepository
import com.split.android.data.rewards.RewardStatsResponse
import com.split.android.data.rewards.RewardsRepository
import com.split.android.data.wallet.BreezApiRepository
import com.split.android.data.wallet.BreezSparkWalletClient
import com.split.android.data.wallet.ActiveSpendWalletStore
import com.split.android.data.wallet.LndBalanceSummary
import com.split.android.data.wallet.LndConnectionState
import com.split.android.data.wallet.LndCredentialStore
import com.split.android.data.wallet.LndNodeCredentials
import com.split.android.data.wallet.LndWalletException
import com.split.android.data.wallet.LndWalletManager
import com.split.android.data.wallet.MnemonicGenerator
import com.split.android.data.wallet.PaymentPreview
import com.split.android.data.wallet.PaymentUsdSnapshot
import com.split.android.data.wallet.PaymentUsdSnapshotStore
import com.split.android.data.wallet.PreparedOutgoingPayment
import com.split.android.data.wallet.PreparedOutgoingPaymentSendResult
import com.split.android.data.wallet.ReceiveInvoice
import com.split.android.data.wallet.SeedStore
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.UnclaimedBitcoinDeposit
import com.split.android.data.wallet.WalletContact
import com.split.android.data.wallet.WalletLightningAddressInfo
import com.split.android.data.wallet.WalletTransactionRow
import com.split.android.data.wallet.WalletManager
import com.split.android.data.wallet.WalletState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SplitRootViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val httpClient = SplitHttpClient()
    private val seedStore = SeedStore(application)
    private val breezApiRepository = BreezApiRepository(httpClient)
    private val btcPriceRepository = BtcPriceRepository()
    private val rewardsRepository = RewardsRepository(httpClient)
    private val authManager = AuthManager(httpClient)
    private val profileIdentityRepository = ProfileIdentityRepository(httpClient)
    private val btcMerchantMapRepository = BtcMerchantMapRepository()
    private val merchantReportRepository = MerchantReportRepository(httpClient)
    private val merchantCouponsRepository = MerchantCouponsRepository(httpClient)
    private val bitcoinEventsRepository = BitcoinEventsRepository(httpClient)
    private val messagingBlockRepository = MessagingBlockRepository(httpClient)
    private val sparkWalletClient = BreezSparkWalletClient()
    private val mnemonicGenerator = MnemonicGenerator(application)
    private val lndCredentialStore = LndCredentialStore(application)
    private val activeSpendWalletStore = ActiveSpendWalletStore(application, lndCredentialStore)
    private val lndPaymentUsdSnapshotStore = PaymentUsdSnapshotStore(application)
    private val _walletEventVersion = MutableStateFlow(0L)
    private val messageStore = MessageStore.getInstance(application)
    private val messageAttachmentManager = MessageAttachmentManager(application)
    private val messageKeyManager = MessageKeyManager(application, httpClient)
    private val messagingDeviceTokenManager = MessagingDeviceTokenManager(
        context = application,
        httpClient = httpClient,
        messageKeyManager = messageKeyManager
    )
    private val walletManager = WalletManager(
        appContext = application,
        seedStore = seedStore,
        breezApiRepository = breezApiRepository,
        sparkWalletClient = sparkWalletClient,
        mnemonicGenerator = mnemonicGenerator,
        authManager = authManager,
        rewardsRepository = rewardsRepository
    )
    private val lndWalletManager = LndWalletManager(
        context = application,
        credentialStore = lndCredentialStore,
        onPaymentReceived = {
            walletManager.showIncomingPaymentResult()
            notifyWalletActivity()
        },
        onWalletActivity = {
            notifyWalletActivity()
        }
    )
    private val messagingRepository = MessagingRepository(
        httpClient = httpClient,
        messageKeyManager = messageKeyManager,
        messageStore = messageStore,
        attachmentManager = messageAttachmentManager,
        deviceTokenManager = messagingDeviceTokenManager
    )

    val walletState: StateFlow<WalletState> = walletManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = WalletState.Loading
        )

    val isStoredWalletRecoveryFlowActive: StateFlow<Boolean> =
        walletManager.isStoredWalletRecoveryFlowActive
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )

    val authState: StateFlow<AuthState> = authManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AuthState.Idle
        )

    val hasValidSession: StateFlow<Boolean> = authManager.hasValidSession
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    val lastAuthError: StateFlow<String?> = authManager.lastError
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val pendingSeedWords: StateFlow<List<String>> = walletManager.pendingSeedWords
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val messagingUiState: StateFlow<MessagingUiState> = messagingRepository.uiState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MessagingUiState()
        )

    val storedMessages: StateFlow<List<StoredMessage>> = messagingRepository.messages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val recipientMetadataByConversationId: StateFlow<Map<String, MessageRecipientMetadata>> =
        messagingRepository.recipientMetadataByConversationId
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap()
            )

    val cachedAttachmentIds = messageAttachmentManager.cachedAttachmentIds
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    val walletEventVersion: StateFlow<Long> = _walletEventVersion.asStateFlow()

    val activeSpendWallet: StateFlow<SpendWalletSource> = activeSpendWalletStore.activeWallet

    val lndConnectionState: StateFlow<LndConnectionState> = lndWalletManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LndConnectionState.Disconnected
        )

    val connectedLndNode: StateFlow<LndNodeCredentials?> = lndWalletManager.connectedNode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val lndBalanceSummary: StateFlow<LndBalanceSummary?> = lndWalletManager.balanceSummary
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val _contactsByPaymentIdentifier = MutableStateFlow<Map<String, WalletContact>>(emptyMap())
    val contactsByPaymentIdentifier: StateFlow<Map<String, WalletContact>> = _contactsByPaymentIdentifier.asStateFlow()

    init {
        bootstrap()
        observeWalletEvents()
    }

    fun bootstrap() {
        launchGuarded("bootstrap") {
            MessageSyncScheduler.disableLegacyPeriodicSync(getApplication())
            walletManager.configure()
            runCatching { lndWalletManager.restoreActiveNode() }
            activeSpendWalletStore.reconcileWithStoredNode()
            authenticateIfPossible()
            loadContactsIfPossible()
            if (walletManager.state.value is WalletState.Ready) {
                MessagingDeviceTokenSyncScheduler.enqueue(getApplication())
            }
        }
    }

    fun restoreWallet(seedPhrase: String) {
        launchGuarded("restoreWallet") {
            MessageSyncScheduler.disableLegacyPeriodicSync(getApplication())
            walletManager.restoreWallet(seedPhrase)
            runCatching { lndWalletManager.restoreActiveNode() }
            activeSpendWalletStore.reconcileWithStoredNode()
            authenticateIfPossible()
            loadContactsIfPossible()
            if (walletManager.state.value is WalletState.Ready) {
                MessagingDeviceTokenSyncScheduler.enqueue(getApplication())
            }
        }
    }

    fun createWallet() {
        walletManager.createWallet()
    }

    fun confirmPendingWalletCreation() {
        launchGuarded("confirmPendingWalletCreation") {
            MessageSyncScheduler.disableLegacyPeriodicSync(getApplication())
            walletManager.confirmPendingWalletCreation()
            runCatching { lndWalletManager.restoreActiveNode() }
            activeSpendWalletStore.reconcileWithStoredNode()
            authenticateIfPossible()
            loadContactsIfPossible()
            if (walletManager.state.value is WalletState.Ready) {
                MessagingDeviceTokenSyncScheduler.enqueue(getApplication())
            }
        }
    }

    fun cancelPendingWalletCreation() {
        walletManager.cancelPendingWalletCreation()
    }

    fun clearWallet() {
        launchGuarded("clearWallet") {
            walletManager.removeWalletFromDevice()
            lndWalletManager.disconnectFromActiveNode()
            messagingRepository.clearAll()
            authManager.invalidateSession()
            _contactsByPaymentIdentifier.value = emptyMap()
            MessageSyncScheduler.cancel(getApplication())
            MessagingDeviceTokenSyncScheduler.cancel(getApplication())
        }
    }

    fun loadContacts() {
        launchGuarded("loadContacts") {
            loadContactsIfPossible()
        }
    }

    fun retryAuth() {
        launchGuarded("retryAuth") {
            authenticateIfPossible()
        }
    }

    suspend fun prepareOutgoingPayment(
        destination: String,
        amountSats: Long?,
        feesIncluded: Boolean = false,
        comment: String? = null
    ): PreparedOutgoingPayment {
        return if (activeSpendWalletStore.isLndActive) {
            lndWalletManager.prepareOutgoingPayment(
                paymentRequest = destination,
                amountSats = amountSats,
                feesIncluded = feesIncluded,
                comment = comment
            )
        } else {
            walletManager.prepareOutgoingPayment(
                destination = destination,
                amountSats = amountSats,
                feesIncluded = feesIncluded,
                comment = comment
            )
        }
    }

    suspend fun presetSendAmountSats(
        destination: String
    ): Long? {
        if (!activeSpendWalletStore.isLndActive) {
            return walletManager.presetAmountSats(destination)
        }

        val normalized = destination.trim()
        if (!com.split.android.data.wallet.LndLightningPaymentResolver.isBolt11(normalized)) {
            return null
        }

        if (!lndWalletManager.isConnected) {
            runCatching { lndWalletManager.restoreActiveNode() }
        }

        return runCatching {
            lndWalletManager.decodeInvoice(normalized).amountSats
        }.getOrNull()?.takeIf { it > 0L }
    }

    suspend fun sendPreparedPayment(preparedPayment: PreparedOutgoingPayment) {
        when (preparedPayment) {
            is PreparedOutgoingPayment.Lnd -> sendPreparedLndPayment(preparedPayment.preview)
            else -> walletManager.sendPreparedPayment(preparedPayment)
        }
    }

    fun submitPreparedPayment(preparedPayment: PreparedOutgoingPayment) {
        walletManager.showOutgoingPaymentPending()
        viewModelScope.launch {
            runCatching {
                when (preparedPayment) {
                    is PreparedOutgoingPayment.Lnd -> {
                        sendPreparedLndPayment(preparedPayment.preview)
                    }

                    else -> {
                        when (val result = walletManager.sendPreparedPayment(preparedPayment)) {
                            is PreparedOutgoingPaymentSendResult.Completed -> {
                                walletManager.suppressOutgoingSuccessToastForPayment(result.paymentId)
                                walletManager.showOutgoingPaymentSuccess()
                            }

                            is PreparedOutgoingPaymentSendResult.Pending -> Unit

                            is PreparedOutgoingPaymentSendResult.Failed -> {
                                result.paymentId?.let(walletManager::suppressOutgoingFailureToastForPayment)
                                val message = result.message?.trim().orEmpty().ifBlank {
                                    "Unable to send payment."
                                }
                                walletManager.showOutgoingPaymentFailure(subtitle = message)
                            }
                        }
                    }
                }
            }.onFailure { error ->
                val message = error.message?.trim().orEmpty().ifBlank {
                    "Unable to send payment."
                }
                walletManager.showOutgoingPaymentFailure(subtitle = message)
            }
        }
    }

    suspend fun createBolt11Invoice(
        amountSats: Long,
        description: String?
    ): ReceiveInvoice {
        return if (activeSpendWalletStore.isLndActive) {
            if (!lndWalletManager.isConnected) {
                lndWalletManager.restoreActiveNode()
            }

            val response = lndWalletManager.createInvoice(
                amountSats = amountSats,
                memo = description
            )
            ReceiveInvoice(
                invoice = response.paymentRequest,
                amountSats = amountSats,
                description = description?.trim()?.ifBlank { null },
                feeSats = 0L
            )
        } else {
            walletManager.createBolt11Invoice(
                amountSats = amountSats,
                description = description
            )
        }
    }

    suspend fun createCashAppBuyUrl(amountSats: Long): String {
        return walletManager.createCashAppBuyUrl(amountSats = amountSats)
    }

    suspend fun fetchUnclaimedBitcoinDeposits(): List<UnclaimedBitcoinDeposit> {
        return walletManager.listUnclaimedBitcoinDeposits()
    }

    suspend fun claimBitcoinDeposit(
        deposit: UnclaimedBitcoinDeposit
    ) {
        val feeRate = deposit.requiredFeeRateSatPerVbyte
            ?: throw IllegalStateException("This deposit is not currently claimable.")

        walletManager.claimDepositWithRate(
            txid = deposit.txid,
            vout = deposit.vout,
            satPerVbyte = feeRate
        )
    }

    suspend fun fetchTransactionRows(
        source: SpendWalletSource = activeSpendWallet.value
    ): List<WalletTransactionRow> {
        return if (source == SpendWalletSource.LND) {
            if (!lndWalletManager.isConnected) {
                lndWalletManager.restoreActiveNode()
            }
            val rows = lndWalletManager.fetchTransactionRows()
            val scope = lndWalletManager.walletScopeIdentifier()
                ?: throw LndWalletException.NoStoredNode
            val userLogs = lndPaymentUsdSnapshotStore.userLogs(
                walletPubkey = scope,
                paymentIds = rows.map { it.id }
            )
            rows.map { row -> row.withUserLog(userLogs[row.id]) }
        } else {
            walletManager.fetchTransactionRows()
        }
    }

    suspend fun ensureTransactionUsdSnapshots(
        transactions: List<WalletTransactionRow>,
        source: SpendWalletSource = activeSpendWallet.value
    ) {
        if (source == SpendWalletSource.LND) {
            ensureLndUsdSnapshots(transactions)
        } else {
            walletManager.ensureUsdSnapshots(transactions)
        }
    }

    suspend fun transactionUsdSnapshots(
        paymentIds: List<String>,
        source: SpendWalletSource = activeSpendWallet.value
    ): Map<String, PaymentUsdSnapshot> {
        return if (source == SpendWalletSource.LND) {
            val scope = lndWalletManager.walletScopeIdentifier()
                ?: throw LndWalletException.NoStoredNode
            lndPaymentUsdSnapshotStore.snapshots(
                walletPubkey = scope,
                paymentIds = paymentIds
            )
        } else {
            val walletPubkey = walletManager.currentWalletPubkey()
            PaymentUsdSnapshotStore(getApplication()).snapshots(
                walletPubkey = walletPubkey,
                paymentIds = paymentIds
            )
        }
    }

    suspend fun transactionReportableStates(
        paymentIds: List<String>,
        source: SpendWalletSource = activeSpendWallet.value
    ): Map<String, Boolean> {
        return if (source == SpendWalletSource.LND) {
            val scope = lndWalletManager.walletScopeIdentifier()
                ?: throw LndWalletException.NoStoredNode
            lndPaymentUsdSnapshotStore.reportableStates(
                walletPubkey = scope,
                paymentIds = paymentIds
            )
        } else {
            walletManager.transactionReportableStates(paymentIds)
        }
    }

    suspend fun setTransactionReportable(
        paymentId: String,
        direction: String,
        isReportable: Boolean,
        source: SpendWalletSource = activeSpendWallet.value
    ) {
        if (source == SpendWalletSource.LND) {
            val scope = lndWalletManager.walletScopeIdentifier()
                ?: throw LndWalletException.NoStoredNode
            lndPaymentUsdSnapshotStore.setReportable(
                walletPubkey = scope,
                paymentId = paymentId,
                paymentType = if (direction == "received") "received" else "sent",
                isReportable = isReportable
            )
        } else {
            walletManager.setTransactionReportable(
                paymentId = paymentId,
                direction = direction,
                isReportable = isReportable
            )
        }
    }

    suspend fun setTransactionUserLog(
        paymentId: String,
        direction: String,
        userLog: String?,
        source: SpendWalletSource = activeSpendWallet.value
    ) {
        if (source == SpendWalletSource.LND) {
            val scope = lndWalletManager.walletScopeIdentifier()
                ?: throw LndWalletException.NoStoredNode
            lndPaymentUsdSnapshotStore.setUserLog(
                walletPubkey = scope,
                paymentId = paymentId,
                paymentType = if (direction == "received") "received" else "sent",
                userLog = userLog
            )
        } else {
            walletManager.setTransactionUserLog(
                paymentId = paymentId,
                direction = direction,
                userLog = userLog
            )
        }
    }

    suspend fun currentWalletPubkey(): String {
        return walletManager.currentWalletPubkey()
    }

    fun hasStoredLndNode(): Boolean {
        return lndWalletManager.connectedNode.value != null || lndCredentialStore.activeNode() != null
    }

    fun activeLndNodeCredentials(): LndNodeCredentials? {
        return lndWalletManager.connectedNode.value ?: lndCredentialStore.activeNode()
    }

    fun transactionActivityScope(source: SpendWalletSource): String {
        return if (source == SpendWalletSource.LND) {
            lndWalletManager.walletScopeIdentifier() ?: "lnd"
        } else {
            "spark"
        }
    }

    fun setSparkSpendWallet() {
        activeSpendWalletStore.setSparkActive()
        notifyWalletActivity()
    }

    fun setLndSpendWalletIfAvailable(): Boolean {
        val didActivate = activeSpendWalletStore.setLndActiveIfAvailable()
        if (didActivate) {
            launchGuarded("setLndSpendWalletIfAvailable") {
                if (!lndWalletManager.isConnected) {
                    lndWalletManager.restoreActiveNode()
                }
                lndWalletManager.refreshBalance()
                notifyWalletActivity()
            }
        } else {
            notifyWalletActivity()
        }
        return didActivate
    }

    fun setLndInvoiceListenerActive(active: Boolean) {
        lndWalletManager.setInvoiceListenerActive(active)
    }

    suspend fun connectLndNode(lndConnectString: String): LndNodeCredentials {
        val node = lndWalletManager.connect(lndConnectString)
        activeSpendWalletStore.setLndActiveIfAvailable()
        notifyWalletActivity()
        return node
    }

    suspend fun restoreLndNode() {
        lndWalletManager.restoreActiveNode()
        activeSpendWalletStore.reconcileWithStoredNode()
        notifyWalletActivity()
    }

    suspend fun refreshLndConnection() {
        lndWalletManager.refreshNodeInfo()
        lndWalletManager.refreshBalance()
        notifyWalletActivity()
    }

    fun forgetLndNode(id: String) {
        lndWalletManager.forgetNode(id)
        activeSpendWalletStore.reconcileWithStoredNode()
        notifyWalletActivity()
    }

    suspend fun fetchRewardsStats(): RewardStatsResponse {
        return rewardsRepository.fetchRewardsStats(
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun currentLightningAddress(): String? {
        return walletManager.currentLightningAddress()
    }

    suspend fun currentLightningAddressInfo(): WalletLightningAddressInfo? {
        val info = walletManager.currentLightningAddressInfo()
        if (info != null) {
            runCatching {
                profileIdentityRepository.syncLightningAddress(
                    lightningAddress = info.lightningAddress,
                    authManager = authManager,
                    walletManager = walletManager
                )
                messageKeyManager.ensureRegistered(authManager, walletManager)
                MessagingDeviceTokenSyncScheduler.enqueue(getApplication())
            }
        }
        return info
    }

    fun normalizedLightningUsername(input: String): String {
        return walletManager.normalizedLightningUsername(input)
    }

    suspend fun isLightningAddressAvailable(username: String): Boolean {
        return walletManager.isLightningAddressAvailable(username)
    }

    suspend fun createLightningAddress(
        username: String,
        description: String? = null
    ): WalletLightningAddressInfo {
        val created = walletManager.createLightningAddress(
            username = username,
            description = description
        )

        runCatching {
            profileIdentityRepository.syncLightningAddress(
                lightningAddress = created.lightningAddress,
                authManager = authManager,
                walletManager = walletManager
            )
            messageKeyManager.ensureRegistered(authManager, walletManager)
            MessagingDeviceTokenSyncScheduler.enqueue(getApplication())
        }

        return created
    }

    suspend fun fetchProfilePicUrl(): String? {
        return profileIdentityRepository.fetchProfilePicUrl(
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun uploadProfilePic(
        fileData: ByteArray,
        fileName: String,
        mimeType: String
    ): String? {
        return profileIdentityRepository.uploadProfilePic(
            fileData = fileData,
            fileName = fileName,
            mimeType = mimeType,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun fetchBtcUsdPrice(): Double {
        return btcPriceRepository.fetchSpotUsdPrice()
    }

    suspend fun fetchBtcUsdPriceSeries(range: BitcoinChartRange): List<BitcoinPricePoint> {
        return btcPriceRepository.fetchUsdPriceSeries(range)
    }

    suspend fun fetchBtcMerchantPlaces(
        latitude: Double,
        longitude: Double,
        radiusKilometers: Double
    ): List<BtcMerchantPlace> {
        return btcMerchantMapRepository.fetchPlaces(
            latitude = latitude,
            longitude = longitude,
            radiusKilometers = radiusKilometers
        )
    }

    suspend fun reportMerchant(
        merchantName: String,
        merchantAddress: String,
        transaction: WalletTransactionRow
    ) {
        merchantReportRepository.reportMerchant(
            merchantName = merchantName,
            merchantAddress = merchantAddress,
            transaction = transaction,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun fetchNearbyCoupons(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = 25.0
    ): NearbyCouponsResponse {
        return merchantCouponsRepository.fetchNearbyCoupons(
            latitude = latitude,
            longitude = longitude,
            radiusMiles = radiusMiles,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun fetchNearbyCoupons(
        postalCode: String,
        radiusMiles: Double = 25.0
    ): NearbyCouponsResponse {
        return merchantCouponsRepository.fetchNearbyCoupons(
            postalCode = postalCode,
            radiusMiles = radiusMiles,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun redeemNearbyCoupon(
        couponId: String
    ): RedeemNearbyCouponResponse {
        return merchantCouponsRepository.redeemCoupon(
            couponId = couponId,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun fetchBitcoinEvents(
        latitude: Double,
        longitude: Double
    ): BitcoinEventsResponse {
        return bitcoinEventsRepository.fetchEvents(
            latitude = latitude,
            longitude = longitude
        )
    }

    suspend fun fetchBitcoinEvents(
        postalCode: String
    ): BitcoinEventsResponse {
        return bitcoinEventsRepository.fetchEvents(
            postalCode = postalCode
        )
    }

    fun conversationPreviews(searchQuery: String = ""): List<MessageConversationPreview> {
        return messagingRepository.conversationPreviews(searchQuery)
    }

    fun messagesForConversation(conversationId: String): List<StoredMessage> {
        return messagingRepository.messagesForConversation(conversationId)
    }

    fun recipientMetadata(conversationId: String): MessageRecipientMetadata? {
        return messagingRepository.recipientMetadata(conversationId)
    }

    fun markConversationAsRead(conversationId: String) {
        messagingRepository.markConversationAsRead(conversationId)
    }

    fun deleteConversation(conversationId: String) {
        messagingRepository.deleteConversation(conversationId)
    }

    fun syncMessages(force: Boolean = false) {
        launchGuarded("syncMessages") {
            if (walletManager.state.value is WalletState.Ready) {
                messagingRepository.syncInbox(
                    authManager = authManager,
                    walletManager = walletManager,
                    force = force
                )
            }
        }
    }

    fun syncOutgoingStatuses(force: Boolean = false) {
        launchGuarded("syncOutgoingStatuses") {
            if (walletManager.state.value is WalletState.Ready) {
                messagingRepository.syncOutgoingStatuses(
                    authManager = authManager,
                    walletManager = walletManager,
                    force = force
                )
            }
        }
    }

    suspend fun sendTextMessage(
        lightningAddress: String,
        plaintext: String
    ): MessageSendResult {
        return messagingRepository.sendTextMessage(
            lightningAddress = lightningAddress,
            plaintext = plaintext,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun sendPaymentRequestMessage(
        lightningAddress: String,
        amountSats: Long,
        note: String? = null
    ): MessageSendResult {
        val requesterLightningAddress = runCatching {
            walletManager.currentLightningAddress()
        }.getOrNull()

        val invoice = createBolt11Invoice(
            amountSats = amountSats,
            description = "Split payment request"
        )

        return messagingRepository.sendPaymentRequestMessage(
            lightningAddress = lightningAddress,
            payload = PaymentRequestMessagePayload(
                invoice = invoice.invoice,
                amountSats = amountSats,
                requesterLightningAddress = requesterLightningAddress,
                note = note
            ),
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun payMessageRequest(
        originalRequest: StoredMessage,
        invoice: String
    ): PreparedOutgoingPayment {
        val preparedPayment = walletManager.prepareOutgoingPayment(
            destination = invoice,
            amountSats = null,
            comment = null
        )
        when (val result = walletManager.sendPreparedPayment(preparedPayment)) {
            is PreparedOutgoingPaymentSendResult.Completed,
            is PreparedOutgoingPaymentSendResult.Pending -> Unit

            is PreparedOutgoingPaymentSendResult.Failed -> {
                throw IllegalStateException(
                    result.message?.trim().orEmpty().ifBlank {
                        "Unable to send payment."
                    }
                )
            }
        }
        messagingRepository.syncPaidPaymentRequestStatus(
            originalRequest = originalRequest,
            invoice = invoice,
            authManager = authManager,
            walletManager = walletManager
        )
        return preparedPayment
    }

    suspend fun sendAttachmentMessage(
        lightningAddress: String,
        fileData: ByteArray,
        fileName: String,
        mimeType: String,
        imageWidth: Int?,
        imageHeight: Int?
    ): MessageSendResult {
        return messagingRepository.sendAttachmentMessage(
            lightningAddress = lightningAddress,
            fileData = fileData,
            fileName = fileName,
            mimeType = mimeType,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun resendStoredMessage(
        storedMessage: StoredMessage
    ): MessageSendResult? {
        return messagingRepository.resendStoredMessage(
            storedMessage = storedMessage,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    fun hasCachedAttachment(payload: AttachmentMessagePayload): Boolean {
        return messagingRepository.hasCachedAttachment(payload)
    }

    fun cachedAttachmentData(payload: AttachmentMessagePayload): ByteArray? {
        return messagingRepository.cachedAttachmentData(payload)
    }

    suspend fun prepareAttachmentData(
        payload: AttachmentMessagePayload,
        shouldMarkReceived: Boolean
    ): ByteArray {
        return messagingRepository.prepareAttachmentData(
            payload = payload,
            shouldMarkReceived = shouldMarkReceived,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun sendReactionMessage(
        lightningAddress: String,
        targetMessageId: String,
        reactionKind: MessageReactionKind
    ): MessageSendResult {
        return messagingRepository.sendReactionMessage(
            lightningAddress = lightningAddress,
            targetMessageId = targetMessageId,
            reactionKind = reactionKind,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun fetchMessagingBlocks(): List<MessagingBlockedUser> {
        return messagingBlockRepository.fetchBlocks(
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun blockMessagingUser(
        walletPubkey: String? = null,
        lightningAddress: String? = null
    ): MessagingBlockedUser {
        return messagingBlockRepository.blockUser(
            walletPubkey = walletPubkey,
            lightningAddress = lightningAddress,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun unblockMessagingUser(
        blockedWalletPubkey: String
    ): Boolean {
        return messagingBlockRepository.unblockUser(
            blockedWalletPubkey = blockedWalletPubkey,
            authManager = authManager,
            walletManager = walletManager
        )
    }

    suspend fun addWalletContact(
        name: String,
        paymentIdentifier: String
    ): WalletContact {
        val created = walletManager.addContact(
            name = name,
            paymentIdentifier = paymentIdentifier
        )
        loadContactsIfPossible()
        return created
    }

    private suspend fun sendPreparedLndPayment(preview: PaymentPreview) = withContext(NonCancellable) {
        Log.i(
            "SplitRootViewModel",
            "Starting LND send paymentHash=${preview.paymentHash ?: "unknown"} amountSats=${preview.amountSats}"
        )

        if (!lndWalletManager.isConnected) {
            lndWalletManager.restoreActiveNode()
        }

        val response = lndWalletManager.payInvoice(
            bolt11 = preview.destination,
            amountSats = preview.lndAmountOverrideSats
        )

        Log.i(
            "SplitRootViewModel",
            "LND payInvoice returned didSucceed=${response.didSucceed} paymentHash=${response.paymentHash ?: preview.paymentHash ?: "unknown"} paymentError=${response.paymentError ?: ""}"
        )

        if (!response.didSucceed) {
            Log.w(
                "SplitRootViewModel",
                "LND send reported failure. ${response.paymentError ?: "Payment failed."}"
            )
            throw IllegalStateException(response.paymentError ?: "Payment failed.")
        }

        walletManager.showOutgoingPaymentSuccess()
        notifyWalletActivity()
        launchLndPostSendReconciliation(preview)
    }

    private fun launchLndPostSendReconciliation(preview: PaymentPreview) {
        viewModelScope.launch {
            runCatching {
                val rows = lndWalletManager.fetchTransactionRows()
                ensureLndUsdSnapshots(rows)
                notifyWalletActivity()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh LND transactions after send. ${error.localizedMessage}",
                    error
                )
            }

            runCatching {
                lndWalletManager.refreshBalance()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh LND balance after send. ${error.localizedMessage}",
                    error
                )
            }

            runCatching {
                val usdAmountCents = lndRewardSpendUsdCents(preview.amountSats)
                rewardsRepository.postRewardSpend(
                    direction = "sent",
                    usdAmountCents = usdAmountCents,
                    btcAmountSats = preview.amountSats,
                    destinationPubkey = preview.destinationPubkey,
                    network = "lightning",
                    status = "Completed",
                    authManager = authManager,
                    walletManager = walletManager
                )
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to post LND reward spend. ${error.localizedMessage}",
                    error
                )
            }
        }
    }

    private suspend fun ensureLndUsdSnapshots(rows: List<WalletTransactionRow>) {
        val scope = lndWalletManager.walletScopeIdentifier() ?: return
        val completedRows = rows
            .filter { row ->
                row.status == "Completed" &&
                    (row.direction == "sent" || row.direction == "received") &&
                    row.amountSats > 0L
            }
            .sortedBy { it.transactionTimestampMillis }

        for (row in completedRows) {
            if (lndPaymentUsdSnapshotStore.containsSnapshot(scope, row.id)) {
                continue
            }

            val rate = runCatching {
                btcPriceRepository.fetchUsdPriceAt(row.transactionTimestampMillis)
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to capture LND BTC/USD snapshot for ${row.id}. ${error.localizedMessage}",
                    error
                )
            }.getOrNull() ?: continue

            if (rate <= 0.0) continue

            lndPaymentUsdSnapshotStore.upsert(
                PaymentUsdSnapshot(
                    walletPubkey = scope,
                    paymentId = row.id,
                    paymentType = if (row.direction == "sent") "sent" else "received",
                    usdValueAtTransaction = (row.amountSats.toDouble() / 100_000_000.0) * rate,
                    btcUsdRateAtTransaction = rate
                )
            )
        }
    }

    private suspend fun lndRewardSpendUsdCents(amountSats: Long): Int {
        val rate = runCatching {
            btcPriceRepository.fetchSpotUsdPrice()
        }.getOrDefault(0.0)

        if (rate <= 0.0) return 0
        val usd = (amountSats.toDouble() / 100_000_000.0) * rate
        return (usd * 100.0).roundToInt()
    }

    private fun notifyWalletActivity() {
        _walletEventVersion.value = _walletEventVersion.value + 1L
    }

    private fun launchGuarded(
        taskName: String,
        block: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                block()
            }.onFailure { error ->
                Log.e(
                    "SplitRootViewModel",
                    "Task $taskName failed. ${error.localizedMessage}",
                    error
                )
            }
        }
    }

    suspend fun updateWalletContact(
        contactId: String,
        name: String,
        paymentIdentifier: String
    ): WalletContact {
        val updated = walletManager.updateContact(
            id = contactId,
            name = name,
            paymentIdentifier = paymentIdentifier
        )
        loadContactsIfPossible()
        return updated
    }

    suspend fun deleteWalletContact(contactId: String) {
        walletManager.deleteContact(contactId)
        loadContactsIfPossible()
    }

    private suspend fun authenticateIfPossible() {
        if (walletManager.state.value is WalletState.Ready) {
            runCatching {
                authManager.ensureSession(walletManager)
            }.onFailure { error ->
                Log.e(
                    "SplitRootViewModel",
                    "Authentication refresh failed. ${error.localizedMessage}",
                    error
                )
            }
        }
    }

    private suspend fun loadContactsIfPossible() {
        if (walletManager.state.value !is WalletState.Ready) {
            _contactsByPaymentIdentifier.value = emptyMap()
            return
        }

        runCatching {
            walletManager.listContacts()
        }.onSuccess { contacts ->
            _contactsByPaymentIdentifier.value = contacts.associateBy {
                it.paymentIdentifier.trim().lowercase()
            }
        }
    }

    private fun observeWalletEvents() {
        viewModelScope.launch {
            walletManager.walletEventVersion.collectLatest { version ->
                if (version <= 0L) return@collectLatest
                notifyWalletActivity()

                runCatching { authenticateIfPossible() }
                runCatching {
                    messagingRepository.syncInbox(
                        authManager = authManager,
                        walletManager = walletManager,
                        force = true
                    )
                }
                runCatching {
                    messagingRepository.syncPaidPaymentRequestStatuses(
                        transactions = walletManager.fetchTransactionRows(),
                        authManager = authManager,
                        walletManager = walletManager
                    )
                }
            }
        }

        viewModelScope.launch {
            lndWalletManager.walletEventVersion.collectLatest { version ->
                if (version <= 0L) return@collectLatest
                notifyWalletActivity()
            }
        }
    }
}
