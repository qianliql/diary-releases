package io.github.qianliql.diary.backup

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import io.github.qianliql.diary.data.*
import io.github.qianliql.diary.media.MediaFiles
import java.time.*
import java.time.format.DateTimeFormatter

class PdfExporter(private val context: Context) {
    fun export(entries: List<Entry>, destination: Uri) {
        val document = PdfDocument(); var page: PdfDocument.Page? = null; var y = 0f; var number = 0
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(53, 44, 39); textSize = 12f }
        fun newPage() {
            page?.let { document.finishPage(it) }
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create()); y = 54f
            page!!.canvas.drawColor(Color.WHITE)
        }
        fun ensure(height: Float) { if (page == null || y + height > 786) newPage() }
        fun text(value: String, size: Float = 12f, bold: Boolean = false) {
            ink.textSize = size; ink.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            value.split('\n').forEach { line ->
                if (line.isEmpty()) { ensure(size * 1.5f); y += size * 1.5f }
                else {
                    var start = 0
                    while (start < line.length) {
                        var chars = ink.breakText(line, start, line.length, true, 487f, null).coerceAtLeast(1)
                        if (start + chars < line.length && Character.isHighSurrogate(line[start + chars - 1])) chars--
                        ensure(size * 1.7f); page!!.canvas.drawText(line, start, start + chars, 54f, y, ink); y += size * 1.7f; start += chars
                    }
                }
            }
        }
        try {
            entries.sortedBy { it.date }.forEachIndexed { index, e ->
                if (index > 0) newPage()
                text(e.displayTitle, 22f, true)
                text(Instant.ofEpochMilli(e.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")), 11f)
                if (e.mood.isNotBlank() || e.tags.isNotEmpty()) text((listOf(e.mood) + e.tags.map { "#$it" }).filter { it.isNotBlank() }.joinToString("  "), 11f)
                y += 16
                e.blocks.forEach { b ->
                    when (b.type) {
                        BlockType.TEXT -> if (b.text.isNotBlank()) text(b.text)
                        BlockType.AUDIO -> {
                            text("语音记录 · ${b.durationMs / 60000}分${b.durationMs / 1000 % 60}秒", 11f, true)
                            if (b.transcript.isNotBlank()) text(b.transcript) else text("（未转写，原始录音保存在完整备份中）", 10f)
                        }
                        BlockType.PHOTO -> {
                            val image = MediaFiles.bitmap(context, b.uri, 1400)
                            if (image == null) text("（原照片已失效）", 10f)
                            else {
                                val ratio = minOf(487f / image.width, 630f / image.height)
                                val w = image.width * ratio; val h = image.height * ratio
                                ensure(h + 12); page!!.canvas.drawBitmap(image, null, RectF(54f, y, 54f + w, y + h), null); y += h + 12; image.recycle()
                            }
                        }
                    }; y += 10
                }
            }
            if (page == null) newPage()
            page?.let { document.finishPage(it); page = null }
            context.contentResolver.openOutputStream(destination, "wt")!!.use { document.writeTo(it) }
        } finally { document.close() }
    }
}
