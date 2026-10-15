package io.github.qianliql.diary

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.qianliql.diary.backup.*
import io.github.qianliql.diary.data.*
import io.github.qianliql.diary.media.MediaFiles
import io.github.qianliql.diary.model.UpdateClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID

class DiaryViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val diary = app as DiaryApp
    val repository = diary.repository
    val entries = repository.entries
    val models = diary.models
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 5)
    var editor by mutableStateOf<Entry?>(null); private set
    var viewing by mutableStateOf<String?>(null)
    var tab by mutableStateOf("timeline")
    var busy by mutableStateOf(false); private set
    var busyText by mutableStateOf(""); private set
    var backupInspection by mutableStateOf<BackupInspection?>(null)
    var updateUrl by mutableStateOf<String?>(null)
    var transcribing by mutableStateOf<String?>(null); private set
    var transcriptionProgress by mutableFloatStateOf(0f); private set
    var modelNeeded by mutableStateOf(false)
    private var saveJob: Job? = null
    private var transcriptionJob: Job? = null
    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refresh()
            val last = savedState.get<String>("editorId")
            if (last != null) withContext(Dispatchers.Main) { editor = entries.value.find { it.id == last && it.draft } }
        }
    }
    fun notify(text: String) { messages.tryEmit(text) }
    fun work(label: String, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; busyText = label
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { if (e !is CancellationException) notify(e.message ?: "操作没有完成，请重试") }
            finally { busy = false }
        }
    }
    fun edit(entry: Entry? = null) = work("正在打开日记") {
        val draft = repository.beginEdit(entry)
        withContext(Dispatchers.Main) { editor = draft; viewing = null; savedState["editorId"] = draft.id }
    }
    fun change(value: Entry) {
        editor = value.copy(updatedAt = System.currentTimeMillis()); val snapshot = editor!!
        saveJob?.cancel(); saveJob = viewModelScope.launch { delay(400); withContext(Dispatchers.IO) { repository.put(snapshot) } }
    }
    fun flush() {
        val snapshot = editor ?: return
        saveJob?.cancel(); saveJob = viewModelScope.launch(Dispatchers.IO) { repository.put(snapshot) }
    }
    fun leaveEditor() {
        flush(); editor = null; savedState["editorId"] = null
    }
    fun publish() {
        val value = editor ?: return
        if (!value.hasContent) { notify("先写点文字，或添加照片与录音"); return }
        val pending = saveJob
        work("正在保存日记") { pending?.cancelAndJoin(); val result = repository.publish(value); withContext(Dispatchers.Main) { editor = null; savedState["editorId"] = null; viewing = result.id } }
    }
    fun discardDraft() {
        val draft = editor ?: return
        val pending = saveJob
        work("正在移入回收站") { pending?.cancelAndJoin(); repository.put(draft.copy(deletedAt = System.currentTimeMillis())); withContext(Dispatchers.Main) { editor = null; savedState["editorId"] = null } }
    }
    fun changeBlock(id: String, block: (Block) -> Block) { editor?.let { e -> change(e.copy(blocks = e.blocks.map { if (it.id == id) block(it) else it })) } }
    fun addBlock(block: Block) { editor?.let { change(it.copy(blocks = it.blocks + block)) } }
    fun prepareCamera(launch: (Uri) -> Unit) {
        val draft = editor ?: return
        val pendingSave = saveJob
        work("正在打开相机") {
            pendingSave?.cancelAndJoin(); repository.put(draft)
            val uri = MediaFiles.cameraUri(getApplication())
            withContext(Dispatchers.Main) {
                savedState["cameraUri"] = uri.toString(); savedState["cameraEntryId"] = draft.id
                launch(uri)
            }
        }
    }
    fun finishCamera(success: Boolean) {
        val uri = savedState.get<String>("cameraUri") ?: return
        val entryId = savedState.get<String>("cameraEntryId") ?: return
        viewModelScope.launch {
            busy = true; busyText = "正在保存照片"
            try {
                val photo = withContext(Dispatchers.IO) { MediaFiles.finishCamera(getApplication(), Uri.parse(uri), success) }
                if (photo != null) {
                    val block = withContext(Dispatchers.IO) { MediaFiles.block(getApplication(), photo.uri, BlockType.PHOTO) }
                    saveJob?.cancelAndJoin()
                    val current = editor?.takeIf { it.id == entryId } ?: withContext(Dispatchers.IO) {
                        repository.refresh(); repository.entries.value.find { it.id == entryId && it.draft }
                    }
                    if (current != null) {
                        val updated = current.copy(blocks = current.blocks + block, updatedAt = System.currentTimeMillis())
                        withContext(Dispatchers.IO) { repository.put(updated) }
                        editor = updated; savedState["editorId"] = updated.id
                    }
                    if (!photo.inGallery) notify("照片已保存在日记，但未能写入相册，请检查可用空间")
                }
            } catch (e: Exception) { notify(e.message ?: "照片保存失败，请重新拍照") }
            finally { savedState["cameraUri"] = null; savedState["cameraEntryId"] = null; busy = false }
        }
    }
    fun addMedia(uri: Uri, type: BlockType, duration: Long = 0) {
        val editorId = editor?.id ?: return
        viewModelScope.launch {
            try {
                val b = withContext(Dispatchers.IO) { MediaFiles.block(getApplication(), uri, type, duration) }
                if (editor?.id == editorId) addBlock(b)
                else withContext(Dispatchers.IO) { repository.entries.value.find { it.id == editorId }?.let { repository.put(it.copy(blocks = it.blocks + b)) } }
            } catch (e: Exception) { notify(e.message ?: "媒体无法读取") }
        }
    }
    fun moveBlock(index: Int, delta: Int) { editor?.let { e -> val b = e.blocks.toMutableList(); val next = index + delta; if (next in b.indices) { val item = b.removeAt(index); b.add(next, item); change(e.copy(blocks = b)) } } }
    fun deleteBlock(id: String) { editor?.let { change(it.copy(blocks = it.blocks.filterNot { b -> b.id == id })) } }
    fun trash(entry: Entry) = work("正在移入回收站") { repository.put(entry.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())); withContext(Dispatchers.Main) { viewing = null }; notify("已移入回收站，30 天内可恢复") }
    fun restoreEntry(entry: Entry) = work("正在恢复") { repository.put(entry.copy(deletedAt = 0, updatedAt = System.currentTimeMillis())); notify("日记已恢复") }
    fun deletePermanently(entry: Entry) = work("正在永久删除") { repository.remove(entry.id); notify("已永久删除") }
    fun prepareBackup() = work("正在检查照片和录音") {
        saveJob?.join(); repository.refresh(); val result = BackupService(getApplication(), repository).inspect(entries.value)
        withContext(Dispatchers.Main) { backupInspection = result }
    }
    fun exportBackup(uri: Uri, password: CharArray, snapshot: List<Entry>) = work("正在加密备份，请保持应用打开") {
        val missing = BackupService(getApplication(), repository).export(snapshot, uri, password)
        io.github.qianliql.diary.reminder.Reminders.schedule(getApplication()); notify(if (missing == 0) "加密备份已保存，请妥善保管密码" else "加密备份已保存，${missing} 项媒体缺失，已保留提示")
    }
    fun restoreBackup(uri: Uri, password: CharArray) = work("正在验证并合并备份") {
        val result = BackupService(getApplication(), repository).restore(uri, password)
        notify("恢复完成：新增 ${result.added} 篇，跳过 ${result.skipped} 篇，保留冲突 ${result.conflicts} 篇")
    }
    fun exportPdf(uri: Uri, snapshot: List<Entry>) = work("正在生成 PDF") { PdfExporter(getApplication()).export(snapshot, uri); notify("PDF 已保存") }
    fun checkUpdate() = work("正在检查应用更新") { val result = UpdateClient.check(BuildConfig.VERSION_CODE.toLong()); withContext(Dispatchers.Main) { updateUrl = result }; if (result == null) notify("当前没有可用的新版本") }
    fun transcribe(block: Block) {
        if (transcribing != null) return
        if (!models.state.value.enabled) { modelNeeded = true; return }
        val editorId = editor?.id ?: return
        transcriptionJob = viewModelScope.launch {
            transcribing = block.id; transcriptionProgress = 0f
            try {
                val text = models.transcribe(block.uri) { p -> transcriptionProgress = p }
                if (editor?.id == editorId) changeBlock(block.id) { it.copy(transcript = text) }
                else withContext(Dispatchers.IO) {
                    repository.entries.value.find { it.id == editorId }?.let { e -> repository.put(e.copy(blocks = e.blocks.map { if (it.id == block.id) it.copy(transcript = text) else it })) }
                }
                notify("本机转写完成，可以校对文字")
            } catch (e: Exception) { if (e is CancellationException) notify("转写已取消，录音仍保留") else notify(e.message ?: "转写失败，原始录音仍保留") }
            finally { transcribing = null }
        }
    }
    fun cancelTranscription() { transcriptionJob?.cancel() }
}
