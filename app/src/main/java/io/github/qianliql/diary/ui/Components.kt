package io.github.qianliql.diary.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.qianliql.diary.media.MediaFiles
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

fun dateText(millis: Long, pattern: String = "M月d日 EEEE"): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, java.util.Locale.SIMPLIFIED_CHINESE))
fun durationText(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
fun sizeText(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
@Composable fun LocalPhoto(uri: String, modifier: Modifier = Modifier, fit: Boolean = false) {
    val context = LocalContext.current
    val image by produceState<android.graphics.Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { MediaFiles.bitmap(context, uri) } }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), "日记照片", Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ImageNotSupported, null); Text("原照片无法读取", style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable fun AudioPlayer(uri: String, duration: Long) {
    val context = LocalContext.current; var playing by remember(uri) { mutableStateOf(false) }; var error by remember(uri) { mutableStateOf("") }
    val player = remember(uri) { MediaPlayer() }; var prepared by remember(uri) { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FilledTonalIconButton(onClick = {
            try {
                if (playing) { player.pause(); playing = false }
                else {
                    if (!prepared) { player.setDataSource(context, Uri.parse(uri)); player.prepare(); prepared = true; player.setOnCompletionListener { playing = false; it.seekTo(0) } }
                    player.start(); playing = true
                }
            } catch (_: Exception) { error = "录音无法读取"; playing = false; prepared = false; runCatching { player.reset() } }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停录音" else "播放录音") }
        Spacer(Modifier.width(12.dp)); Column { Text(if (error.isEmpty()) "原声记录" else error); Text(durationText(duration), style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable fun EmptyState(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.AutoStories, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}
