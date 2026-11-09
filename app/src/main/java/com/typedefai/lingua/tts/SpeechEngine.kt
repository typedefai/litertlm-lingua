package com.typedefai.lingua.tts

import kotlinx.coroutines.flow.Flow

/**
 * Speech output backend. Implemented by the on-device MediaPipe Kokoro/Phonon/Pocket engine and by
 * the Android system TTS engine used as the low-latency option.
 */
interface SpeechEngine : AutoCloseable {

  /** Loads the engine or waits until the platform service is ready. */
  suspend fun warmUp()

  /**
   * Consumes clause chunks as they stream from the LLM and plays them. Suspends until playback of
   * everything queued by this call has finished. [onStarted] fires when the first audio starts.
   */
  suspend fun speakClauses(
    textChunks: Flow<String>,
    onStarted: () -> Unit = {},
  )

  /** Stops playback immediately (barge-in). Safe to call from any thread. */
  fun stop()

  /** Applies a new voice without reloading the engine. No-op for engines that do not expose one. */
  fun updateVoice(voiceId: String) {}
}
