package com.split.android.data.messages

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MessageAttachmentManager(
    context: Context
) {
    companion object {
        const val MAXIMUM_ATTACHMENT_BYTES: Int = 50 * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val directory = File(context.filesDir, "messaging_attachments").apply {
        if (!exists()) {
            mkdirs()
        }
    }
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val _cachedAttachmentIds = MutableStateFlow(loadCachedAttachmentIds())
    val cachedAttachmentIds: StateFlow<Set<String>> = _cachedAttachmentIds.asStateFlow()

    fun hasCachedAttachment(payload: AttachmentMessagePayload): Boolean {
        val file = fileFor(payload.attachmentId, payload.fileName)
        return cachedAttachmentIds.value.contains(payload.attachmentId) && file.exists()
    }

    fun cachedAttachmentData(payload: AttachmentMessagePayload): ByteArray? {
        val file = fileFor(payload.attachmentId, payload.fileName)
        if (!file.exists()) return null

        return runCatching {
            encryptedFile(file).openFileInput().use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    fun cacheAttachment(
        attachmentId: String,
        fileName: String,
        plaintextData: ByteArray
    ) {
        require(plaintextData.isNotEmpty()) { "Attachment data is empty." }
        require(plaintextData.size <= MAXIMUM_ATTACHMENT_BYTES) {
            "Attachments must be smaller than ${MAXIMUM_ATTACHMENT_BYTES / (1024 * 1024)} MB."
        }

        val file = fileFor(attachmentId, fileName)
        encryptedFile(file).openFileOutput().use { output ->
            output.write(plaintextData)
        }

        _cachedAttachmentIds.value = _cachedAttachmentIds.value + attachmentId
    }

    fun clearAll() {
        if (directory.exists()) {
            directory.listFiles()?.forEach { it.delete() }
        }
        _cachedAttachmentIds.value = emptySet()
    }

    private fun encryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            file,
            appContext,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun fileFor(attachmentId: String, fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "${attachmentId}__${safeName}")
    }

    private fun loadCachedAttachmentIds(): Set<String> {
        return directory.listFiles()
            ?.mapNotNull { file ->
                file.name.substringBefore("__").takeIf { it.isNotBlank() }
            }
            ?.toSet()
            ?: emptySet()
    }
}
