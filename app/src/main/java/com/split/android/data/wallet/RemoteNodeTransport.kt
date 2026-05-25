@file:Suppress("DEPRECATION")

package com.split.android.data.wallet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

enum class RemoteNodeTransport {
    DIRECT,
    TOR;

    companion object {
        fun preferredForHost(host: String): RemoteNodeTransport {
            return if (isOnionHost(host)) TOR else DIRECT
        }

        fun preferredForUrl(url: String): RemoteNodeTransport {
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            return preferredForHost(host)
        }

        fun isOnionHost(host: String): Boolean {
            return host.trim().lowercase().endsWith(".onion")
        }
    }
}

sealed interface TorBootstrapState {
    data object Idle : TorBootstrapState
    data object Starting : TorBootstrapState
    data object Ready : TorBootstrapState
    data class Failed(val message: String) : TorBootstrapState
}

object RemoteNodeTorTransport {
    private const val SOCKS_HOST = "127.0.0.1"
    private const val START_TIMEOUT_MILLIS = 90_000L
    private val startMutex = Mutex()

    @Volatile
    private var torService: TorService? = null

    @Volatile
    private var isBound = false

    @Volatile
    private var socksPort = -1

    private val _bootstrapState = MutableStateFlow<TorBootstrapState>(TorBootstrapState.Idle)
    val bootstrapState: StateFlow<TorBootstrapState> = _bootstrapState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val localBinder = service as? TorService.LocalBinder ?: return
            torService = localBinder.service
            localBinder.service.getSocksPort().takeIf { it > 0 }?.let { socksPort = it }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            torService = null
            socksPort = -1
            isBound = false
        }
    }

    val proxy: Proxy
        get() {
            val port = socksPort.takeIf { it > 0 }
                ?: throw IllegalStateException("Tor is not ready for remote node connections.")
            return Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(SOCKS_HOST, port)
            )
        }

    suspend fun ensureStarted(context: Context): Int {
        return startMutex.withLock {
            socksPort.takeIf { it > 0 }?.let {
                _bootstrapState.value = TorBootstrapState.Ready
                return@withLock it
            }

            _bootstrapState.value = TorBootstrapState.Starting
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    bindAndAwaitTor(context.applicationContext)
                }
            }.onSuccess {
                _bootstrapState.value = TorBootstrapState.Ready
            }.onFailure { error ->
                _bootstrapState.value = TorBootstrapState.Failed(
                    error.message ?: "Tor failed to start for remote node connections."
                )
            }.getOrThrow()
        }
    }

    fun warmUp(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (socksPort > 0 || _bootstrapState.value == TorBootstrapState.Starting) return

        scope.launch {
            runCatching { ensureStarted(context.applicationContext) }
        }
    }

    fun okHttpClient(
        connectTimeoutMillis: Long,
        readTimeoutMillis: Long,
        writeTimeoutMillis: Long = connectTimeoutMillis,
        pingIntervalMillis: Long = 0L
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .apply {
                if (pingIntervalMillis > 0L) {
                    pingInterval(pingIntervalMillis, TimeUnit.MILLISECONDS)
                }
            }
            .build()
    }

    private suspend fun bindAndAwaitTor(appContext: Context): Int {
        TorService.setBroadcastPackageName(appContext.packageName)

        val ready = CompletableDeferred<Int>()
        val localBroadcastManager = LocalBroadcastManager.getInstance(appContext)
        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val servicePackage = intent.getStringExtra(TorService.EXTRA_SERVICE_PACKAGE_NAME)
                if (servicePackage != null && servicePackage != appContext.packageName) return

                when (intent.action) {
                    TorService.ACTION_STATUS -> {
                        if (intent.getStringExtra(TorService.EXTRA_STATUS) == TorService.STATUS_ON) {
                            val port = torService?.getSocksPort()?.takeIf { it > 0 } ?: socksPort
                            if (port > 0 && !ready.isCompleted) {
                                socksPort = port
                                ready.complete(port)
                            }
                        }
                    }
                    TorService.ACTION_ERROR -> {
                        val message = intent.getStringExtra(Intent.EXTRA_TEXT)
                            ?: "Tor failed to start for remote node connections."
                        if (!ready.isCompleted) {
                            ready.completeExceptionally(IllegalStateException(message))
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        localBroadcastManager.registerReceiver(statusReceiver, filter)

        try {
            if (!isBound) {
                val intent = Intent(appContext, TorService::class.java)
                isBound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                if (!isBound) {
                    throw IllegalStateException("Unable to bind embedded Tor service.")
                }
            }

            torService?.getSocksPort()?.takeIf { it > 0 }?.let { port ->
                socksPort = port
                if (!ready.isCompleted) ready.complete(port)
            }

            return withTimeout(START_TIMEOUT_MILLIS) { ready.await() }
        } finally {
            localBroadcastManager.unregisterReceiver(statusReceiver)
        }
    }
}

val LndNodeCredentials.transport: RemoteNodeTransport
    get() = RemoteNodeTransport.preferredForHost(host)

val LndNodeCredentials.usesTor: Boolean
    get() = transport == RemoteNodeTransport.TOR

val CoreLightningNodeCredentials.transport: RemoteNodeTransport
    get() = RemoteNodeTransport.preferredForHost(host)

val CoreLightningNodeCredentials.usesTor: Boolean
    get() = transport == RemoteNodeTransport.TOR

val EclairNodeCredentials.transport: RemoteNodeTransport
    get() = RemoteNodeTransport.preferredForHost(host)

val EclairNodeCredentials.usesTor: Boolean
    get() = transport == RemoteNodeTransport.TOR

val NwcWalletCredentials.usesTor: Boolean
    get() = relayUrls.any { relayUrl ->
        RemoteNodeTransport.preferredForUrl(relayUrl) == RemoteNodeTransport.TOR
    }
