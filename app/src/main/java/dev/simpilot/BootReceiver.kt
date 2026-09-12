package dev.simpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (AppPreferences(context).load().enabled) {
            MonitorService.start(context)
        }
    }
}
