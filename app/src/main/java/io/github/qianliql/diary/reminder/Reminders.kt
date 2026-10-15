package io.github.qianliql.diary.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import io.github.qianliql.diary.MainActivity
import io.github.qianliql.diary.R
import java.time.*

object Reminders {
    private const val CHANNEL = "diary-reminders"
    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences("settings", 0)
        val manager = context.getSystemService(AlarmManager::class.java)
        val now = ZonedDateTime.now()
        listOf(1, 2).forEach { kind ->
            val pi = pending(context, kind)
            manager.cancel(pi)
            val enabled = prefs.getBoolean(if (kind == 1) "dailyReminder" else "backupReminder", kind == 2)
            if (!enabled) return@forEach
            val next = if (kind == 1) {
                val hour = prefs.getInt("dailyHour", 21); val minute = prefs.getInt("dailyMinute", 0)
                now.withHour(hour).withMinute(minute).withSecond(0).let { if (it <= now) it.plusDays(1) else it }
            } else {
                val base = maxOf(prefs.getLong("lastBackup", 0), prefs.getLong("backupReminderBase", 0)).takeIf { it != 0L } ?: System.currentTimeMillis().also { prefs.edit().putLong("backupReminderBase", it).apply() }
                Instant.ofEpochMilli(base).atZone(ZoneId.systemDefault()).plusMonths(1).withHour(20).withMinute(0).withSecond(0).let { if (it <= now) now.plusDays(1).withHour(20).withMinute(0).withSecond(0) else it }
            }
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
        }
    }
    private fun pending(context: Context, kind: Int) = PendingIntent.getBroadcast(context, kind, Intent(context, ReminderReceiver::class.java).putExtra("kind", kind), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun notify(context: Context, kind: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED && android.os.Build.VERSION.SDK_INT >= 33) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "日记与备份提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(kind, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_diary)
            .setContentTitle(if (kind == 1) "留一点时间给自己" else "给日记留一份备份")
            .setContentText(if (kind == 1) "今天有什么想记下的？" else "日记保存在本机，可以手动导出加密备份。")
            .setContentIntent(intent).setAutoCancel(true).build())
        if (kind == 2) context.getSharedPreferences("settings", 0).edit().putLong("backupReminderBase", System.currentTimeMillis()).apply()
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Reminders.notify(context, intent.getIntExtra("kind", 1)); Reminders.schedule(context) }
}
class BootReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { Reminders.schedule(context) } }
