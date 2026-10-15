package io.github.qianliql.diary.model

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import io.github.qianliql.diary.backup.hashStream
import io.github.qianliql.diary.media.readWavChunk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

data class ModelCatalog(val version: String, val name: String, val size: Long, val unpackedSize: Long, val sha256: String, val url: String, val license: String) {
    companion object {
        fun from(o: JSONObject) = ModelCatalog(o.getString("version"), o.getString("name"), o.getLong("size"), o.getLong("unpackedSize"), o.getString("sha256"), o.getString("url"), o.getString("license"))
    }
}
data class ModelState(val installed: Boolean = false, val enabled: Boolean = false, val busy: Boolean = false, val progress: Float = 0f, val message: String = "未安装", val version: String = "")
internal data class ModelInstallEnvironment(
    val connection: (String) -> HttpURLConnection = ::trustedConnection,
    val usableSpace: (File) -> Long = { it.usableSpace },
)
class ModelManager internal constructor(private val context: Context, private val environment: ModelInstallEnvironment, initialCatalog: ModelCatalog?) {
    constructor(context: Context) : this(context, ModelInstallEnvironment(), null)
    private val preferences = context.getSharedPreferences("settings", 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val root = File(context.noBackupFilesDir, "speech-model")
    private val mutable = MutableStateFlow(ModelState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    var catalog: ModelCatalog = initialCatalog ?: ModelCatalog.from(JSONObject(context.assets.open("model-catalog.json").bufferedReader().use { it.readText() })); private set
    init { refresh() }
    private fun refresh(message: String? = null) {
        val installed = listOf("encoder.onnx", "decoder.onnx", "tokens.txt", "installed.json").all { File(root, it).isFile }
        val version = if (installed) runCatching { JSONObject(File(root, "installed.json").readText()).getString("version") }.getOrDefault("") else ""
        mutable.value = ModelState(installed, installed && preferences.getBoolean("speechEnabled", false), message = message ?: if (installed) "已安装 · 仅在本机运行" else "未安装", version = version)
    }
    fun setEnabled(enabled: Boolean) { preferences.edit().putBoolean("speechEnabled", enabled && state.value.installed).apply(); refresh() }
    @Synchronized fun cancel() {
        val running = job?.takeIf { it.isActive } ?: return
        mutable.value = mutable.value.copy(message = "正在取消安装…")
        running.cancel()
        // A coroutine cancellation alone cannot wake a blocking socket read.
        activeConnection.get()?.let { connection -> scope.launch { connection.disconnect() } }
    }
    suspend fun checkUpdates(): Boolean = withContext(Dispatchers.IO) {
        val c = environment.connection("https://github.com/qianliql/diary-releases/releases/download/models-v1/model-catalog.json")
        try {
            require(c.responseCode == 200) { "暂时无法读取模型版本，请稍后重试" }
            val text = c.inputStream.bufferedReader().use { it.readText() }; require(text.length < 64_000)
            val remote = ModelCatalog.from(JSONObject(text))
            require(remote.url.startsWith("https://github.com/qianliql/diary-releases/releases/download/") && remote.size in 1..500_000_000 && Regex("[a-f0-9]{64}").matches(remote.sha256)) { "模型版本信息校验失败" }
            catalog = remote
            val available = !state.value.installed || state.value.version != remote.version
            refresh(if (available) "有可安装的模型版本：${remote.version}" else "模型已是当前版本")
            available
        } finally { c.disconnect() }
    }
    @Synchronized fun install() {
        if (job != null || mutable.value.busy) return
        val requested = catalog
        mutable.value = mutable.value.copy(busy = true, progress = 0f, message = "正在准备安装")
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var stage: File? = null
            var outcome = "安装失败，请重试"
            try {
                mutex.withLock {
                    val staging = File(context.noBackupFilesDir, "model-install")
                    stage = staging
                    check(!staging.exists() || staging.deleteRecursively()) { "无法清理安装临时文件" }
                    check(staging.mkdirs()) { "无法创建安装临时目录" }
                    require(environment.usableSpace(context.noBackupFilesDir) > requested.size + requested.unpackedSize + 64L * 1024 * 1024) { "空间不足，请先腾出空间再安装" }
                    ensureActive()
                    mutable.value = mutable.value.copy(message = "正在下载，保持应用打开")
                    val zip = File(staging, "model.zip")
                    download(requested.url, zip, requested.size) { p -> mutable.value = mutable.value.copy(progress = p * .8f) }
                    ensureActive()
                    mutable.value = mutable.value.copy(message = "正在校验和安装", progress = .85f)
                    val unpacked = File(staging, "ready").apply { mkdirs() }
                    val coroutine = currentCoroutineContext()
                    ModelPackageVerifier.unpack(zip, unpacked, requested.size, requested.sha256, requested.unpackedSize) { coroutine.ensureActive() }
                    File(unpacked, "installed.json").writeText(JSONObject().put("version", requested.version).toString())
                    ensureActive()
                    val previous = File(context.noBackupFilesDir, "speech-model.previous").apply { deleteRecursively() }
                    if (root.exists()) check(root.renameTo(previous))
                    if (!unpacked.renameTo(root)) { previous.renameTo(root); error("模型安装失败") }
                    previous.deleteRecursively(); preferences.edit().putBoolean("speechEnabled", true).apply()
                    outcome = "模型已安装，可以离线转写"
                }
            } catch (_: CancellationException) { outcome = "安装已取消，可随时重试" }
            catch (e: Exception) { outcome = if (!currentCoroutineContext().isActive) "安装已取消，可随时重试" else e.message ?: "安装失败，请重试" }
            finally {
                runCatching { stage?.deleteRecursively() }
                synchronized(this@ModelManager) { job = null; refresh(outcome) }
            }
        }
        job = task; task.start()
    }
    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock { root.deleteRecursively(); preferences.edit().putBoolean("speechEnabled", false).apply(); refresh("模型已删除，录音和转写仍然保留") } }
    suspend fun transcribe(uri: String, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(state.value.installed && state.value.enabled) { "请先在设置中安装并启用语音模型" }
            val manifest = JSONObject(File(root, "model.json").readText()).getJSONArray("files")
            for (i in 0 until manifest.length()) {
                val file = manifest.getJSONObject(i)
                require(file.getString("name") in setOf("encoder.onnx", "decoder.onnx", "tokens.txt", "LICENSE.txt"))
                require(File(root, file.getString("name")).inputStream().use(::hashStream) == file.getString("sha256")) { "已安装的模型文件损坏，请在设置中重新安装" }
            }
            val model = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = File(root, "encoder.onnx").path, decoder = File(root, "decoder.onnx").path, language = "zh", task = "transcribe"),
                tokens = File(root, "tokens.txt").path, numThreads = 2, provider = "cpu", debug = false)
            val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = model))
            val temp = File(context.cacheDir, "transcribe-${System.nanoTime()}.wav")
            try {
                context.contentResolver.openInputStream(Uri.parse(uri))!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
                val total = ((temp.length() - 44) / 2).coerceAtLeast(1)
                val output = mutableListOf<String>(); var offset = 0L
                while (offset < total) {
                    ensureActive(); val samples = readWavChunk(temp, offset, 16000 * 25)
                    if (samples.isEmpty()) break
                    if (samples.any { kotlin.math.abs(it) > .015f }) {
                        val stream = recognizer.createStream()
                        try { stream.acceptWaveform(samples, 16000); recognizer.decode(stream); output += recognizer.getResult(stream).text.trim() }
                        finally { stream.release() }
                    }
                    offset += samples.size; onProgress(offset.toFloat() / total)
                }
                output.filter { it.isNotBlank() }.joinToString("\n").ifBlank { error("没有识别到清晰语音，请保留录音并稍后重试") }
            } finally { recognizer.release(); temp.delete() }
        }
    }
    private suspend fun download(address: String, target: File, expected: Long, progress: (Float) -> Unit) {
        val connection = environment.connection(address)
        activeConnection.set(connection)
        try {
            currentCoroutineContext().ensureActive()
            require(connection.responseCode == 200) { "下载服务暂不可用（${connection.responseCode}），请稍后重试" }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var read = 0L
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; read += n; require(read <= expected) { "下载文件大小异常" }; output.write(buffer, 0, n); progress(read.toFloat() / expected) }
            } }
        } finally { activeConnection.compareAndSet(connection, null); connection.disconnect() }
    }
}
fun trustedConnection(address: String): HttpURLConnection {
    val url = URL(address)
    require(url.protocol == "https" && (url.host == "api.github.com" || url.host == "github.com" || url.host == "release-assets.githubusercontent.com" || url.host.endsWith(".githubusercontent.com"))) { "更新地址不受支持" }
    return (url.openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000; setRequestProperty("User-Agent", "Diary-Android/1.0"); setRequestProperty("Accept", "application/vnd.github+json") }
}

object UpdateClient {
    const val RELEASE_PAGE = "https://github.com/qianliql/diary-releases/releases"
    suspend fun check(current: Long): String? = withContext(Dispatchers.IO) {
        val c = trustedConnection("https://api.github.com/repos/qianliql/diary-releases/releases?per_page=100")
        try {
            if (c.responseCode == 404) return@withContext null
            require(c.responseCode == 200) { "暂时无法检查更新，请稍后重试" }
            val data = c.inputStream.bufferedReader().use { it.readText() }; require(data.length < 1_000_000)
            ReleaseVersions.newer(org.json.JSONArray(data), current)
        } finally { c.disconnect() }
    }
}
