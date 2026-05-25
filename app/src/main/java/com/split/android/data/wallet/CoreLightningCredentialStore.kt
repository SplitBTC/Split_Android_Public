package com.split.android.data.wallet

import android.content.Context

class CoreLightningCredentialStore(
    context: Context
) {
    private val externalWalletStore = ExternalWalletStore(context)

    fun loadNodes(): List<CoreLightningNodeCredentials> {
        return externalWalletStore.wallets(ExternalWalletKind.CORE_LIGHTNING).mapNotNull { record ->
            runCatching {
                CoreLightningNodeCredentials.fromJson(record.payload).withLabel(record.label)
            }.getOrNull()
        }
    }

    fun loadNode(id: String): CoreLightningNodeCredentials? {
        return loadNodes().firstOrNull { it.id == id }
    }

    fun activeNode(): CoreLightningNodeCredentials? {
        val nodes = loadNodes()
        val selection = externalWalletStore.reconcileActiveSelection()
        if (selection is SpendWalletSelection.External &&
            selection.kind == ExternalWalletKind.CORE_LIGHTNING
        ) {
            return nodes.firstOrNull { it.id == selection.id } ?: nodes.firstOrNull()
        }
        return nodes.firstOrNull()
    }

    fun saveNode(
        node: CoreLightningNodeCredentials,
        makeActive: Boolean = true
    ): CoreLightningNodeCredentials {
        return externalWalletStore.saveCoreLightningNode(node, makeActive)
    }

    fun setActiveNode(id: String) {
        externalWalletStore.setActiveSelection(
            SpendWalletSelection.External(ExternalWalletKind.CORE_LIGHTNING, id)
        )
    }

    fun deleteNode(id: String) {
        externalWalletStore.deleteWallet(ExternalWalletKind.CORE_LIGHTNING, id)
    }

    fun renameNode(id: String, label: String) {
        externalWalletStore.renameWallet(ExternalWalletKind.CORE_LIGHTNING, id, label)
    }

    fun clear() {
        loadNodes().forEach { node ->
            externalWalletStore.deleteWallet(ExternalWalletKind.CORE_LIGHTNING, node.id)
        }
    }
}
