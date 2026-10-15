package io.github.qianliql.diary

import android.app.Application
import io.github.qianliql.diary.data.DiaryRepository
import io.github.qianliql.diary.model.ModelManager
import io.github.qianliql.diary.reminder.Reminders
import kotlinx.coroutines.*

class DiaryApp : Application() {
    lateinit var repository: DiaryRepository; private set
    lateinit var models: ModelManager; private set
    override fun onCreate() {
        super.onCreate(); repository = DiaryRepository(this); models = ModelManager(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { repository.refresh(); repository.purgeExpired() }
        Reminders.schedule(this)
    }
}
