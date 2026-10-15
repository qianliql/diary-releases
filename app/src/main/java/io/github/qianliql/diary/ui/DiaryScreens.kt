package io.github.qianliql.diary.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianliql.diary.DiaryViewModel
import io.github.qianliql.diary.data.*
import java.time.*
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DiaryScreens(vm: DiaryViewModel, all: List<Entry>, recording: Boolean, onPhoto: () -> Unit, onCamera: () -> Unit, onRecord: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onPdf: (List<Entry>) -> Unit) {
    val editor = vm.editor
    if (editor != null) {
        EditorScreen(vm, editor, recording, onPhoto, onCamera, onRecord); return
    }
    val viewed = all.find { it.id == vm.viewing }
    if (viewed != null) { DetailScreen(viewed, onBack = { vm.viewing = null }, onEdit = { vm.edit(viewed) }, onDelete = { vm.trash(viewed) }, onPdf = { onPdf(listOf(viewed)) }); return }
    var search by rememberSaveable { mutableStateOf("") }
    var filterTag by rememberSaveable { mutableStateOf("") }
    var filterMood by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var trash by rememberSaveable { mutableStateOf(false) }
    val published = all.filter { !it.draft && it.deletedAt == 0L }
    val drafts = all.filter { it.draft && it.deletedAt == 0L }
    val filtered = published.filter { (search.isBlank() || it.searchable.contains(search, ignoreCase = true)) && (filterTag.isEmpty() || filterTag in it.tags) && (filterMood.isEmpty() || it.mood == filterMood) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (trash) "回收站" else if (selected.isNotEmpty()) "已选 ${selected.size} 篇" else "日记", fontWeight = FontWeight.Bold) },
            navigationIcon = { if (trash) IconButton(onClick = { trash = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { onPdf(published.filter { it.id in selected }); selected = emptySet() }) { Icon(Icons.Default.PictureAsPdf, "导出选中日记") }
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "取消选择") }
                } else if (vm.tab == "timeline" && !trash) TextButton(onClick = { if (published.isNotEmpty()) selected = setOf(published.first().id) else vm.notify("先写一篇日记再导出") }) { Text("选择") }
            }) },
        bottomBar = { if (!trash) NavigationBar {
            listOf(Triple("timeline", "日记", Icons.Default.AutoStories), Triple("calendar", "日历", Icons.Default.CalendarMonth), Triple("settings", "设置", Icons.Default.Tune)).forEach { (id, label, icon) ->
                NavigationBarItem(modifier = Modifier.testTag("tab-$id"), selected = vm.tab == id, onClick = { vm.tab = id; selected = emptySet() }, icon = { Icon(icon, label) }, label = { Text(label) })
            }
        } },
        floatingActionButton = { if (vm.tab != "settings" && !trash && selected.isEmpty()) ExtendedFloatingActionButton(modifier = Modifier.testTag("new-entry").semantics { contentDescription = "记一篇" }, onClick = { vm.edit() }, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("记一篇") }) }
    ) { padding ->
        when {
            trash -> TrashScreen(all.filter { it.deletedAt != 0L }, vm, Modifier.padding(padding))
            vm.tab == "settings" -> SettingsScreen(vm, onBackup, onRestore, onTrash = { trash = true }, modifier = Modifier.padding(padding))
            vm.tab == "calendar" -> CalendarScreen(published, { vm.viewing = it.id }, Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Column { Text("把日子，留在这里。", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(6.dp)); Text("${published.size} 篇回忆 · 只属于你的时光", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索文字、转写或标签") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(24.dp)) }
                if (published.any { it.tags.isNotEmpty() || it.mood.isNotBlank() }) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filterTag.isEmpty() && filterMood.isEmpty(), onClick = { filterTag = ""; filterMood = "" }, label = { Text("全部") })
                        published.map { it.mood }.filter { it.isNotBlank() }.distinct().forEach { mood -> FilterChip(selected = mood == filterMood, onClick = { filterMood = if (filterMood == mood) "" else mood }, label = { Text(mood) }) }
                        published.flatMap { it.tags }.distinct().forEach { tag -> FilterChip(selected = tag == filterTag, onClick = { filterTag = if (filterTag == tag) "" else tag }, label = { Text("#$tag") }) }
                    }
                }
                if (drafts.isNotEmpty()) item {
                    Card(onClick = { vm.edit(drafts.first()) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("草稿 · ${drafts.size} 篇", fontWeight = FontWeight.Bold)
                            drafts.forEach { d -> TextButton(onClick = { vm.edit(d) }) { Text(d.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        }
                    }
                }
                if (filtered.isEmpty()) item { EmptyState(if (search.isNotBlank() || filterTag.isNotBlank() || filterMood.isNotBlank()) "没有找到这段回忆" else "从今天的一个瞬间开始", "写几句话、拍一张照，或留下一段原声。") }
                items(filtered, key = { it.id }) { entry -> EntryCard(entry, selected = entry.id in selected, selectionMode = selected.isNotEmpty(), onClick = { if (selected.isEmpty()) vm.viewing = entry.id else selected = if (entry.id in selected) selected - entry.id else selected + entry.id }) }
            }
        }
    }
}

@Composable fun EntryCard(entry: Entry, selected: Boolean = false, selectionMode: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        val image = entry.blocks.firstOrNull { it.type == BlockType.PHOTO }
        if (image != null) LocalPhoto(image.uri, Modifier.fillMaxWidth().height(190.dp))
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(dateText(entry.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (entry.mood.isNotBlank()) Text(entry.mood)
                if (selectionMode) Checkbox(selected, onCheckedChange = null)
            }
            Text(entry.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.sourceNote.isNotBlank()) Text(entry.sourceNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            val body = entry.blocks.firstOrNull { it.type == BlockType.TEXT && it.text.isNotBlank() }?.text.orEmpty()
            if (body.isNotBlank() && body != entry.displayTitle) Text(body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val audios = entry.blocks.count { it.type == BlockType.AUDIO }
            if (audios > 0) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.GraphicEq, null, Modifier.size(16.dp)); Text("  $audios 段原声", style = MaterialTheme.typography.labelMedium) }
            if (entry.tags.isNotEmpty()) Text(entry.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DetailScreen(entry: Entry, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onPdf: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("这一刻") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, actions = {
        IconButton(onClick = onPdf) { Icon(Icons.Default.PictureAsPdf, "导出 PDF") }; IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "删除") }; IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(dateText(entry.date, "yyyy年M月d日 EEEE HH:mm"), color = MaterialTheme.colorScheme.primary); Text(entry.displayTitle, style = MaterialTheme.typography.headlineLarge); Text((listOf(entry.mood) + entry.tags.map { "#$it" }).filter { it.isNotBlank() }.joinToString("  "), color = MaterialTheme.colorScheme.secondary) } }
            items(entry.blocks, key = { it.id }) { b -> when (b.type) {
                BlockType.TEXT -> if (b.text.isNotBlank()) Text(b.text, style = MaterialTheme.typography.bodyLarge)
                BlockType.PHOTO -> LocalPhoto(b.uri, Modifier.fillMaxWidth().height(330.dp), fit = true)
                BlockType.AUDIO -> Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { AudioPlayer(b.uri, b.durationMs); if (b.transcript.isNotBlank()) Text(b.transcript) else Text("尚未转写 · 编辑日记可进行本机转写", style = MaterialTheme.typography.bodySmall) } }
            } }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("移入回收站？") }, text = { Text("30 天内可以恢复。系统相册中的原照片会保留。") }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("移入回收站") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorScreen(vm: DiaryViewModel, entry: Entry, recording: Boolean, onPhoto: () -> Unit, onCamera: () -> Unit, onRecord: () -> Unit) {
    val context = LocalContext.current
    var tagsText by remember(entry.id) { mutableStateOf(entry.tags.joinToString("，")) }
    var showMoods by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) { recordSeconds = 0; while (recording) { kotlinx.coroutines.delay(1000); recordSeconds++ } }
    Scaffold(topBar = { TopAppBar(title = { Column { Text(if (entry.editOf.isEmpty()) "写日记" else "编辑日记"); Text("草稿自动保存在本机", style = MaterialTheme.typography.labelSmall) } }, navigationIcon = { IconButton(onClick = { if (recording) onRecord(); vm.leaveEditor() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "保留草稿并返回") } }, actions = { IconButton(onClick = { discard = true }, enabled = !recording && vm.transcribing == null) { Icon(Icons.Default.DeleteOutline, "删除草稿") }; TextButton(modifier = Modifier.testTag("publish-entry"), onClick = { vm.publish() }, enabled = !recording && vm.transcribing == null) { Text("完成", fontWeight = FontWeight.Bold) } }) },
        bottomBar = { Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.addBlock(Block()) }) { Icon(Icons.Default.Notes, "添加文字") }
            IconButton(onClick = onPhoto, enabled = !recording) { Icon(Icons.Default.PhotoLibrary, "从本机选择照片") }
            IconButton(onClick = onCamera, enabled = !recording) { Icon(Icons.Default.PhotoCamera, "拍照") }
            FilledTonalButton(onClick = onRecord) { Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null); Text(if (recording) " 停止 ${durationText(recordSeconds * 1000L)}" else " 录音") }
        } } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(entry.title, { vm.change(entry.copy(title = it)) }, Modifier.fillMaxWidth().testTag("entry-title"), placeholder = { Text("给这一刻起个名字（可选）") }, textStyle = MaterialTheme.typography.titleLarge, shape = RoundedCornerShape(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {
                            val cal = Calendar.getInstance().apply { timeInMillis = entry.date }
                            DatePickerDialog(context, { _, y, m, d ->
                                cal.set(y, m, d)
                                TimePickerDialog(context, { _, hour, minute -> cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute); vm.change(entry.copy(date = cal.timeInMillis)) }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                        }, label = { Text(dateText(entry.date, "yyyy.M.d HH:mm")) }, leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(16.dp)) })
                        Box { AssistChip(onClick = { showMoods = true }, label = { Text(entry.mood.ifBlank { "添加心情" }) }); DropdownMenu(showMoods, onDismissRequest = { showMoods = false }) {
                            listOf("", "😊 开心", "😌 平静", "😐 一般", "😔 低落", "😣 难过").forEach { mood -> DropdownMenuItem(text = { Text(mood.ifBlank { "不标记心情" }) }, onClick = { vm.change(entry.copy(mood = mood)); showMoods = false }) }
                        } }
                    }
                    OutlinedTextField(tagsText, { value -> tagsText = value; vm.change(entry.copy(tags = value.split(',', '，', '#').map { it.trim() }.filter { it.isNotEmpty() }.distinct())) }, Modifier.fillMaxWidth(), label = { Text("标签，用逗号分隔") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Tag, null) }, shape = RoundedCornerShape(16.dp))
                }
            }
            if (entry.blocks.isEmpty()) item { EmptyState("留下一段回忆", "用下方按钮添加文字、照片或录音。") }
            items(entry.blocks, key = { it.id }) { b ->
                val index = entry.blocks.indexOfFirst { it.id == b.id }
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when (b.type) { BlockType.TEXT -> "文字"; BlockType.PHOTO -> "照片"; BlockType.AUDIO -> "语音" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.moveBlock(index, -1) }, enabled = index > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "上移") }
                            IconButton(onClick = { vm.moveBlock(index, 1) }, enabled = index < entry.blocks.lastIndex, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "下移") }
                            IconButton(onClick = { vm.deleteBlock(b.id) }, enabled = vm.transcribing != b.id, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "移除此内容块") }
                        }
                        when (b.type) {
                            BlockType.TEXT -> OutlinedTextField(b.text, { value -> vm.changeBlock(b.id) { it.copy(text = value) } }, Modifier.fillMaxWidth().testTag("text-block"), placeholder = { Text("此刻想记下什么……") }, minLines = 4, shape = RoundedCornerShape(12.dp))
                            BlockType.PHOTO -> { LocalPhoto(b.uri, Modifier.fillMaxWidth().height(260.dp), fit = true); Text(if (b.uri.startsWith("content:")) "引用系统相册原图，删除原图后可能无法读取" else "照片保存在本机日记中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            BlockType.AUDIO -> {
                                AudioPlayer(b.uri, b.durationMs)
                                if (vm.transcribing == b.id) { LinearProgressIndicator(progress = { vm.transcriptionProgress }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.cancelTranscription() }) { Text("取消转写（保留录音）") } }
                                else TextButton(onClick = { vm.transcribe(b) }) { Icon(Icons.Default.TextSnippet, null); Text(if (b.transcript.isBlank()) " 在本机转文字" else " 重新转写") }
                                if (b.transcript.isNotBlank()) OutlinedTextField(b.transcript, { text -> vm.changeBlock(b.id) { it.copy(transcript = text) } }, Modifier.fillMaxWidth(), label = { Text("转写文字（可校对）") }, minLines = 3)
                            }
                        }
                    }
                }
            }
            if (recording) item { Text("正在录音……切到后台时将停止并保存。", color = MaterialTheme.colorScheme.primary) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("删除这个草稿？") }, text = { Text("草稿会移入回收站。编辑已有日记时，原日记会保留。") }, confirmButton = { TextButton(onClick = { discard = false; vm.discardDraft() }) { Text("删除草稿") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("取消") } })
}

@Composable fun CalendarScreen(entries: List<Entry>, onOpen: (Entry) -> Unit, modifier: Modifier) {
    var month by remember { mutableStateOf(YearMonth.now()) }; var day by remember { mutableStateOf(LocalDate.now()) }
    val daysWithEntries = entries.map { Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
    val visible = entries.filter { Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).toLocalDate() == day }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "上个月") }
                    Text("${month.year}年 ${month.monthValue}月", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { month = YearMonth.now(); day = LocalDate.now() }) { Text("今天") }
                    IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "下个月") }
                }
                Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
                val offset = month.atDay(1).dayOfWeek.value - 1
                for (week in 0 until (offset + month.lengthOfMonth() + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (weekday in 0..6) {
                            val date = week * 7 + weekday - offset + 1
                            Box(Modifier.weight(1f).height(50.dp), contentAlignment = Alignment.Center) {
                                if (date in 1..month.lengthOfMonth()) {
                                    val value = month.atDay(date)
                                    Column(Modifier.clip(CircleShape).background(if (value == day) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background).clickable { day = value }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(date.toString()); Box(Modifier.size(4.dp).background(if (value in daysWithEntries) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
                Text("${day.monthValue}月${day.dayOfMonth}日 · ${visible.size} 篇", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (visible.isEmpty()) item { EmptyState("这天还没有日记", "空白的日子，也可以慢慢补写。") }
        items(visible, key = { it.id }) { EntryCard(it, onClick = { onOpen(it) }) }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable fun TrashScreen(entries: List<Entry>, vm: DiaryViewModel, modifier: Modifier) {
    var deleting by remember { mutableStateOf<Entry?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("删除后保留 30 天，到期在打开应用时清理。原相册照片会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isEmpty()) item { EmptyState("回收站是空的", "误删的日记会暂时保存在这里。") }
        items(entries, key = { it.id }) { entry -> Card { Column(Modifier.padding(16.dp)) { Text(entry.displayTitle, style = MaterialTheme.typography.titleMedium); Text(dateText(entry.date)); Row { TextButton(onClick = { vm.restoreEntry(entry) }) { Text("恢复") }; TextButton(onClick = { deleting = entry }) { Text("永久删除") } } } } }
    }
    deleting?.let { e -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("永久删除这篇日记？") }, text = { Text("此操作无法撤销。") }, confirmButton = { TextButton(onClick = { vm.deletePermanently(e); deleting = null }) { Text("永久删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
