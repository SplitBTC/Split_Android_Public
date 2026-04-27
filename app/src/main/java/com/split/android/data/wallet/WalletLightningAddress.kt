package com.split.android.data.wallet

data class WalletLightningAddressInfo(
    val lightningAddress: String,
    val username: String,
    val description: String?,
    val lnurlUrl: String,
    val lnurlBech32: String
)
