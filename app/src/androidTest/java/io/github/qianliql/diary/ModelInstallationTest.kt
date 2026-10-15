package io.github.qianliql.diary

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qianliql.diary.backup.hashStream
import io.github.qianliql.diary.data.*
import io.github.qianliql.diary.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.zip.*

/** Real loopback TCP faults; available only in the separately named networkQa application. */
@RunWith(AndroidJUnit4::class)
class ModelInstallationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var foreground: ActivityScenario<MainActivity>
    private lateinit var server: ModelFaultServer
    private lateinit var manager: ModelManager
    private lateinit var recording: File
    private lateinit var entry: Entry
    private val repo get() = (context.applicationContext as DiaryApp).repository
    @Before fun setup() = runBlocking {
        Assume.assumeTrue(context.packageName.endsWith(".networkqa"))
        foreground = ActivityScenario.launch(MainActivity::class.java)
        ModelManager(context).delete()
        server = ModelFaultServer()
        manager = ModelManager(context, ModelInstallEnvironment(connection = { server.connection() }), server.catalog)
        recording = File(context.noBackupFilesDir,"media/model-test-original.wav").apply { parentFile!!.mkdirs(); writeText("synthetic original recording") }
        entry = Entry(title="模型安装验收",draft=false,blocks=listOf(Block(type=BlockType.AUDIO,uri=Uri.fromFile(recording).toString(),transcript="需要保留的既有转写",mediaHash=recording.inputStream().use(::hashStream))))
        repo.put(entry)
    }
    @After fun cleanup() = runBlocking {
        if (::server.isInitialized) server.close()
        if (::manager.isInitialized) { manager.cancel(); withTimeout(6000) { manager.delete() } }
        if (::entry.isInitialized) repo.remove(entry.id)
        if (::foreground.isInitialized) foreground.close()
    }
    private suspend fun eventually(timeout: Long = 10000, predicate: () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(20) }
    }
    private suspend fun install() {
        val requests = server.requests.get()+1
        manager.install(); eventually { server.requests.get() >= requests && !manager.state.value.busy }
    }
    private fun assertDiaryIntact() {
        repo.refresh(); assertEquals(entry,repo.entries.value.first { it.id==entry.id })
        assertEquals("synthetic original recording",recording.readText())
    }
    @Test fun noModelDoesNotConnectOrDamageRecording() = runBlocking {
        assertFalse(manager.state.value.installed); manager.setEnabled(true)
        assertFalse(manager.state.value.enabled)
        try { manager.transcribe(Uri.fromFile(recording).toString()) { }; fail("Expected missing model guidance") } catch (_: IllegalStateException) { }
        assertEquals(0,server.requests.get()); assertDiaryIntact()
    }
    @Test fun cancelBlockedDownloadAndRetryPreservesOldModel() = runBlocking {
        install(); assertTrue(manager.state.value.installed); manager.setEnabled(false)
        val encoder = File(context.noBackupFilesDir,"speech-model/encoder.onnx"); val before=encoder.inputStream().use(::hashStream)
        server.mode="STALL"; manager.install()
        assertTrue(server.stalled.await(5,TimeUnit.SECONDS))
        manager.cancel()
        eventually(3000) { !manager.state.value.busy }
        assertTrue(manager.state.value.message.contains("已取消")); assertTrue(manager.state.value.installed); assertFalse(manager.state.value.enabled)
        assertEquals(before,encoder.inputStream().use(::hashStream)); assertDiaryIntact()
        server.release.countDown(); server.mode="VALID"; install()
        assertTrue(manager.state.value.installed); assertTrue(manager.state.value.enabled); assertDiaryIntact()
    }
    @Test fun duplicateInstallTapStartsOneDownload() = runBlocking {
        val reached=CountDownLatch(1); val proceed=CountDownLatch(1)
        manager=ModelManager(context,ModelInstallEnvironment(connection={server.connection()},usableSpace={ reached.countDown(); proceed.await(5,TimeUnit.SECONDS); it.usableSpace }),server.catalog)
        manager.install(); assertTrue(reached.await(5,TimeUnit.SECONDS)); manager.install(); proceed.countDown()
        eventually { server.requests.get()>=1 && !manager.state.value.busy }
        delay(300); assertEquals("Duplicate taps must not queue a second download",1,server.requests.get()); assertDiaryIntact()
    }
    @Test fun interruptedConnectionCanRetry() = runBlocking {
        server.mode="DROP"; install()
        assertFalse(manager.state.value.installed); assertDiaryIntact()
        eventually { !File(context.noBackupFilesDir,"model-install").exists() }
        server.mode="VALID"; install(); assertTrue(manager.state.value.installed); assertDiaryIntact()
    }
    @Test fun corruptedReplacementKeepsOldModelAndDeletionKeepsDiary() = runBlocking {
        install(); assertTrue(manager.state.value.installed)
        val encoder=File(context.noBackupFilesDir,"speech-model/encoder.onnx"); val before=encoder.inputStream().use(::hashStream)
        server.mode="CORRUPT"; install()
        assertTrue(manager.state.value.installed); assertTrue(manager.state.value.message.contains("校验")); assertEquals(before,encoder.inputStream().use(::hashStream)); assertDiaryIntact()
        manager.delete(); assertFalse(manager.state.value.installed); assertFalse(manager.state.value.enabled); assertDiaryIntact()
        server.mode="VALID"; install(); assertTrue(manager.state.value.installed); assertDiaryIntact()
    }
    @Test fun insufficientSpaceDoesNotConnectOrReplaceOldModel() = runBlocking {
        install(); val before=File(context.noBackupFilesDir,"speech-model/encoder.onnx").inputStream().use(::hashStream)
        manager=ModelManager(context,ModelInstallEnvironment(connection={server.connection()},usableSpace={0L}),server.catalog)
        val calls=server.requests.get(); manager.install()
        eventually { manager.state.value.message.contains("空间不足") && !manager.state.value.busy }
        assertEquals(calls,server.requests.get()); assertTrue(manager.state.value.installed)
        assertEquals(before,File(context.noBackupFilesDir,"speech-model/encoder.onnx").inputStream().use(::hashStream)); assertDiaryIntact()
    }
}

internal class ModelFaultServer : Closeable {
    private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val clients=ConcurrentLinkedQueue<Socket>()
    val requests=AtomicInteger(0); val stalled=CountDownLatch(1); val release=CountDownLatch(1)
    @Volatile var mode="VALID"
    private val body: ByteArray
    val catalog: ModelCatalog
    init {
        val random=java.util.Random(42)
        val payload=linkedMapOf("encoder.onnx" to ByteArray(65536).also(random::nextBytes),"decoder.onnx" to ByteArray(16384).also(random::nextBytes),"tokens.txt" to "fixture tokens".toByteArray(),"LICENSE.txt" to "test fixture".toByteArray())
        val files=JSONArray(payload.map { (name,bytes)->JSONObject().put("name",name).put("size",bytes.size).put("sha256",sha256(bytes)) })
        payload["model.json"]=JSONObject().put("files",files).toString().toByteArray()
        val buffer=ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out->payload.forEach { (name,bytes)->out.putNextEntry(ZipEntry(name));out.write(bytes);out.closeEntry() } }
        body=buffer.toByteArray()
        catalog=ModelCatalog("qa-fixture","测试模型",body.size.toLong(),payload.values.sumOf { it.size }.toLong(),sha256(body),"https://github.com/qianliql/diary-releases/releases/download/models-v1/qa-fixture.zip","test fixture")
        Thread {
            while (!socket.isClosed) {
                val client=try { socket.accept() } catch (_: IOException) { break }
                clients.add(client)
                Thread {
                    try { client.use { peer ->
                        val input=peer.getInputStream().bufferedReader(); while (true) { val line=input.readLine()?:break; if (line.isEmpty()) break }
                        val current=mode; requests.incrementAndGet()
                        val out=peer.getOutputStream(); out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                        when(current) {
                            "STALL" -> { out.write(body,0,64);out.flush();stalled.countDown();release.await(20,TimeUnit.SECONDS);out.write(body,64,body.size-64) }
                            "DROP" -> out.write(body,0,body.size/3)
                            "CORRUPT" -> out.write(body.copyOf().apply { this[size/2]=(this[size/2].toInt() xor 1).toByte() })
                            else -> out.write(body)
                        };out.flush()
                    } } catch (_: Exception) { } finally { clients.remove(client) }
                }.apply { isDaemon=true;name="QaModelResponse";start() }
            }
        }.apply { isDaemon=true;name="QaModelServer";start() }
    }
    fun connection(): HttpURLConnection = (URL("http://127.0.0.1:${socket.localPort}/model.zip").openConnection() as HttpURLConnection).apply { connectTimeout=3000;readTimeout=15000 }
    override fun close() { release.countDown();socket.close();clients.forEach { runCatching { it.close() } } }
}
