package com.example.gemmalitertlm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gemmalitertlm.download.ModelDownloader
import com.example.gemmalitertlm.viewmodel.ChatViewModel

private val SAMPLE_PROMPTS = listOf(
    "Briefly explain what an LLM KV cache is.",
    "Write a haiku about on-device AI.",
    "Translate to French: 'The Pixel 8 runs Gemma on its GPU.'",
    "Summarize the difference between LoRA and full fine-tuning in two sentences.",
    "List three reasons to run AI on-device instead of in the cloud.",
)

private val SAMPLE_LONG_PROMPT = """
You are a careful assistant that answers questions about a fictional company called Cactus Robotics.

Cactus Robotics, founded in 2024 in Lagos, builds low-power household robots running on-device LLMs.
Its flagship product is the CR-1, a 2 kg domestic helper with a 6-hour battery.
The company has 47 employees and is funded by Spore Capital and Greenhouse Ventures.
Customer support hours are 9:00–18:00 WAT, Monday through Friday.
Returns are accepted within 14 days of purchase with original packaging.
Firmware updates roll out every second Tuesday of the month at 02:00 WAT.

When answering, cite the relevant fact verbatim. If a question is unrelated to Cactus Robotics, say "I don't have that information."
""".trimIndent()

@Composable
fun AppRoot(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsState()

    if (!state.engineReady) {
        SetupScreen(viewModel, state)
    } else {
        ChatScreen(viewModel, state)
    }
}

// ---- Setup ------------------------------------------------------------------

@Composable
private fun SetupScreen(viewModel: ChatViewModel, state: ChatViewModel.UiState) {
    val context = LocalContext.current
    var hfToken by remember { mutableStateOf("") }
    var urlField by remember { mutableStateOf(ModelDownloader.DEFAULT_URL) }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importPickedFile(uri)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Gemma 4 on LiteRT-LM",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "MediaPipe LLM Inference (the Kotlin entry point to LiteRT-LM) running Gemma 4 E2B on-device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            // Step 1: model file
            Text("1. Model file", style = MaterialTheme.typography.titleLarge)
            if (state.modelPresent) {
                AssistChip(onClick = {}, label = { Text("✓ Model present (${state.modelSizeMb} MB)") })
            } else {
                Text("No model file at the expected location yet.", style = MaterialTheme.typography.bodyMedium)
            }

            Text("Option A — sideload a .task file you downloaded yourself:",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.openHfPage { context.startActivity(it) }
                }) {
                    Text("Open HF page")
                }
                Button(onClick = {
                    pickFileLauncher.launch(arrayOf("*/*"))
                }) {
                    Text("Pick .task file")
                }
            }

            Text("Option B — download in-app with your Hugging Face token:",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hfToken,
                onValueChange = { hfToken = it },
                label = { Text("HF token (hf_xxx…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.downloadModel(urlField, hfToken) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download")
            }

            HorizontalDivider()

            // Step 2: initialize engine
            Text("2. Initialize engine", style = MaterialTheme.typography.titleLarge)
            Text("Loads ~1.5 GB into memory. Takes ~5–20 s on Pixel 8.",
                style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { viewModel.initializeEngine() },
                enabled = state.modelPresent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Initialize")
            }

            HorizontalDivider()

            Text("Status", style = MaterialTheme.typography.titleLarge)
            Text(state.status, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---- Chat -------------------------------------------------------------------

@Composable
private fun ChatScreen(viewModel: ChatViewModel, state: ChatViewModel.UiState) {
    var input by remember { mutableStateOf("") }
    var cacheName by remember { mutableStateOf("") }
    var selectedCacheId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Gemma 4 E2B · LiteRT-LM", style = MaterialTheme.typography.titleLarge)
                    Text(state.status, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Cache controls
                    if (state.cachesOnDisk.isNotEmpty()) {
                        Text("Prompt cache (file.bin equivalent):",
                            style = MaterialTheme.typography.bodyMedium)
                        LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                            items(state.cachesOnDisk) { c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AssistChip(
                                        onClick = {
                                            selectedCacheId = if (selectedCacheId == c.id) null else c.id
                                        },
                                        leadingIcon = { Icon(Icons.Default.Memory, null) },
                                        label = {
                                            Text("${c.name} (~${c.tokenCountHint} tok)" +
                                                if (selectedCacheId == c.id) " ✓" else "")
                                        },
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(onClick = { viewModel.deleteCache(c.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete cache")
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No prompt caches yet. Tap 'Cache long prompt' to store the demo system prompt.",
                            style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cacheName,
                            onValueChange = { cacheName = it },
                            label = { Text("Cache name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            val name = cacheName.ifBlank { "demo" }
                            viewModel.cacheCurrentPrompt(name, SAMPLE_LONG_PROMPT)
                            cacheName = ""
                        }) {
                            Text("Cache long prompt")
                        }
                    }

                    // Sample prompts row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        SAMPLE_PROMPTS.forEach { p ->
                            AssistChip(
                                onClick = { input = p },
                                label = { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }

                    // Input row
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Prompt") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                        )
                        FilledIconButton(onClick = {
                            if (input.isNotBlank()) {
                                viewModel.send(input.trim(), selectedCacheId)
                                input = ""
                            }
                        }) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                        FilledTonalIconButton(onClick = { viewModel.stop() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                    Text(
                        text = if (selectedCacheId == null)
                            "Mode: cold (fresh session each turn)"
                        else "Mode: cached (cloning session '${state.cachesOnDisk.firstOrNull { it.id == selectedCacheId }?.name}')",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(state.messages) { m ->
                MessageBubble(m)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatViewModel.Message) {
    val isUser = m.role == ChatViewModel.Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp),
        ) {
            Text(
                text = m.text.ifEmpty { "…" },
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
