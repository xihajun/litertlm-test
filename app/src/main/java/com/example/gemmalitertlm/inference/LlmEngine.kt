package com.example.gemmalitertlm.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Wrapper around MediaPipe `LlmInference` — the public Kotlin entry point to the
 * LiteRT-LM C++ engine that ships inside `com.google.mediapipe:tasks-genai`.
 *
 * Lifecycle:
 *   - One [LlmEngine] per app process (the base model is huge; loading it twice
 *     would OOM most phones).
 *   - Many [LlmInferenceSession] objects per engine. Sessions hold the KV cache.
 *   - Call [createSession] for a cold session. Call [PromptCacheManager.clone]
 *     to fork a warm (pre-filled) session.
 *
 * GPU vs CPU: MediaPipe defaults to GPU when the device supports OpenCL.
 * On Pixel 8 (Tensor G3, Mali-G715) this means we run on the GPU.
 * The dedicated TPU on Tensor G3 is **not** accessible through this API —
 * it's reserved for system AICore.
 */
class LlmEngine(
    private val appContext: Context,
    private val modelFile: File,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    private var llm: LlmInference? = null

    @Volatile var isReady: Boolean = false
        private set

    @Synchronized
    fun initialize() {
        if (isReady) return
        require(modelFile.exists()) { "Model file does not exist: ${modelFile.absolutePath}" }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(maxTokens)
            // cloneSession() requires an OpenCL-backed (GPU) session. The default may
            // pick CPU on some devices, which silently disables session cloning, so we
            // pin to GPU explicitly. If the device lacks OpenCL, createFromOptions
            // will throw — caller surfaces that to the UI.
            .setPreferredBackend(LlmInference.Backend.GPU)
            .build()
        llm = LlmInference.createFromOptions(appContext, options)
        isReady = true
    }

    fun createSession(
        topK: Int = 40,
        topP: Float = 0.95f,
        temperature: Float = 0.8f,
    ): LlmInferenceSession {
        val inference = checkNotNull(llm) { "Engine not initialized" }
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTopP(topP)
            .setTemperature(temperature)
            .build()
        return LlmInferenceSession.createFromOptions(inference, sessionOptions)
    }

    /**
     * Streams tokens from the given (already prefilled or fresh) session.
     * Caller is responsible for the session lifecycle.
     */
    fun generateStream(session: LlmInferenceSession, prompt: String): Flow<TokenEvent> = callbackFlow {
        // Add the user prompt to the session's running context.
        session.addQueryChunk(prompt)

        val prefillStart = System.nanoTime()
        var firstTokenAt: Long = 0
        var tokenCount = 0
        val accumulated = StringBuilder()

        session.generateResponseAsync { partialResult: String, done: Boolean ->
            val now = System.nanoTime()
            if (firstTokenAt == 0L) {
                firstTokenAt = now
                trySend(TokenEvent.Prefill(elapsedMs = (now - prefillStart) / 1_000_000.0))
            }
            // MediaPipe's listener is called with INCREMENTAL chunks, not cumulative text.
            // Accumulate so the UI receives the full response-so-far on every emission.
            accumulated.append(partialResult)
            tokenCount++
            trySend(TokenEvent.Token(accumulated.toString()))
            if (done) {
                val totalMs = (now - firstTokenAt) / 1_000_000.0
                trySend(TokenEvent.Done(tokens = tokenCount, decodeMs = totalMs))
                close()
            }
        }

        awaitClose { /* session is managed by the caller (cache may keep it alive) */ }
    }

    @Synchronized
    fun close() {
        runCatching { llm?.close() }
        llm = null
        isReady = false
    }

    companion object {
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

/** Streaming events surfaced to UI. */
sealed class TokenEvent {
    /** Fired once after the first token; measures prefill latency. */
    data class Prefill(val elapsedMs: Double) : TokenEvent()
    /** A partial decoded chunk (MediaPipe streams cumulative or incremental text — we forward as-is). */
    data class Token(val text: String) : TokenEvent()
    /** Generation complete. */
    data class Done(val tokens: Int, val decodeMs: Double) : TokenEvent()
}
