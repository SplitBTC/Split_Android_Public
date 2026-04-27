package com.split.android.data.wallet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveSpendWalletStore(
    context: Context,
    private val credentialStore: LndCredentialStore
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "split_active_spend_wallet",
        Context.MODE_PRIVATE
    )

    private val _activeWallet = MutableStateFlow(
        SpendWalletSource.entries.firstOrNull {
            it.name.equals(preferences.getString(ACTIVE_WALLET_KEY, null), ignoreCase = true)
        } ?: SpendWalletSource.SPARK
    )
    val activeWallet: StateFlow<SpendWalletSource> = _activeWallet.asStateFlow()

    val isSparkActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.SPARK

    val isLndActive: Boolean
        get() = _activeWallet.value == SpendWalletSource.LND

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

    fun reconcileWithStoredNode() {
        if (_activeWallet.value == SpendWalletSource.LND &&
            credentialStore.activeNode() == null
        ) {
            setSparkActive()
        }
    }

    private fun setActiveWallet(wallet: SpendWalletSource) {
        if (_activeWallet.value == wallet) return

        _activeWallet.value = wallet
        preferences.edit()
            .putString(ACTIVE_WALLET_KEY, wallet.name.lowercase())
            .apply()
    }

    private companion object {
        const val ACTIVE_WALLET_KEY = "split.activeSpendWallet.v1"
    }
}
