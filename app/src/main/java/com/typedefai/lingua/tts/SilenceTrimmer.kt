package com.typedefai.lingua.tts

import kotlin.math.abs
import kotlin.math.min

/**
 * Trims the padding silence that MediaPipe TTS leaves at the edges of every synthesized clause.
 *
 * Kokoro vocodes a fixed bucket of frames, so even "Hi." comes back as >1 s of audio with most of
 * it silence. Concatenating those clips produces long pauses between clauses ("choppy" speech).
 * Gallery applies the same trim at inner clause joins in `MediaPipeTtsHelper.kt`.
 */
internal object SilenceTrimmer {

  /** Amplitude ratio relative to peak below which edge samples count as silence (-40 dB). */
  private const val SILENCE_RATIO = 0.01f

  /** Leading silence kept at the start of an inner clause (5 ms at 24 kHz). */
  private const val HEAD_MARGIN_SAMPLES = 120

  /** Trailing silence kept at the end of a clause (200 ms at 24 kHz). */
  private const val TAIL_MARGIN_SAMPLES = 4_800

  fun trim(samples: FloatArray, trimHead: Boolean, trimTail: Boolean): FloatArray {
    if (samples.isEmpty() || (!trimHead && !trimTail)) return samples

    var peak = 0f
    for (sample in samples) {
      val magnitude = abs(sample)
      if (magnitude > peak) peak = magnitude
    }
    if (peak <= 0f) {
      return if (trimHead && trimTail) {
        samples.copyOfRange(0, min(samples.size, HEAD_MARGIN_SAMPLES + TAIL_MARGIN_SAMPLES))
      } else {
        samples
      }
    }

    val threshold = peak * SILENCE_RATIO
    var begin = 0
    var end = samples.size
    if (trimHead) {
      while (begin < end && abs(samples[begin]) < threshold) begin++
      begin = (begin - HEAD_MARGIN_SAMPLES).coerceAtLeast(0)
    }
    if (trimTail) {
      while (end > begin && abs(samples[end - 1]) < threshold) end--
      end = (end + TAIL_MARGIN_SAMPLES).coerceAtMost(samples.size)
    }
    if (begin == 0 && end == samples.size) return samples
    return if (end <= begin) FloatArray(0) else samples.copyOfRange(begin, end)
  }
}
