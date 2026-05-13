package com.example.gemmalitertlm.inference

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.UUID

/**
 * "Prompt cache" — stores system prompts / long context for reuse across conversations.
 *
 * With the old MediaPipe API, this used `cloneSession()` to fork KV cache state.
 * With the new LiteRT-LM API, conversations are self-contained. We store the prompt
 * text and recreate conversations with the same system instruction + initial messages.
 *
 * File layout under [cacheDir]:
 *   prompt_cache_index.tsv   — one entry per line, tab-separated:
 *                              id \t name \t tokenCountHint \t createdAtMs \t bytes
 *   <id>.bin                 — the raw prompt text (UTF-8)
 */
class PromptCacheManager(private val cacheDir: File) {

    data class Entry(
        val id: String,
        var name: String,
        val promptText: String,
        val tokenCountHint: Int,
        val createdAtMs: Long,
    )

    private val entries: MutableMap<String, Entry> = linkedMapOf()
    private val indexFile = File(cacheDir, INDEX_FILE)

    init {
        cacheDir.mkdirs()
        loadIndex()
    }

    /** All cached entries, in insertion order. */
    fun list(): List<Entry> = entries.values.toList()

    fun get(id: String): Entry? = entries[id]

    /**
     * Persists a new cache entry to disk.
     */
    fun save(
        name: String,
        promptText: String,
        tokenCountHint: Int,
    ): Entry {
        val id = UUID.randomUUID().toString().take(8)
        val entry = Entry(
            id = id,
            name = name.ifBlank { "cache_$id" },
            promptText = promptText,
            tokenCountHint = tokenCountHint,
            createdAtMs = System.currentTimeMillis(),
        )
        entries[id] = entry
        binFile(id).writeText(promptText, Charsets.UTF_8)
        saveIndex()
        return entry
    }

    /**
     * Creates a conversation pre-loaded with the cached prompt as system instruction.
     * The caller gets a ready-to-use conversation that already has the long context.
     */
    fun createConversationFromCache(
        id: String,
        engine: LlmEngine,
    ): Conversation? {
        val entry = entries[id] ?: return null
        return engine.createConversation(
            systemInstruction = entry.promptText,
        )
    }

    fun delete(id: String) {
        entries.remove(id)?.also {
            binFile(id).delete()
        }
        saveIndex()
    }

    fun deleteAll() {
        list().forEach { delete(it.id) }
    }

    // ---- persistence ---------------------------------------------------------

    private fun binFile(id: String) = File(cacheDir, "$id.bin")

    private fun loadIndex() {
        if (!indexFile.exists()) return
        indexFile.useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size < 5) continue
                val id = parts[0]
                val bin = binFile(id)
                if (!bin.exists()) continue
                val text = bin.readText(Charsets.UTF_8)
                entries[id] = Entry(
                    id = id,
                    name = parts[1],
                    promptText = text,
                    tokenCountHint = parts[2].toIntOrNull() ?: 0,
                    createdAtMs = parts[3].toLongOrNull() ?: 0L,
                )
            }
        }
    }

    private fun saveIndex() {
        val lines = entries.values.joinToString("\n") { e ->
            listOf(
                e.id,
                e.name.replace('\t', ' '),
                e.tokenCountHint.toString(),
                e.createdAtMs.toString(),
                binFile(e.id).length().toString(),
            ).joinToString("\t")
        }
        indexFile.writeText(lines, Charsets.UTF_8)
    }

    companion object {
        private const val INDEX_FILE = "prompt_cache_index.tsv"
    }
}
