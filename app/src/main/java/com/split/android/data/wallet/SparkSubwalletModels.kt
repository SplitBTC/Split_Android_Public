package com.split.android.data.wallet

import org.json.JSONObject

data class SparkSubwalletCredentials(
    val id: String,
    val label: String?,
    val seedStorageKey: String,
    val storageDirectoryName: String,
    val sparkAddress: String?,
    val connectedAtMillis: Long,
    val lastVerifiedAtMillis: Long?
) {
    val displayName: String
        get() = label?.trim()?.ifBlank { null } ?: "Spark Wallet"

    val identityPubkey: String
        get() = id

    fun withLabel(label: String?): SparkSubwalletCredentials {
        return copy(label = label?.trim()?.ifBlank { null })
    }

    fun verified(sparkAddress: String?): SparkSubwalletCredentials {
        return copy(
            sparkAddress = sparkAddress?.trim()?.ifBlank { null } ?: this.sparkAddress,
            lastVerifiedAtMillis = System.currentTimeMillis()
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("label", label ?: JSONObject.NULL)
            .put("seedStorageKey", seedStorageKey)
            .put("storageDirectoryName", storageDirectoryName)
            .put("sparkAddress", sparkAddress ?: JSONObject.NULL)
            .put("connectedAtMillis", connectedAtMillis)
            .put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): SparkSubwalletCredentials {
            return SparkSubwalletCredentials(
                id = json.getString("id"),
                label = json.sparkSubwalletOptNullableString("label"),
                seedStorageKey = json.sparkSubwalletOptNullableString("seedStorageKey")
                    ?: json.sparkSubwalletOptNullableString("seedKeychainKey")
                    ?: "split.sparkSubwallet.seed.${json.getString("id")}",
                storageDirectoryName = json.getString("storageDirectoryName"),
                sparkAddress = json.sparkSubwalletOptNullableString("sparkAddress"),
                connectedAtMillis = json.optLong("connectedAtMillis", System.currentTimeMillis()),
                lastVerifiedAtMillis = json.sparkSubwalletOptNullableLong("lastVerifiedAtMillis")
            )
        }
    }
}

private fun JSONObject.sparkSubwalletOptNullableString(name: String): String? {
    return if (has(name) && !isNull(name)) {
        optString(name).trim().ifBlank { null }
    } else {
        null
    }
}

private fun JSONObject.sparkSubwalletOptNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}
