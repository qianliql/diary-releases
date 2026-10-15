package io.github.qianliql.diary.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Clay = Color(0xFF955A42)
val Paper = Color(0xFFFAF7F2)
val Moss = Color(0xFF6C7762)
@Composable fun DiaryTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFE6B59B), secondary = Color(0xFFB8C4AC), background = Color(0xFF211D1A), surface = Color(0xFF28221E), surfaceVariant = Color(0xFF39312B))
    else lightColorScheme(primary = Clay, onPrimary = Color.White, primaryContainer = Color(0xFFF0D9C8), onPrimaryContainer = Color(0xFF482C21),
        secondary = Moss, secondaryContainer = Color(0xFFE2E7DA), onSecondaryContainer = Color(0xFF303C2B),
        tertiary = Color(0xFF876447), tertiaryContainer = Color(0xFFF0DEC7),
        background = Paper, surface = Color(0xFFFFFDF9), surfaceVariant = Color(0xFFF0E8DE),
        surfaceContainer = Color(0xFFF2EDE5), surfaceContainerLow = Color(0xFFF7F1EA), surfaceContainerLowest = Color(0xFFFFFDF9), surfaceContainerHigh = Color(0xFFEEE7DE), surfaceContainerHighest = Color(0xFFE8DFD5),
        onBackground = Color(0xFF332C27), onSurface = Color(0xFF332C27), onSurfaceVariant = Color(0xFF73665B), outline = Color(0xFFAC9B8E))
    MaterialTheme(colorScheme = colors, content = content)
}
