package io.github.qianliql.diary
import io.github.qianliql.diary.data.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MergePlannerTest {
    private val entry = Entry(id = "original", originId = "original", title = "第一天", draft = false,
        blocks = listOf(Block(type = BlockType.PHOTO, uri = "content://photo/1", mediaHash = "abc"), Block(text = "你好")))
    @Test fun migrationUriDoesNotDuplicate() {
        val restored = entry.copy(blocks = entry.blocks.map { if (it.type == BlockType.PHOTO) it.copy(uri = "file://new/photo") else it })
        val result = MergePlanner.merge(listOf(entry), listOf(restored)); assertEquals(1, result.skipped); assertEquals(1, result.entries.size)
    }
    @Test fun changedVersionPreservedAndRepeatedImportIdempotent() {
        val changed = entry.copy(title = "另一个版本")
        val once = MergePlanner.merge(listOf(entry), listOf(changed)); assertEquals(1, once.conflicts); assertEquals(2, once.entries.size)
        assertTrue(once.entries.last().sourceNote.isNotEmpty()); assertNotEquals(entry.id, once.entries.last().id)
        val twice = MergePlanner.merge(once.entries, listOf(changed)); assertEquals(1, twice.skipped); assertEquals(2, twice.entries.size)
    }
    @Test fun keepsDeletionAndDraftStateInMerge() {
        val draft = entry.copy(id = "draft", originId = "draft", draft = true, deletedAt = 10)
        val result = MergePlanner.merge(emptyList(), listOf(draft)); assertEquals(draft, result.entries.single())
    }
    @Test fun serializationPreservesOrderedBlocksAndChineseTags() {
        val value = entry.copy(tags = listOf("旅行", "家人"), mood = "😊 开心", sourceNote = "备份版本")
        assertEquals(value, Entry.from(JSONObject(value.json().toString())))
    }
}
