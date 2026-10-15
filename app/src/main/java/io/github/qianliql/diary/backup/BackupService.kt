package io.github.qianliql.diary.backup

import android.content.Context
import android.net.Uri
import io.github.qianliql.diary.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupInspection(val entries: List<Entry>, val missing: List<String>)
class BackupService(private val context: Context, private val repo: DiaryRepository) {
    private fun open(uri: String): InputStream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("文件无法读取")
    fun inspect(entries: List<Entry>): BackupInspection {
        val missing = entries.flatMap { e -> e.blocks.filter { it.type != BlockType.TEXT && it.uri.isNotBlank() }.mapNotNull { b ->
            try { open(b.uri).use { it.read() }; null } catch (_: Exception) { "${e.displayTitle} · ${if (b.type == BlockType.PHOTO) "照片" else "录音"}" }
        } }
        return BackupInspection(entries, missing)
    }
    fun export(entries: List<Entry>, destination: Uri, password: CharArray): Int {
        val stage = File(context.cacheDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val mediaDir = File(stage, "media").apply { mkdirs() }
            val missing = mutableListOf<String>()
            val snapshots = entries.map { e -> e.copy(blocks = e.blocks.map { b ->
                if (b.type == BlockType.TEXT || b.uri.isBlank()) b else {
                    val original = try { open(b.uri) } catch (_: FileNotFoundException) { null } catch (_: SecurityException) { null }
                    if (original != null) {
                        val temporary = File(mediaDir, UUID.randomUUID().toString())
                        original.use { i -> temporary.outputStream().use { i.copyTo(it) } }
                        val hash = temporary.inputStream().use(::hashStream)
                        val target = File(mediaDir, hash)
                        if (target.exists()) temporary.delete() else check(temporary.renameTo(target))
                        b.copy(uri = "media/$hash", mediaHash = hash)
                    } else { missing += "${e.id}/${b.id}"; b.copy(uri = "missing:${b.mediaHash}") }
                }
            }) }
            val manifest = JSONObject().put("formatVersion", 1).put("createdAt", System.currentTimeMillis())
                .put("entries", JSONArray(snapshots.map { it.json() })).put("missingMedia", JSONArray(missing))
            val zip = File(stage, "backup.zip")
            ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { out ->
                out.putNextEntry(ZipEntry("manifest.json")); out.write(manifest.toString().toByteArray()); out.closeEntry()
                mediaDir.listFiles().orEmpty().forEach { file ->
                    out.putNextEntry(ZipEntry("media/${file.name}")); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            context.contentResolver.openOutputStream(destination, "wt")!!.use { out -> zip.inputStream().use { BackupCrypto.encrypt(it, out, password) } }
            context.getSharedPreferences("settings", 0).edit().putLong("lastBackup", System.currentTimeMillis()).apply()
            return missing.size
        } finally { password.fill('\u0000'); stage.deleteRecursively() }
    }
    fun restore(source: Uri, password: CharArray): MergeResult {
        val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val installed = mutableListOf<File>()
        try {
            val zip = File(stage, "verified.zip")
            try { open(source.toString()).use { input -> zip.outputStream().use { BackupCrypto.decrypt(input, it, password) } } }
            catch (e: Exception) { throw IOException("密码不正确或备份文件已损坏", e) }
            val unpacked = File(stage, "unpacked").apply { mkdirs() }
            var expanded = 0L; var count = 0
            ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(++count <= 100_000) { "备份文件数量过多" }
                    require(entry.name == "manifest.json" || Regex("media/[a-f0-9]{64}").matches(entry.name)) { "备份包含无效路径" }
                    val file = File(unpacked, entry.name); file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; expanded += n; require(expanded <= 32L * 1024 * 1024 * 1024) { "备份体积超出限制" }; out.write(buffer, 0, n) }
                    }
                    input.closeEntry()
                }
            }
            val mf = File(unpacked, "manifest.json")
            require(mf.length() <= 64L * 1024 * 1024) { "备份目录过大" }
            val manifest = JSONObject(mf.readText()); require(manifest.getInt("formatVersion") == 1) { "备份版本暂不支持" }
            val mediaRoot = File(context.noBackupFilesDir, "media").apply { mkdirs() }
            val mapped = mutableMapOf<String, String>()
            File(unpacked, "media").listFiles().orEmpty().forEach { file ->
                require(file.inputStream().use(::hashStream) == file.name) { "备份媒体校验失败" }
                val destination = File(mediaRoot, file.name)
                if (!destination.exists() || destination.inputStream().use(::hashStream) != file.name) {
                    val temporary = File(mediaRoot, "${file.name}.restoring-${UUID.randomUUID()}")
                    try { file.copyTo(temporary); java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); installed += destination }
                    finally { temporary.delete() }
                }
                mapped["media/${file.name}"] = Uri.fromFile(destination).toString()
            }
            val a = manifest.getJSONArray("entries")
            val restored = (0 until a.length()).map { index ->
                val e = Entry.from(a.getJSONObject(index))
                e.copy(blocks = e.blocks.map { b ->
                    if (b.type == BlockType.TEXT) b
                    else if (b.uri.startsWith("missing:")) b
                    else b.copy(uri = mapped[b.uri] ?: error("备份中缺少媒体文件"))
                })
            }
            return repo.merge(restored)
        } catch (e: Exception) { installed.forEach { it.delete() }; throw e }
        finally { password.fill('\u0000'); stage.deleteRecursively() }
    }
}
fun hashStream(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256"); val b = ByteArray(64 * 1024)
    while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
