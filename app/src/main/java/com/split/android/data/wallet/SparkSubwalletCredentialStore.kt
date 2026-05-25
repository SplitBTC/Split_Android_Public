package com.split.android.data.wallet

import android.content.Context

class SparkSubwalletCredentialStore(
    context: Context
) {
    private val externalWalletStore = ExternalWalletStore(context)

    fun loadWallets(): List<SparkSubwalletCredentials> {
        return externalWalletStore.wallets(ExternalWalletKind.SPARK_SUBWALLET).mapNotNull { record ->
            runCatching {
                SparkSubwalletCredentials.fromJson(record.payload).withLabel(record.label)
            }.getOrNull()
        }
    }

    fun loadWallet(id: String): SparkSubwalletCredentials? {
        return loadWallets().firstOrNull { it.id == id }
    }

    fun activeWallet(): SparkSubwalletCredentials? {
        val wallets = loadWallets()
        val selection = externalWalletStore.reconcileActiveSelection()
        if (selection is SpendWalletSelection.External &&
            selection.kind == ExternalWalletKind.SPARK_SUBWALLET
        ) {
            return wallets.firstOrNull { it.id == selection.id } ?: wallets.firstOrNull()
        }
        return wallets.firstOrNull()
    }

    fun saveWallet(
        wallet: SparkSubwalletCredentials,
        makeActive: Boolean = true
    ): SparkSubwalletCredentials {
        return externalWalletStore.saveSparkSubwallet(wallet, makeActive)
    }

    fun setActiveWallet(id: String) {
        externalWalletStore.setActiveSelection(
            SpendWalletSelection.External(ExternalWalletKind.SPARK_SUBWALLET, id)
        )
    }

    fun deleteWallet(id: String) {
        externalWalletStore.deleteWallet(ExternalWalletKind.SPARK_SUBWALLET, id)
    }

    fun renameWallet(id: String, label: String) {
        externalWalletStore.renameWallet(ExternalWalletKind.SPARK_SUBWALLET, id, label)
    }
}
