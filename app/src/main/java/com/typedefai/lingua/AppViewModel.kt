package com.typedefai.lingua

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import com.typedefai.lingua.asr.AsrEngine
import com.typedefai.lingua.asr.PcmRecorder
import com.typedefai.lingua.dict.DictEntry
import com.typedefai.lingua.dict.DictRepository
import com.typedefai.lingua.dict.SavedWord
import com.typedefai.lingua.dict.WordbookRepository
import com.typedefai.lingua.lingua.Lang
import com.typedefai.lingua.lingua.TranslateMode
import com.typedefai.lingua.lingua.detectLang
import com.typedefai.lingua.lingua.EXPLAIN_TRANSLATION_SYSTEM
import com.typedefai.lingua.lingua.EXPLAIN_WORD_SYSTEM
import com.typedefai.lingua.lingua.TRANSLATE_SYSTEM
import com.typedefai.lingua.lingua.TUTOR_SYSTEM
import com.typedefai.lingua.lingua.TUTOR_WELCOME_SYSTEM
import com.typedefai.lingua.lingua.agent.AddToWordbookTool
import com.typedefai.lingua.lingua.agent.ListWordbookTool
import com.typedefai.lingua.lingua.agent.LookupWordTool
import com.typedefai.lingua.lingua.agent.PronounceWordTool
import com.typedefai.lingua.lingua.agent.RemoveFromWordbookTool
import com.typedefai.lingua.lingua.agent.ToolExecutor
import com.typedefai.lingua.lingua.agent.ToolResult
import com.typedefai.lingua.lingua.agent.TranslateTextTool
import com.typedefai.lingua.llm.LlmEngine
import com.typedefai.lingua.settings.AppSettings
import com.typedefai.lingua.settings.AudioSettings
import com.typedefai.lingua.settings.SettingsStore
import com.typedefai.lingua.tts.SpeechChunker
import com.typedefai.lingua.tts.SpeechEngine
import com.typedefai.lingua.tts.SystemTtsEngine
import com.typedefai.lingua.tts.TtsEngine
import com.typedefai.lingua.tts.TtsModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** High-level state of the voice loop (shared by the translate mic and the practice chat). */
enum class AssistantState {
  Idle,
  Listening,
  Transcribing,
  Thinking,
  Responding,
  Speaking,
}

/** Which surface the hold-to-talk mic feeds: the translate box or the practice chat. */
enum class MicTarget {
  Translate,
  Practice,
}

/** Bottom navigation destinations. */
enum class LinguaTab {
  Translate,
  Dictionary,
  Wordbook,
  Practice,
}

enum class ChatRole {
  User,
  Assistant,
}

/** Audit row for one tool invocation in the practice chat. */
data class ToolCallInfo(
  val name: String,
  val arguments: String,
  val resultJson: String? = null,
)

data class ChatMessage(
  val id: Long,
  val role: ChatRole,
  val text: String,
  val isError: Boolean = false,
  val isStreaming: Boolean = false,
  val isSpeaking: Boolean = false,
  val outputTokens: Int? = null,
  val decodeSpeedTps: Float? = null,
  val ttftMs: Long? = null,
  val toolCall: ToolCallInfo? = null,
)

/** State of the Translate tab. */
data class TranslateUiState(
  val mode: TranslateMode = TranslateMode.AUTO,
  val input: String = "",
  val output: String = "",
  val sourceLang: Lang? = null,
  val targetLang: Lang? = null,
  val busy: Boolean = false,
  val error: String? = null,
  val note: String? = null,
  val isSpeaking: Boolean = false,
  val autoSpeak: Boolean = true,
  val explanation: String = "",
  val explaining: Boolean = false,
)

/** State of the Dictionary tab. */
data class DictionaryUiState(
  val query: String = "",
  val entry: DictEntry? = null,
  val suggestions: List<DictEntry> = emptyList(),
  val chineseResults: List<DictEntry> = emptyList(),
  val searched: Boolean = false,
  val isSaved: Boolean = false,
  val explanation: String = "",
  val explaining: Boolean = false,
  val note: String? = null,
)

/**
 * Voice-first translator/dictionary view model.
 *
 * Owns the resident engines (ASR, Gemma, TTS), the bundled ECDICT dictionary, and the wordbook.
 * Translations are one-shot Gemma calls (no history); the practice chat keeps the persistent
 * LiteRT-LM conversation. TTS speaks each output in its own language: Kokoro's English voice for
 * English and `zf_xiaoxiao` for Chinese, falling back to the platform TTS when the active bundle
 * ships no Chinese voice.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

  private val app: Application = application
  private val recorder = PcmRecorder()
  private val settingsStore = SettingsStore(application)
  private val dict = DictRepository(application)
  private val wordbook = WordbookRepository(application)

  private val _settings = MutableStateFlow(settingsStore.load())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  /** Word/translation tools Gemma can call in the practice chat (manual tool loop). */
  private val wordTools = listOf(
    tool(LookupWordTool()),
    tool(AddToWordbookTool()),
    tool(RemoveFromWordbookTool()),
    tool(ListWordbookTool()),
    tool(TranslateTextTool()),
    tool(PronounceWordTool()),
  )

  private var asr = AsrEngine(application, _settings.value.audio.asrModel)
  private var llm = LlmEngine(
    application,
    _settings.value.llm,
    systemInstruction = TUTOR_SYSTEM,
    tools = wordTools,
  )
  private var tts: SpeechEngine = createTts(_settings.value.audio)
  private var zhTts: SystemTtsEngine? = null

  private var reloadJob: Job? = null
  private var translateJob: Job? = null
  private var explainJob: Job? = null
  private var dictJob: Job? = null
  private var turnJob: Job? = null
  private var welcomeJob: Job? = null

  private var micTarget = MicTarget.Translate

  private val _loadingStatus = MutableStateFlow("")
  val loadingStatus: StateFlow<String> = _loadingStatus.asStateFlow()

  private val _isReady = MutableStateFlow(false)
  val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

  // Translate tab.
  private val _translate = MutableStateFlow(TranslateUiState())
  val translate: StateFlow<TranslateUiState> = _translate.asStateFlow()

  // Dictionary tab.
  private val _dictionary = MutableStateFlow(DictionaryUiState())
  val dictionary: StateFlow<DictionaryUiState> = _dictionary.asStateFlow()

  // Wordbook tab.
  private val _savedWords = MutableStateFlow<List<SavedWord>>(emptyList())
  val savedWords: StateFlow<List<SavedWord>> = _savedWords.asStateFlow()

  // Practice chat.
  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _welcomeText = MutableStateFlow("")
  val welcomeText: StateFlow<String> = _welcomeText.asStateFlow()

  private val _state = MutableStateFlow(AssistantState.Idle)
  val state: StateFlow<AssistantState> = _state.asStateFlow()

  private val _tab = MutableStateFlow(LinguaTab.Translate)
  val tab: StateFlow<LinguaTab> = _tab.asStateFlow()

  private var nextMessageId = 0L

  init {
    ToolExecutor.init(dict, wordbook) { text, target -> translateForTool(text, target) }
    viewModelScope.launch {
      loadModels()
    }
  }

  /** One-shot translation used by the `translate_text` tool (runs on Gemma's dispatcher). */
  private suspend fun translateForTool(text: String, target: Lang): String? {
    val prompt = when (target) {
      Lang.ZH ->
        "Translate the following ${if (detectLang(text) == Lang.EN) "English" else "Chinese"} " +
          "text into Simplified Chinese:\n\n$text"
      Lang.EN -> "Translate the following Chinese text into English:\n\n$text"
    }
    return runCatching {
      llm.translate(TRANSLATE_SYSTEM, prompt, maxOutputTokens = 512) { }.text
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
  }

  // ---------------------------------------------------------------- startup

  /**
   * Loads the requested engines in parallel before dismissing the splash, then keeps them
   * resident. Called at startup and again after settings are saved (only for changed engines).
   */
  private suspend fun loadModels(
    loadAsr: Boolean = true,
    loadTts: Boolean = true,
    loadLlm: Boolean = true,
  ) {
    val start = SystemClock.elapsedRealtime()
    Log.d(TRACE, "model load start (asr=$loadAsr tts=$loadTts llm=$loadLlm)")
    if (!loadAsr && !loadTts && !loadLlm) {
      _loadingStatus.value = app.getString(R.string.loading_all_ready)
      _isReady.value = true
      return
    }

    val pending = buildList {
      if (loadAsr) add(_settings.value.audio.asrModel.title)
      if (loadTts) add(_settings.value.audio.ttsModel.title)
      if (loadLlm) add("Gemma 4 E2B")
    }
    _loadingStatus.value = app.getString(R.string.loading_models, pending.joinToString(", "))

    val done = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun markDone(label: String) {
      synchronized(done) { done += label }
      val ready = synchronized(done) { done.joinToString(", ") }
      _loadingStatus.value = app.getString(R.string.loading_progress, ready)
    }

    val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
    coroutineScope {
      val asrJob = if (loadAsr) {
        async {
          runCatching { asr.warmUp() }
            .onFailure { errors += "ASR: ${it.message ?: "failed to load"}" }
          markDone(_settings.value.audio.asrModel.title)
        }
      } else {
        null
      }
      val ttsJob = if (loadTts) {
        async {
          runCatching { tts.warmUp() }
            .onFailure { errors += "TTS: ${it.message ?: "failed to load"}" }
          markDone(_settings.value.audio.ttsModel.title)
        }
      } else {
        null
      }
      val gemmaJob = if (loadLlm) {
        async {
          runCatching { llm.warmUp() }
            .onFailure { errors += "Gemma: ${it.message ?: "failed to load"}" }
          markDone("Gemma 4 E2B")
        }
      } else {
        null
      }
      asrJob?.await()
      ttsJob?.await()
      gemmaJob?.await()
    }
    Log.d(TRACE, "model load done after ${SystemClock.elapsedRealtime() - start} ms")

    if (errors.isNotEmpty()) {
      addAssistantMessage(errors.joinToString(separator = "\n"), isError = true)
    }
    _loadingStatus.value = app.getString(R.string.loading_dict)
    runCatching { dict.warmUp() }
      .onFailure { Log.w(TRACE, "Dictionary warm-up failed", it) }

    _loadingStatus.value = app.getString(R.string.loading_all_ready)
    _isReady.value = true
    requestTutorWelcome()
  }

  private fun requestTutorWelcome() {
    welcomeJob?.cancel()
    welcomeJob = viewModelScope.launch {
      if (_messages.value.isNotEmpty()) return@launch
      val fallback = app.getString(R.string.tutor_welcome_fallback)
      _welcomeText.value = ""
      val generated = runCatching {
        llm.generateOneShotStreaming(
          systemInstruction = TUTOR_WELCOME_SYSTEM,
          userPrompt = "Greet the learner and invite them to chat in English.",
          maxOutputTokens = 96,
        ) { partial ->
          _welcomeText.value = partial.trim()
        }
      }.onFailure { Log.w(TRACE, "Tutor welcome generation failed", it) }.getOrNull()

      _welcomeText.value = generated?.trim()?.takeIf { it.isNotBlank() } ?: fallback
    }
  }

  // ---------------------------------------------------------------- settings

  /**
   * Persists [newSettings] and recreates only the engines whose configuration changed. The
   * loading screen returns with a per-stage status until the new configuration is resident.
   */
  fun applySettings(newSettings: AppSettings) {
    if (reloadJob?.isActive == true) return
    reloadJob = viewModelScope.launch {
      _isReady.value = false
      val watchdog = launch {
        delay(RELOAD_WATCHDOG_MS)
        if (!_isReady.value) {
          Log.e(TRACE, "settings reload watchdog fired")
          addAssistantMessage(
            app.getString(R.string.settings_reload_timeout),
            isError = true,
          )
          _loadingStatus.value = app.getString(R.string.loading_all_ready)
          _isReady.value = true
        }
      }
      try {
        val previous = _settings.value
        val asrChanged = previous.audio.asrModel != newSettings.audio.asrModel
        val ttsModelChanged = previous.audio.ttsModel != newSettings.audio.ttsModel
        val ttsVoiceChanged = previous.audio.ttsVoiceId != newSettings.audio.ttsVoiceId
        val llmChanged = previous.llm != newSettings.llm
        Log.d(
          TRACE,
          "settings save: asr=$asrChanged ttsModel=$ttsModelChanged " +
            "ttsVoice=$ttsVoiceChanged llm=$llmChanged",
        )
        if (!asrChanged && !ttsModelChanged && !ttsVoiceChanged && !llmChanged) {
          _loadingStatus.value = app.getString(R.string.loading_all_ready)
          return@launch
        }

        _loadingStatus.value = app.getString(R.string.loading_stopping)
        interruptSpeech()
        stopActiveJobs()
        runCatching { recorder.stop() }
        _state.value = AssistantState.Idle

        _loadingStatus.value = app.getString(R.string.loading_saving)
        settingsStore.save(newSettings)
        _settings.value = newSettings

        if (asrChanged) {
          closeEngine("ASR") { asr.close() }
          asr = AsrEngine(app, newSettings.audio.asrModel)
        }
        if (ttsModelChanged) {
          closeEngine("TTS") { tts.close() }
          runCatching { zhTts?.close() }
          zhTts = null
          tts = createTts(newSettings.audio)
        } else if (ttsVoiceChanged) {
          // Voice is a per-utterance option; no need to tear down the MediaPipe task.
          tts.updateVoice(newSettings.audio.ttsVoiceId)
        }
        if (llmChanged) {
          closeEngine("Gemma") { llm.close() }
          llm = LlmEngine(app, newSettings.llm, systemInstruction = TUTOR_SYSTEM, tools = wordTools)
        }

        loadModels(
          loadAsr = asrChanged,
          loadTts = ttsModelChanged,
          loadLlm = llmChanged,
        )
      } catch (t: Throwable) {
        Log.e(TRACE, "settings reload failed", t)
        addAssistantMessage(
          "Settings reload failed: ${t.message ?: t::class.java.simpleName}",
          isError = true,
        )
      } finally {
        watchdog.cancel()
        _isReady.value = true
      }
    }
  }

  /** Factory so the settings sheet can swap Kokoro/Phonon/Pocket/System TTS. */
  private fun createTts(settings: AudioSettings): SpeechEngine =
    if (settings.ttsModel == TtsModel.System) {
      SystemTtsEngine(app)
    } else {
      TtsEngine(app, settings.ttsModel, settings.ttsVoiceId)
    }

  /** Closes one engine off the main thread and reports how long it took. */
  private suspend fun closeEngine(name: String, block: () -> Unit) {
    val start = SystemClock.elapsedRealtime()
    val ticker = viewModelScope.launch {
      while (isActive) {
        val seconds = (SystemClock.elapsedRealtime() - start) / 1_000
        _loadingStatus.value = app.getString(R.string.loading_closing_elapsed, name, seconds)
        delay(1_000)
      }
    }
    try {
      withContext(Dispatchers.Default) { runCatching(block) }
    } finally {
      ticker.cancel()
    }
    Log.d(TRACE, "$name closed in ${SystemClock.elapsedRealtime() - start} ms")
  }

  // ---------------------------------------------------------------- translate

  fun selectTab(tab: LinguaTab) {
    _tab.value = tab
    if (tab == LinguaTab.Wordbook) {
      refreshSavedWords()
    }
  }

  fun setTranslateMode(mode: TranslateMode) {
    _translate.update { it.copy(mode = mode) }
  }

  fun setAutoSpeak(enabled: Boolean) {
    _translate.update { it.copy(autoSpeak = enabled) }
  }

  fun updateTranslateInput(text: String) {
    _translate.update { it.copy(input = text, note = null) }
  }

  /** Translates the current input; the result streams in and (optionally) speaks. */
  fun translate() {
    val text = _translate.value.input.trim()
    if (text.isEmpty() || _translate.value.busy) return
    interruptSpeech()
    val (source, target) = _translate.value.mode.resolve(text)
    _translate.update {
      it.copy(
        busy = true,
        output = "",
        error = null,
        note = null,
        isSpeaking = false,
        explanation = "",
        explaining = false,
        sourceLang = source,
        targetLang = target,
      )
    }
    translateJob = viewModelScope.launch {
      val turnStart = SystemClock.elapsedRealtime()
      Log.d(TRACE, "translate start (${_translate.value.mode}, ${text.length} chars)")
      val speechQueue = Channel<String>(Channel.UNLIMITED)
      var spoke = false
      val speechJob = if (_translate.value.autoSpeak) {
        launch {
          speakFlow(speechQueue.receiveAsFlow(), target) {
            spoke = true
            _translate.update { it.copy(isSpeaking = true) }
          }
          _translate.update { it.copy(isSpeaking = false) }
        }
      } else {
        null
      }

      val prompt = when (target) {
        Lang.ZH ->
          "Translate the following ${if (source == Lang.EN) "English" else "Chinese"} text " +
            "into Simplified Chinese:\n\n$text"
        Lang.EN -> "Translate the following Chinese text into English:\n\n$text"
      }
      val chunker = SpeechChunker()
      var lastLength = 0
      val result = runCatching {
        llm.translate(TRANSLATE_SYSTEM, prompt, maxOutputTokens = 512) { partial ->
          val delta = if (partial.length > lastLength) partial.substring(lastLength) else ""
          lastLength = partial.length
          if (delta.isNotBlank()) {
            _translate.update { it.copy(output = partial.trim()) }
            chunker.append(delta).forEach { speechQueue.trySend(it) }
          }
        }
      }
      chunker.flush().takeIf { it.isNotBlank() }?.let { speechQueue.trySend(it) }
      speechQueue.close()

      val output = result.getOrNull()?.text?.trim().orEmpty()
      val error = result.exceptionOrNull()
      _translate.update {
        it.copy(
          busy = false,
          output = output,
          error = when {
            error is kotlinx.coroutines.CancellationException -> null
            error != null -> app.getString(R.string.llm_failed, error.message ?: "unknown error")
            output.isBlank() -> app.getString(R.string.no_response)
            else -> null
          },
        )
      }
      speechJob?.join()
      Log.d(
        TRACE,
        "translate finished after ${SystemClock.elapsedRealtime() - turnStart} ms " +
          "(chars=${output.length}, spoke=$spoke)",
      )
    }
  }

  /** Re-speaks the current translation result. */
  fun speakTranslation() {
    val text = _translate.value.output.trim()
    val lang = _translate.value.targetLang
    if (text.isEmpty() || lang == null || _translate.value.busy) return
    interruptSpeech()
    viewModelScope.launch {
      _translate.update { it.copy(isSpeaking = true) }
      speakFlow(text.asFlow(), lang) {}
      _translate.update { it.copy(isSpeaking = false) }
    }
  }

  /** Streams a short grammar/vocab explanation of the current translation. */
  fun explainTranslation() {
    val input = _translate.value.input.trim()
    val output = _translate.value.output.trim()
    if (input.isEmpty() || output.isEmpty() || _translate.value.explaining) return
    _translate.update { it.copy(explaining = true, explanation = "") }
    explainJob = viewModelScope.launch {
      val result = runCatching {
        llm.generateOneShotStreaming(
          systemInstruction = EXPLAIN_TRANSLATION_SYSTEM,
          userPrompt = "Sentence: ${input.take(240)}\nTranslation: ${output.take(240)}",
          maxOutputTokens = 256,
        ) { partial ->
          _translate.update { it.copy(explanation = partial.trim()) }
        }
      }
      val error = result.exceptionOrNull()
      if (error != null && error !is kotlinx.coroutines.CancellationException) {
        _translate.update {
          it.copy(explanation = app.getString(R.string.llm_failed, error.message ?: "error"))
        }
      }
      _translate.update { it.copy(explaining = false) }
    }
  }

  // ---------------------------------------------------------------- dictionary

  /** Looks a word up in the bundled dictionary; suggestions back the miss path. */
  fun lookupWord(query: String) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    dictJob?.cancel()
    _dictionary.update {
      it.copy(
        query = trimmed,
        entry = null,
        suggestions = emptyList(),
        chineseResults = emptyList(),
        searched = false,
        explanation = "",
        explaining = false,
        note = null,
      )
    }
    dictJob = viewModelScope.launch {
      val entry = runCatching { dict.lookupExact(trimmed) }.getOrNull()
      if (entry == null && _dictionary.value.query == trimmed) {
        // Miss: show prefix suggestions, plus Chinese-meaning matches for CJK queries.
        val suggestions = runCatching { dict.suggest(trimmed) }.getOrDefault(emptyList())
        val chineseResults =
          if (detectLang(trimmed) == Lang.ZH) {
            runCatching { dict.searchChinese(trimmed) }.getOrDefault(emptyList())
          } else {
            emptyList()
          }
        _dictionary.update {
          it.copy(
            entry = null,
            suggestions = suggestions,
            chineseResults = chineseResults,
            searched = true,
            isSaved = false,
            note = if (suggestions.isEmpty() && chineseResults.isEmpty()) {
              app.getString(R.string.dict_not_found)
            } else {
              null
            },
          )
        }
      } else {
        _dictionary.update {
          it.copy(
            entry = entry,
            searched = true,
            isSaved = entry != null && wordbook.contains(entry.word),
            note = null,
          )
        }
      }
    }
  }

  /** Speaks the dictionary headword in English. */
  fun speakWord(word: String) {
    val trimmed = word.trim()
    if (trimmed.isEmpty()) return
    interruptSpeech()
    viewModelScope.launch {
      speakFlow(trimmed.asFlow(), Lang.EN) {}
    }
  }

  /** Streams a Gemma explanation of the current dictionary entry. */
  fun explainWord() {
    val entry = _dictionary.value.entry ?: return
    if (_dictionary.value.explaining) return
    _dictionary.update { it.copy(explaining = true, explanation = "") }
    explainJob = viewModelScope.launch {
      val prompt = buildString {
        append("Word: ${entry.word}")
        if (entry.phonetic.isNotBlank()) append(" (${entry.phonetic})")
        append("\nDictionary definition: ${entry.translation.take(300)}")
      }
      val result = runCatching {
        llm.generateOneShotStreaming(
          systemInstruction = EXPLAIN_WORD_SYSTEM,
          userPrompt = prompt,
          maxOutputTokens = 256,
        ) { partial ->
          _dictionary.update { it.copy(explanation = partial.trim()) }
        }
      }
      val error = result.exceptionOrNull()
      if (error != null && error !is kotlinx.coroutines.CancellationException) {
        _dictionary.update {
          it.copy(explanation = app.getString(R.string.llm_failed, error.message ?: "error"))
        }
      }
      _dictionary.update { it.copy(explaining = false) }
    }
  }

  /** Saves (or re-stamps) the current entry into the wordbook. */
  fun saveCurrentWord() {
    val entry = _dictionary.value.entry ?: return
    wordbook.save(entry.word, entry.phonetic, entry.translation)
    _dictionary.update { it.copy(isSaved = true) }
    refreshSavedWords()
  }

  fun removeSavedWord(word: String) {
    wordbook.remove(word)
    _dictionary.update { it.copy(isSaved = !it.isSaved) }
    refreshSavedWords()
  }

  /** Opens a saved word in the dictionary tab. */
  fun openSavedWord(word: String) {
    _tab.value = LinguaTab.Dictionary
    lookupWord(word)
  }

  private fun refreshSavedWords() {
    _savedWords.value = runCatching { wordbook.list() }.getOrDefault(emptyList())
  }

  // ---------------------------------------------------------------- practice chat

  fun startListening(target: MicTarget) {
    // A mic press barge-ins on anything still running: speech, translation, or a chat reply.
    interruptCurrentTurn()
    _translate.update { it.copy(busy = false, isSpeaking = false) }
    if (!hasRecordPermission()) {
      if (target == MicTarget.Translate) {
        _translate.update { it.copy(note = app.getString(R.string.microphone_permission_required)) }
      } else {
        addAssistantMessage(
          app.getString(R.string.microphone_permission_required),
          isError = true,
        )
      }
      return
    }
    if (!runCatching { recorder.start() }.getOrDefault(false)) {
      if (target == MicTarget.Translate) {
        _translate.update { it.copy(note = app.getString(R.string.microphone_start_failed)) }
      } else {
        addAssistantMessage(app.getString(R.string.microphone_start_failed), isError = true)
      }
      return
    }
    micTarget = target
    _state.value = AssistantState.Listening
  }

  fun stopAndTranscribe() {
    if (_state.value != AssistantState.Listening) return
    _state.value = AssistantState.Transcribing

    turnJob = viewModelScope.launch {
      val audioResult = runCatching { recorder.stop() }
      if (!isActive) return@launch
      val samples = audioResult.getOrDefault(FloatArray(0))
      val target = micTarget
      if (samples.isEmpty()) {
        val error = audioResult.exceptionOrNull()
        val message = if (error != null) {
          app.getString(R.string.asr_failed, error.message ?: "audio capture failed")
        } else {
          app.getString(R.string.no_speech)
        }
        if (target == MicTarget.Translate) {
          _translate.update { it.copy(note = message) }
        } else {
          addAssistantMessage(message, isError = error != null)
        }
        _state.value = AssistantState.Idle
        return@launch
      }

      val result = runCatching { asr.transcribe(samples) }
      if (!isActive) return@launch
      val text = result.getOrNull()?.trim().orEmpty()
      if (text.isBlank()) {
        val error = result.exceptionOrNull()
        val message = if (error != null) {
          app.getString(R.string.asr_failed, error.message ?: "unknown error")
        } else {
          app.getString(R.string.no_speech)
        }
        if (target == MicTarget.Translate) {
          _translate.update { it.copy(note = message) }
        } else {
          addAssistantMessage(message, isError = error != null)
        }
        _state.value = AssistantState.Idle
        return@launch
      }

      when (target) {
        MicTarget.Translate -> {
          _translate.update { it.copy(input = text) }
          _state.value = AssistantState.Idle
          translate()
        }
        MicTarget.Practice -> {
          addUserMessage(text)
          generatePracticeReply(text)
        }
      }
    }
  }

  fun cancelListening() {
    if (_state.value != AssistantState.Listening) return
    // Keep the state non-Idle until the recorder has actually released the microphone.
    viewModelScope.launch {
      runCatching { recorder.stop() }
      _state.value = AssistantState.Idle
    }
  }

  fun practiceSend(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    if (_state.value != AssistantState.Idle) {
      if (
        _state.value == AssistantState.Speaking ||
          _state.value == AssistantState.Responding
      ) {
        interruptCurrentTurn()
      } else {
        return
      }
    }
    addUserMessage(trimmed)
    turnJob = viewModelScope.launch {
      generatePracticeReply(trimmed)
    }
  }

  fun stopSpeaking() {
    if (
      _state.value != AssistantState.Speaking &&
        !_translate.value.isSpeaking &&
        !_messages.value.any { it.isSpeaking }
    ) {
      return
    }
    Log.d(TRACE, "tap-to-stop requested")
    interruptCurrentTurn()
    _translate.update { it.copy(isSpeaking = false) }
  }

  fun newChat() {
    interruptSpeech()
    stopActiveJobs()
    viewModelScope.launch {
      runCatching { recorder.stop() }
      runCatching { llm.resetConversation() }
      _messages.value = emptyList()
      _state.value = AssistantState.Idle
      requestTutorWelcome()
    }
  }

  private suspend fun generatePracticeReply(prompt: String) = coroutineScope {
    val turnStart = SystemClock.elapsedRealtime()
    Log.d(TRACE, "practice reply start")
    _state.value = AssistantState.Thinking
    val assistantId = nextMessageId++
    _messages.update {
      it + ChatMessage(
        id = assistantId,
        role = ChatRole.Assistant,
        text = "",
        isStreaming = true,
      )
    }

    val speechQueue = Channel<String>(Channel.UNLIMITED)
    val speechJob = launch {
      Log.d(TRACE, "TTS job start")
      try {
        speakFlow(speechQueue.receiveAsFlow(), Lang.EN) {
          Log.d(TRACE, "first TTS audio after ${SystemClock.elapsedRealtime() - turnStart} ms")
          _state.value = AssistantState.Speaking
          setAssistantSpeaking(assistantId, true)
        }
        Log.d(TRACE, "TTS job finished after ${SystemClock.elapsedRealtime() - turnStart} ms")
      } catch (t: Throwable) {
        Log.w(TRACE, "TTS playback failed", t)
      } finally {
        setAssistantSpeaking(assistantId, false)
      }
    }

    val chunker = SpeechChunker()
    var lastPartialLength = 0
    var toolRounds = 0
    var lastToolResult: ToolResult? = null
    val result = runCatching {
      var current = llm.generate(prompt) { partial ->
        val clean = stripToolCallJson(partial)
        if (clean.isNotBlank()) {
          updateAssistant(assistantId, clean, isStreaming = true)
          _state.compareAndSet(AssistantState.Thinking, AssistantState.Responding)
        }
        // Feed the first pass to TTS only while it stays prose. The moment tool-call syntax
        // appears, stop the feed and drop the buffered text so no JSON is ever spoken.
        if (clean != partial) {
          toolRounds = -1 // signal: this pass turned into a tool call
          chunker.reset()
        }
        if (toolRounds >= 0) {
          val delta = if (partial.length >= lastPartialLength) {
            partial.substring(lastPartialLength)
          } else {
            partial
          }
          lastPartialLength = partial.length
          if (delta.isNotBlank()) {
            chunker.append(delta).forEach { speechQueue.trySend(it) }
          }
        }
      }
      if (toolRounds < 0) toolRounds = 0

      while (current.toolCalls.isNotEmpty() && toolRounds < MAX_TOOL_ROUNDS) {
        toolRounds++
        updateAssistant(assistantId, "", isStreaming = true)
        val responses = mutableListOf<Content>()
        for (call in current.toolCalls) {
          val params = org.json.JSONObject()
          for ((key, value) in call.arguments) {
            params.put(key, value)
          }
          val argsJson = params.toString()
          val toolResult = withContext(Dispatchers.IO) {
            ToolExecutor.execute(call.name, params)
          }
          lastToolResult = toolResult
          applyToolSideEffects(toolResult, speechQueue)
          val resultJson = ToolExecutor.toolResponseJson(toolResult)
          _messages.update {
            it + ChatMessage(
              id = nextMessageId++,
              role = ChatRole.Assistant,
              text = "",
              toolCall = ToolCallInfo(
                name = call.name,
                arguments = argsJson,
                resultJson = resultJson,
              ),
            )
          }
          responses += Content.ToolResponse(call.name, resultJson)
        }

        var lastCleanLength = 0
        current = llm.sendToolResponse(Contents.of(responses)) { partial ->
          val clean = stripToolCallJson(partial)
          val delta = if (clean.length >= lastCleanLength) {
            clean.substring(lastCleanLength)
          } else {
            clean
          }
          lastCleanLength = clean.length
          if (delta.isNotBlank()) {
            updateAssistant(assistantId, clean.trim(), isStreaming = true)
            _state.compareAndSet(AssistantState.Thinking, AssistantState.Responding)
            chunker.append(delta).forEach { speechQueue.trySend(it) }
          }
        }
      }
      current to lastToolResult
    }

    val output = result.getOrNull()?.first
    var reply = stripToolCallJson(output?.text.orEmpty()).trim()
    if (reply.isBlank() && toolRounds > 0) {
      // The model produced no prose after the tool rounds: narrate deterministically.
      reply = fallbackToolNarration(lastToolResult)
      chunker.append(reply).forEach { speechQueue.trySend(it) }
    }
    chunker.flush().takeIf { it.isNotBlank() }?.let { speechQueue.trySend(it) }
    speechQueue.close()

    if (result.isSuccess && reply.isNotBlank()) {
      updateAssistant(
        id = assistantId,
        text = reply,
        isStreaming = false,
        outputTokens = output?.outputTokens?.takeIf { it > 0 },
        decodeSpeedTps = output?.decodeSpeedTps?.takeIf { it > 0.0 }?.toFloat(),
        ttftMs = output?.ttftMs?.takeIf { it > 0L },
      )
    } else {
      val error = result.exceptionOrNull()
      if (error !is kotlinx.coroutines.CancellationException) {
        updateAssistant(
          id = assistantId,
          text = if (error != null) {
            val message = error.message.orEmpty()
            if (message.contains("failed to parse tool calls", ignoreCase = true)) {
              app.getString(R.string.llm_tool_parse_error)
            } else {
              app.getString(R.string.llm_failed, message.ifBlank { "unknown error" })
            }
          } else {
            app.getString(R.string.no_response)
          },
          isError = error != null,
          isStreaming = false,
        )
      }
    }

    if (
      _state.value == AssistantState.Thinking ||
        _state.value == AssistantState.Responding
    ) {
      _state.value = AssistantState.Idle
    }

    speechJob.join()
    setAssistantSpeaking(assistantId, false)
    _state.value = AssistantState.Idle
    Log.d(
      TRACE,
      "practice turn finished after ${SystemClock.elapsedRealtime() - turnStart} ms " +
        "(chars=${reply.length}, tokens=${output?.outputTokens ?: 0})",
    )
  }

  // ---------------------------------------------------------------- tool plumbing

  /**
   * Visible effects of a tool call, applied while the tool loop is still running:
   * dictionary lookups open the Dictionary tab, translations fill the Translate tab,
   * pronunciations are queued into the running speech stream, and wordbook changes
   * refresh the saved-word list.
   */
  private suspend fun applyToolSideEffects(
    result: ToolResult,
    speechQueue: Channel<String>,
  ) {
    when (result) {
      is ToolResult.Lookup -> {
        val entry = result.entry
        if (entry != null) {
          _tab.value = LinguaTab.Dictionary
          _dictionary.update {
            it.copy(
              query = entry.word,
              entry = entry,
              suggestions = emptyList(),
              chineseResults = emptyList(),
              searched = true,
              isSaved = wordbook.contains(entry.word),
              explanation = "",
              explaining = false,
              note = null,
            )
          }
        }
      }
      is ToolResult.Translation -> {
        _tab.value = LinguaTab.Translate
        _translate.update {
          it.copy(
            mode = TranslateMode.AUTO,
            input = result.source,
            output = result.text,
            sourceLang = detectLang(result.source),
            targetLang = result.target,
            busy = false,
            error = null,
            note = null,
            isSpeaking = false,
            explanation = "",
            explaining = false,
          )
        }
      }
      is ToolResult.Pronounce -> {
        // Queued ahead of the narration so the word plays first, in the same audio stream.
        speechQueue.trySend(result.word)
      }
      is ToolResult.WordbookChanged,
      is ToolResult.WordbookList -> refreshSavedWords()
      else -> Unit
    }
  }

  /** Deterministic narration when Gemma ends a tool round with no prose. */
  private fun fallbackToolNarration(result: ToolResult?): String = when (result) {
    is ToolResult.Lookup -> {
      val entry = result.entry
      if (entry != null) {
        val senses = entry.translationLines.take(3).joinToString("; ")
        "${entry.word}: $senses"
      } else {
        "I couldn't find \"${result.word}\" in the dictionary. Please check the spelling."
      }
    }
    is ToolResult.WordbookChanged ->
      if (result.added) {
        "Added \"${result.word}\" to your wordbook."
      } else {
        "Removed \"${result.word}\" from your wordbook."
      }
    is ToolResult.WordbookList ->
      if (result.words.isEmpty()) {
        "Your wordbook is empty."
      } else {
        val preview = result.words.take(5).joinToString(", ") { it.word }
        "Your wordbook has ${result.words.size} words: $preview."
      }
    is ToolResult.Translation -> result.text
    is ToolResult.Pronounce -> "\"${result.word}\""
    is ToolResult.ValidationError -> result.message
    is ToolResult.Error -> result.message
    else -> "Done."
  }

  /** Cuts streamed text at the first tool-call marker so no tool JSON reaches the UI or TTS. */
  private fun stripToolCallJson(text: String): String {
    val markers = listOf(
      "<|",
      "```json",
      "*Tool call:",
      "Tool call:",
      "\"tool_calls\"",
      "\"name\":",
    )
    val cut = markers.map { marker -> text.indexOf(marker, ignoreCase = true) }
      .filter { it >= 0 }
      .minOrNull() ?: return text
    return text.substring(0, cut).trimEnd()
  }

  // ---------------------------------------------------------------- speech plumbing

  /**
   * Picks the engine and voice for [lang]: the MediaPipe bundle's own voices when available, and
   * the platform TTS as the Chinese fallback when the active bundle ships no Chinese voice.
   */
  private suspend fun speakerFor(lang: Lang): SpeechEngine {
    val model = _settings.value.audio.ttsModel
    return when {
      model == TtsModel.System -> {
        (tts as SystemTtsEngine).setLanguage(localeFor(lang))
        tts
      }
      lang == Lang.ZH && model.zhVoiceId == null -> {
        val engine = zhTts ?: SystemTtsEngine(app, localeFor(lang)).also { zhTts = it }
        engine.setLanguage(localeFor(lang))
        engine
      }
      else -> {
        val voice = when (lang) {
          Lang.EN -> _settings.value.audio.ttsVoiceId.ifBlank { model.voiceId }
          Lang.ZH -> model.zhVoiceId ?: model.voiceId
        }
        tts.updateVoice(voice)
        tts
      }
    }
  }

  private fun localeFor(lang: Lang): Locale =
    if (lang == Lang.ZH) Locale.forLanguageTag("zh-CN") else Locale.US

  /** Speaks a chunk flow in [lang] through the best available engine. Never throws. */
  private suspend fun speakFlow(chunks: Flow<String>, lang: Lang, onStarted: () -> Unit) {
    try {
      val engine = speakerFor(lang)
      engine.speakClauses(chunks, onStarted)
    } catch (t: Throwable) {
      Log.w(TRACE, "speech playback failed for ${lang.code}", t)
    }
  }

  // ---------------------------------------------------------------- helpers

  private fun updateAssistant(
    id: Long,
    text: String,
    isError: Boolean = false,
    isStreaming: Boolean = false,
    outputTokens: Int? = null,
    decodeSpeedTps: Float? = null,
    ttftMs: Long? = null,
  ) {
    _messages.update { current ->
      current.map { message ->
        if (message.id == id) {
          message.copy(
            text = text,
            isError = isError,
            isStreaming = isStreaming,
            outputTokens = outputTokens ?: message.outputTokens,
            decodeSpeedTps = decodeSpeedTps ?: message.decodeSpeedTps,
            ttftMs = ttftMs ?: message.ttftMs,
          )
        } else {
          message
        }
      }
    }
  }

  private fun addUserMessage(text: String) {
    _messages.update {
      it + ChatMessage(id = nextMessageId++, role = ChatRole.User, text = text)
    }
  }

  private fun addAssistantMessage(text: String, isError: Boolean = false) {
    _messages.update {
      it + ChatMessage(
        id = nextMessageId++,
        role = ChatRole.Assistant,
        text = text,
        isError = isError,
      )
    }
  }

  private fun setAssistantSpeaking(id: Long, speaking: Boolean) {
    _messages.update { current ->
      current.map { message ->
        if (message.id == id) {
          message.copy(isSpeaking = speaking)
        } else {
          message
        }
      }
    }
  }

  /** Stops speech and generation without touching the chat history. */
  private fun interruptCurrentTurn() {
    interruptSpeech()
    stopActiveJobs()
    _messages.update { current ->
      current.map { message ->
        if (message.isSpeaking) message.copy(isSpeaking = false) else message
      }
    }
    _state.value = AssistantState.Idle
  }

  private fun interruptSpeech() {
    tts.stop()
    runCatching { zhTts?.stop() }
  }

  private fun stopActiveJobs() {
    turnJob?.cancel()
    turnJob = null
    translateJob?.cancel()
    translateJob = null
    explainJob?.cancel()
    explainJob = null
  }

  private fun hasRecordPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
      app,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
  }

  override fun onCleared() {
    recorder.close()
    reloadJob?.cancel()
    runCatching { asr.close() }
    runCatching { llm.close() }
    runCatching { tts.close() }
    runCatching { zhTts?.close() }
    runCatching { dict.close() }
    runCatching { wordbook.close() }
    super.onCleared()
  }

  private companion object {
    const val TAG = "AppViewModel"
    const val TRACE = "VoiceTrace"
    const val RELOAD_WATCHDOG_MS = 90_000L
    const val MAX_TOOL_ROUNDS = 3
  }
}
