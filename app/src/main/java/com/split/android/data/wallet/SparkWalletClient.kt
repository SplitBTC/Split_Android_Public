package com.split.android.data.wallet

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.BuyBitcoinRequest
import breez_sdk_spark.CheckLightningAddressRequest
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.InputType
import breez_sdk_spark.AddContactRequest
import breez_sdk_spark.ListContactsRequest
import breez_sdk_spark.LnurlPayRequest
import breez_sdk_spark.PrepareLnurlPayRequest
import breez_sdk_spark.PrepareLnurlPayResponse
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.Network
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.ReceivePaymentResponse
import breez_sdk_spark.RegisterLightningAddressRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.SignMessageRequest
import breez_sdk_spark.UpdateContactRequest
import com.split.android.core.AppConfig
import breez_sdk_spark.UpdateUserSettingsRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import com.split.android.data.rewards.RewardTrace
import java.math.BigInteger
import java.util.UUID

data class SignedMessage(
    val pubkey: String,
    val signature: String
)

interface WalletAuthProvider {
    suspend fun signMessage(message: String): SignedMessage
    suspend fun getSparkAddress(): String
}

data class WalletSnapshot(
    val balanceSats: Long,
    val sparkAddress: String
)

data class WalletConnection(
    val snapshot: WalletSnapshot,
    val authProvider: WalletAuthProvider
)

data class PaymentPreview(
    val backend: WalletBackend = WalletBackend.SPARK,
    val destination: String,
    val amountSats: Long,
    val feeSats: Long?,
    val feesIncluded: Boolean,
    val methodLabel: String,
    val lndAmountOverrideSats: Long? = null,
    val destinationPubkey: String? = null,
    val paymentHash: String? = null,
    val merchantPubkeyHash: String? = null,
    val rewardEligible: Boolean? = null
)

data class Bolt11InvoiceMetadata(
    val amountSats: Long?,
    val paymentHash: String?,
    val destinationPubkey: String?,
    val description: String?
)

enum class WalletBackend {
    SPARK,
    LND,
    NWC,
    CORE_LIGHTNING,
    ECLAIR,
    SPARK_SUBWALLET
}

enum class SpendWalletSource {
    SPARK,
    LND,
    NWC,
    CORE_LIGHTNING,
    ECLAIR,
    SPARK_SUBWALLET
}

sealed interface PreparedOutgoingPayment {
    val preview: PaymentPreview

    data class Standard(
        override val preview: PaymentPreview,
        val prepareResponse: PrepareSendPaymentResponse
    ) : PreparedOutgoingPayment

    data class Lnurl(
        override val preview: PaymentPreview,
        val prepareResponse: PrepareLnurlPayResponse
    ) : PreparedOutgoingPayment

    data class Lnd(
        override val preview: PaymentPreview
    ) : PreparedOutgoingPayment

    data class Nwc(
        override val preview: PaymentPreview
    ) : PreparedOutgoingPayment

    data class CoreLightning(
        override val preview: PaymentPreview
    ) : PreparedOutgoingPayment

    data class Eclair(
        override val preview: PaymentPreview
    ) : PreparedOutgoingPayment
}

data class ReceiveInvoice(
    val invoice: String,
    val amountSats: Long,
    val description: String?,
    val feeSats: Long
)

sealed interface PreparedOutgoingPaymentSendResult {
    data class Completed(val paymentId: String) : PreparedOutgoingPaymentSendResult
    data class Pending(val paymentId: String?) : PreparedOutgoingPaymentSendResult
    data class Failed(
        val paymentId: String? = null,
        val message: String? = null
    ) : PreparedOutgoingPaymentSendResult
}

interface SparkWalletClient {
    suspend fun connectWithSeed(
        mnemonic: String,
        apiKey: String,
        storageDir: String
    ): WalletConnection

    suspend fun refreshWalletSnapshot(): WalletSnapshot
    suspend fun listTransactions(): List<WalletTransactionRow>
    suspend fun presetAmountSats(destination: String): Long?
    suspend fun decodeBolt11InvoiceMetadata(invoice: String): Bolt11InvoiceMetadata?
    suspend fun prepareOutgoingPayment(
        destination: String,
        amountSats: Long?,
        feesIncluded: Boolean = false,
        comment: String? = null
    ): PreparedOutgoingPayment
    suspend fun sendPreparedPayment(preparedPayment: PreparedOutgoingPayment): PreparedOutgoingPaymentSendResult
    suspend fun createBolt11Invoice(
        amountSats: Long?,
        description: String?
    ): ReceiveInvoice
    suspend fun createCashAppBuyUrl(amountSats: Long?): String
    suspend fun currentWalletPubkey(): String
    suspend fun currentLightningAddressInfo(): WalletLightningAddressInfo?
    suspend fun currentLightningAddress(): String?
    suspend fun isLightningAddressAvailable(username: String): Boolean
    suspend fun createLightningAddress(
        username: String,
        description: String? = null
    ): WalletLightningAddressInfo
    suspend fun listContacts(): List<WalletContact>
    suspend fun addContact(name: String, paymentIdentifier: String): WalletContact
    suspend fun updateContact(id: String, name: String, paymentIdentifier: String): WalletContact
    suspend fun deleteContact(id: String)
    suspend fun attachEventListener(onWalletEvent: suspend (SdkEvent) -> Unit)
    suspend fun disconnect()
}

class BreezSparkWalletClient : SparkWalletClient {
    private var sdk: BreezSdk? = null
    private var eventListenerId: String? = null

    override suspend fun connectWithSeed(
        mnemonic: String,
        apiKey: String,
        storageDir: String
    ): WalletConnection {
        disconnect()

        val seed = Seed.Mnemonic(mnemonic, null)
        val config = defaultConfig(Network.MAINNET).apply {
            this.apiKey = apiKey
            this.lnurlDomain = AppConfig.lightningAddressDomain
            this.privateEnabledDefault = true
        }

        val connectedSdk = connect(
            ConnectRequest(
                config = config,
                seed = seed,
                storageDir = storageDir
            )
        )
        sdk = connectedSdk

        val currentUserSettings = connectedSdk.getUserSettings()
        if (currentUserSettings.sparkPrivateModeEnabled != true) {
            connectedSdk.updateUserSettings(
                UpdateUserSettingsRequest(sparkPrivateModeEnabled = true)
            )
        }

        val snapshot = connectedWalletSnapshot(connectedSdk)

        return WalletConnection(
            snapshot = snapshot,
            authProvider = BreezWalletAuthProvider(connectedSdk)
        )
    }

    override suspend fun refreshWalletSnapshot(): WalletSnapshot {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return connectedWalletSnapshot(currentSdk)
    }

    override suspend fun listTransactions(): List<WalletTransactionRow> {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return currentSdk.listPayments(ListPaymentsRequest()).payments
            .map { it.toWalletTransactionRow() }
            .sortedByDescending { it.transactionTimestampMillis }
    }

    override suspend fun presetAmountSats(destination: String): Long? {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val trimmedDestination = destination.trim()
        if (trimmedDestination.isEmpty()) return null
        return currentSdk.parse(trimmedDestination).fixedBolt11AmountSats()
    }

    override suspend fun decodeBolt11InvoiceMetadata(invoice: String): Bolt11InvoiceMetadata? {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val trimmedInvoice = invoice.trim()
        if (!LndLightningPaymentResolver.isBolt11(trimmedInvoice)) return null

        val localMetadata = Bolt11InvoiceMetadataDecoder.decode(trimmedInvoice)
        return when (val parsed = runCatching { currentSdk.parse(trimmedInvoice) }.getOrNull()) {
            is InputType.Bolt11Invoice -> parsed.toMetadata(localMetadata)
            else -> null
        } ?: localMetadata
    }

    override suspend fun prepareOutgoingPayment(
        destination: String,
        amountSats: Long?,
        feesIncluded: Boolean,
        comment: String?
    ): PreparedOutgoingPayment {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val trimmedDestination = destination.trim()
        require(trimmedDestination.isNotEmpty()) { "Enter a payment request to continue." }

        val parsed = currentSdk.parse(trimmedDestination)
        val trimmedComment = comment?.trim()?.ifBlank { null }
        val feePolicy = if (feesIncluded) FeePolicy.FEES_INCLUDED else null

        return when (parsed) {
            is InputType.LightningAddress -> {
                val sats = amountSats?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("Enter an amount in sats.")

                val prepareResponse = currentSdk.prepareLnurlPay(
                    PrepareLnurlPayRequest(
                        amount = BigInteger.valueOf(sats),
                        payRequest = parsed.v1.payRequest,
                        comment = trimmedComment,
                        validateSuccessActionUrl = true,
                        feePolicy = feePolicy
                    )
                )
                val feeSats = prepareResponse.feeSats.toLong()
                val responseFeesIncluded = prepareResponse.feePolicy == FeePolicy.FEES_INCLUDED
                val previewAmountSats = previewAmountSats(
                    requestedAmountSats = sats,
                    feeSats = feeSats,
                    feesIncluded = responseFeesIncluded
                )

                PreparedOutgoingPayment.Lnurl(
                    preview = PaymentPreview(
                        destination = parsed.v1.address,
                        amountSats = previewAmountSats,
                        feeSats = feeSats,
                        feesIncluded = responseFeesIncluded,
                        methodLabel = "Lightning address"
                    ),
                    prepareResponse = prepareResponse
                )
            }

            else -> {
                val bolt11Metadata = (parsed as? InputType.Bolt11Invoice)?.let {
                    it.toMetadata(Bolt11InvoiceMetadataDecoder.decode(trimmedDestination))
                }
                val prepareResponse = currentSdk.prepareSendPayment(
                    PrepareSendPaymentRequest(
                        paymentRequest = trimmedDestination,
                        amount = amountSats?.takeIf { it > 0 }?.let { BigInteger.valueOf(it) },
                        feePolicy = feePolicy
                    )
                )

                val resolvedAmountSats = amountSats?.takeIf { it > 0 }
                    ?: parsed.fixedBolt11AmountSats()
                    ?: prepareResponse.amount.toLong()
                val feeSats = prepareResponse.feeSatsOrNull()
                val responseFeesIncluded = prepareResponse.feePolicy == FeePolicy.FEES_INCLUDED
                val previewAmountSats = previewAmountSats(
                    requestedAmountSats = resolvedAmountSats,
                    feeSats = feeSats,
                    feesIncluded = responseFeesIncluded
                )

                PreparedOutgoingPayment.Standard(
                    preview = PaymentPreview(
                        destination = trimmedDestination,
                        amountSats = previewAmountSats,
                        feeSats = feeSats,
                        feesIncluded = responseFeesIncluded,
                        methodLabel = bolt11Metadata?.description ?: prepareResponse.paymentMethodLabel(),
                        destinationPubkey = bolt11Metadata?.destinationPubkey,
                        paymentHash = bolt11Metadata?.paymentHash
                    ),
                    prepareResponse = prepareResponse
                )
            }
        }
    }

    override suspend fun sendPreparedPayment(
        preparedPayment: PreparedOutgoingPayment
    ): PreparedOutgoingPaymentSendResult {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val idempotencyKey = UUID.randomUUID().toString()

        val payment = when (preparedPayment) {
            is PreparedOutgoingPayment.Standard -> {
                currentSdk.sendPayment(
                    SendPaymentRequest(
                        prepareResponse = preparedPayment.prepareResponse,
                        idempotencyKey = idempotencyKey
                    )
                ).payment
            }

            is PreparedOutgoingPayment.Lnurl -> {
                currentSdk.lnurlPay(
                    LnurlPayRequest(
                        prepareResponse = preparedPayment.prepareResponse,
                        idempotencyKey = idempotencyKey
                    )
                ).payment
            }

            is PreparedOutgoingPayment.Lnd -> {
                throw IllegalArgumentException("LND payments are sent by the LND wallet manager.")
            }

            is PreparedOutgoingPayment.Nwc -> {
                throw IllegalArgumentException("NWC payments are sent by the NWC wallet manager.")
            }

            is PreparedOutgoingPayment.CoreLightning -> {
                throw IllegalArgumentException("Core Lightning payments are sent by the Core Lightning wallet manager.")
            }

            is PreparedOutgoingPayment.Eclair -> {
                throw IllegalArgumentException("Eclair payments are sent by the Eclair wallet manager.")
            }
        }

        val row = payment.toWalletTransactionRow()
        val proof = payment.rewardClaimProof(row)
        RewardTrace.i(
            "spark send returned id=${RewardTrace.id(payment.id)} status=${payment.status} " +
                "type=${payment.paymentType} method=${payment.method} " +
                "details=${payment.details?.javaClass?.simpleName ?: "none"} " +
                "rowInvoice=${RewardTrace.present(row.invoice)} " +
                "rowPaymentHash=${RewardTrace.present(row.paymentHash)} " +
                "rowPaymentHashFp=${RewardTrace.fp(row.paymentHash)} " +
                "rowPreimage=${RewardTrace.present(row.preimage)} " +
                "rowDest=${RewardTrace.present(row.destinationPubkey)} " +
                "rowDestFp=${RewardTrace.fp(row.destinationPubkey)} " +
                "proofDest=${RewardTrace.present(proof?.destinationPubkey)} " +
                "proofDestFp=${RewardTrace.fp(proof?.destinationPubkey)} " +
                "proofInvoice=${RewardTrace.present(proof?.invoice)} " +
                "proofInvoiceLen=${proof?.invoice?.length ?: 0} " +
                "proofPaymentHash=${RewardTrace.present(proof?.paymentHash)} " +
                "proofPaymentHashFp=${RewardTrace.fp(proof?.paymentHash)} " +
                "proofPreimage=${RewardTrace.present(proof?.preimage)}"
        )

        return when (payment.status) {
            PaymentStatus.COMPLETED -> PreparedOutgoingPaymentSendResult.Completed(payment.id)
            PaymentStatus.PENDING -> PreparedOutgoingPaymentSendResult.Pending(payment.id)
            PaymentStatus.FAILED -> PreparedOutgoingPaymentSendResult.Failed(
                paymentId = payment.id,
                message = "Failed to send payment."
            )
        }
    }

    override suspend fun createBolt11Invoice(
        amountSats: Long?,
        description: String?
    ): ReceiveInvoice {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")

        val response: ReceivePaymentResponse = currentSdk.receivePayment(
            ReceivePaymentRequest(
                ReceivePaymentMethod.Bolt11Invoice(
                    description = description?.trim().orEmpty(),
                    amountSats = amountSats?.takeIf { it > 0L }?.toULong(),
                    expirySecs = 3_600u,
                    paymentHash = null
                )
            )
        )

        return ReceiveInvoice(
            invoice = response.paymentRequest,
            amountSats = amountSats?.takeIf { it > 0L } ?: 0L,
            description = description?.trim()?.ifBlank { null },
            feeSats = response.fee.toLong()
        )
    }

    override suspend fun createCashAppBuyUrl(amountSats: Long?): String {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val normalizedAmountSats = amountSats
            ?.takeIf { it > 0 }
            ?.toULong()
            ?: throw IllegalArgumentException("Enter an amount in sats.")

        return currentSdk.buyBitcoin(
            BuyBitcoinRequest.CashApp(amountSats = normalizedAmountSats)
        ).url
    }

    override suspend fun currentWalletPubkey(): String {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return currentSdk.getInfo(GetInfoRequest(ensureSynced = false)).identityPubkey
    }

    override suspend fun currentLightningAddressInfo(): WalletLightningAddressInfo? {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val lightningAddressInfo = runCatching {
            currentSdk.getLightningAddress()
        }.getOrNull() ?: return null

        return lightningAddressInfo.toWalletLightningAddressInfo()
    }

    override suspend fun currentLightningAddress(): String? {
        return currentLightningAddressInfo()
            ?.lightningAddress
            ?.trim()
            ?.ifBlank { null }
    }

    override suspend fun isLightningAddressAvailable(username: String): Boolean {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return currentSdk.checkLightningAddressAvailable(
            CheckLightningAddressRequest(
                username = username.trim().lowercase()
            )
        )
    }

    override suspend fun createLightningAddress(
        username: String,
        description: String?
    ): WalletLightningAddressInfo {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return currentSdk.registerLightningAddress(
            RegisterLightningAddressRequest(
                username = username.trim().lowercase(),
                description = description?.trim()?.ifBlank { null }
            )
        ).toWalletLightningAddressInfo()
    }

    override suspend fun listContacts(): List<WalletContact> {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return currentSdk.listContacts(ListContactsRequest()).map { contact ->
            contact.toWalletContact()
        }
    }

    override suspend fun addContact(
        name: String,
        paymentIdentifier: String
    ): WalletContact {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        val contact = currentSdk.addContact(
            AddContactRequest(
                name = name.trim(),
                paymentIdentifier = paymentIdentifier.trim().lowercase()
            )
        )
        return contact.toWalletContact()
    }

    override suspend fun updateContact(
        id: String,
        name: String,
        paymentIdentifier: String
    ): WalletContact {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        return currentSdk.updateContact(
            UpdateContactRequest(
                id = id,
                name = name.trim(),
                paymentIdentifier = paymentIdentifier.trim().lowercase()
            )
        ).toWalletContact()
    }

    override suspend fun deleteContact(id: String) {
        val currentSdk = sdk ?: throw IllegalStateException("Spark wallet is not connected.")
        currentSdk.deleteContact(id)
    }

    override suspend fun attachEventListener(onWalletEvent: suspend (SdkEvent) -> Unit) {
        val currentSdk = sdk ?: return

        eventListenerId?.let { listenerId ->
            runCatching { currentSdk.removeEventListener(listenerId) }
        }

        eventListenerId = currentSdk.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    when (event) {
                        is SdkEvent.Synced,
                        is SdkEvent.UnclaimedDeposits,
                        is SdkEvent.ClaimedDeposits,
                        is SdkEvent.PaymentSucceeded,
                        is SdkEvent.PaymentPending,
                        is SdkEvent.PaymentFailed,
                        is SdkEvent.LightningAddressChanged -> onWalletEvent(event)
                        else -> Unit
                    }
                }
            }
        )
    }

    override suspend fun disconnect() {
        eventListenerId?.let { listenerId ->
            sdk?.removeEventListener(listenerId)
            eventListenerId = null
        }
        sdk?.disconnect()
        sdk = null
    }

    private suspend fun connectedWalletSnapshot(connectedSdk: BreezSdk): WalletSnapshot {
        val info = connectedSdk.getInfo(GetInfoRequest(false))
        val sparkAddress = connectedSdk.receivePayment(
            ReceivePaymentRequest(ReceivePaymentMethod.SparkAddress)
        ).paymentRequest

        return WalletSnapshot(
            balanceSats = info.balanceSats.toLong(),
            sparkAddress = sparkAddress
        )
    }
}

private fun InputType.fixedBolt11AmountSats(): Long? {
    return when (this) {
        is InputType.Bolt11Invoice -> v1.amountMsat?.toLong()?.div(1_000L)
        else -> null
    }
}

private fun InputType.Bolt11Invoice.toMetadata(
    localMetadata: Bolt11InvoiceMetadata?
): Bolt11InvoiceMetadata {
    return Bolt11InvoiceMetadata(
        amountSats = v1.amountMsat?.toLong()?.div(1_000L) ?: localMetadata?.amountSats,
        paymentHash = v1.paymentHash.trim().ifBlank { null } ?: localMetadata?.paymentHash,
        destinationPubkey = v1.payeePubkey.trim().ifBlank { null } ?: localMetadata?.destinationPubkey,
        description = v1.description?.trim()?.ifBlank { null } ?: localMetadata?.description
    )
}

private fun PrepareSendPaymentResponse.feeSatsOrNull(): Long? {
    return when (val method = paymentMethod) {
        is SendPaymentMethod.Bolt11Invoice -> method.lightningFeeSats.toLong()
        is SendPaymentMethod.SparkAddress -> method.fee.toLong()
        is SendPaymentMethod.SparkInvoice -> method.fee.toLong()
        is SendPaymentMethod.BitcoinAddress -> null
    }
}

private fun previewAmountSats(
    requestedAmountSats: Long,
    feeSats: Long?,
    feesIncluded: Boolean
): Long {
    if (!feesIncluded || feeSats == null) return requestedAmountSats
    return (requestedAmountSats - feeSats).coerceAtLeast(0L)
}

private fun PrepareSendPaymentResponse.paymentMethodLabel(): String {
    return when (paymentMethod) {
        is SendPaymentMethod.Bolt11Invoice -> "Lightning invoice"
        is SendPaymentMethod.SparkAddress -> "Spark address"
        is SendPaymentMethod.SparkInvoice -> "Spark invoice"
        is SendPaymentMethod.BitcoinAddress -> "Bitcoin address"
    }
}

private class BreezWalletAuthProvider(
    private val sdk: BreezSdk
) : WalletAuthProvider {
    override suspend fun signMessage(message: String): SignedMessage {
        val response = sdk.signMessage(
            SignMessageRequest(message = message, compact = true)
        )

        return SignedMessage(
            pubkey = response.pubkey,
            signature = response.signature
        )
    }

    override suspend fun getSparkAddress(): String {
        return sdk.receivePayment(
            ReceivePaymentRequest(ReceivePaymentMethod.SparkAddress)
        ).paymentRequest
    }
}

private fun breez_sdk_spark.LightningAddressInfo.toWalletLightningAddressInfo(): WalletLightningAddressInfo {
    return WalletLightningAddressInfo(
        lightningAddress = lightningAddress,
        username = username,
        description = description.trim().ifBlank { null },
        lnurlUrl = lnurl.url,
        lnurlBech32 = lnurl.bech32
    )
}

private fun breez_sdk_spark.Contact.toWalletContact(): WalletContact {
    return WalletContact(
        id = id,
        name = name,
        paymentIdentifier = paymentIdentifier
    )
}
