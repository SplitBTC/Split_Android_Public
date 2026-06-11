package com.split.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.split.android.core.AppConfig
import com.split.android.data.messages.MessageNotificationManager
import com.split.android.data.messages.MessageNotificationRouter
import com.split.android.ui.AppLockManager
import com.split.android.ui.AppLockOverlay
import com.split.android.ui.GlobalPaymentResultHost
import com.split.android.ui.SplitAndroidApp
import com.split.android.ui.findActivity
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitAndroidTheme
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import com.split.android.ui.theme.SplitSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeNotificationIntent(intent)

        setContent {
            SplitAndroidTheme {
                SplitAndroidRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        val conversationId = intent
            ?.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
            ?.trim()
            .orEmpty()

        if (conversationId.isNotEmpty()) {
            MessageNotificationRouter.queueConversation(conversationId)
            intent?.removeExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
        }
    }
}

private data class VersionGateState(
    val isLoading: Boolean = true,
    val isVersionValid: Boolean = true
)

@Composable
private fun SplitAndroidRoot() {
    var versionGateState by remember {
        mutableStateOf(VersionGateState())
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val appLockManager = remember { AppLockManager() }

    LaunchedEffect(Unit) {
        versionGateState = versionGateState.copy(
            isLoading = true,
            isVersionValid = checkVersionIsValid()
        )
        versionGateState = versionGateState.copy(isLoading = false)
    }

    DisposableEffect(lifecycleOwner, activity, versionGateState.isLoading, versionGateState.isVersionValid) {
        if (versionGateState.isLoading || !versionGateState.isVersionValid) {
            onDispose { }
        } else {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    appLockManager.onAppForeground(activity, scope)
                }

                override fun onStop(owner: LifecycleOwner) {
                    appLockManager.onAppBackground()
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(versionGateState.isLoading, versionGateState.isVersionValid, activity) {
        if (!versionGateState.isLoading && versionGateState.isVersionValid) {
            delay(350)
            appLockManager.onAppForeground(activity, scope)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            versionGateState.isLoading -> VersionGateLoadingScreen()
            !versionGateState.isVersionValid -> ForcedUpdateScreen()
            else -> SplitAndroidApp()
        }

        GlobalPaymentResultHost()

        if (!versionGateState.isLoading &&
            versionGateState.isVersionValid &&
            appLockManager.isLocked
        ) {
            AppLockOverlay(
                isAuthenticating = appLockManager.isAuthenticating,
                errorMessage = appLockManager.errorMessage,
                onUnlock = {
                    appLockManager.requestUnlock(activity, scope)
                }
            )
        }
    }
}

@Composable
private fun VersionGateLoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = SplitBrandBlue)
        Text(
            text = "Checking app version",
            modifier = Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ForcedUpdateScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SplitSurface,
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = "Update Required",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Please update to the latest version of Split for continued usage. If you are having any issues please do not hesitate to reach out to support@example.com",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = {
                        val marketIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=${BuildConfig.APPLICATION_ID}")
                        )
                        val webIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}")
                        )
                        val fallbackIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("${AppConfig.baseUrl}/download")
                        )

                        runCatching {
                            context.startActivity(marketIntent)
                        }.recoverCatching {
                            context.startActivity(webIntent)
                        }.recoverCatching {
                            context.startActivity(fallbackIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Update Now")
                }
            }
        }
    }
}

private suspend fun checkVersionIsValid(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL("${AppConfig.baseUrl}/rewards-version-check?platform=android").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            doInput = true
        }

        connection.use { http ->
            val body = (if (http.responseCode in 200..299) {
                http.inputStream
            } else {
                http.errorStream ?: http.inputStream
            }).bufferedReader().use { it.readText() }

            val minimumVersion = JSONObject(body).optString("minimumVersion").ifBlank { return@use true }
            compareVersions(BuildConfig.VERSION_NAME, minimumVersion)
        }
    }.getOrDefault(true)
}

private fun compareVersions(current: String, required: String): Boolean {
    val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
    val requiredParts = required.split('.').mapNotNull { it.toIntOrNull() }
    val maxSize = maxOf(currentParts.size, requiredParts.size)

    repeat(maxSize) { index ->
        val currentValue = currentParts.getOrElse(index) { 0 }
        val requiredValue = requiredParts.getOrElse(index) { 0 }
        if (currentValue < requiredValue) return false
        if (currentValue > requiredValue) return true
    }

    return true
}

private inline fun <T : HttpURLConnection?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this?.disconnect()
    }
}
