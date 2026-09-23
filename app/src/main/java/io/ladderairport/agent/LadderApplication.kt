package io.ladderairport.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.ladderairport.agent.util.PreferencesHelper

class LadderApplication : Application() {

    companion object {
        const val CHANNEL_ID = "ladder_agent_service"
        lateinit var instance: LadderApplication
            private set

        val logLines = MutableLiveData<List<String>>(emptyList())
        private val logsBuffer = mutableListOf<String>()
        private const val MAX_LOGS = 500

        @Synchronized
        fun appendLog(line: String) {
            logsBuffer.add(line)
            if (logsBuffer.size > MAX_LOGS) {
                logsBuffer.removeAt(0)
            }
            logLines.postValue(logsBuffer.toList())
        }

        @Synchronized
        fun clearLogs() {
            logsBuffer.clear()
            logLines.postValue(emptyList())
        }
    }

    lateinit var prefs: PreferencesHelper
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesHelper(this)

        // Initialize gomobile Seq runtime with Application context.
        Seq.setContext(this)

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
