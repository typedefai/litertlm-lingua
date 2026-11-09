package com.typedefai.lingua.settings

import com.typedefai.lingua.asr.AsrModel
import com.typedefai.lingua.tts.TtsModel

/**
 * Tunable Gemma parameters. Mirrors Gallery's model configs: context size, max output tokens, and
 * the sampler triple. Changing any of these recreates the LiteRT-LM engine.
 */
data class LlmSettings(
  val maxContextTokens: Int = DEFAULT_CONTEXT_TOKENS,
  val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
  val topK: Int = DEFAULT_TOP_K,
  val topP: Float = DEFAULT_TOP_P,
  val temperature: Float = DEFAULT_TEMPERATURE,
) {
  companion object {
    const val DEFAULT_CONTEXT_TOKENS = 4_096
    const val DEFAULT_MAX_OUTPUT_TOKENS = 512
    const val DEFAULT_TOP_K = 64
    const val DEFAULT_TOP_P = 0.95f
    const val DEFAULT_TEMPERATURE = 1.0f

    val DEFAULT = LlmSettings()
  }
}

/** Active ASR/TTS bundles. An empty [ttsVoiceId] means the bundle's own default voice. */
data class AudioSettings(
  val asrModel: AsrModel = AsrModel.Qwen3Asr,
  val ttsModel: TtsModel = TtsModel.KokoroGpu,
  val ttsVoiceId: String = "",
) {
  companion object {
    val DEFAULT = AudioSettings()
  }
}

data class AppSettings(
  val llm: LlmSettings = LlmSettings.DEFAULT,
  val audio: AudioSettings = AudioSettings.DEFAULT,
) {
  companion object {
    val DEFAULT = AppSettings()
  }
}

/** Slider bounds, matching Gallery's `Config.kt` where the option exists. */
object SettingsRanges {
  const val CONTEXT_MIN = 2_048
  const val CONTEXT_MAX = 8_192
  const val MAX_OUTPUT_MIN = 100
  const val MAX_OUTPUT_MAX = 4_096
  const val TOP_K_MIN = 1
  const val TOP_K_MAX = 100
  const val TOP_P_MIN = 0f
  const val TOP_P_MAX = 1f
  const val TEMPERATURE_MIN = 0f
  const val TEMPERATURE_MAX = 2f
}
