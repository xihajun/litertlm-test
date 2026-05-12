package com.example.gemmalitertlm.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.gemmalitertlm.download.ModelDownloader
import com.example.gemmalitertlm.inference.LlmEngine
import com.example.gemmalitertlm.inference.PromptCacheManager
import com.example.gemmalitertlm.inference.TokenEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    // -- Files --------------------------------------------------------------
    private val modelDir: File = File(getApplication<Application>().filesDir, "models")
        .apply { mkdirs() }
    private val modelFile: File = File(modelDir, "gemma-4-e2b.task")
    private val cacheDir: File = File(getApplication<Application>().filesDir, "prompt_caches")
        .apply { mkdirs() }

    // -- Core --------------------------------------------------------------
    private var engine: LlmEngine? = null
    private val cacheManager: PromptCacheManager = PromptCacheManager(cacheDir)

    // -- UI state ----------------------------------------------------------
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    init {
        refreshSetupState()
    }

    // -- Setup -------------------------------------------------------------

    fun refreshSetupState() {
        _uiState.update {
            it.copy(
                modelPresent = modelFile.exists() && modelFile.length() > 0,
                modelSizeMb = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0,
                cachesOnDisk = cacheManager.list().map { e -> CacheUi(e.id, e.name, e.tokenCountHint, warm = e.session != null) },
            )
        }
    }

    fun openHfPage(launchIntent: (Intent) -> Unit) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent(intent)
    }

    /** Called when the user picks a .task file from the system picker. */
    fun importPickedFile(uri: android.net.Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(status = "Copying model file…") }
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open input stream for $uri")
                input.use { stream ->
                    modelFile.outputStream().use { out ->
                        stream.copyTo(out, bufferSize = 1 shl 16)
                    }
                }
                _uiState.update { it.copy(status = "Model imported.") }
                refreshSetupState()
            } catch (t: Throwable) {
                _uiState.update { it.copy(status = "Import failed: ${t.message}") }
            }
        }
    }

    fun downloadModel(url: String, hfToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ModelDownloader.download(url, hfToken.takeIf { it.isNotBlank() }, modelFile).collect { ev ->
                when (ev) {
                    is ModelDownloader.Event.Progress -> {
                        val pct = if (ev.total > 0) (100.0 * ev.bytesRead / ev.total).toInt() else 0
                        _uiState.update { it.copy(status = "Downloading… ${ev.bytesRead / (1024 * 1024)} MB ($pct%)") }
                    }
                    is ModelDownloader.Event.Done -> {
                        _uiState.update { it.copy(status = "Download complete: ${ev.file.name}") }
                        refreshSetupState()
                    }
                    is ModelDownloader.Event.Failed -> {
                        _uiState.update { it.copy(status = "Download failed: ${ev.message}") }
                    }
                }
            }
        }
    }

    fun initializeEngine() {
        if (engine?.isReady == true) {
            _uiState.update { it.copy(status = "Engine already ready.", engineReady = true) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(status = "Loading Gemma 4 E2B into LiteRT-LM…") }
                val e = LlmEngine(getApplication(), modelFile).also { it.initialize() }
                engine = e
                _uiState.update { it.copy(status = "Engine ready.", engineReady = true) }
                // Warm up any caches that were persisted across launches.
                cacheManager.list().forEach { entry ->
                    if (entry.session == null) {
                        cacheManager.warmUp(entry, e) { msg ->
                            _uiState.update { it.copy(status = msg) }
                        }
                    }
                }
                refreshSetupState()
            } catch (t: Throwable) {
                _uiState.update { it.copy(status = "Init failed: ${t.message}") }
            }
        }
    }

    // -- Inference ---------------------------------------------------------

    fun send(prompt: String, useCacheId: String?) {
        val e = engine
        if (e == null || !e.isReady) {
            _uiState.update { it.copy(status = "Engine not ready.") }
            return
        }
        if (prompt.isBlank()) return

        generationJob?.cancel()
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            // Pick session: either a clone of a cached pre-filled session,
            // or a fresh empty session.
            val (session, isClone) = if (useCacheId != null) {
                val cloned = cacheManager.cloneSession(useCacheId)
                if (cloned != null) cloned to true else e.createSession() to false
            } else {
                e.createSession() to false
            }

            appendMessage(role = Role.User, text = prompt)
            appendMessage(role = Role.Assistant, text = "")  // placeholder we'll fill in

            try {
                e.generateStream(session, prompt).collect { ev ->
                    when (ev) {
                        is TokenEvent.Prefill -> {
                            val tag = if (isClone) "cached" else "cold"
                            _uiState.update {
                                it.copy(status = "Prefill ($tag): ${"%.0f".format(ev.elapsedMs)} ms")
                            }
                        }
                        is TokenEvent.Token -> {
                            updateLastAssistantMessage(ev.text)
                        }
                        is TokenEvent.Done -> {
                            val tps = if (ev.decodeMs > 0) (ev.tokens / (ev.decodeMs / 1000.0)) else 0.0
                            _uiState.update {
                                it.copy(status = it.status + " · decode: %.1f tok/s".format(tps))
                            }
                        }
                    }
                }
            } finally {
                // Only close the session if it's NOT the one stored in a cache —
                // closing a cached session would invalidate it for future clones.
                if (!isClone) runCatching { session.close() }
            }
        }
    }

    fun stop() {
        generationJob?.cancel()
        _uiState.update { it.copy(status = "Stopped.") }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    // -- Prompt cache -------------------------------------------------------

    fun cacheCurrentPrompt(name: String, promptText: String) {
        val e = engine ?: run {
            _uiState.update { it.copy(status = "Engine not ready — cannot cache.") }
            return
        }
        if (promptText.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(status = "Caching '$name' — prefilling…") }
            val session = e.createSession()
            session.addQueryChunk(promptText)
            // Approximate token count via 4 chars/token heuristic — fine for UI display.
            val tokenHint = (promptText.length / 4).coerceAtLeast(1)
            val entry = cacheManager.save(name = name, promptText = promptText,
                tokenCountHint = tokenHint, warmSession = session)
            _uiState.update { it.copy(status = "Cache '${entry.name}' saved (${tokenHint} tok est).") }
            refreshSetupState()
        }
    }

    fun deleteCache(id: String) {
        cacheManager.delete(id)
        refreshSetupState()
    }

    // -- Helpers ------------------------------------------------------------

    private fun appendMessage(role: Role, text: String) {
        _uiState.update { it.copy(messages = it.messages + Message(role, text)) }
    }

    private fun updateLastAssistantMessage(newText: String) {
        _uiState.update { st ->
            val msgs = st.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == Role.Assistant }
            if (idx >= 0) {
                msgs[idx] = msgs[idx].copy(text = newText)
            }
            st.copy(messages = msgs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        engine?.close()
    }

    // -- State types --------------------------------------------------------

    enum class Role { User, Assistant }
    data class Message(val role: Role, val text: String)
    data class CacheUi(val id: String, val name: String, val tokenCountHint: Int, val warm: Boolean)
    data class UiState(
        val status: String = "Idle",
        val modelPresent: Boolean = false,
        val modelSizeMb: Long = 0,
        val engineReady: Boolean = false,
        val messages: List<Message> = emptyList(),
        val cachesOnDisk: List<CacheUi> = emptyList(),
    )

    // -- Factory ------------------------------------------------------------

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(app) as T
        }
    }
}
