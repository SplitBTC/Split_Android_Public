package com.split.android.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File

internal fun createResilientEncryptedPreferences(
    context: Context,
    fileName: String,
    logTag: String
): SharedPreferences {
    val appContext = context.applicationContext

    return runCatching {
        createEncryptedPreferences(appContext, fileName)
    }.getOrElse { error ->
        Log.w(
            logTag,
            "EncryptedSharedPreferences init failed for $fileName. Clearing local secure storage and retrying.",
            error
        )
        clearEncryptedPreferenceFiles(appContext, fileName)
        createEncryptedPreferences(appContext, fileName)
    }
}

internal class SecurePreferencesAccessException(
    fileName: String,
    cause: Throwable
) : IllegalStateException(
    "Unable to access encrypted preferences for $fileName.",
    cause
)

internal fun openEncryptedPreferences(
    context: Context,
    fileName: String,
    logTag: String
): SharedPreferences {
    val appContext = context.applicationContext

    return try {
        createEncryptedPreferences(appContext, fileName)
    } catch (error: Throwable) {
        Log.w(
            logTag,
            "EncryptedSharedPreferences init failed for $fileName. Keeping local secure storage untouched.",
            error
        )
        throw SecurePreferencesAccessException(fileName, error)
    }
}

private fun createEncryptedPreferences(
    context: Context,
    fileName: String
): SharedPreferences {
    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    return EncryptedSharedPreferences.create(
        fileName,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

private fun clearEncryptedPreferenceFiles(
    context: Context,
    fileName: String
) {
    runCatching {
        context.deleteSharedPreferences(fileName)
    }

    val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
    File(sharedPrefsDir, "$fileName.xml").delete()
    File(sharedPrefsDir, "$fileName.xml.bak").delete()
}
