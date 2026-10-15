package io.github.qianliql.diary.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class DiaryRepository(private val context: Context, databaseName: String = "diary.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    private val state = MutableStateFlow<List<Entry>>(emptyList())
    val entries = state.asStateFlow()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    @Synchronized fun refresh() {
        state.value = readableDatabase.rawQuery("SELECT payload FROM entries", null).use { c ->
            buildList { while (c.moveToNext()) add(Entry.from(JSONObject(c.getString(0)))) }
        }.sortedByDescending { it.date }
    }
    @Synchronized fun put(entry: Entry) { write(writableDatabase, entry); refresh() }
    private fun write(db: SQLiteDatabase, entry: Entry) {
        db.insertWithOnConflict("entries", null, ContentValues().apply { put("id", entry.id); put("payload", entry.json().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun remove(id: String) { writableDatabase.delete("entries", "id=?", arrayOf(id)); refresh(); collectUnusedMedia() }
    @Synchronized fun beginEdit(entry: Entry?): Entry {
        if (entry == null) return Entry().also(::put)
        if (entry.draft) return entry
        val id = UUID.randomUUID().toString()
        return state.value.firstOrNull { it.draft && it.editOf == entry.id } ?: entry.copy(
            id = id, originId = id, draft = true, editOf = entry.id, deletedAt = 0).also(::put)
    }
    @Synchronized fun publish(draft: Entry): Entry {
        val origin = state.value.find { it.id == draft.editOf }?.originId ?: draft.originId
        val result = draft.copy(id = draft.editOf.ifBlank { draft.id }, originId = origin, draft = false, editOf = "", updatedAt = System.currentTimeMillis())
        val db = writableDatabase; db.beginTransaction()
        try { write(db, result); if (draft.editOf.isNotBlank()) db.delete("entries", "id=?", arrayOf(draft.id)); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        refresh(); return result
    }
    @Synchronized fun merge(entries: List<Entry>): MergeResult {
        refresh(); val plan = MergePlanner.merge(state.value, entries)
        val db = writableDatabase; db.beginTransaction()
        try { plan.entries.forEach { write(db, it) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        refresh(); return plan
    }
    @Synchronized fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val cutoff = now - 30L * 24 * 60 * 60 * 1000
        state.value.filter { it.deletedAt in 1..cutoff }.forEach { writableDatabase.delete("entries", "id=?", arrayOf(it.id)) }
        refresh(); collectUnusedMedia()
    }
    private fun collectUnusedMedia() {
        val used = state.value.flatMap { it.blocks }.map { it.uri }.toSet()
        java.io.File(context.noBackupFilesDir, "media").listFiles().orEmpty().forEach { file ->
            if (android.net.Uri.fromFile(file).toString() !in used) file.delete()
        }
    }
}
