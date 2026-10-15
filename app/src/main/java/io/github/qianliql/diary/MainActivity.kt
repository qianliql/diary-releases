package io.github.qianliql.diary

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.qianliql.diary.backup.BackupInspection
import io.github.qianliql.diary.data.*
import io.github.qianliql.diary.media.*
import io.github.qianliql.diary.ui.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { DiaryTheme { DiaryRoot() } }
    }
}

@Composable fun DiaryRoot(vm: DiaryViewModel = viewModel()) {
    val context = LocalContext.current
    val entries by vm.entries.collectAsState()
    val snack = remember { SnackbarHostState() }
    var backupPassword by remember { mutableStateOf<CharArray?>(null) }
    var backupSnapshot by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var pdfSnapshot by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { WavRecorder(context) }
    fun stopRecording() {
        if (!recording) return
        try { recorder.stop()?.let { (uri, ms) -> vm.addMedia(uri, BlockType.AUDIO, ms) } }
        catch (e: Exception) { vm.notify(e.message ?: "录音未保存") }
        recording = false
    }
    val camera = rememberLauncherForActivityResult(CapturePhoto()) { success -> vm.finishCamera(success) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val intent = result.data
            val uris = if (intent?.clipData != null) (0 until intent.clipData!!.itemCount).map { intent.clipData!!.getItemAt(it).uri } else listOfNotNull(intent?.data)
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                vm.addMedia(uri, BlockType.PHOTO)
            }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) try { recorder.start(); recording = true } catch (e: Exception) { vm.notify(e.message ?: "无法开始录音") }
        else vm.notify("麦克风权限未开启，仍可写文字和添加照片")
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword; backupPassword = null
        if (uri != null && password != null) vm.exportBackup(uri, password, backupSnapshot) else password?.fill('\u0000')
        backupSnapshot = emptyList()
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) vm.exportPdf(uri, pdfSnapshot); pdfSnapshot = emptyList() }
    LaunchedEffect(Unit) { vm.messages.collectLatest { snack.showSnackbar(it) } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { stopRecording(); vm.flush() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); if (recorder.isRecording) runCatching { recorder.stop() } }
    }
    BackHandler(vm.editor != null || vm.viewing != null) { if (recording) stopRecording() else if (vm.editor != null) vm.leaveEditor() else vm.viewing = null }
    Box(Modifier.fillMaxSize()) {
        DiaryScreens(vm, entries, recording,
            onPhoto = { photos.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); putExtra(Intent.EXTRA_LOCAL_ONLY, true); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }) },
            onCamera = { vm.prepareCamera { uri -> try { camera.launch(uri) } catch (_: Exception) { vm.finishCamera(false); vm.notify("没有可用的相机，或无法创建照片") } } },
            onRecord = { if (recording) stopRecording() else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { try { recorder.start(); recording = true } catch (e: Exception) { vm.notify(e.message ?: "无法录音") } } else microphone.launch(Manifest.permission.RECORD_AUDIO) },
            onBackup = { vm.prepareBackup() }, onRestore = { restore.launch(arrayOf("*/*")) },
            onPdf = { list -> pdfSnapshot = list; pdf.launch("日记_${dateText(System.currentTimeMillis(), "yyyyMMdd_HHmm")}.pdf") })
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        if (vm.busy) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(vm.busyText) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    }
    vm.backupInspection?.let { inspected ->
        BackupPasswordDialog(false, inspected.missing, onDismiss = { vm.backupInspection = null }) { password ->
            backupSnapshot = inspected.entries; backupPassword = password; vm.backupInspection = null
            backup.launch("日记备份_${dateText(System.currentTimeMillis(), "yyyyMMdd_HHmm")}.diary")
        }
    }
    restoreUri?.let { uri -> BackupPasswordDialog(true, emptyList(), onDismiss = { restoreUri = null }) { password -> restoreUri = null; vm.restoreBackup(uri, password) } }
    vm.updateUrl?.let { url -> AlertDialog(onDismissRequest = { vm.updateUrl = null }, title = { Text("发现新版本") }, text = { Text("前往发布页下载新版 APK，使用系统安装器覆盖安装即可保留日记。") }, confirmButton = { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); vm.updateUrl = null }) { Text("前往下载") } }, dismissButton = { TextButton(onClick = { vm.updateUrl = null }) { Text("稍后") } }) }
    if (vm.modelNeeded) AlertDialog(onDismissRequest = { vm.modelNeeded = false }, title = { Text("先启用离线语音转写") }, text = { Text("原始录音已经保存在本机。可以在设置中按需安装或启用模型，再回来转写。") }, confirmButton = { TextButton(onClick = { vm.modelNeeded = false; vm.leaveEditor(); vm.tab = "settings" }) { Text("保留草稿，前往设置") } }, dismissButton = { TextButton(onClick = { vm.modelNeeded = false }) { Text("稍后") } })
}
