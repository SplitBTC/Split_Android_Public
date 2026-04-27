package com.split.android.data.wallet

import android.content.SharedPreferences
import android.content.Context
import com.split.android.data.security.openEncryptedPreferences
import org.json.JSONObject

data class PaymentUsdSnapshot(
    val walletPubkey: String,
    val paymentId: String,
    val paymentType: String,
    val usdValueAtTransaction: Double? = null,
    val btcUsdRateAtTransaction: Double? = null,
    val isReportable: Boolean = false,
    val userLog: String? = null
) {
    val hasUsdSnapshot: Boolean
        get() = usdValueAtTransaction != null && btcUsdRateAtTransaction != null

    fun merging(snapshot: PaymentUsdSnapshot): PaymentUsdSnapshot {
        return PaymentUsdSnapshot(
            walletPubkey = snapshot.walletPubkey,
            paymentId = snapshot.paymentId,
            paymentType = snapshot.paymentType,
            usdValueAtTransaction = snapshot.usdValueAtTransaction ?: usdValueAtTransaction,
            btcUsdRateAtTransaction = snapshot.btcUsdRateAtTransaction ?: btcUsdRateAtTransaction,
            isReportable = isReportable || snapshot.isReportable,
            userLog = snapshot.userLog ?: userLog
        )
    }

    fun withReportable(isReportable: Boolean): PaymentUsdSnapshot {
        return copy(isReportable = isReportable)
    }

    fun withUserLog(userLog: String?): PaymentUsdSnapshot {
        return copy(userLog = normalizedUserLog(userLog))
    }

    companion object {
        fun normalizedUserLog(value: String?): String? {
            return value?.trim()?.ifBlank { null }
        }
    }
}

class PaymentUsdSnapshotStore(
    context: Context
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null
    private var storeUnavailable = false

    fun containsSnapshot(walletPubkey: String, paymentId: String): Boolean {
        return snapshot(walletPubkey, paymentId)?.hasUsdSnapshot == true
    }

    fun snapshot(walletPubkey: String, paymentId: String): PaymentUsdSnapshot? {
        val raw = runCatching {
            preferences().getString(snapshotKey(walletPubkey, paymentId), null)
        }.getOrElse { error ->
            storeUnavailable = true
            println("PaymentUsdSnapshotStore: secure snapshot store unavailable, preserving local file. ${error.localizedMessage}")
            return null
        } ?: return null
        storeUnavailable = false
        return runCatching {
            val json = JSONObject(raw)
            PaymentUsdSnapshot(
                walletPubkey = json.getString("walletPubkey"),
                paymentId = json.getString("paymentId"),
                paymentType = json.getString("paymentType"),
                usdValueAtTransaction = json.optNullableDouble("usdValueAtTransaction"),
                btcUsdRateAtTransaction = json.optNullableDouble("btcUsdRateAtTransaction"),
                isReportable = json.optBoolean("isReportable", false),
                userLog = json.optNullableString("userLog")
            )
        }.getOrNull()
    }

    fun snapshots(walletPubkey: String, paymentIds: List<String>): Map<String, PaymentUsdSnapshot> {
        return paymentIds.mapNotNull { paymentId ->
            snapshot(walletPubkey, paymentId)?.let { paymentId to it }
        }.toMap()
    }

    fun reportableStates(walletPubkey: String, paymentIds: List<String>): Map<String, Boolean> {
        return paymentIds.mapNotNull { paymentId ->
            snapshot(walletPubkey, paymentId)?.let { paymentId to it.isReportable }
        }.toMap()
    }

    fun userLogs(walletPubkey: String, paymentIds: List<String>): Map<String, String> {
        return paymentIds.mapNotNull { paymentId ->
            snapshot(walletPubkey, paymentId)?.userLog?.let { paymentId to it }
        }.toMap()
    }

    fun upsert(snapshot: PaymentUsdSnapshot) {
        val key = snapshotKey(snapshot.walletPubkey, snapshot.paymentId)
        val mergedSnapshot = snapshot(snapshot.walletPubkey, snapshot.paymentId)
            ?.merging(snapshot)
            ?: snapshot

        runCatching {
            preferences().edit()
                .putString(key, jsonFor(mergedSnapshot).toString())
                .apply()
            storeUnavailable = false
        }.onFailure { error ->
            storeUnavailable = true
            println("PaymentUsdSnapshotStore: failed to persist secure snapshot store without deleting files. ${error.localizedMessage}")
        }
    }

    fun setReportable(
        walletPubkey: String,
        paymentId: String,
        paymentType: String,
        isReportable: Boolean
    ) {
        val updatedSnapshot = snapshot(walletPubkey, paymentId)
            ?.withReportable(isReportable)
            ?: PaymentUsdSnapshot(
                walletPubkey = walletPubkey,
                paymentId = paymentId,
                paymentType = paymentType,
                isReportable = isReportable
            )

        runCatching {
            preferences().edit()
                .putString(snapshotKey(walletPubkey, paymentId), jsonFor(updatedSnapshot).toString())
                .apply()
            storeUnavailable = false
        }.onFailure { error ->
            storeUnavailable = true
            println("PaymentUsdSnapshotStore: failed to update secure snapshot store without deleting files. ${error.localizedMessage}")
        }
    }

    fun setUserLog(
        walletPubkey: String,
        paymentId: String,
        paymentType: String,
        userLog: String?
    ) {
        val normalizedUserLog = PaymentUsdSnapshot.normalizedUserLog(userLog)
        val updatedSnapshot = snapshot(walletPubkey, paymentId)
            ?.withUserLog(normalizedUserLog)
            ?: if (normalizedUserLog != null) {
                PaymentUsdSnapshot(
                    walletPubkey = walletPubkey,
                    paymentId = paymentId,
                    paymentType = paymentType,
                    userLog = normalizedUserLog
                )
            } else {
                return
            }

        runCatching {
            preferences().edit()
                .putString(snapshotKey(walletPubkey, paymentId), jsonFor(updatedSnapshot).toString())
                .apply()
            storeUnavailable = false
        }.onFailure { error ->
            storeUnavailable = true
            println("PaymentUsdSnapshotStore: failed to update secure snapshot log without deleting files. ${error.localizedMessage}")
        }
    }

    fun clearAll() {
        runCatching {
            preferences().edit().clear().apply()
            storeUnavailable = false
        }.onFailure { error ->
            storeUnavailable = true
            println("PaymentUsdSnapshotStore: failed to clear secure snapshot store without deleting files. ${error.localizedMessage}")
        }
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = FILE_NAME,
                logTag = "PaymentUsdSnapshotStore"
            ).also { preferences = it }
        }
    }

    private fun snapshotKey(walletPubkey: String, paymentId: String): String {
        return "payment_usd_snapshot::${walletPubkey.trim()}::${paymentId.trim()}"
    }

    private fun jsonFor(snapshot: PaymentUsdSnapshot): JSONObject {
        return JSONObject()
            .put("walletPubkey", snapshot.walletPubkey)
            .put("paymentId", snapshot.paymentId)
            .put("paymentType", snapshot.paymentType)
            .put("usdValueAtTransaction", snapshot.usdValueAtTransaction ?: JSONObject.NULL)
            .put("btcUsdRateAtTransaction", snapshot.btcUsdRateAtTransaction ?: JSONObject.NULL)
            .put("isReportable", snapshot.isReportable)
            .put("userLog", snapshot.userLog ?: JSONObject.NULL)
    }

    private companion object {
        const val FILE_NAME = "split_payment_usd_snapshots"
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return getDouble(key)
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return getString(key).trim().ifBlank { null }
}
