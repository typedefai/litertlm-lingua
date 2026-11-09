package com.typedefai.lingua.asr

import com.google.mediapipe.tasks.core.Delegate

/**
 * ASR bundles understood by MediaPipe `AudioTranscriber` (`tasks-audio` 1.2.0).
 *
 * File names must stay exact: the task detects the architecture from the bundle metadata.
 * Parakeet TDT is CPU-first (sequential TDT decode); Moonshine and Qwen3-ASR also run on CPU here.
 */
enum class AsrModel(
  val fileName: String,
  val delegate: Delegate,
  val title: String,
  val detail: String,
) {
  /** 29 MB English model; fastest, lowest accuracy. */
  MoonshineTiny(
    fileName = "moonshine_tiny_5s_i8.litertlm",
    delegate = Delegate.CPU,
    title = "Moonshine Tiny",
    detail = "29 MB · fastest, lower accuracy",
  ),

  /** 915 MB multilingual 0.6B model; stronger on names/numbers, slower. */
  Qwen3Asr(
    fileName = "qwen3_asr_0.6b_5s_i8.litertlm",
    delegate = Delegate.CPU,
    title = "Qwen3-ASR 0.6B",
    detail = "915 MB · best accuracy",
  ),

  /** 0.6B stateful streaming model (Gallery's default); not in `models/` yet. */
  ParakeetTdt(
    fileName = "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm",
    delegate = Delegate.CPU,
    title = "Parakeet TDT 0.6B",
    detail = "615 MB · fast and accurate when downloaded",
  ),
  ;

  companion object {
    /** Used when no setting has been saved yet. */
    val DEFAULT = Qwen3Asr
  }
}
