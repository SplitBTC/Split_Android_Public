package com.split.android.data.wallet

enum class WalletPaymentDirection {
    SENT,
    RECEIVED
}

enum class WalletPaymentToastKind {
    PENDING,
    SUCCESS,
    FAILURE
}

data class WalletPaymentResultEvent(
    val id: Long,
    val kind: WalletPaymentToastKind,
    val direction: WalletPaymentDirection,
    val subtitle: String? = null
)
