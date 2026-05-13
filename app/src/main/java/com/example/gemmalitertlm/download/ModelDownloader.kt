package com.example.gemmalitertlm.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a model .litertlm file from Hugging Face.
 *
 * Gemma is a gated model on HF, so the user must:
 *   1. Accept the license at https://huggingface.co/google/gemma-4-E4B-it
 *   2. Generate a token (read-only is fine) at https://huggingface.co/settings/tokens
 *   3. Paste the token here.
 *
 * The download is streamed to a temp file and atomically renamed on success.
 */
object ModelDownloader {

    /**
     * Default URL pointing to a .litertlm model file.
     * Update this to your preferred model repo.
     */
    const val DEFAULT_URL: String =
        "https://huggingface.co/xihajun/gemma4-e4b-mixed-en-lora-r16-v6e-1536-litert-lm/resolve/main/model.litertlm"

    sealed class Event {
        data class Progress(val bytesRead: Long, val total: Long) : Event()
        data class Done(val file: File) : Event()
        data class Failed(val message: String, val httpCode: Int? = null) : Event()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // streaming, no read timeout
        .build()

    fun download(
        url: String,
        hfToken: String?,
        destFile: File,
    ): Flow<Event> = flow {
        val tmp = File(destFile.absolutePath + ".part")
        tmp.parentFile?.mkdirs()

        val reqBuilder = Request.Builder().url(url).get()
        if (!hfToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { response: Response ->
            if (!response.isSuccessful) {
                emit(Event.Failed(
                    "HTTP ${response.code}: ${response.message}. " +
                            "If 401/403, check your Hugging Face token and that you've accepted the model license.",
                    response.code))
                return@flow
            }
            val body = response.body ?: run {
                emit(Event.Failed("Empty response body"))
                return@flow
            }
            val total = body.contentLength()
            val source = body.byteStream()
            val sink = tmp.outputStream().buffered(BUF_SIZE)
            var read: Long = 0
            val buffer = ByteArray(BUF_SIZE)
            try {
                while (true) {
                    val n = source.read(buffer)
                    if (n <= 0) break
                    sink.write(buffer, 0, n)
                    read += n
                    emit(Event.Progress(read, total))
                }
                sink.flush()
            } finally {
                sink.close()
                source.close()
            }
            // Atomic rename — only show "Done" if the move succeeds.
            if (!tmp.renameTo(destFile)) {
                emit(Event.Failed("Failed to move temp file to ${destFile.absolutePath}"))
                return@flow
            }
            emit(Event.Done(destFile))
        }
    }.flowOn(Dispatchers.IO)

    private const val BUF_SIZE = 1 shl 16  // 64 KB
}
