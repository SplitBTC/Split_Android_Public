package com.split.android.data.wallet

import android.content.SharedPreferences
import android.content.Context
import com.split.android.data.security.openEncryptedPreferences

class SeedStore(
    context: Context
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null

    fun readSeed(): String? {
        return preferences()
            .getString(KEY_WALLET_SEED, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun saveSeed(seedPhrase: String) {
        preferences().edit()
            .putString(KEY_WALLET_SEED, seedPhrase.trim())
            .apply()
    }

    fun clearSeed() {
        preferences().edit()
            .remove(KEY_WALLET_SEED)
            .apply()
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = FILE_NAME,
                logTag = "SeedStore"
            ).also { preferences = it }
        }
    }

    private companion object {
        const val FILE_NAME = "split_secure_wallet"
        const val KEY_WALLET_SEED = "wallet_seed_phrase"
    }
}
