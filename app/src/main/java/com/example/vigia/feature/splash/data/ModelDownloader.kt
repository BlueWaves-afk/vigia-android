package com.example.vigia.feature.splash.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class ModelDownloader(
    private val client: OkHttpClient
) {

    /**
     * Atomic download:
     * 1) download to .temp
     * 2) verify length
     * 3) rename to final
     */
    fun downloadAtomic(
        baseUrl: String,
        fileName: String,
        dir: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Boolean {
        val finalFile = File(dir, fileName)
        val tempFile = File(dir, "$fileName.temp")

        return try {
            val request = Request.Builder().url(baseUrl + fileName).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return false

            val body = response.body ?: return false
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        onProgress(totalRead, contentLength)
                    }
                    output.flush()
                }
            }

            if (contentLength != -1L && tempFile.length() != contentLength) {
                tempFile.delete()
                return false
            }

            if (tempFile.length() <= 0) {
                tempFile.delete()
                return false
            }

            if (finalFile.exists()) finalFile.delete()
            tempFile.renameTo(finalFile)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            false
        }
    }
}