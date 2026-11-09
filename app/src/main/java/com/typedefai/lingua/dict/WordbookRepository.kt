package com.typedefai.lingua.dict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A word the user saved into the wordbook. */
data class SavedWord(
  val word: String,
  val phonetic: String,
  val translation: String,
  val addedAt: Long,
)

/**
 * Local wordbook: words starred in the dictionary, stored in a small SQLite database.
 * One row per word; saving twice just refreshes the timestamp.
 */
class WordbookRepository(context: Context) {

  private val helper = WordbookOpenHelper(context.applicationContext)

  fun save(word: String, phonetic: String, translation: String) {
    val db = helper.writableDatabase
    val values = ContentValues().apply {
      put("word", word)
      put("word_lc", word.lowercase())
      put("phonetic", phonetic)
      put("translation", translation)
      put("added_at", System.currentTimeMillis())
    }
    db.insertWithOnConflict(
      TABLE,
      null,
      values,
      SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  fun remove(word: String) {
    helper.writableDatabase.delete(TABLE, "word_lc = ?", arrayOf(word.lowercase()))
  }

  fun contains(word: String): Boolean {
    helper.readableDatabase.rawQuery(
      "SELECT 1 FROM $TABLE WHERE word_lc = ?",
      arrayOf(word.lowercase()),
    ).use { return it.moveToFirst() }
  }

  /** All saved words, most recently added first. */
  fun list(): List<SavedWord> {
    val result = mutableListOf<SavedWord>()
    helper.readableDatabase.rawQuery(
      "SELECT word, phonetic, translation, added_at FROM $TABLE ORDER BY added_at DESC",
      null,
    ).use { cursor ->
      while (cursor.moveToNext()) {
        result += SavedWord(
          word = cursor.getString(0),
          phonetic = cursor.getString(1) ?: "",
          translation = cursor.getString(2) ?: "",
          addedAt = cursor.getLong(3),
        )
      }
    }
    return result
  }

  fun close() {
    helper.close()
  }

  private class WordbookOpenHelper(context: Context) :
    SQLiteOpenHelper(context, "wordbook.db", null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        """
        CREATE TABLE $TABLE(
          word TEXT NOT NULL,
          word_lc TEXT NOT NULL PRIMARY KEY,
          phonetic TEXT NOT NULL DEFAULT '',
          translation TEXT NOT NULL DEFAULT '',
          added_at INTEGER NOT NULL
        )
        """.trimIndent(),
      )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
  }

  private companion object {
    const val TABLE = "saved_words"
    const val VERSION = 1
  }
}
