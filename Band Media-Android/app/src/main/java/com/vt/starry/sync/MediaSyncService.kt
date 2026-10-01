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
    private var lastMediaKey = ""
    private var lastActiveLyricIndex = -2

    // 网易云已推送选中时以播放器状态为准，避免系统媒体状态覆盖刚推送的歌词/封面。
    private fun activeMedia(): SystemMediaState =
        if (NetEasePlayer.hasSelection()) NetEasePlayer.state.value else SystemMediaMonitor.currentState()

    private val ticker = object : Runnable {
        override fun run() {
            val media = activeMedia()
            val mediaKey = listOf(media.packageName, media.title, media.artist, media.album).joinToString("|")
            val semantic = listOf(
                media.packageName, media.title, media.artist, media.album, media.playbackState,
                media.durationMs, media.volume, media.muted, media.albumArt.hashCode(), media.lyrics.hashCode()
            ).joinToString("|")
            val playbackStateChanged = media.playbackState != lastPlaybackState
            // 切歌（自动或手动，含搜索页手动推送）：先让手环清空上一首歌词与封面缓存，
            // 再下发新歌，避免旧歌词/封面残留造成"手环还停在上一句"的观感。
            val songSwitched = lastMediaKey.isNotBlank() && mediaKey != lastMediaKey && media.title.isNotBlank()
            if (songSwitched) bridge.sendClearCache("switch")
            if (semantic != lastSemantic) {
                CommunicationLog.info("MEDIA", if (media.title.isBlank()) "播放信息已清空" else "${media.sourceName}: ${media.title}")
                lastSemantic = semantic
                bridge.sendMusicState(media)
            }
            lastPlaybackState = media.playbackState
            lastMediaKey = mediaKey
            // 歌词行切换时立即 urgent 抢发进度，让手环当前行紧跟手机，避免落后一句；
            // 平时仍以 100ms 节奏发位置。playbackState 变化同样抢发，避免控制状态滞后。
            val lyricIndexChanged = media.activeLyricIndex != lastActiveLyricIndex
            if (media.title.isNotBlank()) bridge.sendProgressState(media, playbackStateChanged || lyricIndexChanged)
            lastActiveLyricIndex = media.activeLyricIndex
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bridge = WearBridge.get(this)
        SystemMediaMonitor.start(this)
        NetEasePlayer.init(this)
        bridge.onConnected = { bridge.sendMusicState(activeMedia()) }
        bridge.onMusicRequest = { bridge.sendMusicState(activeMedia()) }
        bridge.onControl = { action, value ->
            if (NetEasePlayer.isActive()) {
                when (action.lowercase()) {
                    "play", "pause" -> NetEasePlayer.togglePlayPause()
                    "next" -> NetEasePlayer.next()
                    "prev", "previous" -> NetEasePlayer.previous()
                    "seek" -> value?.let(NetEasePlayer::seek)
                    else -> routeSystem(action, value)
                }
            } else {
                routeSystem(action, value)
            }
            confirmControlResult()
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        bridge.start()
        handler.post(ticker)
    }

    private fun routeSystem(action: String, value: Long?) {
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
    }

    private fun confirmControlResult() {
        // MediaSession commands are asynchronous. Re-read the authoritative state
        // a few times instead of guessing that the requested action succeeded.
        listOf(50L, 150L, 300L).forEach { delayMs ->
            handler.postDelayed({
                val media = activeMedia()
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
        NetEasePlayer.stop()
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
