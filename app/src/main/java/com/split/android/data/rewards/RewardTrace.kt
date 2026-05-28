package com.split.android.data.rewards

import android.util.Log
import java.security.MessageDigest

internal object RewardTrace {
    private const val TAG = "RewardTrace"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, error)
        }
    }

    fun id(value: String?): String {
        val normalized = value?.trim()?.ifBlank { null } ?: return "none"
        return normalized.take(10)
    }

    fun fp(value: String?): String {
        val normalized = value?.trim()?.ifBlank { null } ?: return "none"
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(12)
    }

    fun present(value: String?): Boolean {
        return !value.isNullOrBlank()
    }
}
