package com.vt.starry.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> DebugMediaSession.start(context.applicationContext)
            ACTION_STOP -> DebugMediaSession.stop()
        }
    }

    companion object {
        const val ACTION_START = "com.vt.starry.debug.START_MEDIA"
        const val ACTION_STOP = "com.vt.starry.debug.STOP_MEDIA"
    }
}
