package com.split.android.ui.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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

    if (lower.startsWith("bitcoin:")) {
        return runCatching { Uri.parse(trimmed) }.getOrNull()?.getQueryParameter("lightning")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: trimmed
    }

    runCatching { Uri.parse(trimmed) }.getOrNull()
        ?.getQueryParameter("lightning")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    return trimmed
}

fun shouldOpenEntryFirstSendFlow(paymentRequest: String): Boolean {
    val trimmed = paymentRequest.trim()
    val lower = trimmed.lowercase()
    return lower.startsWith("lnurl") || (trimmed.contains("@") && !trimmed.contains(" "))
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
