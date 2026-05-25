package com.split.android.data.wallet

import android.content.Context
class LndCredentialStore(
    context: Context
) {
    private val externalWalletStore = ExternalWalletStore(context)

    fun loadNodes(): List<LndNodeCredentials> {
        externalWalletStore.migrateLegacyLndNodesIfNeeded()
        return externalWalletStore.wallets(ExternalWalletKind.LND).mapNotNull { record ->
            runCatching {
                LndNodeCredentials.fromJson(record.payload).copy(label = record.label)
            }.getOrNull()
        }
    }

    fun activeNode(): LndNodeCredentials? {
        val nodes = loadNodes()
        val activeId = activeNodeId()
        return nodes.firstOrNull { it.id == activeId } ?: nodes.firstOrNull()
    }

    fun activeNodeId(): String? {
        val selection = externalWalletStore.reconcileActiveSelection()
        return if (selection is SpendWalletSelection.External && selection.kind == ExternalWalletKind.LND) {
            selection.id
        } else {
            null
        }
    }

    fun saveNode(node: LndNodeCredentials, makeActive: Boolean = true): LndNodeCredentials {
        return externalWalletStore.saveLndNode(node, makeActive)
    }

    fun setActiveNode(id: String) {
        externalWalletStore.setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.LND, id))
    }

    fun deleteNode(id: String) {
        externalWalletStore.deleteWallet(ExternalWalletKind.LND, id)

        if (activeNodeId() == null) {
            loadNodes().firstOrNull()?.let { setActiveNode(it.id) }
        }
    }

    fun renameNode(id: String, label: String) {
        externalWalletStore.renameWallet(ExternalWalletKind.LND, id, label)
    }

    fun clear() {
        loadNodes().forEach { node ->
            externalWalletStore.deleteWallet(ExternalWalletKind.LND, node.id)
        }
    }
}
