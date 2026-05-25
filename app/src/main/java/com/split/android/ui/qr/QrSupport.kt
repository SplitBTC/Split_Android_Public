package com.split.android.ui.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

data class SplitContactPayload(
    val type: String,
    val version: Int,
    val lightningAddress: String,
    val suggestedName: String,
    val profilePicUrl: String?
) {
    val id: String get() = lightningAddress

    companion object {
        const val PREFIX = "split-contact:"

        fun parse(raw: String): SplitContactPayload? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            decodePayload(trimmed)?.let { return it }

            trimmed.lineSequence().forEach { line ->
                decodePayload(line.trim())?.let { return it }
            }

            val prefixIndex = trimmed.lowercase().indexOf(PREFIX)
            if (prefixIndex >= 0) {
                return decodePayload(trimmed.substring(prefixIndex).trim())
            }

            return null
        }

        private fun decodePayload(candidate: String): SplitContactPayload? {
            if (!candidate.lowercase().startsWith(PREFIX)) return null

            return runCatching {
                val json = JSONObject(candidate.drop(PREFIX.length).trim())
                val payload = SplitContactPayload(
                    type = json.optString("type"),
                    version = json.optInt("version"),
                    lightningAddress = json.optString("lightningAddress").trim().lowercase(),
                    suggestedName = json.optString("suggestedName").trim(),
                    profilePicUrl = json.optString("profilePicUrl").trim().ifBlank { null }
                )

                if (payload.type != "split_contact" ||
                    payload.version != 1 ||
                    !payload.lightningAddress.contains("@")
                ) {
                    null
                } else {
                    payload
                }
            }.getOrNull()
        }
    }
}

fun suggestedContactName(lightningAddress: String): String {
    val trimmed = lightningAddress.trim()
    val username = trimmed.substringBefore("@").trim()
    return if (username.isBlank()) "Split User" else username
}

fun buildPaymentQrString(lightningAddress: String): String {
    return "lightning:${lightningAddress.trim()}"
}

fun buildSplitContactQrString(
    lightningAddress: String,
    suggestedName: String,
    profilePicUrl: String? = null
): String {
    val payload = JSONObject()
        .put("type", "split_contact")
        .put("version", 1)
        .put("lightningAddress", lightningAddress.trim().lowercase())
        .put("suggestedName", suggestedName.trim().ifBlank { suggestedContactName(lightningAddress) })

    profilePicUrl?.trim()?.takeIf { it.isNotBlank() }?.let {
        payload.put("profilePicUrl", it)
    }

    return "${SplitContactPayload.PREFIX}$payload"
}

fun normalizePaymentRequest(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val lower = trimmed.lowercase()

    if (lower.startsWith("lightning:")) {
        val withoutScheme = trimmed.drop("lightning:".length).trim()
        return normalizePaymentRequest(withoutScheme) ?: withoutScheme
    }

    if (isDirectLightningPaymentRequest(trimmed)) {
        return trimmed
    }

    if (lower.startsWith("bitcoin:")) {
        return runCatching { Uri.parse(trimmed) }.getOrNull()?.getQueryParameter("lightning")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizePaymentRequest(it) ?: it }
            ?: trimmed
    }

    val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
    val invoiceKeys = setOf("lightning", "invoice", "bolt11", "paymentrequest", "payment_request", "pr")
    uri?.queryParameterNames
        ?.firstOrNull { it.lowercase() in invoiceKeys }
        ?.let { key ->
            uri.getQueryParameter(key)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return normalizePaymentRequest(it) ?: it }
        }

    return trimmed
}

private fun isDirectLightningPaymentRequest(value: String): Boolean {
    val lower = value.trim().lowercase()
    return lower.startsWith("lnbc") ||
        lower.startsWith("lntb") ||
        lower.startsWith("lnbcrt") ||
        lower.startsWith("lnurl")
}

fun shouldOpenEntryFirstSendFlow(paymentRequest: String): Boolean {
    val trimmed = paymentRequest.trim()
    val lower = trimmed.lowercase()
    return isAmountlessBolt11PaymentRequest(trimmed) ||
        lower.startsWith("lnurl") ||
        (trimmed.contains("@") && !trimmed.contains(" "))
}

fun isAmountlessBolt11PaymentRequest(paymentRequest: String): Boolean {
    val trimmed = paymentRequest.trim()
    if (trimmed.isEmpty()) return false

    val lower = if (trimmed.lowercase().startsWith("lightning:")) {
        trimmed.drop("lightning:".length).trim().lowercase()
    } else {
        trimmed.lowercase()
    }

    val separatorIndex = lower.lastIndexOf('1')
    if (separatorIndex <= 0) return false

    val humanReadablePart = lower.substring(0, separatorIndex)
    val networkPrefix = listOf("lnbcrt", "lnbc", "lntb")
        .firstOrNull { humanReadablePart.startsWith(it) }
        ?: return false

    return humanReadablePart.drop(networkPrefix.length).isEmpty()
}

sealed interface QrImageDecodeResult {
    data class Success(val value: String) : QrImageDecodeResult
    data object NoQrCode : QrImageDecodeResult
    data object MultipleQrCodes : QrImageDecodeResult
    data object ImageUnreadable : QrImageDecodeResult
}

suspend fun readSingleQrCodeFromImageUri(
    context: Context,
    uri: Uri
): QrImageDecodeResult {
    val bitmap = decodeBitmapFromUri(context, uri) ?: return QrImageDecodeResult.ImageUnreadable
    val decodedQrValues = decodeQrCodeStrings(bitmap).distinct()

    return when {
        decodedQrValues.size > 1 -> QrImageDecodeResult.MultipleQrCodes
        decodedQrValues.size == 1 -> QrImageDecodeResult.Success(decodedQrValues.first())
        else -> QrImageDecodeResult.NoQrCode
    }
}

fun readClipboardPaymentText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null

    for (index in 0 until clip.itemCount) {
        val text = clip.getItemAt(index)
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (text != null) {
            return text
        }
    }

    return null
}

private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }.getOrNull()
}

private suspend fun decodeQrCodeStrings(bitmap: Bitmap): List<String> {
    val decodedValues = linkedSetOf<String>()

    for (candidate in bitmap.qrDecodeCandidates()) {
        decodedValues.addAll(decodeQrCodeStringsWithMlKit(candidate))
        decodedValues.addAll(decodeQrCodeStringsWithZxing(candidate))
    }

    return decodedValues.toList()
}

private suspend fun decodeQrCodeStringsWithMlKit(bitmap: Bitmap): List<String> {
    val image = InputImage.fromBitmap(bitmap, 0)
    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    val scanner = BarcodeScanning.getClient(options)

    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { scanner.close() }
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val values = barcodes
                    .filter { it.format == Barcode.FORMAT_QR_CODE }
                    .mapNotNull { barcode ->
                        listOfNotNull(
                            barcode.rawValue,
                            barcode.displayValue,
                            barcode.url?.url
                        ).firstNotNullOfOrNull { value ->
                            value.trim().takeIf { it.isNotEmpty() }
                        }
                    }

                if (continuation.isActive) {
                    continuation.resume(values)
                }
            }
            .addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(emptyList())
                }
            }
            .addOnCompleteListener {
                scanner.close()
            }
    }
}

private fun decodeQrCodeStringsWithZxing(bitmap: Bitmap): List<String> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    fun BinaryBitmap.decodedMultiple(): List<String> {
        return runCatching {
            QRCodeMultiReader()
                .decodeMultiple(this, hints)
                .mapNotNull { result -> result.text?.trim()?.takeIf { it.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }

    fun BinaryBitmap.decodedSingle(): List<String> {
        return runCatching {
            val reader = MultiFormatReader().apply { setHints(hints) }
            listOfNotNull(reader.decodeWithState(this).text?.trim()?.takeIf { it.isNotEmpty() })
        }.getOrDefault(emptyList())
    }

    val hybridBitmap = BinaryBitmap(HybridBinarizer(source))
    return hybridBitmap.decodedMultiple().ifEmpty { hybridBitmap.decodedSingle() }
}

private fun Bitmap.qrDecodeCandidates(): List<Bitmap> {
    val candidates = mutableListOf<Bitmap>()
    val seen = linkedSetOf<String>()

    fun add(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return
        val candidate = bitmap.scaledForQrDecode()
        val key = "${candidate.width}:${candidate.height}:${System.identityHashCode(candidate)}"
        if (seen.add(key)) {
            candidates.add(candidate)
        }
    }

    add(this)
    heuristicQrCrops().forEach { crop ->
        add(crop)
        add(crop.withWhiteBorder((minOf(crop.width, crop.height) * 0.08f).toInt().coerceAtLeast(16)))
    }

    return candidates
}

private fun Bitmap.heuristicQrCrops(): List<Bitmap> {
    val cropSpecs = listOf(
        Triple(0.04f, 0.12f, 0.92f),
        Triple(0.03f, 0.10f, 0.94f),
        Triple(0.02f, 0.08f, 0.96f),
        Triple(0.00f, 0.00f, 1.00f)
    )
    val crops = mutableListOf<Bitmap>()
    val seenRects = linkedSetOf<String>()

    for ((xRatio, yRatio, sizeRatio) in cropSpecs) {
        val cropSize = minOf((width * sizeRatio).toInt(), height)
        if (cropSize <= 120) continue

        val left = (width * xRatio).toInt().coerceIn(0, (width - cropSize).coerceAtLeast(0))
        val top = (height * yRatio).toInt().coerceIn(0, (height - cropSize).coerceAtLeast(0))
        val key = "$left:$top:$cropSize"
        if (!seenRects.add(key)) continue

        runCatching {
            Bitmap.createBitmap(this, left, top, cropSize, cropSize)
        }.getOrNull()?.let(crops::add)
    }

    return crops
}

private fun Bitmap.withWhiteBorder(borderPx: Int): Bitmap? {
    if (borderPx <= 0) return this

    return runCatching {
        Bitmap.createBitmap(
            width + borderPx * 2,
            height + borderPx * 2,
            Bitmap.Config.ARGB_8888
        ).apply {
            val canvas = Canvas(this)
            canvas.drawColor(AndroidColor.WHITE)
            canvas.drawBitmap(this@withWhiteBorder, borderPx.toFloat(), borderPx.toFloat(), null)
        }
    }.getOrNull()
}

private fun Bitmap.scaledForQrDecode(): Bitmap {
    val maxSide = max(width, height)
    if (maxSide <= 2048) return this

    val scale = 2048f / maxSide.toFloat()
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

fun generateQrBitmap(content: String, sizePx: Int = 720): Bitmap? {
    if (content.isBlank()) return null

    return runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }

        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}

@Composable
fun QrCodeCard(
    qrString: String,
    modifier: Modifier = Modifier,
    size: Int = 220
) {
    val bitmap = remember(qrString, size) { generateQrBitmap(qrString, size * 3) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(24.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Unable to generate QR",
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

fun shareIdentityBitmap(
    context: Context,
    bitmap: Bitmap,
    prefix: String = "split-identity"
) {
    val file = writeBitmapToShareFile(context, bitmap, prefix) ?: run {
        Toast.makeText(context, "Unable to share right now.", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(intent, "Share Split QR")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun copyIdentityBitmap(
    context: Context,
    bitmap: Bitmap,
    prefix: String = "split-identity"
) {
    val file = writeBitmapToShareFile(context, bitmap, prefix) ?: run {
        Toast.makeText(context, "Unable to copy right now.", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newUri(
            context.contentResolver,
            "Split QR",
            uri
        )
    )
    Toast.makeText(context, "Copied.", Toast.LENGTH_SHORT).show()
}

fun copyQrPayload(
    context: Context,
    label: String,
    payload: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, payload))
    Toast.makeText(context, "Copied.", Toast.LENGTH_SHORT).show()
}

fun buildIdentityShareBitmap(
    qrString: String,
    primaryText: String,
    secondaryText: String
): Bitmap {
    val width = 960
    val height = 1290
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(AndroidColor.BLACK)

    val qrBitmap = generateQrBitmap(qrString, 620)
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
    }
    val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 64f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(160, 255, 255, 255)
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    val qrRect = RectF(170f, 130f, 790f, 750f)
    canvas.drawRoundRect(qrRect, 46f, 46f, cardPaint)

    qrBitmap?.let {
        canvas.drawBitmap(it, null, RectF(190f, 150f, 770f, 730f), null)
    }

    val maxTextWidth = width - 120f
    val primary = TextUtils.ellipsize(primaryText, primaryPaint, maxTextWidth, TextUtils.TruncateAt.MIDDLE).toString()
    val secondary = TextUtils.ellipsize(secondaryText, secondaryPaint, maxTextWidth, TextUtils.TruncateAt.MIDDLE).toString()

    canvas.drawText(primary, width / 2f, 915f, primaryPaint)
    canvas.drawText(secondary, width / 2f, 985f, secondaryPaint)

    return bitmap
}

private fun writeBitmapToShareFile(
    context: Context,
    bitmap: Bitmap,
    prefix: String
): File? {
    return runCatching {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        File(directory, "$prefix-${System.currentTimeMillis()}.png").also { file ->
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }.getOrNull()
}
