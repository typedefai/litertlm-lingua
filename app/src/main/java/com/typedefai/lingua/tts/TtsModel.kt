package com.typedefai.lingua.tts

import com.google.mediapipe.tasks.core.Delegate

/**
 * TTS bundles understood by MediaPipe `TextToSpeech` (`tasks-audio` 1.2.0).
 *
 * Each entry drives the model file, the synthesis defaults (voice, locale, delegate), and the
 * audio options. Those options are per model: Kokoro follows the official Android sample
 * (`examples/text_to_speech/android`: `speakingRate = 1.0f`, `targetBucket = 64`).
 */
enum class TtsModel(
  val fileName: String,
  val delegate: Delegate,
  val voiceId: String,
  val locale: String,
  val speakingRate: Float,
  val targetBucket: Int,
  val title: String,
  val detail: String,
  val voiceIds: List<String>,
  /** Voice used for Chinese output, or null when the bundle ships no Chinese voice. */
  val zhVoiceId: String? = null,
) {
  /**
   * Phonon 31M (`phonon_int8.litertlm`, ~87 MB). English only, CPU by design. Uses MediaPipe
   * defaults: no bucket padding, natural rate.
   */
  Phonon(
    fileName = "phonon_int8.litertlm",
    delegate = Delegate.CPU,
    voiceId = "freya",
    locale = "en-US",
    speakingRate = 1.0f,
    targetBucket = 0,
    title = "Phonon 31M",
    detail = "87 MB · CPU, lowest latency",
    voiceIds = listOf("freya", "claire", "lukas", "sofia", "lucas"),
  ),

  /**
   * Kokoro 82M (`kokoro_int8_gpu_fp16.litertlm`). Multilingual, best quality, GPU only here:
   * the CPU bundle was ~2.5x slower on the Xiaomi 14 Pro.
   */
  KokoroGpu(
    fileName = "kokoro_int8_gpu_fp16.litertlm",
    delegate = Delegate.GPU,
    voiceId = "af_heart",
    locale = "en-US",
    speakingRate = 1.0f,
    targetBucket = 64,
    title = "Kokoro 82M",
    detail = "289 MB · GPU, best quality, multilingual",
    voiceIds = listOf(
      "af_heart",
      "af_bella",
      "af_nicole",
      "am_michael",
      "bf_emma",
      "bm_george",
      "ef_dora",
      "zf_xiaoxiao",
      "zf_xiaobei",
    ),
    zhVoiceId = "zf_xiaoxiao",
  ),

  /**
   * Pocket TTS 100M (`pocket_tts_int8.litertlm`). CPU by design, more voices and languages than
   * Phonon, but roughly Kokoro-sized, so not the latency pick.
   */
  Pocket(
    fileName = "pocket_tts_int8.litertlm",
    delegate = Delegate.CPU,
    voiceId = "alba",
    locale = "en-US",
    speakingRate = 1.0f,
    targetBucket = 0,
    title = "Pocket TTS 100M",
    detail = "CPU, 7 languages",
    voiceIds = listOf("alba", "marius", "jean", "fantine", "cosette"),
  ),

  /**
   * Android platform TTS. Runs inside the system TTS service, so it starts almost instantly even
   * while Gemma is decoding. Voice choice is delegated to the device's TTS settings.
   */
  System(
    fileName = "",
    delegate = Delegate.CPU,
    voiceId = "",
    locale = "",
    speakingRate = 1.0f,
    targetBucket = 0,
    title = "System TTS",
    detail = "Android voice · lowest latency",
    voiceIds = emptyList(),
  ),
  ;

  companion object {
    /** Used when no setting has been saved yet. */
    val DEFAULT = KokoroGpu
  }
}
