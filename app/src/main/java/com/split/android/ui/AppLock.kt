package com.split.android.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.splitandroid.R
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val APP_LOCK_BACKGROUND_THRESHOLD_MILLIS = 30_000L

class AppLockManager {
    var isLocked by mutableStateOf(true)
        private set

    var isAuthenticating by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var backgroundedAtMillis: Long? = null
    private var hasUnlockedSinceLaunch = false

    fun onAppBackground() {
        backgroundedAtMillis = SystemClock.elapsedRealtime()
    }

    fun onAppForeground(
        activity: Activity?,
        scope: CoroutineScope
    ) {
        val needsUnlock = shouldRequireUnlock()
        backgroundedAtMillis = null

        if (!needsUnlock) {
            return
        }

        isLocked = true
        requestUnlock(activity, scope)
    }

    fun requestUnlock(
        activity: Activity?,
        scope: CoroutineScope
    ) {
        if (isAuthenticating) {
            return
        }

        if (activity == null || !deviceAuthenticationAvailable(activity)) {
            hasUnlockedSinceLaunch = true
            isLocked = false
            isAuthenticating = false
            errorMessage = null
            return
        }

        isLocked = true
        isAuthenticating = true
        errorMessage = null

        scope.launchUnlock(activity, this)
    }

    private fun shouldRequireUnlock(): Boolean {
        if (!hasUnlockedSinceLaunch) {
            return true
        }

        val backgroundedAtMillis = backgroundedAtMillis ?: return false
        return SystemClock.elapsedRealtime() - backgroundedAtMillis >= APP_LOCK_BACKGROUND_THRESHOLD_MILLIS
    }

    private companion object {
        fun kotlinx.coroutines.CoroutineScope.launchUnlock(
            activity: Activity,
            manager: AppLockManager
        ) {
            launch {
                try {
                    val didAuthenticate = authenticateWithDevice(activity)
                    if (didAuthenticate) {
                        manager.isLocked = false
                        manager.errorMessage = null
                        manager.hasUnlockedSinceLaunch = true
                    } else {
                        manager.isLocked = true
                        manager.errorMessage = "Unlock Split to continue."
                    }
                } catch (error: Exception) {
                    manager.isLocked = true
                    manager.errorMessage = userFacingErrorMessage(error)
                }

                manager.isAuthenticating = false
            }
        }

        @Suppress("DEPRECATION")
        fun deviceAuthenticationAvailable(activity: Activity): Boolean {
            val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
            val hasDeviceCredential = keyguardManager?.isDeviceSecure == true

            val biometricManager = activity.getSystemService(BiometricManager::class.java)
            val hasBiometrics = when {
                biometricManager == null -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                        BiometricManager.BIOMETRIC_SUCCESS
                }

                else -> biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }

            return hasDeviceCredential || hasBiometrics
        }

        @Suppress("DEPRECATION")
        suspend fun authenticateWithDevice(activity: Activity): Boolean =
            suspendCancellableCoroutine { continuation ->
                val executor = ContextCompat.getMainExecutor(activity)
                val cancellationSignal = CancellationSignal()

                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                }

                val promptBuilder = BiometricPrompt.Builder(activity)
                    .setTitle("Unlock Split")
                    .setSubtitle("Confirm your identity to continue")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    promptBuilder.setConfirmationRequired(false)
                    promptBuilder.setDeviceCredentialAllowed(true)
                } else {
                    promptBuilder.setNegativeButton("Cancel", executor) { _, _ ->
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }

                val prompt = promptBuilder.build()

                prompt.authenticate(
                    cancellationSignal,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence?
                        ) {
                            if (!continuation.isActive) {
                                return
                            }

                            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                            ) {
                                continuation.resume(false)
                            } else {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        errString?.toString()
                                            ?: "Unlock Split to continue."
                                    )
                                )
                            }
                        }

                        override fun onAuthenticationFailed() {
                            // Let the system prompt continue handling retries.
                        }
                    }
                )
            }

        fun userFacingErrorMessage(error: Exception): String {
            val message = error.message?.trim().orEmpty()
            return when {
                message.contains("lock", ignoreCase = true) &&
                    message.contains("credential", ignoreCase = true) -> {
                    "Use your device PIN, pattern, or password to unlock Split."
                }

                message.isNotEmpty() -> message
                else -> "Unlock Split to continue."
            }
        }
    }
}

@Composable
fun AppLockOverlay(
    isAuthenticating: Boolean,
    errorMessage: String?,
    onUnlock: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            SplitBrandBlue.copy(alpha = 0.22f),
                            SplitBrandPink.copy(alpha = 0.16f),
                            SplitBlack.copy(alpha = 0.04f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF141830),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.token_logo),
                    contentDescription = "Split",
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Unlock Split",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = "Use biometrics or your device PIN, pattern, or password to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB3CF),
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = onUnlock,
                    enabled = !isAuthenticating,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SplitBrandPink,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = if (isAuthenticating) "Checking Identity" else "Unlock Split",
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
