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
import com.split.android.data.wallet.Bolt11InvoiceMetadataDecoder
import com.split.android.data.wallet.CoreLightningBalanceSummary
import com.split.android.data.wallet.CoreLightningConnectionState
import com.split.android.data.wallet.CoreLightningCredentialStore
import com.split.android.data.wallet.CoreLightningNodeCredentials
import com.split.android.data.wallet.CoreLightningWalletException
import com.split.android.data.wallet.CoreLightningWalletManager
import com.split.android.data.wallet.EclairBalanceSummary
import com.split.android.data.wallet.EclairConnectionState
import com.split.android.data.wallet.EclairCredentialStore
import com.split.android.data.wallet.EclairNodeCredentials
import com.split.android.data.wallet.EclairWalletException
import com.split.android.data.wallet.EclairWalletManager
import com.split.android.data.wallet.ExternalWalletRecord
import com.split.android.data.wallet.ExternalWalletStore
import com.split.android.data.wallet.LndBalanceSummary
import com.split.android.data.wallet.LndConnectionState
import com.split.android.data.wallet.LndCredentialStore
import com.split.android.data.wallet.LndLightningPaymentResolver
import com.split.android.data.wallet.LndNodeCredentials
import com.split.android.data.wallet.LndWalletException
import com.split.android.data.wallet.LndWalletManager
import com.split.android.data.wallet.MnemonicGenerator
import com.split.android.data.wallet.NwcBalanceSummary
import com.split.android.data.wallet.NwcConnectionState
import com.split.android.data.wallet.NwcCredentialStore
import com.split.android.data.wallet.NwcWalletCredentials
import com.split.android.data.wallet.NwcWalletException
import com.split.android.data.wallet.NwcWalletManager
import com.split.android.data.wallet.PaymentPreview
import com.split.android.data.wallet.PaymentDestinationMetadata
import com.split.android.data.wallet.PaymentUsdSnapshot
import com.split.android.data.wallet.PaymentUsdSnapshotStore
import com.split.android.data.wallet.PreparedOutgoingPayment
import com.split.android.data.wallet.PreparedOutgoingPaymentSendResult
import com.split.android.data.wallet.ReceiveInvoice
import com.split.android.data.wallet.RemoteNodeTorTransport
import com.split.android.data.wallet.SeedStore
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.SparkSubwalletBalanceSummary
import com.split.android.data.wallet.SparkSubwalletConnectionState
import com.split.android.data.wallet.SparkSubwalletCredentialStore
import com.split.android.data.wallet.SparkSubwalletCredentials
import com.split.android.data.wallet.SparkSubwalletException
import com.split.android.data.wallet.SparkSubwalletManager
import com.split.android.data.wallet.SparkSubwalletStore
import com.split.android.data.wallet.UnclaimedBitcoinDeposit
import com.split.android.data.wallet.WalletContact
import com.split.android.data.wallet.WalletBackend
import com.split.android.data.wallet.WalletLightningAddressInfo
import com.split.android.data.wallet.WalletTransactionRow
import com.split.android.data.wallet.WalletManager
import com.split.android.data.wallet.WalletState
import com.split.android.data.wallet.usesTor
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

private fun PreparedOutgoingPayment.withPreview(
    preview: PaymentPreview
): PreparedOutgoingPayment {
    return when (this) {
        is PreparedOutgoingPayment.Standard -> copy(preview = preview)
        is PreparedOutgoingPayment.Lnurl -> copy(preview = preview)
        is PreparedOutgoingPayment.Lnd -> copy(preview = preview)
        is PreparedOutgoingPayment.Nwc -> copy(preview = preview)
        is PreparedOutgoingPayment.CoreLightning -> copy(preview = preview)
        is PreparedOutgoingPayment.Eclair -> copy(preview = preview)
    }
}

private fun WalletBackend.rewardsSpendWalletSource(): SpendWalletSource {
    return when (this) {
        WalletBackend.SPARK -> SpendWalletSource.SPARK
        WalletBackend.LND -> SpendWalletSource.LND
        WalletBackend.NWC -> SpendWalletSource.NWC
        WalletBackend.CORE_LIGHTNING -> SpendWalletSource.CORE_LIGHTNING
        WalletBackend.ECLAIR -> SpendWalletSource.ECLAIR
        WalletBackend.SPARK_SUBWALLET -> SpendWalletSource.SPARK_SUBWALLET
    }
}

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
    private val sparkSubwalletWalletClient = BreezSparkWalletClient()
    private val mnemonicGenerator = MnemonicGenerator(application)
    private val lndCredentialStore = LndCredentialStore(application)
    private val nwcCredentialStore = NwcCredentialStore(application)
    private val coreLightningCredentialStore = CoreLightningCredentialStore(application)
    private val eclairCredentialStore = EclairCredentialStore(application)
    private val sparkSubwalletCredentialStore = SparkSubwalletCredentialStore(application)
    private val sparkSubwalletStore = SparkSubwalletStore(application)
    private val externalWalletStore = ExternalWalletStore(application)
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
    private val nwcWalletManager = NwcWalletManager(
        context = application,
        credentialStore = nwcCredentialStore,
        onPaymentReceived = {
            walletManager.showIncomingPaymentResult()
            notifyWalletActivity()
        },
        onWalletActivity = {
            notifyWalletActivity()
        }
    )
    private val coreLightningWalletManager = CoreLightningWalletManager(
        context = application,
        credentialStore = coreLightningCredentialStore,
        onWalletActivity = {
            notifyWalletActivity()
        }
    )
    private val eclairWalletManager = EclairWalletManager(
        context = application,
        credentialStore = eclairCredentialStore,
        onWalletActivity = {
            notifyWalletActivity()
        }
    )
    private val sparkSubwalletManager = SparkSubwalletManager(
        context = application,
        credentialStore = sparkSubwalletCredentialStore,
        store = sparkSubwalletStore,
        breezApiRepository = breezApiRepository,
        sparkWalletClient = sparkSubwalletWalletClient,
        mnemonicGenerator = mnemonicGenerator,
        rewardsRepository = rewardsRepository,
        authManager = authManager,
        walletManager = walletManager,
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

    val nwcConnectionState: StateFlow<NwcConnectionState> = nwcWalletManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NwcConnectionState.Disconnected
        )

    val connectedNwcWallet: StateFlow<NwcWalletCredentials?> = nwcWalletManager.connectedWallet
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val nwcBalanceSummary: StateFlow<NwcBalanceSummary?> = nwcWalletManager.balanceSummary
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val coreLightningConnectionState: StateFlow<CoreLightningConnectionState> = coreLightningWalletManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CoreLightningConnectionState.Disconnected
        )

    val connectedCoreLightningNode: StateFlow<CoreLightningNodeCredentials?> = coreLightningWalletManager.connectedNode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val coreLightningBalanceSummary: StateFlow<CoreLightningBalanceSummary?> = coreLightningWalletManager.balanceSummary
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val eclairConnectionState: StateFlow<EclairConnectionState> = eclairWalletManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = EclairConnectionState.Disconnected
        )

    val connectedEclairNode: StateFlow<EclairNodeCredentials?> = eclairWalletManager.connectedNode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val eclairBalanceSummary: StateFlow<EclairBalanceSummary?> = eclairWalletManager.balanceSummary
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val sparkSubwalletConnectionState: StateFlow<SparkSubwalletConnectionState> = sparkSubwalletManager.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SparkSubwalletConnectionState.Disconnected
        )

    val connectedSparkSubwallet: StateFlow<SparkSubwalletCredentials?> = sparkSubwalletManager.connectedWallet
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val sparkSubwalletBalanceSummary: StateFlow<SparkSubwalletBalanceSummary?> = sparkSubwalletManager.balanceSummary
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val sparkSubwalletPendingSeedWords: StateFlow<List<String>> = sparkSubwalletManager.pendingSeedWords
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val storedNwcWalletsVersion: StateFlow<Long> = nwcWalletManager.storedWalletsVersion
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0L
        )

    val storedCoreLightningNodesVersion: StateFlow<Long> = coreLightningWalletManager.storedNodesVersion
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0L
        )

    val storedEclairNodesVersion: StateFlow<Long> = eclairWalletManager.storedNodesVersion
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0L
        )

    val storedSparkSubwalletsVersion: StateFlow<Long> = sparkSubwalletManager.storedWalletsVersion
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0L
        )

    val storedLndNodesVersion: StateFlow<Long> = lndWalletManager.storedNodesVersion
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0L
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
            runCatching { nwcWalletManager.restoreActiveWallet() }
            runCatching { coreLightningWalletManager.restoreActiveNode() }
            runCatching { eclairWalletManager.restoreActiveNode() }
            activeSpendWalletStore.reconcileWithStoredWallets()
            warmTorForActiveWalletIfNeeded()
            if (activeSpendWalletStore.isSparkSubwalletActive) {
                runCatching { sparkSubwalletManager.restoreWalletIfNeeded() }
            }
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
            runCatching { nwcWalletManager.restoreActiveWallet() }
            runCatching { coreLightningWalletManager.restoreActiveNode() }
            runCatching { eclairWalletManager.restoreActiveNode() }
            activeSpendWalletStore.reconcileWithStoredWallets()
            warmTorForActiveWalletIfNeeded()
            if (activeSpendWalletStore.isSparkSubwalletActive) {
                runCatching { sparkSubwalletManager.restoreWalletIfNeeded() }
            }
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
            runCatching { nwcWalletManager.restoreActiveWallet() }
            runCatching { coreLightningWalletManager.restoreActiveNode() }
            runCatching { eclairWalletManager.restoreActiveNode() }
            activeSpendWalletStore.reconcileWithStoredWallets()
            warmTorForActiveWalletIfNeeded()
            if (activeSpendWalletStore.isSparkSubwalletActive) {
                runCatching { sparkSubwalletManager.restoreWalletIfNeeded() }
            }
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
            nwcWalletManager.disconnectFromActiveWallet()
            coreLightningWalletManager.disconnectFromActiveNode()
            eclairWalletManager.disconnectFromActiveNode()
            sparkSubwalletManager.disconnectActiveWallet()
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
        return when (activeSpendWallet.value) {
            SpendWalletSource.LND -> {
                lndWalletManager.prepareOutgoingPayment(
                    paymentRequest = destination,
                    amountSats = amountSats,
                    feesIncluded = feesIncluded,
                    comment = comment
                )
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                coreLightningWalletManager.prepareOutgoingPayment(
                    paymentRequest = destination,
                    amountSats = amountSats,
                    feesIncluded = feesIncluded,
                    comment = comment
                )
            }
            SpendWalletSource.ECLAIR -> {
                eclairWalletManager.prepareOutgoingPayment(
                    paymentRequest = destination,
                    amountSats = amountSats,
                    feesIncluded = feesIncluded,
                    comment = comment
                )
            }
            SpendWalletSource.NWC -> {
                if (!nwcWalletManager.isConnected) {
                    nwcWalletManager.restoreActiveWallet()
                }
                val invoice = destination.trim()
                if (!LndLightningPaymentResolver.isBolt11(invoice)) {
                    throw IllegalArgumentException("NWC payments require a Lightning invoice.")
                }
                val localMetadata = runCatching {
                    walletManager.decodeBolt11InvoiceMetadata(invoice)
                }.getOrNull() ?: Bolt11InvoiceMetadataDecoder.decode(invoice)
                val lookup = runCatching { nwcWalletManager.lookupInvoice(invoice) }.getOrNull()
                val resolvedAmountSats = localMetadata?.amountSats?.takeIf { it > 0L }
                    ?: lookup?.amountSats?.takeIf { it > 0L }
                    ?: amountSats?.takeIf { it > 0L }
                    ?: throw IllegalArgumentException("Enter an amount for this invoice.")
                val destinationPubkey = localMetadata?.destinationPubkey?.trim()?.ifBlank { null }
                val paymentHash = lookup?.paymentHash?.trim()?.ifBlank { null }
                    ?: localMetadata?.paymentHash?.trim()?.ifBlank { null }

                if (destinationPubkey == null || paymentHash == null) {
                    throw NwcWalletException.RewardsMetadataUnavailable
                }

                PreparedOutgoingPayment.Nwc(
                    preview = PaymentPreview(
                        backend = WalletBackend.NWC,
                        destination = invoice,
                        amountSats = resolvedAmountSats,
                        feeSats = lookup?.feesPaidSats,
                        feesIncluded = feesIncluded,
                        methodLabel = lookup?.description?.trim()?.ifBlank { null }
                            ?: localMetadata.description?.trim()?.ifBlank { null }
                            ?: "Lightning Invoice",
                        lndAmountOverrideSats = if (localMetadata.amountSats == null && lookup?.amountSats == null) resolvedAmountSats else null,
                        destinationPubkey = destinationPubkey,
                        paymentHash = paymentHash
                    )
                )
            }
            SpendWalletSource.SPARK -> {
                walletManager.prepareOutgoingPayment(
                    destination = destination,
                    amountSats = amountSats,
                    feesIncluded = feesIncluded,
                    comment = comment
                )
            }
            SpendWalletSource.SPARK_SUBWALLET -> {
                if (!sparkSubwalletManager.isConnected) {
                    sparkSubwalletManager.restoreWalletIfNeeded()
                }
                sparkSubwalletManager.prepareOutgoingPayment(
                    destination = destination,
                    amountSats = amountSats,
                    feesIncluded = feesIncluded,
                    comment = comment
                )
            }
        }
    }

    suspend fun checkPreparedPaymentRewards(preparedPayment: PreparedOutgoingPayment): PreparedOutgoingPayment {
        val preview = preparedPayment.preview
        val fallbackMetadata = if (preview.destinationPubkey.isNullOrBlank()) {
            decodeInvoiceDestinationMetadata(preview.destination, preview.backend.rewardsSpendWalletSource())
        } else {
            null
        }
        val destinationPubkey = preview.destinationPubkey
            ?.trim()
            ?.ifBlank { null }
            ?: fallbackMetadata?.destinationPubkey?.trim()?.ifBlank { null }
        val paymentHash = preview.paymentHash
            ?.trim()
            ?.ifBlank { null }
            ?: fallbackMetadata?.paymentHash?.trim()?.ifBlank { null }

        val rewardEligible = if (destinationPubkey == null) {
            null
        } else {
            runCatching {
                rewardsRepository.localRewardsCheck(destinationPubkey)
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to check rewards eligibility. ${error.localizedMessage}",
                    error
                )
            }.getOrNull()
        }

        return preparedPayment.withPreview(
            preview.copy(
                destinationPubkey = destinationPubkey,
                paymentHash = paymentHash,
                merchantPubkeyHash = rewardEligible?.merchantPubkeyHash,
                rewardEligible = rewardEligible?.rewardEligible ?: false
            )
        )
    }

    suspend fun presetSendAmountSats(
        destination: String
    ): Long? {
        val normalized = destination.trim()
        return when (activeSpendWallet.value) {
            SpendWalletSource.SPARK -> walletManager.presetAmountSats(destination)
            SpendWalletSource.NWC -> {
                if (!LndLightningPaymentResolver.isBolt11(normalized)) {
                    return null
                }
                if (!nwcWalletManager.isConnected) {
                    runCatching { nwcWalletManager.restoreActiveWallet() }
                }
                runCatching { nwcWalletManager.lookupInvoice(normalized).amountSats }
                    .getOrNull()
                    ?.takeIf { it > 0L }
            }
            SpendWalletSource.LND -> {
                if (!LndLightningPaymentResolver.isBolt11(normalized)) {
                    return null
                }
                if (!lndWalletManager.isConnected) {
                    runCatching { lndWalletManager.restoreActiveNode() }
                }
                runCatching {
                    lndWalletManager.decodeInvoice(normalized).amountSats
                }.getOrNull()?.takeIf { it > 0L }
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                if (!LndLightningPaymentResolver.isBolt11(normalized)) {
                    return null
                }
                if (!coreLightningWalletManager.isConnected) {
                    runCatching { coreLightningWalletManager.restoreActiveNode() }
                }
                runCatching {
                    coreLightningWalletManager.decodeInvoice(normalized).amountSats
                }.getOrNull()?.takeIf { it > 0L }
            }
            SpendWalletSource.ECLAIR -> {
                if (!LndLightningPaymentResolver.isBolt11(normalized)) {
                    return null
                }
                if (!eclairWalletManager.isConnected) {
                    runCatching { eclairWalletManager.restoreActiveNode() }
                }
                runCatching {
                    eclairWalletManager.decodeInvoice(normalized).amountSats
                }.getOrNull()?.takeIf { it > 0L }
            }
            SpendWalletSource.SPARK_SUBWALLET -> {
                if (!sparkSubwalletManager.isConnected) {
                    runCatching { sparkSubwalletManager.restoreWalletIfNeeded() }
                }
                runCatching { sparkSubwalletManager.presetAmountSats(destination) }
                    .getOrNull()
                    ?.takeIf { it > 0L }
            }
        }
    }

    suspend fun sendPreparedPayment(preparedPayment: PreparedOutgoingPayment) {
        when (preparedPayment) {
            is PreparedOutgoingPayment.Lnd -> sendPreparedLndPayment(preparedPayment.preview)
            is PreparedOutgoingPayment.Nwc -> sendPreparedNwcPayment(preparedPayment.preview)
            is PreparedOutgoingPayment.CoreLightning -> sendPreparedCoreLightningPayment(preparedPayment.preview)
            is PreparedOutgoingPayment.Eclair -> sendPreparedEclairPayment(preparedPayment.preview)
            is PreparedOutgoingPayment.Standard,
            is PreparedOutgoingPayment.Lnurl -> {
                if (preparedPayment.preview.backend == WalletBackend.SPARK_SUBWALLET) {
                    sendPreparedSparkSubwalletPayment(preparedPayment)
                } else {
                    walletManager.sendPreparedPayment(preparedPayment)
                }
            }
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
                    is PreparedOutgoingPayment.Nwc -> {
                        sendPreparedNwcPayment(preparedPayment.preview)
                    }
                    is PreparedOutgoingPayment.CoreLightning -> {
                        sendPreparedCoreLightningPayment(preparedPayment.preview)
                    }
                    is PreparedOutgoingPayment.Eclair -> {
                        sendPreparedEclairPayment(preparedPayment.preview)
                    }
                    is PreparedOutgoingPayment.Standard,
                    is PreparedOutgoingPayment.Lnurl -> {
                        if (preparedPayment.preview.backend == WalletBackend.SPARK_SUBWALLET) {
                            sendPreparedSparkSubwalletPayment(preparedPayment)
                        } else {
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
        amountSats: Long?,
        description: String?
    ): ReceiveInvoice {
        return when (activeSpendWallet.value) {
            SpendWalletSource.LND -> {
                if (!lndWalletManager.isConnected) {
                    lndWalletManager.restoreActiveNode()
                }

                val response = lndWalletManager.createInvoice(
                    amountSats = amountSats,
                    memo = description
                )
                ReceiveInvoice(
                    invoice = response.paymentRequest,
                    amountSats = amountSats?.takeIf { it > 0L } ?: 0L,
                    description = description?.trim()?.ifBlank { null },
                    feeSats = 0L
                )
            }
            SpendWalletSource.NWC -> {
                if (!nwcWalletManager.isConnected) {
                    nwcWalletManager.restoreActiveWallet()
                }
                val result = nwcWalletManager.createInvoice(
                    amountSats = amountSats,
                    memo = description
                )
                ReceiveInvoice(
                    invoice = result.invoice ?: throw NwcWalletException.InvalidRelayResponse,
                    amountSats = amountSats?.takeIf { it > 0L } ?: 0L,
                    description = description?.trim()?.ifBlank { null },
                    feeSats = 0L
                )
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                if (!coreLightningWalletManager.isConnected) {
                    coreLightningWalletManager.restoreActiveNode()
                }
                val result = coreLightningWalletManager.createInvoice(
                    amountSats = amountSats,
                    memo = description
                )
                ReceiveInvoice(
                    invoice = result.bolt11,
                    amountSats = amountSats?.takeIf { it > 0L } ?: 0L,
                    description = description?.trim()?.ifBlank { null },
                    feeSats = 0L
                )
            }
            SpendWalletSource.ECLAIR -> {
                if (!eclairWalletManager.isConnected) {
                    eclairWalletManager.restoreActiveNode()
                }
                val result = eclairWalletManager.createInvoice(
                    amountSats = amountSats,
                    memo = description
                )
                ReceiveInvoice(
                    invoice = result.serialized,
                    amountSats = amountSats?.takeIf { it > 0L } ?: 0L,
                    description = description?.trim()?.ifBlank { null },
                    feeSats = 0L
                )
            }
            SpendWalletSource.SPARK -> {
                walletManager.createBolt11Invoice(
                    amountSats = amountSats,
                    description = description
                )
            }
            SpendWalletSource.SPARK_SUBWALLET -> {
                if (!sparkSubwalletManager.isConnected) {
                    sparkSubwalletManager.restoreWalletIfNeeded()
                }
                sparkSubwalletManager.createInvoice(
                    amountSats = amountSats,
                    memo = description
                )
            }
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
        return when (source) {
            SpendWalletSource.LND -> {
                if (!lndWalletManager.isConnected) {
                    lndWalletManager.restoreActiveNode()
                }
                val rows = lndWalletManager.fetchTransactionRows()
                val scope = lndWalletManager.walletScopeIdentifier()
                    ?: throw LndWalletException.NoStoredNode
                captureTransactionDestinationMetadata(rows, scope)
                val userLogs = lndPaymentUsdSnapshotStore.userLogs(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                val destinationMetadata = lndPaymentUsdSnapshotStore.destinationMetadata(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                rows.map { row -> row.withUserLogAndDestinationMetadata(userLogs, destinationMetadata) }
            }
            SpendWalletSource.NWC -> {
                if (!nwcWalletManager.isConnected) {
                    nwcWalletManager.restoreActiveWallet()
                }
                val rows = nwcWalletManager.fetchTransactionRows()
                val scope = nwcWalletManager.walletScopeIdentifier()
                    ?: throw NwcWalletException.NoStoredConnection
                captureTransactionDestinationMetadata(rows, scope)
                val userLogs = lndPaymentUsdSnapshotStore.userLogs(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                val destinationMetadata = lndPaymentUsdSnapshotStore.destinationMetadata(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                rows.map { row -> row.withUserLogAndDestinationMetadata(userLogs, destinationMetadata) }
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                if (!coreLightningWalletManager.isConnected) {
                    coreLightningWalletManager.restoreActiveNode()
                }
                val rows = coreLightningWalletManager.fetchTransactionRows()
                val scope = coreLightningWalletManager.walletScopeIdentifier()
                    ?: throw CoreLightningWalletException.NoStoredNode
                captureTransactionDestinationMetadata(rows, scope)
                val userLogs = lndPaymentUsdSnapshotStore.userLogs(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                val destinationMetadata = lndPaymentUsdSnapshotStore.destinationMetadata(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                rows.map { row -> row.withUserLogAndDestinationMetadata(userLogs, destinationMetadata) }
            }
            SpendWalletSource.ECLAIR -> {
                if (!eclairWalletManager.isConnected) {
                    eclairWalletManager.restoreActiveNode()
                }
                val rows = eclairWalletManager.fetchTransactionRows()
                val scope = eclairWalletManager.walletScopeIdentifier()
                    ?: throw EclairWalletException.NoStoredNode
                captureTransactionDestinationMetadata(rows, scope)
                val userLogs = lndPaymentUsdSnapshotStore.userLogs(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                val destinationMetadata = lndPaymentUsdSnapshotStore.destinationMetadata(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                rows.map { row -> row.withUserLogAndDestinationMetadata(userLogs, destinationMetadata) }
            }
            SpendWalletSource.SPARK -> walletManager.fetchTransactionRows()
            SpendWalletSource.SPARK_SUBWALLET -> {
                if (!sparkSubwalletManager.isConnected) {
                    sparkSubwalletManager.restoreWalletIfNeeded()
                }
                val rows = sparkSubwalletManager.fetchTransactionRows()
                val scope = sparkSubwalletManager.walletScopeIdentifier()
                    ?: throw SparkSubwalletException.NoStoredWallet
                captureTransactionDestinationMetadata(rows, scope)
                val userLogs = lndPaymentUsdSnapshotStore.userLogs(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                val destinationMetadata = lndPaymentUsdSnapshotStore.destinationMetadata(
                    walletPubkey = scope,
                    paymentIds = rows.map { it.id }
                )
                rows.map { row -> row.withUserLogAndDestinationMetadata(userLogs, destinationMetadata) }
            }
        }
    }

    suspend fun ensureTransactionUsdSnapshots(
        transactions: List<WalletTransactionRow>,
        source: SpendWalletSource = activeSpendWallet.value
    ) {
        when (source) {
            SpendWalletSource.LND -> ensureLndUsdSnapshots(transactions)
            SpendWalletSource.NWC -> {
                ensureExternalUsdSnapshots(
                    transactions = transactions,
                    scope = nwcWalletManager.walletScopeIdentifier() ?: throw NwcWalletException.NoStoredConnection
                )
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                ensureExternalUsdSnapshots(
                    transactions = transactions,
                    scope = coreLightningWalletManager.walletScopeIdentifier()
                        ?: throw CoreLightningWalletException.NoStoredNode
                )
            }
            SpendWalletSource.ECLAIR -> {
                ensureExternalUsdSnapshots(
                    transactions = transactions,
                    scope = eclairWalletManager.walletScopeIdentifier()
                        ?: throw EclairWalletException.NoStoredNode
                )
            }
            SpendWalletSource.SPARK -> walletManager.ensureUsdSnapshots(transactions)
            SpendWalletSource.SPARK_SUBWALLET -> {
                ensureExternalUsdSnapshots(
                    transactions = transactions,
                    scope = sparkSubwalletManager.walletScopeIdentifier()
                        ?: throw SparkSubwalletException.NoStoredWallet
                )
            }
        }
    }

    private fun WalletTransactionRow.withUserLogAndDestinationMetadata(
        userLogs: Map<String, String>,
        destinationMetadata: Map<String, PaymentDestinationMetadata>
    ): WalletTransactionRow {
        val metadata = destinationMetadata[id]
        return withUserLog(userLogs[id])
            .withDestinationMetadata(
                destinationPubkey = metadata?.destinationPubkey,
                paymentHash = metadata?.paymentHash
            )
    }

    private fun captureTransactionDestinationMetadata(
        rows: List<WalletTransactionRow>,
        scope: String
    ) {
        rows
            .filter { it.direction == "sent" }
            .forEach { row ->
                lndPaymentUsdSnapshotStore.setDestinationMetadata(
                    walletPubkey = scope,
                    paymentId = row.id,
                    paymentType = if (row.direction == "received") "received" else "sent",
                    destinationPubkey = row.destinationPubkey,
                    paymentHash = row.paymentHash
                )
            }
    }

    suspend fun transactionUsdSnapshots(
        paymentIds: List<String>,
        source: SpendWalletSource = activeSpendWallet.value
    ): Map<String, PaymentUsdSnapshot> {
        return when (source) {
            SpendWalletSource.LND -> {
                val scope = lndWalletManager.walletScopeIdentifier()
                    ?: throw LndWalletException.NoStoredNode
                lndPaymentUsdSnapshotStore.snapshots(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.NWC -> {
                val scope = nwcWalletManager.walletScopeIdentifier()
                    ?: throw NwcWalletException.NoStoredConnection
                lndPaymentUsdSnapshotStore.snapshots(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                val scope = coreLightningWalletManager.walletScopeIdentifier()
                    ?: throw CoreLightningWalletException.NoStoredNode
                lndPaymentUsdSnapshotStore.snapshots(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.ECLAIR -> {
                val scope = eclairWalletManager.walletScopeIdentifier()
                    ?: throw EclairWalletException.NoStoredNode
                lndPaymentUsdSnapshotStore.snapshots(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.SPARK -> {
                val walletPubkey = walletManager.currentWalletPubkey()
                PaymentUsdSnapshotStore(getApplication()).snapshots(
                    walletPubkey = walletPubkey,
                    paymentIds = paymentIds
                )
            }
            SpendWalletSource.SPARK_SUBWALLET -> {
                val scope = sparkSubwalletManager.walletScopeIdentifier()
                    ?: throw SparkSubwalletException.NoStoredWallet
                lndPaymentUsdSnapshotStore.snapshots(walletPubkey = scope, paymentIds = paymentIds)
            }
        }
    }

    suspend fun transactionReportableStates(
        paymentIds: List<String>,
        source: SpendWalletSource = activeSpendWallet.value
    ): Map<String, Boolean> {
        return when (source) {
            SpendWalletSource.LND -> {
                val scope = lndWalletManager.walletScopeIdentifier()
                    ?: throw LndWalletException.NoStoredNode
                lndPaymentUsdSnapshotStore.reportableStates(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.NWC -> {
                val scope = nwcWalletManager.walletScopeIdentifier()
                    ?: throw NwcWalletException.NoStoredConnection
                lndPaymentUsdSnapshotStore.reportableStates(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                val scope = coreLightningWalletManager.walletScopeIdentifier()
                    ?: throw CoreLightningWalletException.NoStoredNode
                lndPaymentUsdSnapshotStore.reportableStates(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.ECLAIR -> {
                val scope = eclairWalletManager.walletScopeIdentifier()
                    ?: throw EclairWalletException.NoStoredNode
                lndPaymentUsdSnapshotStore.reportableStates(walletPubkey = scope, paymentIds = paymentIds)
            }
            SpendWalletSource.SPARK -> walletManager.transactionReportableStates(paymentIds)
            SpendWalletSource.SPARK_SUBWALLET -> {
                val scope = sparkSubwalletManager.walletScopeIdentifier()
                    ?: throw SparkSubwalletException.NoStoredWallet
                lndPaymentUsdSnapshotStore.reportableStates(walletPubkey = scope, paymentIds = paymentIds)
            }
        }
    }

    suspend fun setTransactionReportable(
        paymentId: String,
        direction: String,
        isReportable: Boolean,
        source: SpendWalletSource = activeSpendWallet.value
    ) {
        when (source) {
            SpendWalletSource.LND,
            SpendWalletSource.NWC,
            SpendWalletSource.CORE_LIGHTNING,
            SpendWalletSource.ECLAIR,
            SpendWalletSource.SPARK_SUBWALLET -> {
                val scope = when (source) {
                    SpendWalletSource.LND -> lndWalletManager.walletScopeIdentifier()
                        ?: throw LndWalletException.NoStoredNode
                    SpendWalletSource.NWC -> nwcWalletManager.walletScopeIdentifier()
                        ?: throw NwcWalletException.NoStoredConnection
                    SpendWalletSource.CORE_LIGHTNING -> coreLightningWalletManager.walletScopeIdentifier()
                        ?: throw CoreLightningWalletException.NoStoredNode
                    SpendWalletSource.ECLAIR -> eclairWalletManager.walletScopeIdentifier()
                        ?: throw EclairWalletException.NoStoredNode
                    SpendWalletSource.SPARK_SUBWALLET -> sparkSubwalletManager.walletScopeIdentifier()
                        ?: throw SparkSubwalletException.NoStoredWallet
                    SpendWalletSource.SPARK -> "spark"
                }
                lndPaymentUsdSnapshotStore.setReportable(
                    walletPubkey = scope,
                    paymentId = paymentId,
                    paymentType = if (direction == "received") "received" else "sent",
                    isReportable = isReportable
                )
            }
            SpendWalletSource.SPARK -> {
                walletManager.setTransactionReportable(
                    paymentId = paymentId,
                    direction = direction,
                    isReportable = isReportable
                )
            }
        }
    }

    suspend fun setTransactionUserLog(
        paymentId: String,
        direction: String,
        userLog: String?,
        source: SpendWalletSource = activeSpendWallet.value
    ) {
        when (source) {
            SpendWalletSource.LND,
            SpendWalletSource.NWC,
            SpendWalletSource.CORE_LIGHTNING,
            SpendWalletSource.ECLAIR,
            SpendWalletSource.SPARK_SUBWALLET -> {
                val scope = when (source) {
                    SpendWalletSource.LND -> lndWalletManager.walletScopeIdentifier()
                        ?: throw LndWalletException.NoStoredNode
                    SpendWalletSource.NWC -> nwcWalletManager.walletScopeIdentifier()
                        ?: throw NwcWalletException.NoStoredConnection
                    SpendWalletSource.CORE_LIGHTNING -> coreLightningWalletManager.walletScopeIdentifier()
                        ?: throw CoreLightningWalletException.NoStoredNode
                    SpendWalletSource.ECLAIR -> eclairWalletManager.walletScopeIdentifier()
                        ?: throw EclairWalletException.NoStoredNode
                    SpendWalletSource.SPARK_SUBWALLET -> sparkSubwalletManager.walletScopeIdentifier()
                        ?: throw SparkSubwalletException.NoStoredWallet
                    SpendWalletSource.SPARK -> "spark"
                }
                lndPaymentUsdSnapshotStore.setUserLog(
                    walletPubkey = scope,
                    paymentId = paymentId,
                    paymentType = if (direction == "received") "received" else "sent",
                    userLog = userLog
                )
            }
            SpendWalletSource.SPARK -> {
                walletManager.setTransactionUserLog(
                    paymentId = paymentId,
                    direction = direction,
                    userLog = userLog
                )
            }
        }
    }

    suspend fun resolveMerchantReportTransaction(
        transaction: WalletTransactionRow,
        source: SpendWalletSource = activeSpendWallet.value
    ): WalletTransactionRow {
        val metadata = resolveDestinationMetadata(transaction, source)
            ?: throw IllegalStateException("Unable to determine destination pubkey.")
        val destinationPubkey = metadata.destinationPubkey?.trim()?.ifBlank { null }
            ?: throw IllegalStateException("Unable to determine destination pubkey.")
        val enrichedTransaction = transaction.withDestinationMetadata(
            destinationPubkey = destinationPubkey,
            paymentHash = metadata.paymentHash ?: transaction.paymentHash
        )
        persistTransactionDestinationMetadata(enrichedTransaction, source)
        return enrichedTransaction
    }

    private suspend fun resolveDestinationMetadata(
        transaction: WalletTransactionRow,
        source: SpendWalletSource
    ): PaymentDestinationMetadata? {
        transaction.destinationPubkey?.trim()?.ifBlank { null }?.let { destinationPubkey ->
            return PaymentDestinationMetadata(
                destinationPubkey = destinationPubkey,
                paymentHash = transaction.paymentHash?.trim()?.ifBlank { null }
            )
        }

        storedDestinationMetadata(transaction, source)
            ?.takeIf { !it.destinationPubkey.isNullOrBlank() }
            ?.let { return it }

        transaction.invoice?.trim()?.ifBlank { null }?.let { invoice ->
            decodeInvoiceDestinationMetadata(invoice, source)
                ?.takeIf { !it.destinationPubkey.isNullOrBlank() }
                ?.let { return it }
        }

        return resolveDestinationMetadataFromWallet(transaction, source)
    }

    private suspend fun storedDestinationMetadata(
        transaction: WalletTransactionRow,
        source: SpendWalletSource
    ): PaymentDestinationMetadata? {
        val scope = transactionMetadataScope(source) ?: return null
        val store = if (source == SpendWalletSource.SPARK) {
            PaymentUsdSnapshotStore(getApplication())
        } else {
            lndPaymentUsdSnapshotStore
        }
        return store.destinationMetadata(
            walletPubkey = scope,
            paymentIds = listOf(transaction.id)
        )[transaction.id]
    }

    private suspend fun decodeInvoiceDestinationMetadata(
        invoice: String,
        source: SpendWalletSource
    ): PaymentDestinationMetadata? {
        val decodedMetadata = runCatching {
            when (source) {
                SpendWalletSource.LND -> {
                    val decoded = lndWalletManager.decodeInvoice(invoice)
                    PaymentDestinationMetadata(
                        destinationPubkey = decoded.destination?.trim()?.ifBlank { null },
                        paymentHash = decoded.paymentHash?.trim()?.ifBlank { null }
                    )
                }
                SpendWalletSource.CORE_LIGHTNING -> {
                    val decoded = coreLightningWalletManager.decodeInvoice(invoice)
                    PaymentDestinationMetadata(
                        destinationPubkey = decoded.payee?.trim()?.ifBlank { null },
                        paymentHash = decoded.paymentHash?.trim()?.ifBlank { null }
                    )
                }
                SpendWalletSource.ECLAIR -> {
                    val decoded = eclairWalletManager.decodeInvoice(invoice)
                    PaymentDestinationMetadata(
                        destinationPubkey = decoded.nodeId?.trim()?.ifBlank { null },
                        paymentHash = decoded.paymentHash?.trim()?.ifBlank { null }
                    )
                }
                SpendWalletSource.SPARK_SUBWALLET -> {
                    val decoded = sparkSubwalletManager.decodeBolt11InvoiceMetadata(invoice)
                    PaymentDestinationMetadata(
                        destinationPubkey = decoded?.destinationPubkey?.trim()?.ifBlank { null },
                        paymentHash = decoded?.paymentHash?.trim()?.ifBlank { null }
                    )
                }
                SpendWalletSource.SPARK,
                SpendWalletSource.NWC -> {
                    val decoded = walletManager.decodeBolt11InvoiceMetadata(invoice)
                    PaymentDestinationMetadata(
                        destinationPubkey = decoded?.destinationPubkey?.trim()?.ifBlank { null },
                        paymentHash = decoded?.paymentHash?.trim()?.ifBlank { null }
                    )
                }
            }
        }.getOrNull()
        val localMetadata = Bolt11InvoiceMetadataDecoder.decode(invoice)

        return PaymentDestinationMetadata(
            destinationPubkey = decodedMetadata?.destinationPubkey?.trim()?.ifBlank { null }
                ?: localMetadata?.destinationPubkey?.trim()?.ifBlank { null },
            paymentHash = decodedMetadata?.paymentHash?.trim()?.ifBlank { null }
                ?: localMetadata?.paymentHash?.trim()?.ifBlank { null }
        ).takeIf { it.destinationPubkey != null || it.paymentHash != null }
    }

    private suspend fun resolveDestinationMetadataFromWallet(
        transaction: WalletTransactionRow,
        source: SpendWalletSource
    ): PaymentDestinationMetadata? {
        return when (source) {
            SpendWalletSource.LND -> {
                if (!lndWalletManager.isConnected) lndWalletManager.restoreActiveNode()
                lndWalletManager.fetchTransactionRows()
                    .matchingTransaction(transaction)
                    ?.destinationMetadata()
            }
            SpendWalletSource.NWC -> {
                if (!nwcWalletManager.isConnected) nwcWalletManager.restoreActiveWallet()
                nwcWalletManager.fetchTransactionRows()
                    .matchingTransaction(transaction)
                    ?.destinationMetadata()
                    ?.takeIf { !it.destinationPubkey.isNullOrBlank() }
                    ?: resolveNwcDestinationMetadataByPaymentHash(transaction)
            }
            SpendWalletSource.CORE_LIGHTNING -> {
                if (!coreLightningWalletManager.isConnected) coreLightningWalletManager.restoreActiveNode()
                coreLightningWalletManager.fetchTransactionRows()
                    .matchingTransaction(transaction)
                    ?.destinationMetadata()
            }
            SpendWalletSource.ECLAIR -> {
                if (!eclairWalletManager.isConnected) eclairWalletManager.restoreActiveNode()
                eclairWalletManager.fetchTransactionRows()
                    .matchingTransaction(transaction)
                    ?.destinationMetadata()
                    ?.takeIf { !it.destinationPubkey.isNullOrBlank() }
                    ?: resolveEclairDestinationMetadataByPaymentHash(transaction)
            }
            SpendWalletSource.SPARK -> {
                walletManager.fetchTransactionRows()
                    .matchingTransaction(transaction)
                    ?.destinationMetadata()
            }
            SpendWalletSource.SPARK_SUBWALLET -> {
                if (!sparkSubwalletManager.isConnected) sparkSubwalletManager.restoreWalletIfNeeded()
                sparkSubwalletManager.fetchTransactionRows()
                    .matchingTransaction(transaction)
                    ?.destinationMetadata()
            }
        }
    }

    private suspend fun resolveNwcDestinationMetadataByPaymentHash(
        transaction: WalletTransactionRow
    ): PaymentDestinationMetadata? {
        val paymentHash = transaction.paymentHash?.trim()?.ifBlank { null }
            ?: transaction.txReference?.trim()?.ifBlank { null }
            ?: return null
        val invoice = runCatching { nwcWalletManager.lookupInvoiceByPaymentHash(paymentHash) }
            .getOrNull()
            ?.invoice
            ?.trim()
            ?.ifBlank { null }
            ?: return null
        return decodeInvoiceDestinationMetadata(invoice, SpendWalletSource.NWC)
    }

    private suspend fun resolveEclairDestinationMetadataByPaymentHash(
        transaction: WalletTransactionRow
    ): PaymentDestinationMetadata? {
        val paymentHash = transaction.paymentHash?.trim()?.ifBlank { null }
            ?: transaction.txReference?.trim()?.ifBlank { null }
            ?: return null
        return runCatching {
            eclairWalletManager.getSentInfo(paymentHash)
                .firstNotNullOfOrNull { info ->
                    val invoice = info.payment?.serialized?.trim()?.ifBlank { null }
                    if (invoice != null) {
                        decodeInvoiceDestinationMetadata(invoice, SpendWalletSource.ECLAIR)
                    } else {
                        PaymentDestinationMetadata(
                            destinationPubkey = info.payment?.nodeId?.trim()?.ifBlank { null },
                            paymentHash = info.paymentHash?.trim()?.ifBlank { null } ?: paymentHash
                        )
                    }
                }
        }.getOrNull()
    }

    private suspend fun persistTransactionDestinationMetadata(
        transaction: WalletTransactionRow,
        source: SpendWalletSource
    ) {
        val scope = transactionMetadataScope(source) ?: return
        val store = if (source == SpendWalletSource.SPARK) {
            PaymentUsdSnapshotStore(getApplication())
        } else {
            lndPaymentUsdSnapshotStore
        }
        store.setDestinationMetadata(
            walletPubkey = scope,
            paymentId = transaction.id,
            paymentType = if (transaction.direction == "received") "received" else "sent",
            destinationPubkey = transaction.destinationPubkey,
            paymentHash = transaction.paymentHash
        )
    }

    private suspend fun transactionMetadataScope(source: SpendWalletSource): String? {
        return when (source) {
            SpendWalletSource.LND -> lndWalletManager.walletScopeIdentifier()
                ?: throw LndWalletException.NoStoredNode
            SpendWalletSource.NWC -> nwcWalletManager.walletScopeIdentifier()
                ?: throw NwcWalletException.NoStoredConnection
            SpendWalletSource.CORE_LIGHTNING -> coreLightningWalletManager.walletScopeIdentifier()
                ?: throw CoreLightningWalletException.NoStoredNode
            SpendWalletSource.ECLAIR -> eclairWalletManager.walletScopeIdentifier()
                ?: throw EclairWalletException.NoStoredNode
            SpendWalletSource.SPARK -> walletManager.currentWalletPubkey()
            SpendWalletSource.SPARK_SUBWALLET -> sparkSubwalletManager.walletScopeIdentifier()
                ?: throw SparkSubwalletException.NoStoredWallet
        }
    }

    private fun List<WalletTransactionRow>.matchingTransaction(
        transaction: WalletTransactionRow
    ): WalletTransactionRow? {
        val paymentHash = transaction.paymentHash?.trim()?.ifBlank { null }
        val txReference = transaction.txReference?.trim()?.ifBlank { null }
        return firstOrNull { row ->
            row.id == transaction.id ||
                (paymentHash != null && row.paymentHash?.trim()?.ifBlank { null } == paymentHash) ||
                (txReference != null && row.txReference?.trim()?.ifBlank { null } == txReference)
        }
    }

    private fun WalletTransactionRow.destinationMetadata(): PaymentDestinationMetadata {
        return PaymentDestinationMetadata(
            destinationPubkey = destinationPubkey?.trim()?.ifBlank { null },
            paymentHash = paymentHash?.trim()?.ifBlank { null }
        )
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

    fun storedLndNodes(): List<LndNodeCredentials> {
        return lndWalletManager.storedNodes()
    }

    fun hasStoredNwcWallet(): Boolean {
        return nwcWalletManager.connectedWallet.value != null || nwcCredentialStore.activeWallet() != null
    }

    fun activeNwcWalletCredentials(): NwcWalletCredentials? {
        return nwcWalletManager.connectedWallet.value ?: nwcCredentialStore.activeWallet()
    }

    fun storedNwcWallets(): List<NwcWalletCredentials> {
        return nwcWalletManager.storedWallets()
    }

    fun hasStoredCoreLightningNode(): Boolean {
        return coreLightningWalletManager.connectedNode.value != null ||
            coreLightningCredentialStore.activeNode() != null
    }

    fun hasStoredEclairNode(): Boolean {
        return eclairWalletManager.connectedNode.value != null ||
            eclairCredentialStore.activeNode() != null
    }

    fun activeCoreLightningNodeCredentials(): CoreLightningNodeCredentials? {
        return coreLightningWalletManager.connectedNode.value ?: coreLightningCredentialStore.activeNode()
    }

    fun activeEclairNodeCredentials(): EclairNodeCredentials? {
        return eclairWalletManager.connectedNode.value ?: eclairCredentialStore.activeNode()
    }

    fun storedCoreLightningNodes(): List<CoreLightningNodeCredentials> {
        return coreLightningWalletManager.storedNodes()
    }

    fun storedEclairNodes(): List<EclairNodeCredentials> {
        return eclairWalletManager.storedNodes()
    }

    fun hasStoredSparkSubwallet(): Boolean {
        return sparkSubwalletManager.connectedWallet.value != null ||
            sparkSubwalletCredentialStore.activeWallet() != null
    }

    fun activeSparkSubwalletCredentials(): SparkSubwalletCredentials? {
        return sparkSubwalletManager.connectedWallet.value ?: sparkSubwalletCredentialStore.activeWallet()
    }

    fun storedSparkSubwallets(): List<SparkSubwalletCredentials> {
        return sparkSubwalletManager.storedWallets()
    }

    fun storedLightningWallets(): List<ExternalWalletRecord> {
        return externalWalletStore.loadWallets()
    }

    fun transactionActivityScope(source: SpendWalletSource): String {
        return when (source) {
            SpendWalletSource.LND -> lndWalletManager.walletScopeIdentifier() ?: "lnd"
            SpendWalletSource.NWC -> nwcWalletManager.walletScopeIdentifier() ?: "nwc"
            SpendWalletSource.SPARK -> "spark"
            SpendWalletSource.CORE_LIGHTNING -> coreLightningWalletManager.walletScopeIdentifier() ?: "core-lightning"
            SpendWalletSource.ECLAIR -> eclairWalletManager.walletScopeIdentifier() ?: "eclair"
            SpendWalletSource.SPARK_SUBWALLET -> sparkSubwalletManager.walletScopeIdentifier() ?: "spark-subwallet"
        }
    }

    fun setSparkSpendWallet() {
        activeSpendWalletStore.setSparkActive()
        notifyWalletActivity()
    }

    fun warmTorForActiveWalletIfNeeded() {
        val usesTor = when (activeSpendWalletStore.activeWallet.value) {
            SpendWalletSource.LND -> activeLndNodeCredentials()?.usesTor == true
            SpendWalletSource.NWC -> activeNwcWalletCredentials()?.usesTor == true
            SpendWalletSource.CORE_LIGHTNING -> activeCoreLightningNodeCredentials()?.usesTor == true
            SpendWalletSource.ECLAIR -> activeEclairNodeCredentials()?.usesTor == true
            SpendWalletSource.SPARK,
            SpendWalletSource.SPARK_SUBWALLET -> false
        }

        if (usesTor) {
            RemoteNodeTorTransport.warmUp(getApplication(), viewModelScope)
        }
    }

    fun setLndSpendWalletIfAvailable(): Boolean {
        val didActivate = activeSpendWalletStore.setLndActiveIfAvailable()
        if (didActivate) {
            warmTorForActiveWalletIfNeeded()
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

    fun setLndSpendWallet(id: String): Boolean {
        val didActivate = activeSpendWalletStore.setLndActive(id)
        if (didActivate) {
            warmTorForActiveWalletIfNeeded()
            launchGuarded("setLndSpendWallet") {
                lndWalletManager.setActiveStoredNode(id)
                lndWalletManager.refreshBalance()
                notifyWalletActivity()
            }
        } else {
            notifyWalletActivity()
        }
        return didActivate
    }

    fun setNwcSpendWallet(id: String): Boolean {
        val didActivate = activeSpendWalletStore.setNwcActive(id)
        if (didActivate) {
            warmTorForActiveWalletIfNeeded()
            launchGuarded("setNwcSpendWallet") {
                nwcWalletManager.setActiveStoredWallet(id)
                nwcWalletManager.refreshBalance()
                notifyWalletActivity()
            }
        } else {
            notifyWalletActivity()
        }
        return didActivate
    }

    fun setCoreLightningSpendWallet(id: String): Boolean {
        val didActivate = activeSpendWalletStore.setCoreLightningActive(id)
        if (didActivate) {
            warmTorForActiveWalletIfNeeded()
            launchGuarded("setCoreLightningSpendWallet") {
                coreLightningWalletManager.setActiveStoredNode(id)
                coreLightningWalletManager.refreshBalance()
                notifyWalletActivity()
            }
        } else {
            notifyWalletActivity()
        }
        return didActivate
    }

    fun setEclairSpendWallet(id: String): Boolean {
        val didActivate = activeSpendWalletStore.setEclairActive(id)
        if (didActivate) {
            warmTorForActiveWalletIfNeeded()
            launchGuarded("setEclairSpendWallet") {
                eclairWalletManager.setActiveStoredNode(id)
                eclairWalletManager.refreshBalance()
                notifyWalletActivity()
            }
        } else {
            notifyWalletActivity()
        }
        return didActivate
    }

    fun setSparkSubwalletSpendWallet(id: String): Boolean {
        val didActivate = activeSpendWalletStore.setSparkSubwalletActive(id)
        if (didActivate) {
            launchGuarded("setSparkSubwalletSpendWallet") {
                sparkSubwalletManager.setActiveStoredWallet(id)
                sparkSubwalletManager.refreshBalance()
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

    fun setNwcNotificationListenerActive(active: Boolean) {
        nwcWalletManager.setNotificationListenerActive(active)
    }

    suspend fun connectLndNode(
        lndConnectString: String,
        label: String? = null
    ): LndNodeCredentials {
        val node = lndWalletManager.connect(lndConnectString, label)
        activeSpendWalletStore.setLndActiveIfAvailable()
        notifyWalletActivity()
        return node
    }

    suspend fun connectNwcWallet(
        nwcConnectionString: String,
        label: String? = null
    ): NwcWalletCredentials {
        val wallet = nwcWalletManager.connect(nwcConnectionString, label)
        activeSpendWalletStore.setNwcActive(wallet.id)
        notifyWalletActivity()
        return wallet
    }

    suspend fun connectCoreLightningNode(
        connectionString: String,
        label: String? = null
    ): CoreLightningNodeCredentials {
        val node = coreLightningWalletManager.connect(connectionString, label)
        activeSpendWalletStore.setCoreLightningActive(node.id)
        notifyWalletActivity()
        return node
    }

    suspend fun connectEclairNode(
        scheme: String,
        host: String,
        port: Int,
        apiPassword: String,
        label: String? = null
    ): EclairNodeCredentials {
        val node = eclairWalletManager.connect(scheme, host, port, apiPassword, label)
        activeSpendWalletStore.setEclairActive(node.id)
        notifyWalletActivity()
        return node
    }

    suspend fun connectEclairNode(
        connectionString: String,
        label: String? = null
    ): EclairNodeCredentials {
        val node = eclairWalletManager.connect(connectionString, label)
        activeSpendWalletStore.setEclairActive(node.id)
        notifyWalletActivity()
        return node
    }

    suspend fun restoreNwcWallet() {
        nwcWalletManager.restoreActiveWallet()
        notifyWalletActivity()
    }

    suspend fun refreshNwcConnection() {
        nwcWalletManager.refreshWalletInfo()
        nwcWalletManager.refreshBalance()
        notifyWalletActivity()
    }

    suspend fun restoreCoreLightningNode() {
        coreLightningWalletManager.restoreActiveNode()
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    suspend fun refreshCoreLightningConnection() {
        coreLightningWalletManager.refreshNodeInfo()
        coreLightningWalletManager.refreshBalance()
        notifyWalletActivity()
    }

    fun forgetCoreLightningNode(id: String) {
        coreLightningWalletManager.forgetNode(id)
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    fun renameCoreLightningNode(id: String, label: String) {
        coreLightningWalletManager.renameNode(id, label)
        notifyWalletActivity()
    }

    suspend fun restoreEclairNode() {
        eclairWalletManager.restoreActiveNode()
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    suspend fun refreshEclairConnection() {
        eclairWalletManager.refreshNodeInfo()
        eclairWalletManager.refreshBalance()
        notifyWalletActivity()
    }

    fun forgetEclairNode(id: String) {
        eclairWalletManager.forgetNode(id)
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    fun renameEclairNode(id: String, label: String) {
        eclairWalletManager.renameNode(id, label)
        notifyWalletActivity()
    }

    fun createPendingSparkSubwalletSeed() {
        sparkSubwalletManager.createPendingWalletSeed()
    }

    fun cancelPendingSparkSubwalletSeed() {
        sparkSubwalletManager.cancelPendingWalletSeed()
    }

    suspend fun createSparkSubwallet(label: String): SparkSubwalletCredentials {
        val wallet = sparkSubwalletManager.createWallet(label)
        activeSpendWalletStore.setSparkSubwalletActive(wallet.id)
        notifyWalletActivity()
        return wallet
    }

    suspend fun restoreSparkSubwallet(
        seedPhrase: String,
        label: String
    ): SparkSubwalletCredentials {
        val wallet = sparkSubwalletManager.restoreWallet(seedPhrase = seedPhrase, label = label)
        activeSpendWalletStore.setSparkSubwalletActive(wallet.id)
        notifyWalletActivity()
        return wallet
    }

    suspend fun restoreSparkSubwallet(id: String? = null) {
        sparkSubwalletManager.restoreWalletIfNeeded(id)
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    suspend fun refreshSparkSubwalletConnection() {
        sparkSubwalletManager.refreshBalance()
        notifyWalletActivity()
    }

    fun forgetSparkSubwallet(id: String) {
        launchGuarded("forgetSparkSubwallet") {
            sparkSubwalletManager.forgetWallet(id)
            lndPaymentUsdSnapshotStore.clearWallet("spark-subwallet:$id")
            activeSpendWalletStore.reconcileWithStoredWallets()
            notifyWalletActivity()
        }
    }

    fun renameSparkSubwallet(id: String, label: String) {
        sparkSubwalletManager.renameWallet(id, label)
        notifyWalletActivity()
    }

    fun forgetNwcWallet(id: String) {
        nwcWalletManager.forgetWallet(id)
        notifyWalletActivity()
    }

    fun renameNwcWallet(id: String, label: String) {
        nwcWalletManager.renameWallet(id, label)
        notifyWalletActivity()
    }

    suspend fun restoreLndNode() {
        lndWalletManager.restoreActiveNode()
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    suspend fun refreshLndConnection() {
        lndWalletManager.refreshNodeInfo()
        lndWalletManager.refreshBalance()
        notifyWalletActivity()
    }

    fun forgetLndNode(id: String) {
        lndWalletManager.forgetNode(id)
        activeSpendWalletStore.reconcileWithStoredWallets()
        notifyWalletActivity()
    }

    fun renameLndNode(id: String, label: String) {
        lndWalletManager.renameNode(id, label)
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
        launchLndPostSendReconciliation(
            preview = preview,
            paidPaymentHash = response.paymentHash,
            preimage = response.paymentPreimage
        )
    }

    private suspend fun sendPreparedNwcPayment(preview: PaymentPreview) = withContext(NonCancellable) {
        Log.i(
            "SplitRootViewModel",
            "Starting NWC send paymentHash=${preview.paymentHash ?: "unknown"} amountSats=${preview.amountSats}"
        )

        if (!nwcWalletManager.isConnected) {
            nwcWalletManager.restoreActiveWallet()
        }

        val response = nwcWalletManager.payInvoice(
            bolt11 = preview.destination,
            amountSats = preview.lndAmountOverrideSats
        )

        walletManager.showOutgoingPaymentSuccess()
        notifyWalletActivity()
        launchNwcPostSendReconciliation(
            preview = preview,
            preimage = response.preimage
        )
    }

    private suspend fun sendPreparedCoreLightningPayment(preview: PaymentPreview) = withContext(NonCancellable) {
        Log.i(
            "SplitRootViewModel",
            "Starting Core Lightning send paymentHash=${preview.paymentHash ?: "unknown"} amountSats=${preview.amountSats}"
        )

        if (!coreLightningWalletManager.isConnected) {
            coreLightningWalletManager.restoreActiveNode()
        }

        val response = coreLightningWalletManager.payInvoice(
            bolt11 = preview.destination,
            amountSats = preview.lndAmountOverrideSats
        )

        if (!response.didSucceed) {
            Log.w(
                "SplitRootViewModel",
                "Core Lightning send reported status=${response.status ?: "unknown"}"
            )
            throw CoreLightningWalletException.PaymentFailed
        }

        walletManager.showOutgoingPaymentSuccess()
        notifyWalletActivity()
        launchCoreLightningPostSendReconciliation(
            preview = preview,
            paidPaymentHash = response.paymentHash,
            preimage = response.paymentPreimage
        )
    }

    private suspend fun sendPreparedEclairPayment(preview: PaymentPreview) = withContext(NonCancellable) {
        Log.i(
            "SplitRootViewModel",
            "Starting Eclair send paymentHash=${preview.paymentHash ?: "unknown"} amountSats=${preview.amountSats}"
        )

        if (!eclairWalletManager.isConnected) {
            eclairWalletManager.restoreActiveNode()
        }

        val response = eclairWalletManager.payInvoice(
            bolt11 = preview.destination,
            amountSats = preview.lndAmountOverrideSats
        )

        if (!response.didSucceed) {
            Log.w(
                "SplitRootViewModel",
                "Eclair send reported failure. ${response.failureMessage ?: "Payment failed."}"
            )
            throw EclairWalletException.PaymentFailed(response.failureMessage)
        }

        walletManager.showOutgoingPaymentSuccess()
        notifyWalletActivity()
        launchEclairPostSendReconciliation(
            preview = preview,
            paidPaymentHash = response.paymentHash,
            preimage = response.paymentPreimage
        )
    }

    private suspend fun sendPreparedSparkSubwalletPayment(
        preparedPayment: PreparedOutgoingPayment
    ) = withContext(NonCancellable) {
        val preview = preparedPayment.preview
        Log.i(
            "SplitRootViewModel",
            "Starting Spark sub-wallet send paymentHash=${preview.paymentHash ?: "unknown"} amountSats=${preview.amountSats}"
        )

        if (!sparkSubwalletManager.isConnected) {
            sparkSubwalletManager.restoreWalletIfNeeded()
        }

        when (val result = sparkSubwalletManager.sendPreparedPayment(preparedPayment)) {
            is PreparedOutgoingPaymentSendResult.Completed -> {
                walletManager.suppressOutgoingSuccessToastForPayment(result.paymentId)
                walletManager.showOutgoingPaymentSuccess()
                notifyWalletActivity()
                launchSparkSubwalletPostSendReconciliation()
            }

            is PreparedOutgoingPaymentSendResult.Pending -> {
                result.paymentId?.let(walletManager::suppressOutgoingSuccessToastForPayment)
                notifyWalletActivity()
                launchSparkSubwalletPostSendReconciliation()
            }

            is PreparedOutgoingPaymentSendResult.Failed -> {
                result.paymentId?.let(walletManager::suppressOutgoingFailureToastForPayment)
                val message = result.message?.trim().orEmpty().ifBlank {
                    "Unable to send payment."
                }
                walletManager.showOutgoingPaymentFailure(subtitle = message)
                throw IllegalStateException(message)
            }
        }
    }

    private fun launchLndPostSendReconciliation(
        preview: PaymentPreview,
        paidPaymentHash: String?,
        preimage: String?
    ) {
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

            submitPreparedPaymentRewardClaim(
                preview = preview,
                paidPaymentHash = paidPaymentHash,
                preimage = preimage,
                source = "LND"
            )
        }
    }

    private fun launchNwcPostSendReconciliation(
        preview: PaymentPreview,
        preimage: String?
    ) {
        viewModelScope.launch {
            runCatching {
                val rows = nwcWalletManager.fetchTransactionRows()
                ensureExternalUsdSnapshots(
                    transactions = rows,
                    scope = nwcWalletManager.walletScopeIdentifier() ?: return@runCatching
                )
                notifyWalletActivity()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh NWC transactions after send. ${error.localizedMessage}",
                    error
                )
            }

            runCatching {
                nwcWalletManager.refreshBalance()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh NWC balance after send. ${error.localizedMessage}",
                    error
                )
            }

            submitPreparedPaymentRewardClaim(
                preview = preview,
                paidPaymentHash = null,
                preimage = preimage,
                source = "NWC"
            )
        }
    }

    private fun launchCoreLightningPostSendReconciliation(
        preview: PaymentPreview,
        paidPaymentHash: String?,
        preimage: String?
    ) {
        viewModelScope.launch {
            runCatching {
                val rows = coreLightningWalletManager.fetchTransactionRows()
                ensureExternalUsdSnapshots(
                    transactions = rows,
                    scope = coreLightningWalletManager.walletScopeIdentifier() ?: return@runCatching
                )
                notifyWalletActivity()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh Core Lightning transactions after send. ${error.localizedMessage}",
                    error
                )
            }

            runCatching {
                coreLightningWalletManager.refreshBalance()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh Core Lightning balance after send. ${error.localizedMessage}",
                    error
                )
            }

            submitPreparedPaymentRewardClaim(
                preview = preview,
                paidPaymentHash = paidPaymentHash,
                preimage = preimage,
                source = "Core Lightning"
            )
        }
    }

    private fun launchEclairPostSendReconciliation(
        preview: PaymentPreview,
        paidPaymentHash: String?,
        preimage: String?
    ) {
        viewModelScope.launch {
            runCatching {
                val rows = eclairWalletManager.fetchTransactionRows()
                ensureExternalUsdSnapshots(
                    transactions = rows,
                    scope = eclairWalletManager.walletScopeIdentifier() ?: return@runCatching
                )
                notifyWalletActivity()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh Eclair transactions after send. ${error.localizedMessage}",
                    error
                )
            }

            runCatching {
                eclairWalletManager.refreshBalance()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh Eclair balance after send. ${error.localizedMessage}",
                    error
                )
            }

            submitPreparedPaymentRewardClaim(
                preview = preview,
                paidPaymentHash = paidPaymentHash,
                preimage = preimage,
                source = "Eclair"
            )
        }
    }

    private fun launchSparkSubwalletPostSendReconciliation() {
        viewModelScope.launch {
            runCatching {
                val rows = sparkSubwalletManager.fetchTransactionRows()
                ensureExternalUsdSnapshots(
                    transactions = rows,
                    scope = sparkSubwalletManager.walletScopeIdentifier() ?: return@runCatching
                )
                notifyWalletActivity()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh Spark sub-wallet transactions after send. ${error.localizedMessage}",
                    error
                )
            }

            runCatching {
                sparkSubwalletManager.refreshBalance()
            }.onFailure { error ->
                Log.w(
                    "SplitRootViewModel",
                    "Failed to refresh Spark sub-wallet balance after send. ${error.localizedMessage}",
                    error
                )
            }
        }
    }

    private suspend fun submitPreparedPaymentRewardClaim(
        preview: PaymentPreview,
        paidPaymentHash: String?,
        preimage: String?,
        source: String
    ) {
        if (preview.rewardEligible != true) return

        runCatching {
            val paymentHash = paidPaymentHash?.trim()?.ifBlank { null }
                ?: preview.paymentHash?.trim()?.ifBlank { null }
            rewardsRepository.postEncryptedRewardSpendClaim(
                merchantPubkeyHash = preview.merchantPubkeyHash,
                paymentHash = paymentHash,
                preimage = preimage,
                btcAmountSats = preview.amountSats,
                usdAmountCents = lndRewardSpendUsdCents(preview.amountSats),
                invoice = preview.destination,
                authManager = authManager,
                walletManager = walletManager
            )
        }.onFailure { error ->
            Log.w(
                "SplitRootViewModel",
                "Failed to post $source encrypted reward claim. ${error.localizedMessage}",
                error
            )
        }
    }

    private suspend fun ensureLndUsdSnapshots(rows: List<WalletTransactionRow>) {
        val scope = lndWalletManager.walletScopeIdentifier() ?: return
        ensureExternalUsdSnapshots(rows, scope)
    }

    private suspend fun ensureExternalUsdSnapshots(
        transactions: List<WalletTransactionRow>,
        scope: String
    ) {
        val completedRows = transactions
            .filter { row ->
                row.status == "Completed" &&
                    (row.direction == "sent" || row.direction == "received") &&
                    row.amountSats > 0L
            }
            .sortedBy { it.transactionTimestampMillis }

        for (row in completedRows) {
            lndPaymentUsdSnapshotStore.setDestinationMetadata(
                walletPubkey = scope,
                paymentId = row.id,
                paymentType = if (row.direction == "received") "received" else "sent",
                destinationPubkey = row.destinationPubkey,
                paymentHash = row.paymentHash
            )

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
