package io.github.qianliql.diary.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.qianliql.diary.backup.hashStream
import io.github.qianliql.diary.data.Block
import io.github.qianliql.diary.data.BlockType
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object MediaFiles {
    fun cameraUri(context: Context): Uri {
        val root = File(context.filesDir, "camera").apply { check(mkdirs() || isDirectory) }
        val file = File.createTempFile("capture-", ".jpg", root)
        return FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    }
    private fun captureFile(context: Context, uri: Uri): File {
        require(uri.authority == "${context.packageName}.camera" && uri.pathSegments.size == 2 && uri.pathSegments.first() == "capture")
        val root = File(context.filesDir, "camera").canonicalFile
        return File(root, uri.lastPathSegment!!).canonicalFile.also { require(it.parentFile == root) }
    }
    fun finishCamera(context: Context, uri: Uri, success: Boolean): CameraPhoto? {
        val file = captureFile(context, uri)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!success) { file.delete(); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { file.delete(); error("相机没有保存有效照片，请重新拍照") }
        val resolver = context.contentResolver
        var gallery: Uri? = null
        try {
            gallery = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "日记_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/日记")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: error("无法创建相册照片")
            ParcelFileDescriptor.AutoCloseOutputStream(resolver.openFileDescriptor(gallery, "w")!!).use { output ->
                file.inputStream().use { it.copyTo(output) }; output.fd.sync()
            }
            check(resolver.update(gallery, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            file.delete()
            return CameraPhoto(gallery, true)
        } catch (_: Exception) {
            gallery?.let { runCatching { resolver.delete(it, null, null) } }
            // Keep a readable original in the diary when gallery storage is temporarily unavailable.
            return CameraPhoto(Uri.fromFile(file), false)
        }
    }
    fun block(context: Context, uri: Uri, type: BlockType, duration: Long = 0): Block {
        val hash = context.contentResolver.openInputStream(uri)!!.use(::hashStream)
        return Block(type = type, uri = uri.toString(), mediaHash = hash, durationMs = duration)
    }
    fun bitmap(context: Context, uri: String, max: Int = 1600): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, parsed)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, max.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
    }.getOrNull()
}

/** Records 16 kHz mono PCM to a WAV file; no external speech service or audio upload. */
class WavRecorder(private val context: Context) {
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var file: File? = null
    private var failure: Exception? = null
    private var started = 0L
    val isRecording: Boolean get() = running.get()
    fun start() {
        check(!running.get())
        check(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
        val root = File(context.noBackupFilesDir, "media").apply { mkdirs() }
        val target = File(root, "${UUID.randomUUID()}.wav")
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "当前设备无法录制音频" }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        failure = null; file = target; recorder = audio; running.set(true); started = System.currentTimeMillis()
        try { audio.startRecording() } catch (e: Exception) { running.set(false); audio.release(); recorder = null; target.delete(); throw e }
        thread = Thread {
            try {
                RandomAccessFile(target, "rw").use { out ->
                    out.write(ByteArray(44)); val bytes = ByteArray(8192)
                    while (running.get()) { val n = audio.read(bytes, 0, bytes.size); if (n < 0) { if (running.get()) throw IOException("麦克风读取失败") else break }; if (n > 0) out.write(bytes, 0, n) }
                    out.seek(0); out.write(wavHeader(out.length() - 44)); out.fd.sync()
                }
            } catch (e: Exception) { failure = e; running.set(false) }
        }.apply { name = "DiaryRecorder"; start() }
    }
    fun stop(): Pair<Uri, Long>? {
        if (recorder == null) return null
        running.set(false)
        runCatching { recorder?.stop() }; thread?.join(3000); recorder?.release(); recorder = null
        failure?.let { file?.delete(); throw it }
        val target = file ?: return null
        if (target.length() <= 44) { target.delete(); return null }
        return Uri.fromFile(target) to ((target.length() - 44) * 1000 / 32000)
    }
    companion object {
        fun wavHeader(length: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + length).toInt()); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(length.toInt())
        }.array()
    }
}

fun readWavChunk(file: File, offsetSamples: Long, count: Int): FloatArray {
    RandomAccessFile(file, "r").use { input ->
        input.seek(44 + offsetSamples * 2)
        val n = minOf(count.toLong(), (input.length() - input.filePointer) / 2).coerceAtLeast(0).toInt()
        val bytes = ByteArray(n * 2); input.readFully(bytes)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { b.short / 32768f }
    }
}
