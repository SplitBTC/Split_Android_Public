package com.split.android.ui.qr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun SplitQrScannerView(
    modifier: Modifier = Modifier,
    onCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCodeScanned = rememberUpdatedState(onCodeScanned)
    val scannerSessionId = remember { SplitQrCameraSessionCoordinator.nextSessionId() }
    var hasCameraPermission by remember { mutableStateOf(checkCameraPermission(context)) }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(hasCameraPermission) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var scanErrorMessage by remember { mutableStateOf<String?>(null) }

    val scanOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val barcodeScanner = remember { BarcodeScanning.getClient(scanOptions) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val canProcessFrame = remember { AtomicBoolean(true) }
    val hasEmittedCode = remember { AtomicBoolean(false) }
    val isDisposed = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        hasRequestedPermission = true
    }

    LaunchedEffect(hasCameraPermission, hasRequestedPermission) {
        if (!hasCameraPermission && !hasRequestedPermission) {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            isDisposed.set(true)
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
    }

    DisposableEffect(hasCameraPermission, previewViewRef, lifecycleOwner) {
        val previewView = previewViewRef
        if (!hasCameraPermission || previewView == null) {
            onDispose { }
        } else {
            isDisposed.set(false)
            hasEmittedCode.set(false)
            canProcessFrame.set(true)
            SplitQrCameraSessionCoordinator.activate(scannerSessionId)
            scanErrorMessage = null
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            var imageAnalysis: ImageAnalysis? = null

            cameraProviderFuture.addListener(
                {
                    if (isDisposed.get() || !SplitQrCameraSessionCoordinator.isActive(scannerSessionId)) {
                        return@addListener
                    }

                    runCatching {
                        val provider = cameraProviderFuture.get()

                        if (isDisposed.get() || !SplitQrCameraSessionCoordinator.isActive(scannerSessionId)) {
                            return@runCatching
                        }

                        val preview = Preview.Builder().build().apply {
                            surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(1920, 1920),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        imageAnalysis = analysis
                        canProcessFrame.set(true)
                        hasEmittedCode.set(false)

                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            if (hasEmittedCode.get() || !canProcessFrame.compareAndSet(true, false)) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val mediaImage = imageProxy.image
                            if (mediaImage == null) {
                                canProcessFrame.set(true)
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val raw = barcodes.firstNotNullOfOrNull { barcode ->
                                        listOfNotNull(
                                            barcode.rawValue,
                                            barcode.displayValue,
                                            barcode.url?.url
                                        ).firstNotNullOfOrNull { value ->
                                            value.trim().takeIf { it.isNotEmpty() }
                                        }
                                    }

                                    if (!raw.isNullOrBlank() &&
                                        hasEmittedCode.compareAndSet(false, true)
                                    ) {
                                        previewView.post {
                                            if (!isDisposed.get() &&
                                                SplitQrCameraSessionCoordinator.isActive(scannerSessionId)
                                            ) {
                                                analysis.clearAnalyzer()
                                                currentOnCodeScanned.value(raw)
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener {
                                    previewView.post {
                                        if (!isDisposed.get() &&
                                            !hasEmittedCode.get() &&
                                            SplitQrCameraSessionCoordinator.isActive(scannerSessionId)
                                        ) {
                                            scanErrorMessage = "Unable to read the camera feed."
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    if (!hasEmittedCode.get()) {
                                        canProcessFrame.set(true)
                                    }
                                    imageProxy.close()
                                }
                        }

                        if (isDisposed.get() || !SplitQrCameraSessionCoordinator.isActive(scannerSessionId)) {
                            analysis.clearAnalyzer()
                            return@runCatching
                        }

                        SplitQrCameraSessionCoordinator.unbindCurrentBinding()

                        if (isDisposed.get() || !SplitQrCameraSessionCoordinator.isActive(scannerSessionId)) {
                            analysis.clearAnalyzer()
                            return@runCatching
                        }

                        val camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )

                        if (!SplitQrCameraSessionCoordinator.registerBoundSession(
                                sessionId = scannerSessionId,
                                provider = provider,
                                preview = preview,
                                analysis = analysis
                            )
                        ) {
                            analysis.clearAnalyzer()
                            provider.unbind(preview, analysis)
                            return@runCatching
                        }

                        previewView.post {
                            if (!isDisposed.get() &&
                                SplitQrCameraSessionCoordinator.isActive(scannerSessionId) &&
                                previewView.width > 0 &&
                                previewView.height > 0
                            ) {
                                val meteringPoint = previewView.meteringPointFactory.createPoint(
                                    previewView.width / 2f,
                                    previewView.height / 2f
                                )
                                camera.cameraControl.startFocusAndMetering(
                                    FocusMeteringAction.Builder(
                                        meteringPoint,
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                    )
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build()
                                )
                            }
                        }
                    }.onFailure {
                        previewView.post {
                            if (!isDisposed.get() &&
                                SplitQrCameraSessionCoordinator.isActive(scannerSessionId)
                            ) {
                                scanErrorMessage = "Unable to start the QR scanner."
                            }
                        }
                    }
                },
                mainExecutor
            )

            onDispose {
                isDisposed.set(true)
                imageAnalysis?.clearAnalyzer()
                SplitQrCameraSessionCoordinator.unbindIfOwner(scannerSessionId)
                canProcessFrame.set(true)
            }
        }
    }

    if (hasCameraPermission) {
        Box(modifier = modifier.background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { androidContext ->
                    PreviewView(androidContext).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewViewRef = this
                    }
                },
                update = { previewView ->
                    previewViewRef = previewView
                }
            )

            if (!scanErrorMessage.isNullOrBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = scanErrorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Camera access is required to scan payment and contact QR codes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = {
                        hasRequestedPermission = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text("Allow Camera")
                }
            }
        }
    }
}

private fun checkCameraPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}

private data class BoundQrCameraSession(
    val sessionId: Long,
    val provider: ProcessCameraProvider,
    val preview: Preview,
    val analysis: ImageAnalysis
)

private object SplitQrCameraSessionCoordinator {
    private val nextSessionId = AtomicLong(0)
    private var activeSessionId: Long = 0
    private var boundSession: BoundQrCameraSession? = null

    fun nextSessionId(): Long = nextSessionId.incrementAndGet()

    @Synchronized
    fun activate(sessionId: Long) {
        activeSessionId = sessionId
    }

    @Synchronized
    fun isActive(sessionId: Long): Boolean {
        return activeSessionId == sessionId
    }

    fun unbindCurrentBinding() {
        val session = synchronized(this) {
            boundSession.also { boundSession = null }
        }
        session?.unbind()
    }

    fun registerBoundSession(
        sessionId: Long,
        provider: ProcessCameraProvider,
        preview: Preview,
        analysis: ImageAnalysis
    ): Boolean {
        return synchronized(this) {
            if (activeSessionId != sessionId) {
                false
            } else {
                boundSession = BoundQrCameraSession(
                    sessionId = sessionId,
                    provider = provider,
                    preview = preview,
                    analysis = analysis
                )
                true
            }
        }
    }

    fun unbindIfOwner(sessionId: Long) {
        val session = synchronized(this) {
            if (activeSessionId == sessionId) {
                activeSessionId = 0
            }

            if (boundSession?.sessionId == sessionId) {
                boundSession.also { boundSession = null }
            } else {
                null
            }
        }
        session?.unbind()
    }
}

private fun BoundQrCameraSession.unbind() {
    analysis.clearAnalyzer()
    runCatching {
        provider.unbind(preview, analysis)
    }
}
