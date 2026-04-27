package com.split.android.data.network

import com.split.android.core.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

data class MultipartFilePart(
    val fieldName: String,
    val fileName: String,
    val mimeType: String,
    val fileData: ByteArray
)

class SplitHttpClient {

    companion object {
        private val cookieManager = CookieManager().apply {
            setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        }

        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 45_000
        const val DEFAULT_RETRY_ATTEMPTS = 2
        const val RETRY_DELAY_MILLIS = 1_500L

        init {
            CookieHandler.setDefault(cookieManager)
        }
    }

    suspend fun get(path: String): HttpResponse = request(
        method = "GET",
        path = path,
        jsonBody = null
    )

    suspend fun delete(path: String): HttpResponse = request(
        method = "DELETE",
        path = path,
        jsonBody = null
    )

    suspend fun getBytes(path: String): BinaryHttpResponse = binaryRequest(
        method = "GET",
        path = path
    )

    suspend fun postJson(path: String, jsonBody: String?): HttpResponse = request(
        method = "POST",
        path = path,
        jsonBody = jsonBody,
        retryOnIoFailure = false
    )

    suspend fun postMultipart(
        path: String,
        formFields: Map<String, String>,
        fileFieldName: String,
        fileName: String,
        mimeType: String,
        fileData: ByteArray
    ): HttpResponse = postMultipart(
        path = path,
        formFields = formFields,
        files = listOf(
            MultipartFilePart(
                fieldName = fileFieldName,
                fileName = fileName,
                mimeType = mimeType,
                fileData = fileData
            )
        )
    )

    suspend fun postMultipart(
        path: String,
        formFields: Map<String, String>,
        files: List<MultipartFilePart>
    ): HttpResponse = withContext(Dispatchers.IO) {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val url = URL("${AppConfig.baseUrl}$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doInput = true
            doOutput = true
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                formFields.forEach { (key, value) ->
                    writer.append("--$boundary\r\n")
                    writer.append("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
                    writer.append(value)
                    writer.append("\r\n")
                }

                files.forEach { file ->
                    writer.append("--$boundary\r\n")
                    writer.append(
                        "Content-Disposition: form-data; name=\"${file.fieldName}\"; filename=\"${file.fileName}\"\r\n"
                    )
                    writer.append("Content-Type: ${file.mimeType}\r\n\r\n")
                    writer.flush()

                    output.write(file.fileData)
                    output.flush()

                    writer.append("\r\n")
                }

                writer.append("--$boundary--\r\n")
                writer.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            HttpResponse(
                statusCode = statusCode,
                body = stream?.readUtf8().orEmpty()
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        jsonBody: String?,
        retryOnIoFailure: Boolean = method == "GET" || method == "DELETE"
    ): HttpResponse {
        val maxAttempts = if (retryOnIoFailure) {
            DEFAULT_RETRY_ATTEMPTS
        } else {
            1
        }

        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            try {
                return withContext(Dispatchers.IO) {
                    executeRequest(
                        method = method,
                        path = path,
                        jsonBody = jsonBody
                    )
                }
            } catch (error: IOException) {
                lastError = error
                attempt += 1
                if (attempt >= maxAttempts) {
                    throw error
                }

                delay(RETRY_DELAY_MILLIS)
            }
        }

        throw lastError ?: IllegalStateException("HTTP request failed without an exception.")
    }

    private fun executeRequest(
        method: String,
        path: String,
        jsonBody: String?
    ): HttpResponse {
        val url = URL("${AppConfig.baseUrl}$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doInput = true
            instanceFollowRedirects = true
            useCaches = false

            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        }

        return try {
            if (jsonBody != null) {
                connection.outputStream.use { output ->
                    output.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val body = stream?.readUtf8().orEmpty()
            HttpResponse(
                statusCode = statusCode,
                body = body
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun binaryRequest(
        method: String,
        path: String
    ): BinaryHttpResponse {
        var attempt = 0
        var lastError: IOException? = null

        while (attempt < DEFAULT_RETRY_ATTEMPTS) {
            try {
                return withContext(Dispatchers.IO) {
                    executeBinaryRequest(
                        method = method,
                        path = path
                    )
                }
            } catch (error: IOException) {
                lastError = error
                attempt += 1
                if (attempt >= DEFAULT_RETRY_ATTEMPTS) {
                    throw error
                }

                delay(RETRY_DELAY_MILLIS)
            }
        }

        throw lastError ?: IllegalStateException("Binary HTTP request failed without an exception.")
    }

    private fun executeBinaryRequest(
        method: String,
        path: String
    ): BinaryHttpResponse {
        val url = URL("${AppConfig.baseUrl}$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doInput = true
            instanceFollowRedirects = true
            useCaches = false
        }

        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            BinaryHttpResponse(
                statusCode = statusCode,
                body = stream?.readBytesSafe() ?: ByteArray(0),
                contentType = connection.contentType
            )
        } finally {
            connection.disconnect()
        }
    }
}

data class HttpResponse(
    val statusCode: Int,
    val body: String
)

data class BinaryHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String?
)

private fun InputStream.readUtf8(): String {
    return BufferedReader(InputStreamReader(this, Charsets.UTF_8)).use { reader ->
        reader.readText()
    }
}

private fun InputStream.readBytesSafe(): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    return ByteArrayOutputStream().use { output ->
        while (true) {
            val count = read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
