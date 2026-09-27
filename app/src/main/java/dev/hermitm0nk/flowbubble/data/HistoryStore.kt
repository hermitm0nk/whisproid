package dev.hermitm0nk.flowbubble.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TranscriptEntry(val id: Long, val text: String, val createdAt: Long)

/** Private, local transcript log. Audio is neither written to disk nor retained after a session. */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "transcripts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE transcript (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun add(text: String): Long = writableDatabase.insertOrThrow("transcript", null,
        ContentValues().apply { put("text", text); put("created_at", System.currentTimeMillis()) })
    @Synchronized fun all(): List<TranscriptEntry> {
        val result = mutableListOf<TranscriptEntry>()
        readableDatabase.rawQuery("SELECT id,text,created_at FROM transcript ORDER BY id DESC", null).use { cursor ->
            while (cursor.moveToNext()) result.add(TranscriptEntry(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)))
        }
        return result
    }
    @Synchronized fun delete(id: Long) { writableDatabase.delete("transcript", "id=?", arrayOf(id.toString())) }
    @Synchronized fun clear() { writableDatabase.delete("transcript", null, null) }
}
