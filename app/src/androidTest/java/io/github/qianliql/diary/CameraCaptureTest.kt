package io.github.qianliql.diary

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianliql.diary.data.*
import io.github.qianliql.diary.media.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraCaptureTest {
    private val app = ApplicationProvider.getApplicationContext<DiaryApp>()
    private lateinit var foreground: ActivityScenario<MainActivity>
    private val gallery = mutableListOf<Uri>()
    private val temporary = mutableListOf<Uri>()
    @Before fun setup() { foreground = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() {
        gallery.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
        temporary.forEach { runCatching { MediaFiles.finishCamera(app, it, false) } }
        foreground.close()
    }
    private fun externalWrite(uri: Uri): Pair<Int, Bundle> {
        val done = CountDownLatch(1); var code = 0; var payload = Bundle()
        val report = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) { code = resultCode; payload = resultData; done.countDown() }
        }
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        app.startActivity(CapturePhoto().createIntent(app, uri).apply {
            component = ComponentName(testPackage, FixtureCameraActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra("report", report)
        })
        assertTrue("External camera did not return", done.await(10, TimeUnit.SECONDS))
        assertNotEquals(android.os.Process.myUid(), payload.getInt("writerUid"))
        return code to payload
    }
    @Test fun pendingGalleryRowReproducesExternalCameraFailure() {
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "diary-pending-regression.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        gallery += uri
        val result = externalWrite(uri)
        assertEquals(Activity.RESULT_CANCELED, result.first)
        assertTrue(result.second.getString("error").orEmpty().contains("owner", ignoreCase = true))
    }
    @Test fun separateCameraCanWriteAndPublishedPhotoReopens() {
        val uri = MediaFiles.cameraUri(app).also { temporary += it }
        assertEquals(Activity.RESULT_OK, externalWrite(uri).first)
        val result = MediaFiles.finishCamera(app, uri, true)!!
        assertTrue(result.inGallery); gallery += result.uri
        app.contentResolver.query(result.uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE), null, null, null)!!.use { row ->
            assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0)); assertTrue(row.getLong(1) > 0)
        }
        assertNotNull(MediaFiles.bitmap(app, result.uri.toString()))
        val repo = DiaryRepository(app, "camera-test.db"); repo.refresh()
        val entry = repo.publish(repo.beginEdit(null).copy(blocks = listOf(MediaFiles.block(app, result.uri, BlockType.PHOTO))))
        repo.close()
        DiaryRepository(app, "camera-test.db").use { reopened ->
            reopened.refresh(); assertNotNull(MediaFiles.bitmap(app, reopened.entries.value.first { it.id == entry.id }.blocks.single().uri))
        }
        app.deleteDatabase("camera-test.db")
    }
    @Test fun cancelAndEmptyCameraOutputLeaveNoGalleryImage() {
        val uri = MediaFiles.cameraUri(app).also { temporary += it }
        assertNull(MediaFiles.finishCamera(app, uri, false))
        assertThrows(Exception::class.java) { app.contentResolver.openInputStream(uri) }
        val empty = MediaFiles.cameraUri(app).also { temporary += it }
        assertThrows(IllegalStateException::class.java) { MediaFiles.finishCamera(app, empty, true) }
    }
    @Test fun exifPortraitDirectionAndDecodeSizeAreRespected() {
        val photo = File(app.cacheDir, "camera-orientation.jpg")
        try {
            photo.outputStream().use { stream -> Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.JPEG, 95, stream); recycle() } }
            ExifInterface(photo.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            val decoded = MediaFiles.bitmap(app, Uri.fromFile(photo).toString(), 40)!!
            assertEquals(20, decoded.width); assertEquals(40, decoded.height); decoded.recycle()
        } finally { photo.delete() }
    }
    @Test fun returningResultRestoresDraftEvenBeforeEditorLoads() = runBlocking {
        val uri = MediaFiles.cameraUri(app).also { temporary += it }
        assertEquals(Activity.RESULT_OK, externalWrite(uri).first)
        val draft = app.repository.beginEdit(null).copy(title = "相机返回恢复测试", blocks = listOf(Block(text = "切换前已保存的文字")))
        app.repository.put(draft)
        val handle = SavedStateHandle(mapOf("editorId" to draft.id, "cameraUri" to uri.toString(), "cameraEntryId" to draft.id))
        val store = ViewModelStore()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val vm = DiaryViewModel(app, handle); store.put("camera", vm); vm.finishCamera(true)
            }
            withTimeout(10000) { while (app.repository.entries.value.first { it.id == draft.id }.blocks.none { it.type == BlockType.PHOTO }) delay(20) }
            val restored = app.repository.entries.value.first { it.id == draft.id }
            assertEquals(draft.blocks.first(), restored.blocks.first())
            val captured = restored.blocks.last(); gallery += Uri.parse(captured.uri)
            assertNotNull(MediaFiles.bitmap(app, captured.uri)); assertEquals(2, restored.blocks.size)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }; app.repository.remove(draft.id)
        }
    }
}
