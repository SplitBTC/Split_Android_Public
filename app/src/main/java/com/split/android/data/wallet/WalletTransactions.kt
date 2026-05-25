package com.split.android.data.wallet

import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class WalletTransactionRow(
    val id: String,
    val transactionTimestampMillis: Long,
    val direction: String,
    val btcAmount: String,
    val feeBtcAmount: String,
    val network: String,
    val status: String,
    val dateString: String,
    val note: String,
    val amountSats: Long,
    val feeSats: Long,
    val method: String,
    val destinationPubkey: String?,
    val invoice: String?,
    val lnAddress: String?,
    val lnurlDomain: String?,
    val lnurlComment: String?,
    val senderComment: String?,
    val paymentHash: String?,
    val preimage: String?,
    val expiryDateString: String?,
    val txReferenceLabel: String?,
    val txReference: String?,
    val hasConversion: Boolean,
    val userLog: String? = null
) {
    fun withUserLog(userLog: String?): WalletTransactionRow {
        return copy(userLog = PaymentUsdSnapshot.normalizedUserLog(userLog))
    }

    fun withDestinationMetadata(
        destinationPubkey: String?,
        paymentHash: String?
    ): WalletTransactionRow {
        return copy(
            destinationPubkey = PaymentUsdSnapshot.normalizedUserLog(destinationPubkey) ?: this.destinationPubkey,
            paymentHash = PaymentUsdSnapshot.normalizedUserLog(paymentHash) ?: this.paymentHash
        )
    }
}

internal fun Payment.toWalletTransactionRow(): WalletTransactionRow {
    val direction = when (paymentType) {
        PaymentType.SEND -> "sent"
        PaymentType.RECEIVE -> "received"
    }

    val amountSats = amount.toCappedLong()
    val feeSats = fees.toCappedLong()
    val transactionDate = timestamp.toDisplayDate()
    val paymentDetails = this.details
    val details = paymentDetailsSummary(paymentDetails)

    return WalletTransactionRow(
        id = id,
        transactionTimestampMillis = transactionDate.time,
        direction = direction,
        btcAmount = amountSats.toBtcString(),
        feeBtcAmount = feeSats.toBtcString(),
        network = networkLabel(method, paymentDetails),
        status = status.displayLabel(),
        dateString = transactionDate.displayString(),
        note = userNote(paymentDetails),
        amountSats = amountSats,
        feeSats = feeSats,
        method = method.displayLabel(),
        destinationPubkey = details.destinationPubkey,
        invoice = details.invoice,
        lnAddress = details.lnAddress,
        lnurlDomain = details.lnurlDomain,
        lnurlComment = details.lnurlComment,
        senderComment = details.senderComment,
        paymentHash = details.paymentHash,
        preimage = details.preimage,
        expiryDateString = details.expiryDateString,
        txReferenceLabel = details.txReferenceLabel,
        txReference = details.txReference,
        hasConversion = conversionDetails != null
    )
}

private data class PaymentDetailsSummary(
    val destinationPubkey: String?,
    val invoice: String?,
    val lnAddress: String?,
    val lnurlDomain: String?,
    val lnurlComment: String?,
    val senderComment: String?,
    val paymentHash: String?,
    val preimage: String?,
    val expiryDateString: String?,
    val txReferenceLabel: String?,
    val txReference: String?
)

private fun userNote(details: PaymentDetails?): String {
    if (details == null) return ""
    return when (details) {
        is PaymentDetails.Lightning -> {
            val description = details.description.cleaned()
            val lnurlComment = details.lnurlPayInfo?.comment.cleaned()
            if (lnurlComment != null &&
                lnurlComment.startsWith("zap from ", ignoreCase = true)
            ) {
                lnurlComment
            } else {
                description ?: lnurlComment
            }
        }
        is PaymentDetails.Spark -> details.invoiceDetails?.description.cleaned()
        is PaymentDetails.Token -> details.invoiceDetails?.description.cleaned()
        is PaymentDetails.Deposit,
        is PaymentDetails.Withdraw -> ""
    }.orEmpty()
}

private fun networkLabel(method: PaymentMethod, details: PaymentDetails?): String {
    return when (method) {
        PaymentMethod.LIGHTNING,
        PaymentMethod.SPARK,
        PaymentMethod.TOKEN -> "lightning"

        PaymentMethod.DEPOSIT,
        PaymentMethod.WITHDRAW -> "bitcoin"

        PaymentMethod.UNKNOWN -> when (details) {
            is PaymentDetails.Lightning,
            is PaymentDetails.Spark,
            is PaymentDetails.Token -> "lightning"

            is PaymentDetails.Deposit,
            is PaymentDetails.Withdraw -> "bitcoin"

            null -> "unknown"
        }
    }
}

private fun PaymentMethod.displayLabel(): String {
    return when (this) {
        PaymentMethod.LIGHTNING -> "Lightning"
        PaymentMethod.SPARK -> "Spark"
        PaymentMethod.TOKEN -> "Token"
        PaymentMethod.DEPOSIT -> "Deposit"
        PaymentMethod.WITHDRAW -> "Withdraw"
        PaymentMethod.UNKNOWN -> "Unknown"
    }
}

private fun PaymentStatus.displayLabel(): String {
    return when (this) {
        PaymentStatus.COMPLETED -> "Completed"
        PaymentStatus.PENDING -> "Pending"
        PaymentStatus.FAILED -> "Failed"
    }
}

private fun paymentDetailsSummary(details: PaymentDetails?): PaymentDetailsSummary {
    if (details == null) {
        return PaymentDetailsSummary(
            destinationPubkey = null,
            invoice = null,
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = null,
            preimage = null,
            expiryDateString = null,
            txReferenceLabel = null,
            txReference = null
        )
    }

    return when (details) {
        is PaymentDetails.Lightning -> PaymentDetailsSummary(
            destinationPubkey = details.destinationPubkey.cleaned(),
            invoice = details.invoice.cleaned(),
            lnAddress = details.lnurlPayInfo?.lnAddress.cleaned(),
            lnurlDomain = details.lnurlPayInfo?.domain.cleaned(),
            lnurlComment = details.lnurlPayInfo?.comment.cleaned(),
            senderComment = details.lnurlReceiveMetadata?.senderComment.cleaned(),
            paymentHash = details.htlcDetails.paymentHash.cleaned(),
            preimage = details.htlcDetails.preimage.cleaned(),
            expiryDateString = details.htlcDetails.expiryTime.toDisplayDate().displayString(),
            txReferenceLabel = null,
            txReference = null
        )

        is PaymentDetails.Spark -> PaymentDetailsSummary(
            destinationPubkey = null,
            invoice = details.invoiceDetails?.invoice.cleaned(),
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = details.htlcDetails?.paymentHash.cleaned(),
            preimage = details.htlcDetails?.preimage.cleaned(),
            expiryDateString = details.htlcDetails?.expiryTime?.toDisplayDate()?.displayString(),
            txReferenceLabel = null,
            txReference = null
        )

        is PaymentDetails.Token -> PaymentDetailsSummary(
            destinationPubkey = null,
            invoice = details.invoiceDetails?.invoice.cleaned(),
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = null,
            preimage = null,
            expiryDateString = null,
            txReferenceLabel = "Tx Hash",
            txReference = details.txHash.cleaned()
        )

        is PaymentDetails.Deposit -> PaymentDetailsSummary(
            destinationPubkey = null,
            invoice = null,
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = null,
            preimage = null,
            expiryDateString = null,
            txReferenceLabel = "Txid",
            txReference = details.txId.cleaned()
        )

        is PaymentDetails.Withdraw -> PaymentDetailsSummary(
            destinationPubkey = null,
            invoice = null,
            lnAddress = null,
            lnurlDomain = null,
            lnurlComment = null,
            senderComment = null,
            paymentHash = null,
            preimage = null,
            expiryDateString = null,
            txReferenceLabel = "Txid",
            txReference = details.txId.cleaned()
        )
    }
}

private fun Long.toBtcString(): String {
    val btc = toDouble() / 100_000_000.0
    return String.format(Locale.US, "%.8f", btc)
}

private fun BigInteger.toCappedLong(): Long {
    return min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
}

private fun ULong.toDisplayDate(): Date {
    val raw = toLong()
    val seconds = if (raw > 10_000_000_000L) raw / 1_000L else raw
    return Date(seconds * 1_000L)
}

private fun Date.displayString(): String {
    return DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.US
    ).format(this)
}

private fun String?.cleaned(): String? {
    val trimmed = this?.trim().orEmpty()
    return trimmed.ifBlank { null }
}
