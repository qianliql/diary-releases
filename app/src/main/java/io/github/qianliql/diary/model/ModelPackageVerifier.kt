package io.github.qianliql.diary.model

import io.github.qianliql.diary.backup.hashStream
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

object ModelPackageVerifier {
    private val payload = setOf("encoder.onnx", "decoder.onnx", "tokens.txt", "LICENSE.txt")
    fun unpack(zip: File, output: File, size: Long, sha256: String, expandedLimit: Long, checkCancelled: () -> Unit = {}) {
        require(zip.length() == size && zip.inputStream().use(::hashStream) == sha256) { "模型校验失败，请重新下载" }
        output.mkdirs(); val allowed = payload + "model.json"; val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                checkCancelled(); val item = input.nextEntry ?: break
                require(item.name in allowed && seen.add(item.name)) { "模型包格式不正确" }
                File(output, item.name).outputStream().use { file ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; total += n; require(total <= expandedLimit) { "模型包大小异常" }; file.write(buffer, 0, n) }
                }; input.closeEntry()
            }
        }
        require(seen == allowed) { "模型包不完整" }
        val files = JSONObject(File(output, "model.json").readText()).getJSONArray("files")
        val listed = mutableSetOf<String>()
        for (i in 0 until files.length()) {
            checkCancelled(); val item = files.getJSONObject(i); val name = item.getString("name")
            require(name in payload && listed.add(name)) { "模型文件目录不正确" }
            require(File(output, name).inputStream().use(::hashStream) == item.getString("sha256")) { "模型文件已损坏" }
        }
        require(listed == payload) { "模型文件目录不完整" }
    }
}
