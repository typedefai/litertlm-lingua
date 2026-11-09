package com.typedefai.lingua.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.typedefai.lingua.settings.LlmSettings
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class LlmResult(
  val text: String,
  val outputTokens: Int,
  val decodeSpeedTps: Double,
  val ttftMs: Long,
)

/**
 * LiteRT-LM wrapper for Gemma 4 E2B.
 *
 * Two modes:
 * - The persistent [Conversation] backs the English practice chat, so Gemma sees previous turns;
 *   [resetConversation] starts a new chat.
 * - One-shot calls ([translate], [generateOneShot], [generateOneShotStreaming]) run in fresh
 *   conversations with their own system instruction and sampler, so translations never touch the
 *   tutor history.
 *
 * All engine calls run on one dedicated thread; streaming callbacks arrive on LiteRT's native
 * thread.
 */
class LlmEngine(
  private val context: Context,
  private val settings: LlmSettings = LlmSettings.DEFAULT,
  private val modelFileName: String = DEFAULT_MODEL_FILE,
  private val systemInstruction: String = "",
) : AutoCloseable {

  private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "litert-llm").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private var engine: Engine? = null
  private var conversation: Conversation? = null

  /** Loads Gemma, initializes the native engine, and creates the first conversation. */
  suspend fun warmUp() {
    withContext(dispatcher) {
      ensureConversation()
    }
  }

  /**
   * Sends [prompt] to the persistent chat conversation and streams the assistant text through
   * [onUpdate]. The conversation survives across calls so Gemma sees the chat history.
   */
  @OptIn(ExperimentalApi::class)
  suspend fun generate(prompt: String, onUpdate: (String) -> Unit): LlmResult =
    withContext(dispatcher) {
      val activeConversation = ensureConversation()
      try {
        val startedAt = SystemClock.elapsedRealtime()
        var firstPartial = true
        val builder = StringBuilder()
        activeConversation.sendMessageAsync(Contents.of(prompt)).collect { message: Message ->
          val snapshot = synchronized(builder) {
            builder.append(message.toString())
            builder.toString()
          }
          if (snapshot.isNotBlank()) {
            if (firstPartial) {
              firstPartial = false
              Log.d(
                TRACE,
                "LLM first partial after ${SystemClock.elapsedRealtime() - startedAt} ms " +
                  "(${snapshot.length} chars)",
              )
            }
            runCatching { onUpdate(snapshot) }
          }
        }
        val text = synchronized(builder) { builder.toString() }
        val benchmark = runCatching { activeConversation.getBenchmarkInfo() }.getOrNull()
        Log.d(
          TRACE,
          "LLM generation complete after ${SystemClock.elapsedRealtime() - startedAt} ms " +
            "(chars=${text.length}, tokens=${benchmark?.lastDecodeTokenCount ?: 0}, " +
            "speed=${benchmark?.lastDecodeTokensPerSecond ?: 0.0})",
        )
        LlmResult(
          text = text,
          outputTokens = benchmark?.lastDecodeTokenCount ?: 0,
          decodeSpeedTps = benchmark?.lastDecodeTokensPerSecond ?: 0.0,
          ttftMs = ((benchmark?.timeToFirstTokenInSecond ?: 0.0) * 1_000.0).toLong(),
        )
      } catch (t: Throwable) {
        if (t is CancellationException) {
          runCatching { activeConversation.cancelProcess() }
        } else {
          runCatching { activeConversation.close() }
          if (conversation === activeConversation) {
            conversation = null
          }
        }
        throw t
      }
    }

  /**
   * Streams a one-shot translation in a fresh conversation with a greedy sampler (deterministic,
   * fast; translations are short enough that diversity only hurts).
   */
  @OptIn(ExperimentalApi::class)
  suspend fun translate(
    systemInstruction: String,
    userPrompt: String,
    maxOutputTokens: Int = 512,
    onUpdate: (String) -> Unit,
  ): LlmResult = withContext(dispatcher) {
    oneShotStreaming(
      systemInstruction = systemInstruction,
      contents = Contents.of(Content.Text(userPrompt)),
      maxOutputTokens = maxOutputTokens,
      sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
      onUpdate = onUpdate,
    )
  }

  /** Streams a one-shot text call in a fresh conversation (no chat history). */
  @OptIn(ExperimentalApi::class)
  suspend fun generateOneShotStreaming(
    systemInstruction: String,
    userPrompt: String,
    maxOutputTokens: Int = 512,
    onUpdate: (String) -> Unit,
  ): String? = withContext(dispatcher) {
    oneShotStreaming(
      systemInstruction = systemInstruction,
      contents = Contents.of(Content.Text(userPrompt)),
      maxOutputTokens = maxOutputTokens,
      sampler = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.8),
      onUpdate = onUpdate,
    ).text
  }

  @OptIn(ExperimentalApi::class)
  private suspend fun oneShotStreaming(
    systemInstruction: String,
    contents: Contents,
    maxOutputTokens: Int,
    sampler: SamplerConfig,
    onUpdate: (String) -> Unit,
  ): LlmResult {
    val activeEngine = ensureEngine()
    val created = activeEngine.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        samplerConfig = sampler,
        maxOutputToken = maxOutputTokens,
        thinkingConfig = ThinkingConfig(false),
      ),
    )
    try {
      val startedAt = SystemClock.elapsedRealtime()
      val builder = StringBuilder()
      created.sendMessageAsync(contents).collect { message: Message ->
        val snapshot = synchronized(builder) {
          builder.append(message.toString())
          builder.toString()
        }
        if (snapshot.isNotBlank()) {
          runCatching { onUpdate(snapshot) }
        }
      }
      val text = synchronized(builder) { builder.toString() }
      val benchmark = runCatching { created.getBenchmarkInfo() }.getOrNull()
      Log.d(
        TRACE,
        "LLM one-shot complete after ${SystemClock.elapsedRealtime() - startedAt} ms " +
          "(chars=${text.length}, tokens=${benchmark?.lastDecodeTokenCount ?: 0})",
      )
      LlmResult(
        text = text,
        outputTokens = benchmark?.lastDecodeTokenCount ?: 0,
        decodeSpeedTps = benchmark?.lastDecodeTokensPerSecond ?: 0.0,
        ttftMs = ((benchmark?.timeToFirstTokenInSecond ?: 0.0) * 1_000.0).toLong(),
      )
    } finally {
      runCatching { created.close() }
    }
  }

  /** Runs a one-shot text call in a fresh conversation (no chat history, no streaming). */
  suspend fun generateOneShot(
    systemInstruction: String,
    userPrompt: String,
    maxOutputTokens: Int = 160,
  ): String? = withContext(dispatcher) {
    val activeEngine = ensureEngine()
    val conversation = activeEngine.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        maxOutputToken = maxOutputTokens,
        thinkingConfig = ThinkingConfig(false),
      ),
    )
    try {
      val builder = StringBuilder()
      conversation.sendMessageAsync(Contents.of(Content.Text(userPrompt))).collect { message ->
        builder.append(message.toString())
      }
      builder.toString()
    } finally {
      runCatching { conversation.close() }
    }
  }

  /** Closes the current conversation so the next turn starts with a clean context. */
  suspend fun resetConversation() {
    withContext(dispatcher) {
      runCatching { conversation?.close() }
      conversation = null
    }
  }

  private fun ensureConversation(): Conversation {
    conversation?.let { return it }
    val created = ensureEngine().createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        samplerConfig = SamplerConfig(
          topK = settings.topK,
          topP = settings.topP.toDouble(),
          temperature = settings.temperature.toDouble(),
        ),
        maxOutputToken = settings.maxOutputTokens,
        thinkingConfig = ThinkingConfig(false),
      ),
    )
    conversation = created
    return created
  }

  private fun ensureEngine(): Engine {
    engine?.let { return it }
    val modelFile = findModelFile()
      ?: error(
        "Gemma model not found. Push $modelFileName to " +
          "${context.getExternalFilesDir(null)?.resolve("models")?.absolutePath}.",
      )
    val cacheDir = File(context.cacheDir, "litert-lm").apply { mkdirs() }

    val created = runCatching { createEngine(modelFile, cacheDir, Backend.GPU()) }
      .getOrElse { createEngine(modelFile, cacheDir, Backend.CPU()) }
    engine = created
    return created
  }

  @OptIn(ExperimentalApi::class)
  private fun createEngine(modelFile: File, cacheDir: File, backend: Backend): Engine {
    val config = EngineConfig(
      modelPath = modelFile.absolutePath,
      backend = backend,
      visionBackend = Backend.GPU(),
      audioBackend = Backend.CPU(),
      maxNumTokens = settings.maxContextTokens,
      maxNumImages = 1,
      cacheDir = cacheDir.absolutePath,
    )
    val created = Engine(config)
    try {
      // Keep LiteRT-LM's per-turn timing/token counters available for the message footer.
      ExperimentalFlags.enableBenchmark = true
      created.initialize()
    } catch (t: Throwable) {
      runCatching { created.close() }
      throw t
    }
    return created
  }

  private fun findModelFile(): File? {
    val dirs = listOfNotNull(
      context.getExternalFilesDir(null)?.resolve("models"),
      File(context.filesDir, "models"),
    )
    return dirs.asSequence()
      .map { File(it, modelFileName) }
      .firstOrNull { it.isFile }
  }

  override fun close() {
    runCatching {
      runBlocking(dispatcher) {
        runCatching { conversation?.close() }
        conversation = null
        engine?.close()
        engine = null
      }
    }
    dispatcher.close()
  }

  companion object {
    const val DEFAULT_MODEL_FILE = "gemma-4-E2B-it.litertlm"

    private const val TRACE = "VoiceTrace"
  }
}
