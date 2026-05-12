# gemma4-litertlm-android

A small Android app that runs **Google Gemma 4 E2B** on-device using the **LiteRT-LM** engine (via the public MediaPipe LLM Inference Kotlin API), and demonstrates a **prompt-cache** flow that mirrors `llama.cpp`'s `--prompt-cache file.bin` as closely as the public API allows.

> Why MediaPipe? On Android, the Kotlin/Java front-end to LiteRT-LM is `com.google.mediapipe:tasks-genai`. It bundles the LiteRT-LM C++ engine. Going to the raw LiteRT-LM C++ preview API requires the NDK and writing your own JNI — out of scope for a small demo app.

## What this app does

1. Helps you get the **Gemma 4 E2B `.task` file** onto your device (sideload via system file picker, or in-app HTTP download with a Hugging Face token).
2. Initializes a single shared `LlmInference` instance pointing at that file.
3. Lets you fire off multiple prompts — either as fresh "cold" sessions, or against a saved **prompt cache** that skips prefill via `session.cloneSession()`.
4. Persists prompt-cache entries to `filesDir/prompt_caches/` as one `.bin` file per cache.

## Prompt cache — what's actually happening

`llama.cpp` writes the entire KV-cache to disk as a blob and reads it back on the next run. **MediaPipe's public Kotlin API does not expose KV serialization** — only `LlmInferenceSession.cloneSession()` for in-memory forks of pre-filled sessions.

So this app implements the **same observable contract** at the cost of one prefill per app launch:

| | llama.cpp | This app (MediaPipe / LiteRT-LM) |
|---|---|---|
| `.bin` file stores | full KV cache (~MB-scale) | the **prompt text + metadata** (few KB) |
| First use of cache after app launch | reads KV from file — 0 prefill | reads text, runs prefill once — pays prefill once per process |
| Subsequent uses in same process | 0 prefill | 0 prefill (via `cloneSession`) |
| Survives app kill | yes | yes (text persists; prefill rebuilds the warm session) |

To get true zero-prefill across app restarts you'd need to drop down to the LiteRT-LM C++ preview API and serialize the `Session` state yourself. The `PromptCacheManager` class is structured so adding that later is a localized change.

## Repository layout

```
.
├── .github/workflows/build-apk.yml   # CI — builds debug APK on push
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/gemmalitertlm/
│       │   ├── MainActivity.kt
│       │   ├── inference/
│       │   │   ├── LlmEngine.kt          # wraps MediaPipe LLM Inference
│       │   │   └── PromptCacheManager.kt # the file.bin equivalent
│       │   ├── download/ModelDownloader.kt
│       │   ├── ui/AppRoot.kt             # all Compose screens
│       │   ├── ui/theme/*                # Material3 theme
│       │   └── viewmodel/ChatViewModel.kt
│       └── res/...
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew, gradlew.bat
└── README.md
```

> The `gradle-wrapper.jar` is **not** checked in. The GH Actions workflow regenerates it via `gradle wrapper --gradle-version 8.7`. Local devs: run that once and `./gradlew` will work.

## Build instructions

### Option 1 — push to GitHub, let CI build the APK (recommended)

```bash
git init && git add -A && git commit -m "Initial commit"
git remote add origin git@github.com:<you>/gemma4-litertlm-android.git
git push -u origin main
```

The `.github/workflows/build-apk.yml` will run on every push to `main`/`master` and on PR. Find the APK under **Actions → latest run → Artifacts → `gemma4-litertlm-debug-apk`**.

Tagging a `v*` tag (e.g. `git tag v0.1.0 && git push --tags`) also creates a GitHub Release with the APK attached.

### Option 2 — local build

You need Android SDK + JDK 17. Then:

```bash
gradle wrapper --gradle-version 8.7   # once, to generate gradle-wrapper.jar
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Using the app on a real device

1. Sideload the APK via `adb install app-debug.apk` or by transferring the file.
2. Open the app — you'll see the **Model setup** screen.
3. Get the Gemma 4 E2B `.task` file. Two paths:
   - **Sideload** (recommended for first run):
     1. Visit https://huggingface.co/litert-community in a browser, find a Gemma 4 E2B `.task` artifact (filename is roughly `gemma-4-E2B-it-int4.task`).
     2. Accept the Gemma license at the model card.
     3. Download via browser, then move the file to the device (Files app, `adb push`, etc.).
     4. In the app, tap **Pick .task file** and select it.
   - **In-app download**:
     1. Get a HF read-token at https://huggingface.co/settings/tokens.
     2. Paste it into the **HF token** field, leave the URL as default (or update if the path has changed), and tap **Download**.
4. Once the model is present, tap **Initialize**. This loads ~1.5 GB into memory and may take 5–20 s on a Pixel 8.
5. The chat screen opens. Try a few sample prompts.
6. To create a prompt cache: name it in the field next to **Cache long prompt**, then tap that button. The demo bakes a ~100-token system prompt for a fictional company; you can edit `SAMPLE_LONG_PROMPT` in `AppRoot.kt` to plug in your own.
7. The cache appears as a chip at the bottom. Tap it to "select" it for the next send — that send will use `cloneSession()` from the warm session.
8. The status line shows **prefill (cold) ~XXX ms** vs **prefill (cached) ~0–10 ms** so you can see the difference.

## Tested target

- Pixel 8 (Tensor G3, Mali-G715 MC7, 8 GB RAM, Android 14+)
- Should also work on any Android 9+ device with at least 6 GB RAM and a GPU that MediaPipe recognizes for OpenCL acceleration

## Notes / gotchas

- **TPU is not used.** Tensor G3's TPU is locked behind system AICore; MediaPipe LLM Inference runs on the GPU (or CPU fallback). Expect ~6–12 tokens/s decode on Pixel 8 with E2B int4.
- **Gemma is gated.** First-time HF download requires accepting the license on the model card with the account whose token you paste.
- **`.task` vs `.litertlm`.** Both are valid LiteRT-LM artifacts; recent MediaPipe versions accept either. The code passes the path through to `LlmInference.LlmInferenceOptions.setModelPath` which infers format from extension.
- **Memory.** Pixel 8 (non-Pro) has 8 GB; with the model + KV cache + system, you may see OOM at very long contexts. The default `maxTokens = 4096` keeps things safe.
- **MediaPipe version drift.** The `tasks-genai:0.10.20` API used here is stable, but newer minor versions occasionally rename methods. If `cloneSession()` or `addQueryChunk()` doesn't compile, check `LlmInferenceSession` in your installed AAR and update the call sites in `LlmEngine.kt` / `PromptCacheManager.kt`.

## What I'd extend next

- Replace the prompt-text `.bin` with a real KV serialization once LiteRT-LM C++ preview surfaces it through JNI.
- Hook up `setPreferredBackend(GPU)` / quantized KV cache flags explicitly once they ship in stable MediaPipe.
- Add a side-by-side "cold vs cached" timing chart so the prefill saving is visible at a glance.

## License

Project code: Apache 2.0. Gemma weights are governed by the [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
