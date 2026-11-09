package com.typedefai.lingua.lingua

/** Languages the app translates between. */
enum class Lang(val code: String, val label: String) {
  EN("en", "English"),
  ZH("zh", "中文"),
}

/** How the source language of a translation is decided. */
enum class TranslateMode(val label: String) {
  AUTO("自动"),
  EN_TO_ZH("EN → 中文"),
  ZH_TO_EN("中文 → EN"),
  ;

  /** Resolves the concrete source/target pair for one input text. */
  fun resolve(text: String): Pair<Lang, Lang> = when (this) {
    AUTO -> {
      val source = detectLang(text)
      source to (if (source == Lang.ZH) Lang.EN else Lang.ZH)
    }
    EN_TO_ZH -> Lang.EN to Lang.ZH
    ZH_TO_EN -> Lang.ZH to Lang.EN
  }
}

/**
 * Cheap, instant language detection for a two-language app: any CJK character means Chinese.
 * Deterministic and free, unlike a LanguageDetector model — Gemma handles the ambiguous cases
 * through the translation itself.
 */
fun detectLang(text: String): Lang = if (text.any { isCjk(it) }) Lang.ZH else Lang.EN

fun isCjk(char: Char): Boolean {
  val code = char.code
  return code in 0x2E80..0x9FFF || // CJK radicals, punctuation, ideographs
    code in 0xF900..0xFAFF || // CJK compatibility ideographs
    code in 0xFF00..0xFFEF // full-width forms
}
