package com.split.android.data.messages

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MessagePushEvents {
    private val _incomingMessagePushes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1
    )
    val incomingMessagePushes: SharedFlow<Unit> = _incomingMessagePushes.asSharedFlow()

    fun notifyIncomingMessagePush() {
        _incomingMessagePushes.tryEmit(Unit)
    }
}
