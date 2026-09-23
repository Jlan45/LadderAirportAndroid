package io.ladderairport.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.service.AgentService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = LadderApplication.instance.prefs
            if (prefs.autoStart && prefs.isConfigured()) {
                LadderApplication.appendLog("收到开机自启广播，正在启动 Agent...")
                AgentService.start(context)
            }
        }
    }
}
