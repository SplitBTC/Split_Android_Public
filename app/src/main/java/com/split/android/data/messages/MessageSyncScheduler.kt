package com.split.android.data.messages

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.split.android.data.auth.AuthManager
import com.split.android.data.network.SplitHttpClient
import com.split.android.data.rewards.RewardsRepository
import com.split.android.data.wallet.BreezApiRepository
import com.split.android.data.wallet.BreezSparkWalletClient
import com.split.android.data.wallet.MnemonicGenerator
import com.split.android.data.wallet.SeedStore
import com.split.android.data.wallet.WalletManager
import com.split.android.data.wallet.WalletState

object MessageSyncScheduler {
    private const val LEGACY_PERIODIC_WORK_NAME = "split_message_sync"
    private const val UNIQUE_IMMEDIATE_WORK_NAME = "split_message_sync_now"
    private const val INPUT_SYNC_REASON = "sync_reason"
    private const val REASON_INBOX = "inbox"
    private const val REASON_OUTGOING_STATUSES = "outgoing_statuses"

    fun disableLegacyPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK_NAME)
    }

    fun triggerImmediateSync(
        context: Context,
        reason: String = REASON_INBOX
    ) {
        val request = OneTimeWorkRequestBuilder<MessageSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(INPUT_SYNC_REASON to reason)
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    internal fun reasonFrom(workerParameters: WorkerParameters): String {
        return workerParameters.inputData.getString(INPUT_SYNC_REASON) ?: REASON_INBOX
    }

    internal fun outgoingStatusReason(): String = REASON_OUTGOING_STATUSES
}

object MessagingDeviceTokenSyncScheduler {
    private const val UNIQUE_TOKEN_SYNC_NAME = "split_message_device_token_sync"
    private const val INPUT_DEVICE_TOKEN = "device_token"

    fun enqueue(context: Context, tokenOverride: String? = null) {
        val request = OneTimeWorkRequestBuilder<MessagingDeviceTokenSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(INPUT_DEVICE_TOKEN to tokenOverride)
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_TOKEN_SYNC_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    internal fun tokenFrom(workerParameters: WorkerParameters): String? {
        return workerParameters.inputData.getString(INPUT_DEVICE_TOKEN)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_TOKEN_SYNC_NAME)
    }
}

class MessageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val syncReason = MessageSyncScheduler.reasonFrom(params)

    override suspend fun doWork(): Result {
        return withMessagingRuntime(applicationContext) { walletManager, authManager, messageKeyManager, messagingRepository ->
            try {
                walletManager.configure()
                if (walletManager.state.value !is WalletState.Ready) {
                    return@withMessagingRuntime Result.success()
                }

                authManager.ensureSession(walletManager)
                if (syncReason == MessageSyncScheduler.outgoingStatusReason()) {
                    messagingRepository.syncOutgoingStatuses(
                        authManager = authManager,
                        walletManager = walletManager,
                        force = true,
                        minimumIntervalMillis = 0L
                    )
                } else {
                    messagingRepository.syncInbox(
                        authManager = authManager,
                        walletManager = walletManager,
                        force = true,
                        minimumIntervalMillis = 0L
                    )
                    runCatching {
                        messagingRepository.syncPaidPaymentRequestStatuses(
                            transactions = walletManager.fetchTransactionRows(),
                            authManager = authManager,
                            walletManager = walletManager
                        )
                    }
                }
                Result.success()
            } catch (error: Throwable) {
                if (messageKeyManager.shouldSilentlyDeferActivation(error)) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        }
    }
}

class MessagingDeviceTokenSyncWorker(
    appContext: Context,
    private val workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val tokenOverride = MessagingDeviceTokenSyncScheduler.tokenFrom(workerParams)

        return withMessagingRuntime(applicationContext) { walletManager, authManager, messageKeyManager, _ ->
            val deviceTokenManager = MessagingDeviceTokenManager(
                context = applicationContext,
                httpClient = SplitHttpClient(),
                messageKeyManager = messageKeyManager
            )

            try {
                walletManager.configure()
                if (walletManager.state.value !is WalletState.Ready) {
                    return@withMessagingRuntime Result.success()
                }

                authManager.ensureSession(walletManager)
                if (tokenOverride.isNullOrBlank()) {
                    deviceTokenManager.syncCurrentDeviceToken(authManager, walletManager)
                } else {
                    deviceTokenManager.syncProvidedDeviceToken(
                        token = tokenOverride,
                        authManager = authManager,
                        walletManager = walletManager
                    )
                }
                Result.success()
            } catch (error: Throwable) {
                if (messageKeyManager.shouldSilentlyDeferActivation(error) ||
                    deviceTokenManager.shouldSilentlySkip(error)
                ) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        }
    }
}

private suspend fun <T> withMessagingRuntime(
    context: Context,
    block: suspend (
        walletManager: WalletManager,
        authManager: AuthManager,
        messageKeyManager: MessageKeyManager,
        messagingRepository: MessagingRepository
    ) -> T
): T {
    val httpClient = SplitHttpClient()
    val seedStore = SeedStore(context)
    val breezApiRepository = BreezApiRepository(httpClient)
    val authManager = AuthManager(httpClient)
    val rewardsRepository = RewardsRepository(httpClient)
    val sparkWalletClient = BreezSparkWalletClient()
    val walletManager = WalletManager(
        appContext = context,
        seedStore = seedStore,
        breezApiRepository = breezApiRepository,
        sparkWalletClient = sparkWalletClient,
        mnemonicGenerator = MnemonicGenerator(context),
        authManager = authManager,
        rewardsRepository = rewardsRepository
    )
    val messageStore = MessageStore.getInstance(context)
    val attachmentManager = MessageAttachmentManager(context)
    val messageKeyManager = MessageKeyManager(context, httpClient)
    val deviceTokenManager = MessagingDeviceTokenManager(
        context = context,
        httpClient = httpClient,
        messageKeyManager = messageKeyManager
    )
    val messagingRepository = MessagingRepository(
        httpClient = httpClient,
        messageKeyManager = messageKeyManager,
        messageStore = messageStore,
        attachmentManager = attachmentManager,
        deviceTokenManager = deviceTokenManager
    )

    return try {
        block(walletManager, authManager, messageKeyManager, messagingRepository)
    } finally {
        walletManager.shutdown()
    }
}
