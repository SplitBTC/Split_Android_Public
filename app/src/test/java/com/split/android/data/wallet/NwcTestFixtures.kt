package com.split.android.data.wallet

internal const val testSecret = "1111111111111111111111111111111111111111111111111111111111111111"
internal const val testWalletSecret = "2222222222222222222222222222222222222222222222222222222222222222"

internal val testWalletPubkey: String
    get() = NwcNostrCryptography.publicKeyHex(testWalletSecret)

internal fun testWalletCredentials(
    relayUrls: List<String> = listOf("wss://relay.example.com"),
    encryptionModes: List<String> = listOf(NwcEncryptionMode.NIP04.rawValue)
): NwcWalletCredentials {
    return NwcWalletCredentials(
        walletPubkey = testWalletPubkey,
        relayUrls = relayUrls,
        secret = testSecret,
        lud16 = null,
        label = "Test Wallet",
        connectedAtMillis = 1_765_000_000_000L,
        lastVerifiedAtMillis = 1_765_000_000_000L,
        capabilities = NwcWalletCapabilities(
            methods = listOf(
                "get_balance",
                "make_invoice",
                "pay_invoice",
                "lookup_invoice",
                "list_transactions"
            ),
            notifications = listOf("payment_received", "payment_sent"),
            encryptionModes = encryptionModes,
            walletAlias = "Test Wallet"
        )
    )
}

