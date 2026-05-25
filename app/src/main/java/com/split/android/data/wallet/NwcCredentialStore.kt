package com.split.android.data.wallet

import android.content.Context

class NwcCredentialStore(
    context: Context
) {
    private val externalWalletStore = ExternalWalletStore(context)

    fun loadWallets(): List<NwcWalletCredentials> {
        return externalWalletStore.wallets(ExternalWalletKind.NWC).mapNotNull { record ->
            runCatching {
                NwcWalletCredentials.fromJson(record.payload).copy(label = record.label)
            }.getOrNull()
        }
    }

    fun activeWallet(): NwcWalletCredentials? {
        val wallets = loadWallets()
        val selection = externalWalletStore.reconcileActiveSelection()
        if (selection is SpendWalletSelection.External && selection.kind == ExternalWalletKind.NWC) {
            return wallets.firstOrNull { it.id == selection.id } ?: wallets.firstOrNull()
        }
        return wallets.firstOrNull()
    }

    fun activeWalletId(): String? = activeWallet()?.id

    fun saveWallet(wallet: NwcWalletCredentials, makeActive: Boolean = true): NwcWalletCredentials {
        return externalWalletStore.saveNwcWallet(wallet, makeActive)
    }

    fun setActiveWallet(id: String) {
        externalWalletStore.setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.NWC, id))
    }

    fun deleteWallet(id: String) {
        externalWalletStore.deleteWallet(ExternalWalletKind.NWC, id)
    }

    fun renameWallet(id: String, label: String) {
        externalWalletStore.renameWallet(ExternalWalletKind.NWC, id, label)
    }
}
