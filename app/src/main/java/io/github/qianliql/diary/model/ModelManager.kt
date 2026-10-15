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
import java.util.zip.ZipInputStream

data class ModelCatalog(val version: String, val name: String, val size: Long, val unpackedSize: Long, val sha256: String, val url: String, val license: String) {
    companion object {
        fun from(o: JSONObject) = ModelCatalog(o.getString("version"), o.getString("name"), o.getLong("size"), o.getLong("unpackedSize"), o.getString("sha256"), o.getString("url"), o.getString("license"))
    }
}
data class ModelState(val installed: Boolean = false, val enabled: Boolean = false, val busy: Boolean = false, val progress: Float = 0f, val message: String = "未安装", val version: String = "")
class ModelManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("settings", 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val root = File(context.noBackupFilesDir, "speech-model")
    private val mutable = MutableStateFlow(ModelState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    var catalog: ModelCatalog = ModelCatalog.from(JSONObject(context.assets.open("model-catalog.json").bufferedReader().use { it.readText() })); private set
    init { refresh() }
    private fun refresh(message: String? = null) {
        val installed = listOf("encoder.onnx", "decoder.onnx", "tokens.txt", "installed.json").all { File(root, it).isFile }
        val version = if (installed) runCatching { JSONObject(File(root, "installed.json").readText()).getString("version") }.getOrDefault("") else ""
        mutable.value = ModelState(installed, installed && preferences.getBoolean("speechEnabled", false), message = message ?: if (installed) "已安装 · 仅在本机运行" else "未安装", version = version)
    }
    fun setEnabled(enabled: Boolean) { preferences.edit().putBoolean("speechEnabled", enabled && state.value.installed).apply(); refresh() }
    fun cancel() { mutable.value = mutable.value.copy(message = "正在取消安装…"); job?.cancel() }
    suspend fun checkUpdates(): Boolean = withContext(Dispatchers.IO) {
        val c = trustedConnection("https://github.com/qianliql/diary-releases/releases/download/models-v1/model-catalog.json")
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
    fun install() {
        if (mutable.value.busy) return
        job = scope.launch {
            mutex.withLock {
                val stage = File(context.noBackupFilesDir, "model-install").apply { deleteRecursively(); mkdirs() }
                val old = mutable.value
                try {
                    require(context.noBackupFilesDir.usableSpace > catalog.size + catalog.unpackedSize + 64L * 1024 * 1024) { "空间不足，请先腾出空间再安装" }
                    mutable.value = old.copy(busy = true, progress = 0f, message = "正在下载，保持应用打开")
                    val zip = File(stage, "model.zip")
                    download(catalog.url, zip, catalog.size) { p -> mutable.value = mutable.value.copy(progress = p * .8f) }
                    ensureActive()
                    mutable.value = mutable.value.copy(message = "正在校验和安装", progress = .85f)
                    val unpacked = File(stage, "ready").apply { mkdirs() }
                    val coroutine = currentCoroutineContext()
                    ModelPackageVerifier.unpack(zip, unpacked, catalog.size, catalog.sha256, catalog.unpackedSize) { coroutine.ensureActive() }
                    File(unpacked, "installed.json").writeText(JSONObject().put("version", catalog.version).toString())
                    val previous = File(context.noBackupFilesDir, "speech-model.previous").apply { deleteRecursively() }
                    if (root.exists()) check(root.renameTo(previous))
                    if (!unpacked.renameTo(root)) { previous.renameTo(root); error("模型安装失败") }
                    previous.deleteRecursively(); preferences.edit().putBoolean("speechEnabled", true).apply(); refresh("模型已安装，可以离线转写")
                } catch (_: CancellationException) { refresh("安装已取消，可随时重试") }
                catch (e: Exception) { refresh(e.message ?: "安装失败，请重试") }
                finally { stage.deleteRecursively() }
            }
        }
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
        val connection = trustedConnection(address)
        try {
            require(connection.responseCode == 200) { "下载服务暂不可用（${connection.responseCode}），请稍后重试" }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var read = 0L
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; read += n; require(read <= expected) { "下载文件大小异常" }; output.write(buffer, 0, n); progress(read.toFloat() / expected) }
            } }
        } finally { connection.disconnect() }
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
        val c = trustedConnection("https://api.github.com/repos/qianliql/diary-releases/releases/latest")
        try {
            if (c.responseCode == 404) return@withContext null
            require(c.responseCode == 200) { "暂时无法检查更新，请稍后重试" }
            val data = c.inputStream.bufferedReader().use { it.readText() }; require(data.length < 1_000_000)
            val release = JSONObject(data); val code = release.optString("tag_name").removePrefix("v").split('.').let { p -> 1 + (p.getOrNull(0)?.toLongOrNull() ?: 0) * 10000 + (p.getOrNull(1)?.toLongOrNull() ?: 0) * 100 + (p.getOrNull(2)?.toLongOrNull() ?: 0) }
            if (code > current) release.getString("html_url").also { require(it.startsWith("https://github.com/qianliql/diary-releases/")) } else null
        } finally { c.disconnect() }
    }
}
