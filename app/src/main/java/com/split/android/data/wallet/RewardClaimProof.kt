package com.split.android.data.wallet

import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails

internal data class RewardClaimProof(
    val destinationPubkey: String?,
    val invoice: String?,
    val paymentHash: String?,
    val preimage: String?
)

internal fun Payment.rewardClaimProof(row: WalletTransactionRow? = null): RewardClaimProof? {
    return when (val details = details) {
        is PaymentDetails.Lightning -> {
            val invoice = details.invoice.normalizedProofField()
            val invoiceMetadata = invoice?.let(Bolt11InvoiceMetadataDecoder::decode)
            RewardClaimProof(
                destinationPubkey = details.destinationPubkey.normalizedProofField()
                    ?: invoiceMetadata?.destinationPubkey,
                invoice = invoice,
                paymentHash = details.htlcDetails.paymentHash.normalizedProofField()
                    ?: row?.paymentHash.normalizedProofField()
                    ?: invoiceMetadata?.paymentHash,
                preimage = details.htlcDetails.preimage.normalizedProofField()
                    ?: row?.preimage.normalizedProofField()
            )
        }

        is PaymentDetails.Spark -> {
            val invoice = details.invoiceDetails?.invoice.normalizedProofField()
            val invoiceMetadata = invoice?.let(Bolt11InvoiceMetadataDecoder::decode)
            RewardClaimProof(
                destinationPubkey = invoiceMetadata?.destinationPubkey,
                invoice = invoice,
                paymentHash = details.htlcDetails?.paymentHash.normalizedProofField()
                    ?: row?.paymentHash.normalizedProofField()
                    ?: invoiceMetadata?.paymentHash,
                preimage = details.htlcDetails?.preimage.normalizedProofField()
                    ?: row?.preimage.normalizedProofField()
            )
        }

        else -> null
    }
}

private fun String?.normalizedProofField(): String? {
    return this?.trim()?.ifBlank { null }
}
