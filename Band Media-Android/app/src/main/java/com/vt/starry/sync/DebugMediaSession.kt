package com.vt.starry.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

object DebugMediaSession {
    private const val DURATION = 245_000L
    private var session: MediaSession? = null
    private var playing = true
    private var position = 32_000L
    private var updatedAt = 0L

    fun start(context: Context) {
        if (session != null) return
        updatedAt = SystemClock.elapsedRealtime()
        session = MediaSession(context, "StarryDebugMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = updatePlayback(true)
                override fun onPause() = updatePlayback(false)
                override fun onSeekTo(pos: Long) { currentPosition(); position = pos.coerceIn(0, DURATION); publishState() }
                override fun onSkipToNext() { position = 0; publishState() }
                override fun onSkipToPrevious() { position = 0; publishState() }
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "星河测试曲")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Starry MediaSession")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "系统媒体读取验证")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, DURATION)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork())
                .putString("com.vt.starry.LYRICS", "[00:00.00]这是第一行歌词\n[00:15.00]媒体信息读取正常\n[00:30.00]封面和进度正在同步\n[00:45.00]手环控制会回到系统播放器")
                .build())
            isActive = true
        }
        publishState()
    }

    fun stop() { session?.release(); session = null }

    private fun updatePlayback(value: Boolean) {
        currentPosition()
        playing = value
        updatedAt = SystemClock.elapsedRealtime()
        publishState()
    }

    private fun currentPosition(): Long {
        if (playing) {
            val now = SystemClock.elapsedRealtime()
            position = (position + now - updatedAt).coerceAtMost(DURATION)
            updatedAt = now
        }
        return position
    }

    private fun publishState() {
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, currentPosition(), if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
    }

    private fun artwork(): Bitmap {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(28, 32, 38))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 209, 197) }
        canvas.drawCircle(96f, 96f, 62f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 54f
        canvas.drawText("S", 96f, 115f, paint)
        return bitmap
    }
}
