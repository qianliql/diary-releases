package io.github.qianliql.diary

import android.app.NotificationManager
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qianliql.diary.media.WavRecorder
import io.github.qianliql.diary.reminder.Reminders
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit device acceptance: microphone and notification grants are supplied by the operator. */
@RunWith(AndroidJUnit4::class)
class HardwareTest {
    @Test fun microphonePlaybackAndLocalNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS) else arrayOf(android.Manifest.permission.RECORD_AUDIO)
            scenario.onActivity { activity -> activity.requestPermissions(permissions, 800) }
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (permissions.any { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(300)
            permissions.forEach { permission -> assertEquals("Grant $permission in the phone permission prompt", android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission)) }
            SystemClock.sleep(700)
            val recorder = WavRecorder(context)
            var captured: File? = null
            val player = MediaPlayer()
            try {
                recorder.start(); SystemClock.sleep(2500)
                val result = recorder.stop() ?: error("No recorded audio")
                captured = File(result.first.path!!)
                assertTrue(result.second > 1000)
                val bytes = captured!!.readBytes()
                assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
                assertTrue(bytes.drop(44).any { it.toInt() != 0 })
                player.setDataSource(context, result.first); player.prepare()
                assertTrue(player.duration > 1000); player.start(); SystemClock.sleep(350)
                assertTrue(player.isPlaying)
                val notifications = context.getSystemService(NotificationManager::class.java)
                assertTrue(notifications.areNotificationsEnabled())
                Reminders.notify(context, 1); SystemClock.sleep(500)
                assertTrue(notifications.activeNotifications.any { n -> n.id == 1 })
                notifications.cancel(1)
            } finally {
                recorder.stop(); player.release(); captured?.delete()
            }
        }
    }
}
