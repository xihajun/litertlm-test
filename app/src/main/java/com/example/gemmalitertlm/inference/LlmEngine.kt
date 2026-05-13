package com.example.gemmalitertlm.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.Closeable

/**
 * Wrapper around LiteRT-LM [Engine] — the current (non-deprecated) Google
 * on-device LLM inference SDK.
 *
 * Replaces the old MediaPipe `LlmInference` / `LlmInferenceSession` API.
 *
 * Lifecycle:
 *   - One [LlmEngine] per app process (the base model is huge; loading it twice
 *     would OOM most phones).
 *   - Use [createConversation] for a fresh chat session.
 *   - Conversations maintain their own KV cache and message history.
 */
class LlmEngine(
    private val modelPath: String,
    private val backend: Backend = Backend.GPU(),
    private val cacheDir: String? = null,
) : Closeable {

    private var engine: Engine? = null

    @Volatile
    var isReady: Boolean = false
        private set

    @Synchronized
    fun initialize() {
        if (isReady) return

        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir,
        )
        engine = Engine(config).also { it.initialize() }
        isReady = true
    }

    /**
     * Creates a new conversation with optional system instruction and sampler config.
     * Each conversation maintains its own KV cache and message history.
     */
    fun createConversation(
        systemInstruction: String? = null,
        topK: Int = 40,
        topP: Double = 0.95,
        temperature: Double = 0.8,
    ): Conversation {
        val e = checkNotNull(engine) { "Engine not initialized" }
        val convConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let {
                com.google.ai.edge.litertlm.Contents.of(it)
            },
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP,
                temperature = temperature,
            ),
        )
        return e.createConversation(convConfig)
    }

    /**
     * Streams tokens from a conversation for the given prompt.
     * Uses the coroutine Flow API for streaming.
     */
    fun generateStream(conversation: Conversation, prompt: String): Flow<TokenEvent> = callbackFlow {
        val prefillStart = System.nanoTime()
        var firstTokenAt: Long = 0
        var tokenCount = 0
        val accumulated = StringBuilder()

        conversation.sendMessageAsync(prompt).collect { token ->
            val now = System.nanoTime()
            if (firstTokenAt == 0L) {
                firstTokenAt = now
                trySend(TokenEvent.Prefill(elapsedMs = (now - prefillStart) / 1_000_000.0))
            }
            accumulated.append(token)
            tokenCount++
            trySend(TokenEvent.Token(accumulated.toString()))
        }

        val now = System.nanoTime()
        val totalMs = if (firstTokenAt > 0) (now - firstTokenAt) / 1_000_000.0 else 0.0
        trySend(TokenEvent.Done(tokens = tokenCount, decodeMs = totalMs))
        close()

        awaitClose { /* conversation lifecycle managed by caller */ }
    }

    @Synchronized
    override fun close() {
        runCatching { engine?.close() }
        engine = null
        isReady = false
    }
}

/** Streaming events surfaced to UI. */
sealed class TokenEvent {
    /** Fired once after the first token; measures prefill latency. */
    data class Prefill(val elapsedMs: Double) : TokenEvent()
    /** The full response text so far (accumulated). */
    data class Token(val text: String) : TokenEvent()
    /** Generation complete. */
    data class Done(val tokens: Int, val decodeMs: Double) : TokenEvent()
}
