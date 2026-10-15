package io.github.qianliql.diary.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class BlockType { TEXT, PHOTO, AUDIO }
data class Block(
    val id: String = UUID.randomUUID().toString(),
    val type: BlockType = BlockType.TEXT,
    val text: String = "",
    val uri: String = "",
    val transcript: String = "",
    val durationMs: Long = 0,
    val mediaHash: String = "",
) {
    fun json() = JSONObject().put("id", id).put("type", type.name).put("text", text).put("uri", uri)
        .put("transcript", transcript).put("durationMs", durationMs).put("mediaHash", mediaHash)
    companion object {
        fun from(o: JSONObject) = Block(o.getString("id"), BlockType.valueOf(o.getString("type")),
            o.optString("text"), o.optString("uri"), o.optString("transcript"), o.optLong("durationMs"), o.optString("mediaHash"))
    }
}
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val originId: String = id,
    val date: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val title: String = "",
    val mood: String = "",
    val tags: List<String> = emptyList(),
    val blocks: List<Block> = listOf(Block()),
    val draft: Boolean = true,
    val editOf: String = "",
    val deletedAt: Long = 0,
    val sourceNote: String = "",
) {
    val displayTitle: String get() = title.ifBlank { blocks.firstOrNull { it.type == BlockType.TEXT && it.text.isNotBlank() }?.text?.lineSequence()?.first()?.take(30) ?: "无题日记" }
    val searchable: String get() = (listOf(title, mood) + tags + blocks.flatMap { listOf(it.text, it.transcript) }).joinToString("\n")
    val hasContent: Boolean get() = title.isNotBlank() || blocks.any { it.text.isNotBlank() || it.uri.isNotBlank() }
    fun json() = JSONObject().put("id", id).put("originId", originId).put("date", date).put("createdAt", createdAt)
        .put("updatedAt", updatedAt).put("title", title).put("mood", mood).put("tags", JSONArray(tags))
        .put("blocks", JSONArray(blocks.map { it.json() })).put("draft", draft).put("editOf", editOf).put("deletedAt", deletedAt).put("sourceNote", sourceNote)
    /** URI locations change during migration. Identity compares content and stable media hashes. */
    fun fingerprint(): String {
        val o = json().apply { remove("id"); remove("originId"); remove("createdAt"); remove("updatedAt"); remove("editOf"); remove("sourceNote") }
        o.put("blocks", JSONArray(blocks.map { b -> b.json().apply { remove("id"); remove("uri") } }))
        return sha256(o.toString().toByteArray())
    }
    companion object {
        fun from(o: JSONObject): Entry = Entry(
            id = o.getString("id"), originId = o.optString("originId", o.getString("id")), date = o.getLong("date"),
            createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"), title = o.optString("title"),
            mood = o.optString("mood"), tags = o.optJSONArray("tags").strings(),
            blocks = o.getJSONArray("blocks").let { a -> (0 until a.length()).map { Block.from(a.getJSONObject(it)) } },
            draft = o.optBoolean("draft"), editOf = o.optString("editOf"), deletedAt = o.optLong("deletedAt"), sourceNote = o.optString("sourceNote"))
    }
}
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class MergeResult(val entries: List<Entry>, val added: Int, val skipped: Int, val conflicts: Int)
object MergePlanner {
    fun merge(existing: List<Entry>, incoming: List<Entry>): MergeResult {
        val result = existing.toMutableList(); var added = 0; var skipped = 0; var conflicts = 0
        incoming.forEach { entry ->
            val family = result.filter { it.id == entry.id || it.originId == entry.originId }
            if (family.any { it.fingerprint() == entry.fingerprint() }) skipped++
            else if (family.isEmpty()) { result += entry; added++ }
            else {
                result += entry.copy(id = UUID.randomUUID().toString(), editOf = "", sourceNote = "备份版本 · 合并时保留")
                added++; conflicts++
            }
        }
        return MergeResult(result, added, skipped, conflicts)
    }
}
