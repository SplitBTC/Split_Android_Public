package com.split.android.data.messages

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class MessageVideoProcessor(
    context: Context
) {
    private val appContext = context.applicationContext
    private val exportDirectory = File(appContext.cacheDir, "message_video_exports").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private data class CompressionProfile(
        val shortSidePx: Int?,
        val videoBitrate: Int,
        val audioBitrate: Int
    )

    data class ProcessedVideoAttachment(
        val data: ByteArray,
        val fileName: String,
        val mimeType: String
    )

    suspend fun prepareAttachment(
        uri: Uri,
        originalFileName: String,
        maxBytes: Int = MessageAttachmentManager.MAXIMUM_ATTACHMENT_BYTES
    ): ProcessedVideoAttachment {
        val profiles = listOf(
            CompressionProfile(shortSidePx = 720, videoBitrate = 2_500_000, audioBitrate = 128_000),
            CompressionProfile(shortSidePx = 540, videoBitrate = 1_800_000, audioBitrate = 96_000),
            CompressionProfile(shortSidePx = 480, videoBitrate = 1_200_000, audioBitrate = 96_000),
            CompressionProfile(shortSidePx = 360, videoBitrate = 900_000, audioBitrate = 64_000)
        )

        val outputFileName = normalizedOutputFileName(originalFileName)
        var lastFailure: Throwable? = null

        for (profile in profiles) {
            val outputFile = File(exportDirectory, "${UUID.randomUUID()}_$outputFileName")
            try {
                exportVideo(uri, outputFile, profile)
                val data = withContext(Dispatchers.IO) { outputFile.readBytes() }
                if (data.isNotEmpty() && data.size <= maxBytes) {
                    return ProcessedVideoAttachment(
                        data = data,
                        fileName = outputFileName,
                        mimeType = "video/mp4"
                    )
                }
                lastFailure = IllegalStateException(
                    "Videos must be smaller than ${maxBytes / (1024 * 1024)} MB after compression."
                )
            } catch (error: Throwable) {
                lastFailure = error
            } finally {
                outputFile.delete()
            }
        }

        throw lastFailure ?: IllegalStateException("Failed to prepare video attachment.")
    }

    private suspend fun exportVideo(
        uri: Uri,
        outputFile: File,
        profile: CompressionProfile
    ) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val effects = profile.shortSidePx?.let { shortSide ->
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForShortSide(shortSide))
                    )
                } ?: Effects.EMPTY

                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(effects)
                    .setFrameRate(30)
                    .setFlattenForSlowMotion(true)
                    .build()

                val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(profile.videoBitrate)
                            .build()
                    )
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder()
                            .setBitrate(profile.audioBitrate)
                            .build()
                    )
                    .build()

                lateinit var transformer: Transformer
                transformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: androidx.media3.transformer.Composition,
                                result: ExportResult
                            ) {
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(
                                composition: androidx.media3.transformer.Composition,
                                result: ExportResult,
                                exception: ExportException
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(exception)
                                }
                            }
                        }
                    )
                    .build()

                continuation.invokeOnCancellation {
                    transformer.cancel()
                    outputFile.delete()
                }

                transformer.start(editedMediaItem, outputFile.absolutePath)
            }
        }
    }

    private fun normalizedOutputFileName(originalFileName: String): String {
        val baseName = originalFileName.substringBeforeLast('.').ifBlank { "video" }
        val safeBaseName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$safeBaseName.mp4"
    }
}
