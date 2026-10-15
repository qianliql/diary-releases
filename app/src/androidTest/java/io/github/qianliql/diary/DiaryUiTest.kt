package io.github.qianliql.diary

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class DiaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun writePublishAndFindDiary() {
        compose.onNodeWithTag("tab-timeline").performClick()
        compose.onNodeWithTag("new-entry").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("entry-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entry-title").performTextInput("今天的晚霞")
        compose.onAllNodesWithTag("text-block").onFirst().performTextInput("风吹过树梢，留下一天的温柔。")
        compose.onNodeWithTag("publish-entry").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("这一刻").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("今天的晚霞").assertExists()
        compose.onNodeWithText("风吹过树梢，留下一天的温柔。").assertExists()
    }
    @Test fun modelIsOptionalInSettings() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithText("离线语音转写").assertExists()
        compose.onNodeWithText("未安装").assertExists()
    }
}
