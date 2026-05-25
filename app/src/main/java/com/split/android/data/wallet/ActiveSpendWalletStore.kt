package com.split.android.data.wallet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveSpendWalletStore(
    context: Context,
    private val credentialStore: LndCredentialStore
) {
    private val externalWalletStore = ExternalWalletStore(context)

    private val _activeWallet = MutableStateFlow(SpendWalletSource.SPARK)
    val activeWallet: StateFlow<SpendWalletSource> = _activeWallet.asStateFlow()

    init {
        credentialStore.loadNodes()
        _activeWallet.value = externalWalletStore.reconcileActiveSelection().toSpendWalletSource()
    }

    val isSparkActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.SPARK

    val isLndActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.LND

    val isNwcActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.NWC

    val isCoreLightningActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.CORE_LIGHTNING

    val isEclairActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.ECLAIR

    val isSparkSubwalletActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.SPARK_SUBWALLET

    fun setSparkActive() {
        setActiveWallet(SpendWalletSource.SPARK)
    }

    fun setLndActiveIfAvailable(): Boolean {
        if (credentialStore.activeNode() == null) {
            setSparkActive()
            return false
        }

        setActiveWallet(SpendWalletSource.LND)
        return true
    }

    fun setLndActive(id: String): Boolean {
        if (credentialStore.loadNodes().none { it.id == id }) {
            setSparkActive()
            return false
        }

        credentialStore.setActiveNode(id)
        externalWalletStore.setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.LND, id))
        setActiveWallet(SpendWalletSource.LND)
        return true
    }

    fun setNwcActive(id: String): Boolean {
        if (externalWalletStore.loadWallet(ExternalWalletKind.NWC, id) == null) {
            setSparkActive()
            return false
        }

        externalWalletStore.setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.NWC, id))
        setActiveWallet(SpendWalletSource.NWC)
        return true
    }

    fun setCoreLightningActive(id: String): Boolean {
        return setExternalWalletActive(ExternalWalletKind.CORE_LIGHTNING, id)
    }

    fun setEclairActive(id: String): Boolean {
        return setExternalWalletActive(ExternalWalletKind.ECLAIR, id)
    }

    fun setSparkSubwalletActive(id: String): Boolean {
        return setExternalWalletActive(ExternalWalletKind.SPARK_SUBWALLET, id)
    }

    fun reconcileWithStoredNode() {
        reconcileWithStoredWallets()
    }

    fun reconcileWithStoredWallets() {
        if (_activeWallet.value == SpendWalletSource.LND &&
            credentialStore.activeNode() == null
        ) {
            setSparkActive()
            return
        }

        val selection = externalWalletStore.reconcileActiveSelection()
        val reconciled = selection.toSpendWalletSource()
        if (_activeWallet.value != reconciled) {
            _activeWallet.value = reconciled
        }
    }

    private fun setExternalWalletActive(
        kind: ExternalWalletKind,
        id: String
    ): Boolean {
        if (externalWalletStore.loadWallet(kind, id) == null) {
            setSparkActive()
            return false
        }

        externalWalletStore.setActiveSelection(SpendWalletSelection.External(kind, id))
        setActiveWallet(kind.toSpendWalletSource())
        return true
    }

    private fun setActiveWallet(wallet: SpendWalletSource) {
        if (_activeWallet.value == wallet) return

        _activeWallet.value = wallet
        when (wallet) {
            SpendWalletSource.SPARK -> externalWalletStore.setActiveSelection(SpendWalletSelection.Spark)
            SpendWalletSource.LND -> {
                val activeNode = credentialStore.activeNode()
                if (activeNode != null) {
                    externalWalletStore.setActiveSelection(
                        SpendWalletSelection.External(ExternalWalletKind.LND, activeNode.id)
                    )
                } else {
                    externalWalletStore.setActiveSelection(SpendWalletSelection.Spark)
                }
            }
            SpendWalletSource.NWC -> Unit
            SpendWalletSource.CORE_LIGHTNING -> Unit
            SpendWalletSource.ECLAIR -> Unit
            SpendWalletSource.SPARK_SUBWALLET -> Unit
        }
    }
}

private fun SpendWalletSelection.toSpendWalletSource(): SpendWalletSource {
    return when (this) {
        SpendWalletSelection.Spark -> SpendWalletSource.SPARK
        is SpendWalletSelection.External -> when (kind) {
            ExternalWalletKind.LND -> SpendWalletSource.LND
            ExternalWalletKind.NWC -> SpendWalletSource.NWC
            ExternalWalletKind.CORE_LIGHTNING -> SpendWalletSource.CORE_LIGHTNING
            ExternalWalletKind.ECLAIR -> SpendWalletSource.ECLAIR
            ExternalWalletKind.SPARK_SUBWALLET -> SpendWalletSource.SPARK_SUBWALLET
        }
    }
}

private fun ExternalWalletKind.toSpendWalletSource(): SpendWalletSource {
    return when (this) {
        ExternalWalletKind.LND -> SpendWalletSource.LND
        ExternalWalletKind.NWC -> SpendWalletSource.NWC
        ExternalWalletKind.CORE_LIGHTNING -> SpendWalletSource.CORE_LIGHTNING
        ExternalWalletKind.ECLAIR -> SpendWalletSource.ECLAIR
        ExternalWalletKind.SPARK_SUBWALLET -> SpendWalletSource.SPARK_SUBWALLET
    }
}
