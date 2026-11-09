package com.typedefai.lingua.settings

import android.content.Context
import com.typedefai.lingua.asr.AsrModel
import com.typedefai.lingua.tts.TtsModel

/**
 * Persists [AppSettings] in shared preferences.
 *
 * Enum names are stored by name so new bundles can be added without migrating keys; an unknown or
 * removed entry falls back to the default instead of crashing.
 */
class SettingsStore(context: Context) {

  private val prefs =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

  fun load(): AppSettings {
    val defaults = AppSettings.DEFAULT
    val llm = LlmSettings(
      maxContextTokens = prefs.getInt(KEY_CONTEXT_TOKENS, defaults.llm.maxContextTokens),
      maxOutputTokens = prefs.getInt(KEY_MAX_OUTPUT_TOKENS, defaults.llm.maxOutputTokens),
      topK = prefs.getInt(KEY_TOP_K, defaults.llm.topK),
      topP = prefs.getFloat(KEY_TOP_P, defaults.llm.topP),
      temperature = prefs.getFloat(KEY_TEMPERATURE, defaults.llm.temperature),
    )
    val audio = AudioSettings(
      asrModel = prefs.getString(KEY_ASR_MODEL, null)
        ?.let { name -> AsrModel.entries.firstOrNull { it.name == name } }
        ?: defaults.audio.asrModel,
      ttsModel = prefs.getString(KEY_TTS_MODEL, null)
        ?.let { name -> TtsModel.entries.firstOrNull { it.name == name } }
        ?: defaults.audio.ttsModel,
      ttsVoiceId = prefs.getString(KEY_TTS_VOICE, defaults.audio.ttsVoiceId).orEmpty(),
    )
    return AppSettings(llm = llm, audio = audio)
  }

  fun save(settings: AppSettings) {
    prefs.edit()
      .putInt(KEY_CONTEXT_TOKENS, settings.llm.maxContextTokens)
      .putInt(KEY_MAX_OUTPUT_TOKENS, settings.llm.maxOutputTokens)
      .putInt(KEY_TOP_K, settings.llm.topK)
      .putFloat(KEY_TOP_P, settings.llm.topP)
      .putFloat(KEY_TEMPERATURE, settings.llm.temperature)
      .putString(KEY_ASR_MODEL, settings.audio.asrModel.name)
      .putString(KEY_TTS_MODEL, settings.audio.ttsModel.name)
      .putString(KEY_TTS_VOICE, settings.audio.ttsVoiceId)
      .apply()
  }

  private companion object {
    const val PREF_NAME = "lingua_settings"
    const val KEY_CONTEXT_TOKENS = "llm_max_context_tokens"
    const val KEY_MAX_OUTPUT_TOKENS = "llm_max_output_tokens"
    const val KEY_TOP_K = "llm_top_k"
    const val KEY_TOP_P = "llm_top_p"
    const val KEY_TEMPERATURE = "llm_temperature"
    const val KEY_ASR_MODEL = "asr_model"
    const val KEY_TTS_MODEL = "tts_model"
    const val KEY_TTS_VOICE = "tts_voice_id"
  }
}
