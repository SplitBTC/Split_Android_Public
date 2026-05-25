package com.split.android.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.split.android.data.security.openEncryptedPreferences
import java.io.File
import java.util.UUID

class SparkSubwalletStore(
    context: Context
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null

    fun seedKey(forWalletId: String): String {
        return "$SEED_KEY_PREFIX${forWalletId.trim().lowercase()}"
    }

    fun temporaryStorageDirectoryName(): String {
        return "pending-${UUID.randomUUID()}"
    }

    fun readSeed(wallet: SparkSubwalletCredentials): String? {
        return preferences()
            .getString(wallet.seedStorageKey, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun saveSeed(seedPhrase: String, wallet: SparkSubwalletCredentials) {
        preferences().edit()
            .putString(wallet.seedStorageKey, seedPhrase.trim())
            .apply()
    }

    fun deleteSeed(wallet: SparkSubwalletCredentials) {
        preferences().edit()
            .remove(wallet.seedStorageKey)
            .apply()
    }

    fun createStorageDirectory(name: String): File {
        val directory = storageDirectory(name)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create local Breez subwallet storage directory.")
        }
        return directory
    }

    fun storageDirectory(wallet: SparkSubwalletCredentials): File {
        return storageDirectory(wallet.storageDirectoryName)
    }

    fun deleteStorageDirectory(wallet: SparkSubwalletCredentials) {
        storageDirectory(wallet).deleteRecursively()
    }

    fun deleteStorageDirectory(name: String) {
        storageDirectory(name).deleteRecursively()
    }

    private fun storageDirectory(name: String): File {
        val base = File(appContext.filesDir, BASE_STORAGE_DIRECTORY_NAME)
        if (!base.exists() && !base.mkdirs()) {
            throw IllegalStateException("Unable to create local Breez subwallet storage directory.")
        }
        return File(base, name.trim().ifBlank { temporaryStorageDirectoryName() })
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = FILE_NAME,
                logTag = "SparkSubwalletStore"
            ).also { preferences = it }
        }
    }

    private companion object {
        const val FILE_NAME = "split_spark_subwallet_seeds"
        const val SEED_KEY_PREFIX = "split.sparkSubwallet.seed."
        const val BASE_STORAGE_DIRECTORY_NAME = "breez-spark-subwallets"
    }
}
