package com.vt.starry.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MediaSyncService : Service() {
    private lateinit var bridge: WearBridge
    private val handler = Handler(Looper.getMainLooper())
    private var lastSemantic = ""
    private var lastPlaybackState = ""

    private val ticker = object : Runnable {
        override fun run() {
            val media = SystemMediaMonitor.currentState()
            val semantic = listOf(
                media.packageName, media.title, media.artist, media.album, media.playbackState,
                media.durationMs, media.volume, media.muted, media.albumArt.hashCode(), media.lyrics.hashCode()
            ).joinToString("|")
            val playbackStateChanged = media.playbackState != lastPlaybackState
            if (semantic != lastSemantic) {
                CommunicationLog.info("MEDIA", if (media.title.isBlank()) "播放信息已清空" else "${media.sourceName}: ${media.title}")
                lastSemantic = semantic
                bridge.sendMusicState(media)
            }
            lastPlaybackState = media.playbackState
            // Keep confirming paused/buffering/stopped as well as playing. A state
            // transition is urgent so lyrics or artwork cannot leave stale controls.
            if (media.title.isNotBlank()) bridge.sendProgressState(media, playbackStateChanged)
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bridge = WearBridge.get(this)
        SystemMediaMonitor.start(this)
        bridge.onConnected = { bridge.sendMusicState(SystemMediaMonitor.currentState()) }
        bridge.onMusicRequest = { bridge.sendMusicState(SystemMediaMonitor.currentState()) }
        bridge.onControl = { action, value ->
            when (action.lowercase()) {
                "play" -> SystemMediaMonitor.play()
                "pause" -> SystemMediaMonitor.pause()
                "next" -> SystemMediaMonitor.next()
                "prev", "previous" -> SystemMediaMonitor.previous()
                "seek" -> value?.let(SystemMediaMonitor::seek)
                "setvolume" -> value?.toInt()?.let(SystemMediaMonitor::setVolume)
                "volume_up" -> SystemMediaMonitor.adjustVolume(1)
                "volume_down" -> SystemMediaMonitor.adjustVolume(-1)
                "togglemute" -> SystemMediaMonitor.toggleMute()
            }
            confirmControlResult()
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        bridge.start()
        handler.post(ticker)
    }

    private fun confirmControlResult() {
        // MediaSession commands are asynchronous. Re-read the authoritative state
        // a few times instead of guessing that the requested action succeeded.
        listOf(50L, 150L, 300L).forEach { delayMs ->
            handler.postDelayed({
                val media = SystemMediaMonitor.currentState()
                if (media.title.isNotBlank()) bridge.sendProgressState(media, urgent = true)
            }, delayMs)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "starry 媒体同步", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("starry")
        .setContentText("正在读取系统媒体并通过互联互通同步")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        SystemMediaMonitor.stop()
        bridge.stop()
        bridge.onControl = null
        bridge.onMusicRequest = null
        bridge.onConnected = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (bridge.state.value == WearState.DISCONNECTED || bridge.state.value == WearState.ERROR) bridge.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "bandmedia_sync"
        private const val NOTIFICATION_ID = 1001
    }
}
