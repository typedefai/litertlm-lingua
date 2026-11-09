package com.typedefai.lingua.tts

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Android platform TTS (`android.speech.tts.TextToSpeech`) as the low-latency speech option.
 *
 * Synthesis happens inside the system TTS service process, so it is not starved by Gemma's decode
 * threads the way Kokoro's CPU acoustic stage is. Utterances are queued with
 * [TextToSpeech.QUEUE_ADD] so streamed clauses play back-to-back without gaps.
 */
class SystemTtsEngine(
  context: Context,
  private val defaultLocale: Locale = Locale.US,
) : SpeechEngine {

  private val appContext = context.applicationContext
  private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "system-tts").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private var engine: TextToSpeech? = null

  private val pending = mutableSetOf<String>()
  private var completion: CompletableDeferred<Unit>? = null
  private var utteranceCounter = 0L

  private val listener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = Unit

    override fun onDone(utteranceId: String?) {
      markFinished(utteranceId)
    }

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) {
      markFinished(utteranceId)
    }

    override fun onError(utteranceId: String?, errorCode: Int) {
      markFinished(utteranceId)
    }

    override fun onStop(utteranceId: String?, interrupted: Boolean) {
      markFinished(utteranceId)
    }
  }

  override suspend fun warmUp() {
    withContext(dispatcher) {
      ensureEngine()
    }
  }

  /**
   * Switches the spoken language for the next utterances. Runs on the same single thread as
   * [speakClauses], so a call from the same coroutine is ordered before the speak that follows it.
   */
  suspend fun setLanguage(locale: Locale) {
    withContext(dispatcher) {
      val tts = ensureEngine()
      val result = tts.setLanguage(locale)
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w(TRACE, "system TTS ${locale.language} unavailable (setLanguage=$result)")
      }
    }
  }

  override suspend fun speakClauses(
    textChunks: Flow<String>,
    onStarted: () -> Unit,
  ) {
    withContext(dispatcher) {
      val start = SystemClock.elapsedRealtime()
      Log.d(TRACE, "system TTS speakClauses start")
      val tts = ensureEngine()
      val done = CompletableDeferred<Unit>()
      synchronized(pending) {
        pending.clear()
        completion = done
      }

      var started = false
      var queued = 0
      var failed = 0
      try {
        textChunks.collect { text ->
          if (text.isBlank()) return@collect
          val id = "utt-${utteranceCounter++}"
          synchronized(pending) { pending += id }
          val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
          if (result == TextToSpeech.SUCCESS) {
            queued++
            if (!started) {
              started = true
              Log.d(
                TRACE,
                "system TTS first utterance after " +
                  "${SystemClock.elapsedRealtime() - start} ms",
              )
              runCatching { onStarted() }
            }
          } else {
            failed++
            synchronized(pending) { pending -= id }
            Log.w(TRACE, "system TTS speak failed ($result)")
          }
        }

        val hasPending = synchronized(pending) { pending.isNotEmpty() }
        Log.d(
          TRACE,
          "system TTS queued $queued utterances ($failed failed) in " +
            "${SystemClock.elapsedRealtime() - start} ms",
        )
        if (hasPending) {
          done.await()
        }
        Log.d(
          TRACE,
          "system TTS playback done after ${SystemClock.elapsedRealtime() - start} ms",
        )
      } finally {
        synchronized(pending) {
          pending.clear()
          if (completion === done) completion = null
        }
      }
    }
  }

  override fun stop() {
    Log.d(TRACE, "system TTS stop requested")
    val finished = synchronized(pending) {
      engine?.stop()
      pending.clear()
      completion.also { completion = null }
    }
    finished?.complete(Unit)
  }

  private fun markFinished(utteranceId: String?) {
    if (utteranceId == null) return
    val finished = synchronized(pending) {
      pending.remove(utteranceId)
      if (pending.isEmpty()) {
        completion.also { completion = null }
      } else {
        null
      }
    }
    finished?.complete(Unit)
  }

  /**
   * Creates the platform engine on the dispatcher thread. The constructor returns immediately and
   * reports readiness through its listener; wait for that callback so later `speak` calls work.
   */
  private fun ensureEngine(): TextToSpeech {
    engine?.let { return it }
    val start = SystemClock.elapsedRealtime()
    val latch = CountDownLatch(1)
    var status = TextToSpeech.ERROR
    lateinit var created: TextToSpeech
    created = TextToSpeech(appContext) { initStatus ->
      status = initStatus
      latch.countDown()
    }
    check(latch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "System TTS initialization timed out"
    }
    val engines = runCatching {
      created.engines?.joinToString { "${it.name} (${it.label})" }
    }.getOrNull()
    check(status == TextToSpeech.SUCCESS) {
      Log.e(TRACE, "system TTS init failed status=$status engines=$engines")
      "System TTS initialization failed (status=$status, engines=$engines)"
    }
    created.setOnUtteranceProgressListener(listener)
    val language = created.setLanguage(defaultLocale)
    if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
      Log.w(TRACE, "system TTS ${defaultLocale.language} unavailable (setLanguage=$language); using device default")
      created.setLanguage(Locale.getDefault())
    }
    engine = created
    Log.d(
      TRACE,
      "system TTS loaded in ${SystemClock.elapsedRealtime() - start} ms " +
        "(engine=${created.defaultEngine}, engines=$engines)",
    )
    return created
  }

  override fun close() {
    Log.d(TRACE, "system TTS close")
    runCatching { engine?.stop() }
    runCatching { engine?.shutdown() }
    engine = null
    dispatcher.close()
  }

  private companion object {
    const val TRACE = "VoiceTrace"
    const val INIT_TIMEOUT_SECONDS = 15L
  }
}
