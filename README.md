# LiteRT-LM Gemma 4 — Android Demo

A Jetpack Compose Android app that runs **Gemma 4** locally on-device using
**Google AI Edge LiteRT-LM** with GPU acceleration.

## What's changed (v0.2.0)

Migrated from the **deprecated** `com.google.mediapipe:tasks-genai` to the
**current** `com.google.ai.edge.litertlm:litertlm-android` SDK.

| | Old (v0.1.x) | New (v0.2.0) |
|---|---|---|
| SDK | `com.google.mediapipe:tasks-genai` | `com.google.ai.edge.litertlm:litertlm-android` |
| Format | `.task` | `.litertlm` |
| API | `LlmInference` / `LlmInferenceSession` | `Engine` / `Conversation` |
| Status | Deprecated | Current, production-grade |

## Features

- **GPU-accelerated** inference via OpenCL
- **Streaming** token generation with real-time display
- **Prompt caching** — save long system prompts for instant reuse
- **Model download** from HuggingFace with token support
- **Sideload** `.litertlm` files via Android file picker
- Jetpack Compose UI with Material 3

## Requirements

- Android phone with arm64-v8a CPU (Android 8.0+ / API 26+)
- A `.litertlm` model file (e.g., from
  [`xihajun/gemma4-e4b-mixed-en-lora-r16-v6e-1536-litert-lm`](https://huggingface.co/xihajun/gemma4-e4b-mixed-en-lora-r16-v6e-1536-litert-lm)
  or [litert-community](https://huggingface.co/litert-community))

## Build

```bash
./gradlew :app:assembleDebug
```

Or use the GitHub Actions workflow to build remotely.

## Install & Run

```bash
adb install app/build/outputs/apk/debug/app-debug.apk

# Push model file directly:
adb push model.litertlm /data/data/com.example.gemmalitertlm/files/models/model.litertlm
```

## Architecture

```
├── MainActivity.kt          # Compose entry point
├── viewmodel/ChatViewModel.kt  # MVVM state management
├── inference/
│   ├── LlmEngine.kt         # LiteRT-LM Engine wrapper
│   └── PromptCacheManager.kt  # System prompt persistence
├── download/ModelDownloader.kt  # HuggingFace file download
└── ui/AppRoot.kt            # Compose UI (setup + chat screens)
```
