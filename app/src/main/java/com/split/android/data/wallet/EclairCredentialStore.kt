package com.split.android.data.wallet

import android.content.Context

class EclairCredentialStore(
    context: Context
) {
    private val externalWalletStore = ExternalWalletStore(context)

    fun loadNodes(): List<EclairNodeCredentials> {
        return externalWalletStore.wallets(ExternalWalletKind.ECLAIR).mapNotNull { record ->
            runCatching {
                EclairNodeCredentials.fromJson(record.payload).withLabel(record.label)
            }.getOrNull()
        }
    }

    fun activeNode(): EclairNodeCredentials? {
        val nodes = loadNodes()
        val selection = externalWalletStore.reconcileActiveSelection()
        if (selection is SpendWalletSelection.External && selection.kind == ExternalWalletKind.ECLAIR) {
            return nodes.firstOrNull { it.id == selection.id } ?: nodes.firstOrNull()
        }
        return nodes.firstOrNull()
    }

    fun saveNode(
        node: EclairNodeCredentials,
        makeActive: Boolean = true
    ): EclairNodeCredentials {
        return externalWalletStore.saveEclairNode(node, makeActive)
    }

    fun setActiveNode(id: String) {
        externalWalletStore.setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.ECLAIR, id))
    }

    fun deleteNode(id: String) {
        externalWalletStore.deleteWallet(ExternalWalletKind.ECLAIR, id)
    }

    fun renameNode(id: String, label: String) {
        externalWalletStore.renameWallet(ExternalWalletKind.ECLAIR, id, label)
    }
}
