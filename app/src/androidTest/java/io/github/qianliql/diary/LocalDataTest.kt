package io.github.qianliql.diary

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qianliql.diary.backup.*
import io.github.qianliql.diary.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalDataTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repo: DiaryRepository
    private lateinit var foreground: ActivityScenario<MainActivity>
    @Before fun setup() { foreground = ActivityScenario.launch(MainActivity::class.java); context.deleteDatabase("test-diary.db"); repo = DiaryRepository(context, "test-diary.db"); repo.refresh() }
    @After fun close() { repo.close(); foreground.close() }
    @Test fun draftPublishTrashAndExpiry() {
        val draft = repo.beginEdit(null).copy(title = "中文日记", blocks = listOf(Block(text = "今天拍下了晚霞")))
        repo.put(draft); val published = repo.publish(draft)
        assertFalse(repo.entries.value.single().draft)
        val edit = repo.beginEdit(published); repo.put(edit.copy(title = "新标题")); assertEquals("中文日记", repo.entries.value.first { !it.draft }.title)
        repo.publish(edit.copy(title = "新标题")); assertEquals(1, repo.entries.value.size)
        repo.put(published.copy(deletedAt = System.currentTimeMillis() - 31L * 86400000)); repo.purgeExpired(); assertTrue(repo.entries.value.isEmpty())
    }
    @Test fun encryptedBackupWithPhotoRestoresAndMerges() {
        val photo = File(context.cacheDir, "test-photo.png")
        Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 160, 100)); photo.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle() }
        val entry = Entry(title = "图文备份", draft = false, blocks = listOf(Block(text = "可搜索的中文"), Block(type = BlockType.PHOTO, uri = Uri.fromFile(photo).toString(), mediaHash = photo.inputStream().use(::hashStream))))
        repo.put(entry)
        val backup = File(context.cacheDir, "test.diary")
        val service = BackupService(context, repo); service.export(repo.entries.value, Uri.fromFile(backup), "password123".toCharArray())
        repo.remove(entry.id); photo.delete()
        val result = service.restore(Uri.fromFile(backup), "password123".toCharArray()); assertEquals(1, result.added)
        val restored = repo.entries.value.single(); val restoredPhoto = Uri.parse(restored.blocks.last().uri)
        assertTrue(File(restoredPhoto.path!!).isFile); assertFalse(restored.draft)
        assertEquals(1, service.restore(Uri.fromFile(backup), "password123".toCharArray()).skipped)
        val before = repo.entries.value
        assertThrows(Exception::class.java) { service.restore(Uri.fromFile(backup), "wrong".toCharArray()) }; assertEquals(before, repo.entries.value)
        val pdf = File(context.cacheDir, "test.pdf"); PdfExporter(context).export(listOf(restored), Uri.fromFile(pdf)); assertTrue(pdf.length() > 1000)
        assertEquals("%PDF", pdf.inputStream().use { input -> val header = ByteArray(4); input.read(header); String(header) })
    }
    @Test fun missingReferenceIsReportedAndCanBeBackedUp() {
        val entry = Entry(title = "缺失照片", blocks = listOf(Block(type = BlockType.PHOTO, uri = "file:///missing/photo.jpg", mediaHash = "missing-hash")), draft = false)
        repo.put(entry); val service = BackupService(context, repo)
        assertEquals(1, service.inspect(repo.entries.value).missing.size)
        val file = File(context.cacheDir, "missing.diary"); service.export(repo.entries.value, Uri.fromFile(file), "password123".toCharArray())
        repo.remove(entry.id); service.restore(Uri.fromFile(file), "password123".toCharArray())
        assertTrue(repo.entries.value.single().blocks.single().uri.startsWith("missing:"))
    }
}
