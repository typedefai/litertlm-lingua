package com.typedefai.lingua.tts

/**
 * Splits streamed LLM text into speakable chunks.
 *
 * The first chunk is emitted early (clause punctuation or 16 chars) so playback can start while
 * Gemma is still generating; later chunks wait for clause punctuation or 110 chars. Latin text is
 * only cut at word boundaries: a cut mid-word makes the synthesized fragment unintelligible.
 * Chinese text has no spaces, so a CJK run that outgrows the limit is cut at the limit (each CJK
 * character is a self-contained unit for the vocoder).
 */
class SpeechChunker(
  private val minChars: Int = 2,
  private val firstChunkMaxChars: Int = 16,
  private val maxChars: Int = 110,
) {

  private val buffer = StringBuilder()
  private var emittedChunk = false

  /** Appends a text delta and returns any complete speech chunks. */
  fun append(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    buffer.append(text)

    val chunks = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < buffer.length) {
      if (isBoundary(buffer[index], index)) {
        val end = index + 1
        val candidate = buffer.substring(start, end).trim()
        if (candidate.length >= minChars) {
          chunks += candidate
          emittedChunk = true
          start = end
        }
      }
      index++
    }
    if (start > 0) {
      buffer.delete(0, start)
    }

    while (buffer.length > if (emittedChunk) maxChars else firstChunkMaxChars) {
      val limit = if (emittedChunk) maxChars else firstChunkMaxChars
      val window = buffer.substring(0, limit)
      val space = window.lastIndexOf(' ')
      val hasCjk = window.any { isCjk(it) }
      if (space >= minChars) {
        // Wait for a word boundary instead of cutting a word in half.
        val chunk = buffer.substring(0, space).trim()
        if (chunk.isNotEmpty()) {
          chunks += chunk
          emittedChunk = true
        }
        buffer.delete(0, space)
      } else if (hasCjk) {
        // No word boundaries in a CJK run; each character is its own unit.
        val chunk = buffer.substring(0, limit).trim()
        if (chunk.isNotEmpty()) {
          chunks += chunk
          emittedChunk = true
        }
        buffer.delete(0, limit)
      } else {
        // Pure Latin without a safe cut point: keep buffering rather than split a word.
        break
      }
    }
    return chunks
  }

  /** Returns and clears the remaining incomplete text. */
  fun flush(): String {
    val text = buffer.toString().trim()
    buffer.clear()
    return text
  }

  /** Drops all buffered text (used when a stream turns out to be a tool call, not prose). */
  fun reset() {
    buffer.clear()
    emittedChunk = false
  }

  private fun isBoundary(char: Char, index: Int): Boolean {
    val boundary = char == '.' || char == ',' || char == '!' || char == '?' ||
      char == ';' || char == ':' || char == '\n' ||
      char == '。' || char == '，' || char == '！' || char == '？' ||
      char == '；' || char == '：' || char == '、' || char == '…'
    if (!boundary) return false
    if (char == '.') {
      val previous = if (index > 0) buffer[index - 1] else null
      val next = if (index + 1 < buffer.length) buffer[index + 1] else null
      if (previous?.isDigit() == true || next?.isDigit() == true) {
        return false
      }
    }
    return true
  }

  private fun isCjk(char: Char): Boolean {
    val code = char.code
    return code in 0x2E80..0x9FFF || // CJK radicals, punctuation, ideographs
      code in 0xF900..0xFAFF || // CJK compatibility ideographs
      code in 0xFF00..0xFFEF // full-width forms
  }
}
