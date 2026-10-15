package io.github.qianliql.diary

import io.github.qianliql.diary.backup.hashStream
import io.github.qianliql.diary.data.sha256
import io.github.qianliql.diary.model.ModelPackageVerifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelPackageTest {
    private fun packageFile(root: File, extra: String? = null): File {
        val data = mapOf("encoder.onnx" to "encoder", "decoder.onnx" to "decoder", "tokens.txt" to "字词表", "LICENSE.txt" to "MIT")
        val manifest = JSONObject().put("files", JSONArray(data.map { (name, body) -> JSONObject().put("name", name).put("sha256", sha256(body.toByteArray())) }))
        val archive = File(root, "model.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            (data + ("model.json" to manifest.toString()) + if (extra != null) mapOf(extra to "bad") else emptyMap()).forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }; return archive
    }
    @Test fun completePackageVerifiesAndExtracts() {
        val root = Files.createTempDirectory("diary-model-test").toFile()
        try { val zip = packageFile(root); val target = File(root, "stage"); ModelPackageVerifier.unpack(zip, target, zip.length(), zip.inputStream().use(::hashStream), 10000); assertEquals("encoder", File(target, "encoder.onnx").readText()) } finally { root.deleteRecursively() }
    }
    @Test fun tamperedDownloadRejectedBeforeExtraction() {
        val root = Files.createTempDirectory("diary-model-test").toFile()
        try { val zip = packageFile(root); val sha = zip.inputStream().use(::hashStream); zip.appendText("corrupt"); val target = File(root, "stage"); assertThrows(IllegalArgumentException::class.java) { ModelPackageVerifier.unpack(zip, target, zip.length(), sha, 10000) }; assertFalse(target.exists()) } finally { root.deleteRecursively() }
    }
    @Test fun rejectsTraversalAndExpandedSizeOverflow() {
        val root = Files.createTempDirectory("diary-model-test").toFile()
        try {
            val zip = packageFile(root, "../outside")
            assertThrows(IllegalArgumentException::class.java) { ModelPackageVerifier.unpack(zip, File(root, "stage"), zip.length(), zip.inputStream().use(::hashStream), 10000) }; assertFalse(File(root, "outside").exists())
            val clean = packageFile(root)
            assertThrows(IllegalArgumentException::class.java) { ModelPackageVerifier.unpack(clean, File(root, "overflow"), clean.length(), clean.inputStream().use(::hashStream), 1) }
        } finally { root.deleteRecursively() }
    }
}
