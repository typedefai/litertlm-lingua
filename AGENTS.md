# AGENTS.md

## What this is
Native Android **voice translator + dictionary** for learning English and translating to Chinese. Kotlin + Jetpack Compose, one `:app` module. Primary target device: **Xiaomi 14 Pro** (Snapdragon 8 Gen 3 / `SM8650`, Adreno 750, 12–16 GB RAM). Everything runs on-device; no network is used at runtime.

Pipeline (reused from the `litertlm-scaffold` voice assistant):

```
Mic (16 kHz mono) -> VAD-less ASR -> Gemma 4 E2B -> SpeechChunker -> TTS -> AudioTrack (24 kHz)
```

Four tabs: **Translate** (EN↔ZH voice/text, streamed, auto-spoken), **Dictionary** (bundled ECDICT subset), **Wordbook** (saved words), **Practice** (chat with Lily, an English-tutor persona).

## Architecture

- **Translate**: one-shot Gemma calls in fresh conversations (`LlmEngine.translate`, greedy sampler `topK=1 temp=0`). Source language is auto-detected by a CJK heuristic (`lingua/Language.kt`); `TranslateMode.AUTO` then targets the opposite language. The result streams into the result card and, when auto-speak is on, into the TTS through `SpeechChunker`. “Explain this sentence” streams a short grammar/vocab note (`EXPLAIN_TRANSLATION_SYSTEM`).
- **Dictionary**: `dict/DictRepository.kt` opens the SQLite built by `scripts/build_dict.py` (ECDICT top-60K by frequency, 12.5 MB, committed under `app/src/main/assets/databases/ecdict.db`; copied to filesDir on first run). Exact lookup is case-insensitive; misses show prefix suggestions and, for CJK queries, Chinese-meaning substring matches. “AI explain” streams `EXPLAIN_WORD_SYSTEM`.
- **Wordbook**: `dict/WordbookRepository.kt`, tiny `SQLiteOpenHelper` (`wordbook.db`). Star in the dictionary to save; rows open the entry, speak, or delete.
- **Practice**: the scaffold chat shell; Gemma keeps one persistent LiteRT-LM conversation with `TUTOR_SYSTEM`. Welcome line under the empty-state orb is a one-shot call (`TUTOR_WELCOME_SYSTEM`), display-only.
- **Voice mapping** (`AppViewModel.speakerFor`): English output uses the settings voice (Kokoro default `af_heart`); Chinese output uses the bundle's Chinese voice when it ships one (Kokoro `zf_xiaoxiao`, alt `zf_xiaobei`), else the platform `TextToSpeech` with `zh-CN` (`zhTts` fallback engine). Voice switches use `updateVoice`/`setLanguage` without reloading bundles. Phonon/Pocket have no Chinese voice and fall back to System TTS for Chinese.
- Prompts are fixed in `lingua/Prompts.kt`; there is no user-editable system prompt (Settings shows Model configs + Audio tabs only).
- `SpeechChunker` understands Chinese clause punctuation (。，！？；：、…) and cuts CJK runs at the limit (no word boundaries to respect); Latin text is still never cut mid-word.
- Startup: splash until ASR + TTS + Gemma are resident (~3.3 s), then dictionary warm-up, then ready. Per-stage loading status shown on the in-app loading screen; `VoiceTrace` logs the pipeline timings.

## Runtime split (do not guess this)
`.litertlm` is a **container format**, not a model type. The same extension is used by LLM, ASR, and TTS bundles, and different host APIs are required:

| Stage | Default model | Host API (Android/Kotlin) |
|---|---|---|
| LLM | `gemma-4-E2B-it.litertlm` | LiteRT-LM `Engine` / `Conversation` (`litertlm-android` 0.18.1) |
| ASR | `qwen3_asr_0.6b_5s_i8.litertlm` (settings default) | MediaPipe `AudioTranscriber` (`tasks-audio` 1.2.0) |
| TTS | `kokoro_int8_gpu_fp16.litertlm` (settings default) | MediaPipe `TextToSpeech` (`tasks-audio` 1.2.0) |

- `litertlm-android` has **no Kotlin ASR/TTS API**; speech support is MediaPipe `tasks-audio` only (or the C++ Omni API via JNI, which this app does not use).
- There is **no MediaPipe Translator task** for Android (text tasks are classifier/language detector/embedder/proofreader/summarizer). Translation goes through Gemma. A dedicated TranslateGemma 4B `.litertlm` exists on HF but is not used here.
- MediaPipe tasks accept a `cacheDirectory`; all three engines pass one so later loads are fast.

## Local models (`./models`, gitignored)
Push to the device after install; **keep original filenames** (MediaPipe detects the model type from the filename/bundle metadata):

```bash
adb shell mkdir -p /sdcard/Android/data/com.typedefai.lingua/files/models
adb push models/gemma-4-E2B-it.litertlm models/qwen3_asr_0.6b_5s_i8.litertlm models/kokoro_int8_gpu_fp16.litertlm /sdcard/Android/data/com.typedefai.lingua/files/models/
```

| File | Task | Notes |
|---|---|---|
| `gemma-4-E2B-it.litertlm` (2.4G) | LLM translate/tutor | GPU backend, CPU fallback |
| `qwen3_asr_0.6b_5s_i8.litertlm` (915M) | ASR, bilingual EN/ZH | CPU. Default; needed for Chinese voice input |
| `moonshine_tiny_5s_i8.litertlm` (29M) | ASR alternative | English-only, fastest |
| `parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm` (615M) | ASR alternative | English-only; not in `models/` by default |
| `kokoro_int8_gpu_fp16.litertlm` (289M) | TTS, EN+ZH voices | GPU only; `af_heart` EN, `zf_xiaoxiao` ZH |
| `phonon_int8.litertlm` (87M) | TTS alternative | CPU, EN only (ZH falls back to System TTS) |
| `pocket_tts_int8.litertlm` | TTS alternative | CPU, no ZH voice |
| `kokoro_int8_fp16.litertlm` | Ignored by the app | — |

System TTS lives in the platform TTS service process; the manifest `<queries>` block (`android.intent.action.TTS_SERVICE` + `com.google.android.tts`) is required for it to be visible on Android 11+.

## Dictionary data pipeline
`scripts/build_dict.py` (stdlib only, `curl` preferred for download) rebuilds the asset:
1. Downloads the ECDICT CSV (~66 MB, MIT, skywind3000/ECDICT).
2. Filters entries with a translation/definition, scores by frq/collins/oxford/bnc, keeps the top 60K.
3. Writes `app/src/main/assets/databases/ecdict.db` (`dict_entries` + `idx_dict_word_lc` + provenance `meta`).

The generated DB **is committed** (MIT data, needed for offline installs). Re-run the script to change the subset size (`--top N`). Column layout is fixed by ECDICT: `word, phonetic, definition, translation, pos, collins, oxford, tag, bnc, frq, exchange, detail, audio` (detail/audio are dropped). `DictRepository` must stay in sync with the script's schema.

## Toolchain baseline
Use the checked-in versions unless there is a concrete reason not to:

- Gradle 9.6.1 (wrapper), AGP 9.3.1, Kotlin 2.3.10. Run Gradle with JDK 21+ (verified locally with 25); compile/target Java 11.
- `compileSdk`/`targetSdk` 37, `minSdk` 31 (Android 12+).
- Compose BOM 2026.02.00 + Material 3; `core-ktx` 1.18.0, `activity-compose` 1.13.0, `lifecycle` 2.9.4.
- Call `enableEdgeToEdge()` and use dynamic color (minSdk 31).
- LiteRT-LM: `com.google.ai.edge.litertlm:litertlm-android:0.18.1` (Google Maven). 0.19.0 exists but still has no Kotlin speech API.
- MediaPipe audio: `com.google.mediapipe:tasks-audio:1.2.0`. Do **not** add `litert*` or `play-services-tflite-*` deps (accelerator registration is internal to the task JNI; the extra AARs conflict with `litert-api`).
- Keep `packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }` or the build fails on duplicate native libs.
- Native libs are 64-bit only (`arm64-v8a`, `x86_64`).
- Keep `-Xskip-metadata-version-check` in `kotlin.compilerOptions`; LiteRT-LM 0.18.1 pulls Kotlin 2.4 artifacts.
- No Room/KSP: both databases use raw `SQLiteOpenHelper`, matching the scaffold.

## Reference implementation (read-only)
`../gallery` is a checkout of google-ai-edge/gallery. Use it as the source of truth for API usage; never edit it from this repo.

- Gallery source root: `../gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/`
- `ui/llmchat/LlmChatModelHelper.kt` — LiteRT-LM Engine/Conversation lifecycle
- `services/asr/SpeechProcessorAsrService.kt` — MediaPipe `AudioTranscriber` usage
- `services/tts/MediaPipeTtsEngine.kt` — MediaPipe `TextToSpeech` usage
- Upstream fallback: https://github.com/google-ai-edge/gallery/tree/main/Android/src

Do not guess LiteRT-LM or MediaPipe APIs. Both are pre-1.0 and change between releases.

## LiteRT-LM / MediaPipe gotchas
- Pattern: `Engine(EngineConfig(modelPath = ..., backend = ..., maxNumTokens = ...))` then `engine.initialize()` then `engine.createConversation(ConversationConfig(...))`.
- Gemma 4 E2B: `visionBackend = Backend.GPU()`, `audioBackend = Backend.CPU()`; 32K max context, 4000 default max output tokens in Gallery's config. This app caps context at 4096.
- `engine.initialize()`/`close()` are blocking and can take seconds. Create/close off the main thread; close conversations before the engine.
- One-shot calls create a fresh `Conversation` per call and close it afterwards; the practice chat reuses one persistent conversation.
- Experimental flags (`ExperimentalFlags`) require `@OptIn(ExperimentalApi::class)`; `enableBenchmark` feeds the token/tok-s footer.
- MediaPipe TTS outputs 24 kHz mono float PCM; `AudioTrack` with `ENCODING_PCM_FLOAT`, sized ~0.5 s, rate from `AudioResult.sampleRateHz`.
- Voices are per model in `TtsModel`: Kokoro `af_heart` (EN) / `zf_xiaoxiao` (ZH, alt `zf_xiaobei`), Phonon `freya` (EN only), Pocket `alba` (no ZH). A voice the bundle does not ship fails synthesis — the app only routes ZH to the bundle when `zhVoiceId` is set.
- Kokoro's GPU delegate can silently fall back to CPU (first clause ~1.5 s, RTF ≈ 1.4 instead of ~0.12). MediaPipe gates GPU behind an internal "ML Drift compatibility list"; there is no Kotlin flag to force it.

## Device notes (Xiaomi 14 Pro)
- **GPU** backend for Gemma and Kokoro; **CPU** for ASR bundles.
- Resident set is roughly Gemma 3.0–3.5 GB + Qwen3-ASR ~1.2 GB + Kokoro ~0.4 GB. Keep exactly one ASR bundle loaded.
- Add `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and `libvndksupport.so` for the LiteRT-LM GPU backend.
- HyperOS: use a microphone foreground service, handle battery-optimization prompts, and do not assume Google Play Services or AICore. Avoid ML Kit ASR/TTS as the primary path.
- Half-duplex v1: the mic press barge-ins on speech (mute-while-speaking); AEC barge-in is a later milestone.
- Chinese system TTS availability depends on the device's installed TTS engines; when Kokoro is active this fallback is rarely used.

## Working agreement
- Do not run `./gradlew`, `adb install`, or on-device tests unless the user explicitly asks. The user builds and runs the app manually.

## Build / verify
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:installDebug
```

- `assembleDebug` and install/launch are verified on the Xiaomi 14 Pro.
- Use the checked-in wrapper, not a system `gradle`.
- CI parity: JDK 21 + SDK platform 37 (`platforms;android-37.0`), then `./gradlew assembleRelease`.
- The APK must contain no model file: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E '\.litertlm|\.task'` should print nothing (the 12.5 MB `assets/databases/ecdict.db` is expected and allowed).
- Translate acceptance: type or hold the mic → source fills → result streams → auto-speak starts in the target language (Chinese voice for ZH) → speak/copy/explain actions work; `自动` detects EN/ZH and targets the opposite language; `EN → 中文` and `中文 → EN` are fixed-direction.
- Dictionary acceptance: word lookup shows phonetic, POS/exam tags, inflections, Chinese senses, English definition; pronunciation speaks; star saves; “AI explain” streams; a miss shows suggestions; a Chinese query shows meaning matches.
- Wordbook acceptance: saved words persist across restarts; rows reopen the entry, speak, and delete.
- Practice acceptance: hold-to-talk or type → user bubble → “Thinking…” → streamed Lily reply → spoken in English while still generating; new-chat resets the conversation; the welcome line streams under the orb.
- Settings acceptance: saving recreates only the engines whose settings changed; the Audio tab offers ASR/TTS/voice pickers (missing bundles greyed as “Not downloaded”) and always offers `System TTS`.
- TTS chunking: `SpeechChunker` emits the first chunk at clause punctuation or 16 chars, later chunks at punctuation or 110 chars; Latin never splits mid-word, CJK may split at the limit.
- If TTS appears to wait for the full reply, check `VoiceTrace`: `TTS loaded <file> with <delegate>`, `LLM first partial`, `first TTS audio`, per-clause synth/write lines, `composer state`, `turn finished`. The official sample reports Kokoro GPU RTF ≈ 0.12; ~1.5 s per short clause means the vocoder is on CPU.
- Models must be at `/sdcard/Android/data/com.typedefai.lingua/files/models/` (see the push command above).
- Kokoro follows the official sample: `speakingRate = 1.0f`, `targetBucket = 64`. The playback sink mirrors the sample: one streaming `AudioTrack`, `stop()` pauses + flushes for barge-in.
