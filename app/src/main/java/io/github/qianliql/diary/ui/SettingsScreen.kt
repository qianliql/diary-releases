package io.github.qianliql.diary.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.github.qianliql.diary.BuildConfig
import io.github.qianliql.diary.DiaryViewModel
import io.github.qianliql.diary.reminder.Reminders
import kotlinx.coroutines.launch

@Composable fun SettingsScreen(vm: DiaryViewModel, onBackup: () -> Unit, onRestore: () -> Unit, onTrash: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("settings", 0) }
    val model by vm.models.state.collectAsState()
    val scope = rememberCoroutineScope()
    var installDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var daily by remember { mutableStateOf(preferences.getBoolean("dailyReminder", false)) }
    var monthly by remember { mutableStateOf(preferences.getBoolean("backupReminder", true)) }
    var hour by remember { mutableIntStateOf(preferences.getInt("dailyHour", 21)) }
    var minute by remember { mutableIntStateOf(preferences.getInt("dailyMinute", 0)) }
    var notificationAllowed by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationAllowed = granted; Reminders.schedule(context); if (!granted) vm.notify("通知未开启，日记功能仍可正常使用") }
    fun requestNotification() { if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationAllowed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("安心记录，自在回看。", style = MaterialTheme.typography.headlineMedium) }
        item { SettingsCard("离线语音转写", Icons.Default.GraphicEq) {
            Text("${vm.models.catalog.name}\n模型可选加装，录音与播放始终可用。", style = MaterialTheme.typography.bodyMedium)
            Text(model.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (model.busy) {
                LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                Text("${(model.progress * 100).toInt()}% · 下载期间保持应用打开", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.models.cancel() }) { Text("取消安装") }
            } else if (model.installed) {
                SettingSwitch("启用本机转写", "关闭后仍保留模型和已有文字", model.enabled) { vm.models.setEnabled(it) }
                Text("模型版本：${model.version}", style = MaterialTheme.typography.labelSmall)
                Row { TextButton(onClick = { installDialog = true }, enabled = vm.transcribing == null) { Text(if (model.version == vm.models.catalog.version) "重新安装" else "安装新版") }; TextButton(onClick = { deleteDialog = true }, enabled = vm.transcribing == null) { Text("删除模型") } }
            } else FilledTonalButton(onClick = { installDialog = true }) { Icon(Icons.Default.Download, null); Text(" 安装模型 · ${sizeText(vm.models.catalog.size)}") }
            if (!model.busy) TextButton(onClick = { vm.work("正在检查模型版本") { val available = vm.models.checkUpdates(); vm.notify(if (available) "模型版本已更新，可按需安装" else "模型已是当前版本") } }) { Text("检查模型更新") }
        } }
        item { SettingsCard("备份与迁移", Icons.Default.Inventory2) {
            Text("日记保存在这台手机。请定期把加密备份保存到你保管的位置。", style = MaterialTheme.typography.bodyMedium)
            val last = preferences.getLong("lastBackup", 0)
            Text(if (last == 0L) "还没有导出过备份" else "上次备份：${dateText(last, "yyyy.M.d HH:mm")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton(onClick = onBackup) { Text("导出加密备份") }; OutlinedButton(onClick = onRestore) { Text("导入合并") } }
            Text("备份不含语音模型。相同记录跳过，修改冲突保留两个版本。", style = MaterialTheme.typography.labelSmall)
        } }
        item { SettingsCard("温柔提醒", Icons.Default.NotificationsNone) {
            SettingSwitch("每日写作提醒", "默认关闭，时间可能受系统省电影响", daily) { value -> daily = value; preferences.edit().putBoolean("dailyReminder", value).apply(); Reminders.schedule(context); if (value) requestNotification() }
            if (daily) TextButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m; preferences.edit().putInt("dailyHour", h).putInt("dailyMinute", m).apply(); Reminders.schedule(context) }, hour, minute, true).show() }) { Text("提醒时间 · %02d:%02d".format(hour, minute)) }
            SettingSwitch("每月备份提醒", "只发送本地提醒，不会自动备份", monthly) { value -> monthly = value; preferences.edit().putBoolean("backupReminder", value).apply(); Reminders.schedule(context); if (value) requestNotification() }
            if (!notificationAllowed && (daily || monthly)) OutlinedButton(onClick = { requestNotification() }) { Text("开启通知权限") }
        } }
        item { SettingsCard("本机数据", Icons.Default.Storage) {
            Text("相册照片只保留引用，原图删除后可能失效；已恢复的照片存入日记私有空间。系统相册是否同步由手机设置决定。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onTrash) { Icon(Icons.Default.DeleteOutline, null); Text(" 回收站 · 保留 30 天") }
        } }
        item { SettingsCard("关于日记", Icons.Default.AutoStories) {
            Text("版本 ${BuildConfig.VERSION_NAME} · 个人模式")
            Text("只有主动检查更新、安装模型时联网。日记内容与转写均在本机处理。", style = MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick = { vm.checkUpdate() }) { Text("检查应用更新") }; TextButton(onClick = { licenses = true }) { Text("开源许可") } }
        } }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (installDialog) AlertDialog(onDismissRequest = { installDialog = false }, title = { Text(if (model.installed) "重新安装语音模型？" else "加装离线语音模型？") }, text = {
        Text("下载约 ${sizeText(vm.models.catalog.size)}，安装后约 ${sizeText(vm.models.catalog.unpackedSize)}。建议使用 Wi-Fi。\n\n只下载模型，不会上传录音或日记。安装完成后，断网也能按需转写普通话。\n\n${vm.models.catalog.license}")
    }, confirmButton = { TextButton(onClick = { installDialog = false; vm.models.install() }) { Text("下载并安装") } }, dismissButton = { TextButton(onClick = { installDialog = false }) { Text("暂不需要") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除语音模型？") }, text = { Text("释放模型占用的空间。日记、原始录音及已有转写全部保留，需要时可重新安装。") }, confirmButton = { TextButton(onClick = { deleteDialog = false; scope.launch { vm.models.delete() } }) { Text("删除模型") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源许可与模型来源") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(remember { context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }, style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } })
}

@Composable fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium) }
        content()
    } }
}
@Composable fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable fun BackupPasswordDialog(restore: Boolean, missing: List<String>, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }; var acceptMissing by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (restore) "导入加密备份" else "导出加密备份") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (restore) "输入备份密码。合并会保留已有日记，冲突内容保留两个版本。" else "设置至少 8 个字符的密码。没有账号或云端找回服务，请妥善保管。", style = MaterialTheme.typography.bodySmall)
            if (missing.isNotEmpty()) {
                Text("有 ${missing.size} 项媒体无法读取：", color = MaterialTheme.colorScheme.error)
                Text(missing.take(6).joinToString("\n") + if (missing.size > 6) "\n……" else "", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acceptMissing, { acceptMissing = it }); Text("保留缺失提示，继续备份", style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedTextField(password, { password = it }, label = { Text("备份密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (!restore) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次输入密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = confirmation.isNotEmpty() && password != confirmation)
        }
    }, confirmButton = { TextButton(onClick = { val chars = password.toCharArray(); password = ""; confirmation = ""; onConfirm(chars) }, enabled = (if (restore) password.isNotEmpty() else password.length >= 8 && password == confirmation) && (missing.isEmpty() || acceptMissing)) { Text(if (restore) "验证并合并" else "选择保存位置") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
