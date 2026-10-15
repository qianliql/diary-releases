package io.github.qianliql.diary

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianliql.diary.backup.hashStream
import io.github.qianliql.diary.data.*
import io.github.qianliql.diary.model.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile

/** Explicit opt-in only: downloads the public model twice, never production diary data. */
class LiveModelInstallationTest {
    private val app = ApplicationProvider.getApplicationContext<DiaryApp>()
    private var foreground: ActivityScenario<MainActivity>? = null
    private var manager: ModelManager? = null
    private var entry: Entry? = null
    @After fun close() = runBlocking {
        manager?.let { it.cancel(); withTimeout(20000) { it.delete() } }
        entry?.let { app.repository.remove(it.id) }
        foreground?.close()
        Unit
    }
    private suspend fun eventually(timeout: Long = 30000, predicate: () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(10) }
    }
    @Test fun githubDownloadCancelVerifyTranscribeDeleteAndReinstall() = runBlocking {
        Assume.assumeTrue(app.packageName.endsWith(".networkqa") && InstrumentationRegistry.getArguments().getString("liveModel") == "true")
        val sample = File(app.filesDir, "qa-sample.wav")
        Assume.assumeTrue("Stage the public Mandarin fixture first", sample.isFile)
        foreground = ActivityScenario.launch(MainActivity::class.java)
        val model = ModelManager(app); manager = model; model.delete()
        val audio = File(app.noBackupFilesDir, "media/live-model.wav").apply { parentFile!!.mkdirs(); sample.copyTo(this, overwrite = true) }
        var saved = Entry(title = "真实模型下载验收", draft = false, blocks = listOf(Block(type = BlockType.AUDIO,
            uri = Uri.fromFile(audio).toString(), mediaHash = audio.inputStream().use(::hashStream), transcript = "下载前已有转写")))
        entry = saved; app.repository.put(saved)
        fun intact() {
            app.repository.refresh(); assertEquals(saved, app.repository.entries.value.first { it.id == saved.id })
            assertEquals(saved.blocks.single().mediaHash, audio.inputStream().use(::hashStream))
        }
        model.install(); eventually(120000) { model.state.value.progress > 0f || !model.state.value.busy }
        assertTrue("Download stopped before cancellation: ${model.state.value}", model.state.value.busy)
        model.cancel(); eventually(5000) { !model.state.value.busy }
        assertTrue(model.state.value.message.contains("已取消")); assertFalse(model.state.value.installed); intact()
        val durations = mutableListOf<Double>()
        suspend fun download() {
            val started = System.nanoTime(); model.install(); eventually(240000) { !model.state.value.busy }
            assertTrue(model.state.value.message, model.state.value.installed)
            durations += (System.nanoTime() - started) / 1e9; intact()
        }
        download()
        val offline = ModelManager(app, ModelInstallEnvironment(connection = { error("Transcription attempted network access") }), null)
        val text = offline.transcribe(Uri.fromFile(audio).toString()) { }
        assertTrue(text.any { it in '\u4e00'..'\u9fff' })
        saved = saved.copy(blocks = listOf(saved.blocks.single().copy(transcript = text))); entry = saved; app.repository.put(saved)
        val encoder = File(app.noBackupFilesDir, "speech-model/encoder.onnx")
        RandomAccessFile(encoder, "rw").use { file -> val first = file.read(); file.seek(0); file.write(first xor 1) }
        try { offline.transcribe(Uri.fromFile(audio).toString()) { }; fail("Corrupt installed weights must be rejected before native inference") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("损坏")) }
        intact(); model.delete(); assertFalse(model.state.value.installed); intact()
        download()
        assertTrue(ModelManager(app).transcribe(Uri.fromFile(audio).toString()) { }.any { it in '\u4e00'..'\u9fff' }); intact()
        File(app.filesDir, "qa-live-model.txt").writeText("GitHub real downloads seconds: $durations\nCancellation, SHA256 verification, Mandarin inference, corruption rejection, deletion and reinstallation passed.\n")
        Unit
    }
}
