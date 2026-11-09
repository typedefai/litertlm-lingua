package com.typedefai.lingua.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * One dictionary entry from the bundled ECDICT subset (scripts/build_dict.py).
 */
data class DictEntry(
  val word: String,
  val phonetic: String,
  val definition: String,
  val translation: String,
  val pos: String,
  val collins: Int,
  val oxford: Boolean,
  val tag: String,
  val bnc: Int,
  val frq: Int,
  val exchange: String,
) {
  /** Chinese definition lines, one sense per line. */
  val translationLines: List<String>
    get() = translation.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

  /**
   * Inflected forms in (form, Chinese label) pairs, parsed from the ECDICT `exchange` field
   * (e.g. "p:went/d:done/i:going/3:goes").
   */
  fun exchangeForms(labelOf: (String) -> String): List<Pair<String, String>> {
    if (exchange.isBlank()) return emptyList()
    return exchange.split('/').mapNotNull { pair ->
      val colon = pair.indexOf(':')
      if (colon <= 0 || colon == pair.lastIndex) return@mapNotNull null
      val key = pair.substring(0, colon)
      val form = pair.substring(colon + 1).trim()
      if (form.isBlank()) return@mapNotNull null
      form to labelOf(key)
    }
  }

  /** Exam-level tags as human-readable Chinese labels (cet4/cet6/ielts/...). */
  fun examTags(labelOf: (String) -> String): List<String> =
    tag.split(Regex("\\s+")).mapNotNull { key ->
      key.trim().takeIf { it.isNotEmpty() }?.let(labelOf)
    }
}

/**
 * Read-only English-Chinese dictionary backed by the ECDICT subset shipped in
 * `assets/databases/ecdict.db`. The asset is copied to the app's files dir on first run; all
 * queries run on one background thread.
 */
class DictRepository(private val context: Context) : AutoCloseable {

  private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "lingua-dict").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  @Volatile
  private var db: SQLiteDatabase? = null

  /** Opens (copying the asset on first run) the dictionary database. */
  suspend fun warmUp() {
    withContext(dispatcher) {
      ensureDb()
    }
  }

  /** Exact (case-insensitive) word lookup, or null when the word is not in the dictionary. */
  suspend fun lookupExact(word: String): DictEntry? = withContext(dispatcher) {
    val active = ensureDb()
    val lc = word.trim().lowercase()
    if (lc.isBlank()) return@withContext null
    val entry = queryOne("SELECT * FROM $TABLE WHERE word_lc = ?", arrayOf(lc))
      ?: queryOne("SELECT * FROM $TABLE WHERE word = ?", arrayOf(word.trim()))
    Log.d(TRACE, "dict lookup \"$word\" -> ${entry?.word ?: "miss"}")
    entry
  }

  /** Words starting with [prefix], used for the suggestion list. */
  suspend fun suggest(prefix: String, limit: Int = 10): List<DictEntry> = withContext(dispatcher) {
    val active = ensureDb()
    val lc = prefix.trim().lowercase()
    if (lc.isBlank()) return@withContext emptyList()
    queryMany(
      "SELECT * FROM $TABLE WHERE word_lc LIKE ? ORDER BY frq DESC, word_lc LIMIT $limit",
      arrayOf("$lc%"),
    )
  }

  /** Words whose Chinese translation contains [term]; CJK substring search, no tokenizer. */
  suspend fun searchChinese(term: String, limit: Int = 10): List<DictEntry> =
    withContext(dispatcher) {
      val active = ensureDb()
      val query = term.trim()
      if (query.isBlank()) return@withContext emptyList()
      queryMany(
        "SELECT * FROM $TABLE WHERE translation LIKE ? ORDER BY frq DESC, word_lc LIMIT $limit",
        arrayOf("%$query%"),
      )
    }

  private fun queryOne(sql: String, args: Array<String>): DictEntry? {
    val db = ensureDb()
    db.rawQuery(sql, args).use { cursor ->
      if (!cursor.moveToFirst()) return null
      return fromCursor(cursor)
    }
  }

  private fun queryMany(sql: String, args: Array<String>): List<DictEntry> {
    val db = ensureDb()
    val result = mutableListOf<DictEntry>()
    db.rawQuery(sql, args).use { cursor ->
      while (cursor.moveToNext()) {
        result += fromCursor(cursor)
      }
    }
    return result
  }

  private fun fromCursor(cursor: android.database.Cursor): DictEntry = DictEntry(
    word = cursor.getString(cursor.getColumnIndexOrThrow("word")),
    phonetic = cursor.getString(cursor.getColumnIndexOrThrow("phonetic")) ?: "",
    definition = cursor.getString(cursor.getColumnIndexOrThrow("definition")) ?: "",
    translation = cursor.getString(cursor.getColumnIndexOrThrow("translation")) ?: "",
    pos = cursor.getString(cursor.getColumnIndexOrThrow("pos")) ?: "",
    collins = cursor.getInt(cursor.getColumnIndexOrThrow("collins")),
    oxford = cursor.getInt(cursor.getColumnIndexOrThrow("oxford")) == 1,
    tag = cursor.getString(cursor.getColumnIndexOrThrow("tag")) ?: "",
    bnc = cursor.getInt(cursor.getColumnIndexOrThrow("bnc")),
    frq = cursor.getInt(cursor.getColumnIndexOrThrow("frq")),
    exchange = cursor.getString(cursor.getColumnIndexOrThrow("exchange")) ?: "",
  )

  /** Opens the database, copying the bundled asset into the files dir on first run. */
  private fun ensureDb(): SQLiteDatabase {
    db?.let { return it }
    val target = File(context.filesDir, "databases/ecdict.db")
    if (!target.isFile) {
      val start = System.currentTimeMillis()
      target.parentFile?.mkdirs()
      context.assets.open(ASSET_PATH).use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
      }
      Log.d(TRACE, "dict database copied from assets in ${System.currentTimeMillis() - start} ms")
    }
    val opened = SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    db = opened
    return opened
  }

  override fun close() {
    runCatching {
      kotlinx.coroutines.runBlocking(dispatcher) {
        db?.close()
        db = null
      }
    }
    dispatcher.close()
  }

  companion object {
    private const val TRACE = "LinguaDict"
    private const val ASSET_PATH = "databases/ecdict.db"
    private const val TABLE = "dict_entries"
  }
}
