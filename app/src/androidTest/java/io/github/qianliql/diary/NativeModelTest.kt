package io.github.qianliql.diary

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qianliql.diary.model.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

/** Run explicitly after staging verified optional model files and qa-sample.wav with adb run-as. */
@RunWith(AndroidJUnit4::class)
class NativeModelTest {
    private lateinit var foreground: ActivityScenario<MainActivity>
    @Before fun open() { foreground = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() { foreground.close() }
    @Test fun offlineMandarinAndDeletionPreserveDiary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Offline acceptance app must have no Internet permission", android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.INTERNET))
        val sample = File(context.filesDir, "qa-sample.wav")
        org.junit.Assume.assumeTrue("Optional model fixture not staged", sample.isFile)
        val app = context.applicationContext as DiaryApp
        app.repository.refresh(); val before = app.repository.entries.value
        val manager = ModelManager(context)
        assertTrue(manager.state.value.installed); manager.setEnabled(true)
        val transcript = manager.transcribe(android.net.Uri.fromFile(sample).toString()) { }
        assertTrue("Expected Mandarin text, got: $transcript", transcript.any { it in '\u4e00'..'\u9fff' })
        File(context.filesDir, "qa-transcript.txt").writeText(transcript)
        val pcm = sample.readBytes().drop(44).toByteArray()
        val longAudio = File(context.filesDir, "qa-long.wav")
        val length = 16000 * 2 * 600
        longAudio.outputStream().use { out ->
            out.write(io.github.qianliql.diary.media.WavRecorder.wavHeader(length.toLong()))
            var remaining = length
            while (remaining > 0) { val count = minOf(remaining, pcm.size); out.write(pcm, 0, count); remaining -= count }
        }
        val started = System.nanoTime()
        val longText = manager.transcribe(android.net.Uri.fromFile(longAudio).toString()) { }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(longText.isNotBlank()); assertTrue("10 minute transcription exceeded 10 minutes", seconds < 600)
        File(context.filesDir, "qa-performance.txt").writeText("600-second WAV processed offline in $seconds seconds on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}.\n")
        longAudio.delete()
        manager.delete()
        assertFalse(manager.state.value.installed); assertFalse(manager.state.value.enabled)
        app.repository.refresh(); assertEquals(before, app.repository.entries.value); assertTrue(sample.exists())
        sample.delete()
        Unit
    }
}
