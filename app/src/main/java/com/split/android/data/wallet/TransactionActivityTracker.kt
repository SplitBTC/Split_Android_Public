package com.split.android.data.wallet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TransactionActivityTracker private constructor(
    context: Context
) {
    companion object {
        @Volatile
        private var instance: TransactionActivityTracker? = null

        fun getInstance(context: Context): TransactionActivityTracker {
            return instance ?: synchronized(this) {
                instance ?: TransactionActivityTracker(
                    context = context.applicationContext
                ).also { instance = it }
            }
        }
    }

    private val preferences = context.getSharedPreferences(
        "split_transaction_activity",
        Context.MODE_PRIVATE
    )

    private val _unseenTransactionIds = MutableStateFlow<Set<String>>(emptySet())
    val unseenTransactionIds: StateFlow<Set<String>> = _unseenTransactionIds.asStateFlow()

    fun reconcile(
        rows: List<WalletTransactionRow>,
        scope: String = "spark"
    ) {
        val currentIds = badgeEligibleTransactionIds(rows)

        if (!hasSeededSeenTransactions(scope)) {
            persistSeenIds(scope, currentIds)
            preferences.edit().putBoolean(hasSeededKey(scope), true).apply()
            _unseenTransactionIds.value = emptySet()
            return
        }

        val persistedSeenIds = loadSeenIds(scope)
        val prunedSeenIds = persistedSeenIds.intersect(currentIds)

        if (prunedSeenIds != persistedSeenIds) {
            persistSeenIds(scope, prunedSeenIds)
        }

        _unseenTransactionIds.value = currentIds - prunedSeenIds
    }

    fun captureVisibleUnseenAndMarkSeen(
        rows: List<WalletTransactionRow>,
        scope: String = "spark"
    ): Set<String> {
        val currentIds = badgeEligibleTransactionIds(rows)
        val visibleUnseenIds = _unseenTransactionIds.value.intersect(currentIds)
        markSeen(scope, currentIds)
        return visibleUnseenIds
    }

    private fun markSeen(scope: String, ids: Set<String>) {
        if (ids.isEmpty()) return

        val updatedSeenIds = loadSeenIds(scope) + ids
        persistSeenIds(scope, updatedSeenIds)
        _unseenTransactionIds.value = _unseenTransactionIds.value - ids
    }

    private fun hasSeededSeenTransactions(scope: String): Boolean {
        return preferences.getBoolean(hasSeededKey(scope), false)
    }

    private fun badgeEligibleTransactionIds(
        rows: List<WalletTransactionRow>
    ): Set<String> {
        return rows
            .asSequence()
            .filter { it.isBadgeEligibleTransactionActivity }
            .mapTo(linkedSetOf()) { it.id }
    }

    private fun loadSeenIds(scope: String): Set<String> {
        return preferences.getStringSet(seenIdsKey(scope), emptySet()).orEmpty().toSet()
    }

    private fun persistSeenIds(scope: String, ids: Set<String>) {
        preferences.edit()
            .putStringSet(seenIdsKey(scope), ids)
            .apply()
    }

    private fun seenIdsKey(scope: String): String {
        return "seen_transaction_ids::${scope.trim().ifBlank { "spark" }}"
    }

    private fun hasSeededKey(scope: String): String {
        return "has_seeded_seen_transactions::${scope.trim().ifBlank { "spark" }}"
    }
}

private val WalletTransactionRow.isBadgeEligibleTransactionActivity: Boolean
    get() {
        val normalizedStatus = status.trim()
        if (normalizedStatus.isEmpty()) return false

        return normalizedStatus.contains("complete", ignoreCase = true) ||
            normalizedStatus.contains("succeed", ignoreCase = true) ||
            normalizedStatus.contains("fail", ignoreCase = true)
    }
