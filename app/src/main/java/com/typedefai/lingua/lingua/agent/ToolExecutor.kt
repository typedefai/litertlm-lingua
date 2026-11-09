package com.typedefai.lingua.lingua.agent

import android.util.Log
import com.typedefai.lingua.dict.DictEntry
import com.typedefai.lingua.dict.DictRepository
import com.typedefai.lingua.dict.SavedWord
import com.typedefai.lingua.dict.WordbookRepository
import com.typedefai.lingua.lingua.Lang
import com.typedefai.lingua.lingua.detectLang
import org.json.JSONArray
import org.json.JSONObject

/** Outcomes of a word/translation tool call. */
sealed class ToolResult {
  /** Dictionary lookup; [entry] is null when the word is not in the bundled dictionary. */
  data class Lookup(val word: String, val entry: DictEntry?) : ToolResult()

  data class WordbookChanged(
    val word: String,
    val added: Boolean,
    val count: Int,
  ) : ToolResult()

  data class WordbookList(val words: List<SavedWord>) : ToolResult()

  data class Translation(
    val source: String,
    val target: Lang,
    val text: String,
  ) : ToolResult()

  data class Pronounce(val word: String) : ToolResult()

  data class ValidationError(val message: String) : ToolResult()
  data class Error(val message: String) : ToolResult()
}

/**
 * Deterministic tool execution for word commands in the practice chat.
 *
 * Reads come from the bundled dictionary; wordbook writes are local SQLite; translation runs
 * through the on-device Gemma engine via the injected [translator]. Nothing leaves the device.
 */
object ToolExecutor {

  private lateinit var dict: DictRepository
  private lateinit var wordbook: WordbookRepository
  private var translator: (suspend (text: String, target: Lang) -> String?)? = null

  fun init(
    dict: DictRepository,
    wordbook: WordbookRepository,
    translator: suspend (text: String, target: Lang) -> String?,
  ) {
    this.dict = dict
    this.wordbook = wordbook
    this.translator = translator
  }

  suspend fun execute(tool: String, params: JSONObject): ToolResult {
    val canonical = canonicalToolName(tool)
    Log.d(TAG, "execute tool=$tool canonical=$canonical params=$params")
    return when (canonical) {
      "lookup_word" -> lookup(params)
      "add_to_wordbook" -> addToWordbook(params)
      "remove_from_wordbook" -> removeFromWordbook(params)
      "list_wordbook" -> listWordbook()
      "translate_text" -> translate(params)
      "pronounce_word" -> pronounce(params)
      else -> ToolResult.Error("Unknown tool: $tool")
    }
  }

  /**
   * Gemma occasionally emits a slightly different tool name (e.g. `save_word`). Map obvious
   * variants back to the registered schema so the turn does not fail on a naming slip.
   */
  fun canonicalToolName(raw: String): String {
    val name = raw.lowercase().replace('-', '_').replace(' ', '_')
    return when {
      name.contains("pronounce") || name.contains("pronunciation") ||
        name.contains("speak") || name.contains("read_aloud") -> "pronounce_word"
      name.contains("translate") || name.contains("translation") -> "translate_text"
      name.contains("remove") || name.contains("delete") ||
        name.contains("unsave") || name.contains("unstar") -> "remove_from_wordbook"
      name.contains("add") || name.contains("save") || name.contains("star") ||
        name.contains("collect") || name.contains("remember") ||
        name.contains("bookmark") -> "add_to_wordbook"
      name.contains("list") || name == "wordbook" || name.contains("my_words") ||
        name.contains("saved_words") || name.contains("show_words") -> "list_wordbook"
      name.contains("lookup") || name.contains("look_up") || name.contains("define") ||
        name.contains("meaning") || name.contains("dictionary") ||
        name.contains("search") -> "lookup_word"
      else -> raw
    }
  }

  private fun wordParam(params: JSONObject, prompt: String): String? {
    val word = params.optString("word", "").trim()
      .trim('"', '\'', '。', '.', '，', ',', '!', '?', '！', '？')
    if (word.isBlank()) {
      Log.w(TAG, "$prompt: word param missing")
      return null
    }
    return word
  }

  private suspend fun lookup(params: JSONObject): ToolResult {
    val word = wordParam(params, "lookup")
      ?: return ToolResult.ValidationError("Which word should I look up?")
    val entry = dict.lookupExact(word)
    if (entry == null) {
      // A miss may still be a word the dictionary lacks; suggest the prefix matches.
      val suggestions = dict.suggest(word, limit = 3)
      val hint = if (suggestions.isNotEmpty()) {
        " Close matches: ${suggestions.joinToString(", ") { it.word }}."
      } else {
        ""
      }
      return ToolResult.Lookup(word, null).also {
        Log.d(TAG, "lookup miss for \"$word\"$hint")
      }
    }
    return ToolResult.Lookup(word, entry)
  }

  private suspend fun addToWordbook(params: JSONObject): ToolResult {
    val word = wordParam(params, "add_to_wordbook")
      ?: return ToolResult.ValidationError("Which word should I add to your list?")
    val entry = dict.lookupExact(word)
      ?: return ToolResult.ValidationError(
        "I couldn't find \"$word\" in the dictionary, so I didn't add it. " +
          "Please check the spelling.",
      )
    wordbook.save(entry.word, entry.phonetic, entry.translation)
    val count = wordbook.list().size
    Log.d(TAG, "wordbook add \"${entry.word}\" -> $count words")
    return ToolResult.WordbookChanged(entry.word, added = true, count = count)
  }

  private fun removeFromWordbook(params: JSONObject): ToolResult {
    val word = wordParam(params, "remove_from_wordbook")
      ?: return ToolResult.ValidationError("Which word should I remove?")
    if (!wordbook.contains(word)) {
      return ToolResult.ValidationError("\"$word\" is not in your wordbook.")
    }
    wordbook.remove(word)
    val count = wordbook.list().size
    Log.d(TAG, "wordbook remove \"$word\" -> $count words")
    return ToolResult.WordbookChanged(word, added = false, count = count)
  }

  private fun listWordbook(): ToolResult = ToolResult.WordbookList(wordbook.list())

  private suspend fun translate(params: JSONObject): ToolResult {
    val text = params.optString("text", "").trim()
    if (text.isBlank()) {
      return ToolResult.ValidationError("What should I translate?")
    }
    val target = when (params.optString("to", "").trim().lowercase()) {
      "zh", "zh-cn", "zh_cn", "chinese", "中文" -> Lang.ZH
      "en", "english" -> Lang.EN
      else -> if (detectLang(text) == Lang.ZH) Lang.EN else Lang.ZH
    }
    val output = runCatching { translator?.invoke(text, target) }
      .onFailure { Log.e(TAG, "translation failed", it) }
      .getOrNull()
      ?.trim()
      .orEmpty()
    if (output.isEmpty()) {
      return ToolResult.Error("Translation failed. Please try again.")
    }
    Log.d(TAG, "translate \"${text.take(40)}\" -> ${target.code} (${output.length} chars)")
    return ToolResult.Translation(text, target, output)
  }

  private fun pronounce(params: JSONObject): ToolResult {
    val word = wordParam(params, "pronounce")
      ?: return ToolResult.ValidationError("Which word should I pronounce?")
    return ToolResult.Pronounce(word)
  }

  /** Serializes a result into the JSON fed back into the LiteRT conversation. */
  fun toolResponseJson(result: ToolResult): String {
    val json = JSONObject()
    when (result) {
      is ToolResult.Lookup -> {
        val entry = result.entry
        if (entry == null) {
          json.put("error", "Not found in the dictionary. Check the spelling.")
        } else {
          val meanings = JSONArray()
          entry.translationLines.take(8).forEach { meanings.put(it) }
          json.put("status", "ok")
            .put("word", entry.word)
            .put("phonetic", entry.phonetic)
            .put("meanings", meanings)
            .put("definition", entry.definition.take(400))
        }
      }
      is ToolResult.WordbookChanged -> json
        .put("status", "ok")
        .put("word", result.word)
        .put("added", result.added)
        .put("wordbook_count", result.count)
      is ToolResult.WordbookList -> {
        val words = JSONArray()
        for (word in result.words) {
          words.put(
            JSONObject()
              .put("word", word.word)
              .put("translation", word.translation.split('\n').firstOrNull().orEmpty()),
          )
        }
        json.put("status", "ok")
          .put("count", result.words.size)
          .put("words", words)
      }
      is ToolResult.Translation -> json
        .put("status", "ok")
        .put("source", result.source)
        .put("target", result.target.code)
        .put("translation", result.text)
      is ToolResult.Pronounce -> json
        .put("status", "ok")
        .put("word", result.word)
        .put("spoken", true)
      is ToolResult.ValidationError -> json.put("error", result.message)
      is ToolResult.Error -> json.put("error", result.message)
    }
    return json.toString()
  }

  private const val TAG = "ToolExecutor"
}
