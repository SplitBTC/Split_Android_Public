package com.split.android.data.wallet

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object WalletToastManager {
    private const val TAG = "WalletToastManager"
    private const val TRANSACTION_BADGE_LEAD_WINDOW_MS = 240L

    private val nextEventId = AtomicLong(1L)
    private val _activeToast = MutableStateFlow<WalletPaymentResultEvent?>(null)
    private val _isTransactionBadgeHeld = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transactionBadgeHoldJob: Job? = null

    val activeToast: StateFlow<WalletPaymentResultEvent?> = _activeToast.asStateFlow()
    val isTransactionBadgeHeld: StateFlow<Boolean> = _isTransactionBadgeHeld.asStateFlow()

    fun showOutgoingPaymentPending() {
        showPaymentResult(
            kind = WalletPaymentToastKind.PENDING,
            direction = WalletPaymentDirection.SENT
        )
    }

    fun showOutgoingPaymentSuccess() {
        showPaymentResult(
            kind = WalletPaymentToastKind.SUCCESS,
            direction = WalletPaymentDirection.SENT
        )
    }

    fun showIncomingPaymentSuccess() {
        showPaymentResult(
            kind = WalletPaymentToastKind.SUCCESS,
            direction = WalletPaymentDirection.RECEIVED
        )
    }

    fun showOutgoingPaymentFailure(subtitle: String? = null) {
        showPaymentResult(
            kind = WalletPaymentToastKind.FAILURE,
            direction = WalletPaymentDirection.SENT,
            subtitle = subtitle
        )
    }

    fun showIncomingPaymentFailure(subtitle: String? = null) {
        showPaymentResult(
            kind = WalletPaymentToastKind.FAILURE,
            direction = WalletPaymentDirection.RECEIVED,
            subtitle = subtitle
        )
    }

    fun clear(eventId: Long) {
        if (_activeToast.value?.id == eventId) {
            Log.d(TAG, "Clearing wallet toast id=$eventId")
            _activeToast.value = null
        }
    }

    private fun showPaymentResult(
        kind: WalletPaymentToastKind,
        direction: WalletPaymentDirection,
        subtitle: String? = null
    ) {
        val event = WalletPaymentResultEvent(
            id = nextEventId.getAndIncrement(),
            kind = kind,
            direction = direction,
            subtitle = subtitle?.trim()?.ifBlank { null }
        )
        if (kind == WalletPaymentToastKind.SUCCESS || kind == WalletPaymentToastKind.FAILURE) {
            holdTransactionBadgeBriefly()
        }
        Log.d(TAG, "Showing wallet toast id=${event.id} kind=$kind direction=$direction")
        _activeToast.value = event
    }

    private fun holdTransactionBadgeBriefly() {
        transactionBadgeHoldJob?.cancel()
        _isTransactionBadgeHeld.value = true
        transactionBadgeHoldJob = scope.launch {
            delay(TRANSACTION_BADGE_LEAD_WINDOW_MS)
            _isTransactionBadgeHeld.value = false
            transactionBadgeHoldJob = null
        }
    }
}
