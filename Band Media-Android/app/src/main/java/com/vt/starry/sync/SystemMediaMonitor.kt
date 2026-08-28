package com.vt.starry.sync

import com.vt.starry.BuildConfig

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class LyricLine(val startMs: Long?, val text: String)

data class SystemMediaState(
    val packageName: String = "",
    val sourceName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArt: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playbackState: String = "stopped",
    val volume: Int = 0,
    val muted: Boolean = false,
    val lyrics: List<LyricLine> = emptyList(),
    val activeLyricIndex: Int = -1,
)

object SystemMediaMonitor {
    private const val TAG = "SystemMediaMonitor"
    // The watch renders the cover at roughly 230 px. Keep enough source pixels to avoid
    // upscaling blur; WearBridge still divides this into three logical pieces and safe packets.
    private const val MAX_ARTWORK_DATA_URI_CHARS = 60_000
    private val _state = MutableStateFlow(SystemMediaState())
    val state: StateFlow<SystemMediaState> = _state.asStateFlow()
    private val _notificationAccess = MutableStateFlow(false)
    val notificationAccess: StateFlow<Boolean> = _notificationAccess.asStateFlow()

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private val controllers = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var controller: MediaController? = null
    private var notificationLyrics = emptyMap<String, String>()
    private var notificationArtwork = emptyMap<String, String>()
    private var previousVolume = 5
    private var cachedArtworkKey = ""
    private var cachedArtwork = ""
    private var lastRefreshErrorKey = ""
    private var lastRefreshErrorAt = 0L

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        refreshControllers(it.orEmpty())
        safeRefresh("sessions")
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        refreshPermission(context)
        if (manager != null) return
        manager = context.getSystemService(MediaSessionManager::class.java)
        listenerComponent = ComponentName(context, StarryNotificationListener::class.java)
        try {
            manager?.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent)
            refreshControllers(manager?.getActiveSessions(listenerComponent).orEmpty())
            safeRefresh("start")
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification access is not enabled", error)
            _state.value = SystemMediaState()
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        controllers.values.forEach { (item, callback) -> item.unregisterCallback(callback) }
        controllers.clear()
        controller = null
        manager = null
        cachedArtworkKey = ""
        cachedArtwork = ""
    }

    fun refreshPermission(context: Context) {
        _notificationAccess.value = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(context).contains(context.packageName)
    }

    fun refreshFromListener(context: Context) {
        if (manager == null) start(context)
        try { refreshControllers(manager?.getActiveSessions(listenerComponent).orEmpty()) } catch (_: SecurityException) {}
        safeRefresh("listener")
    }

    fun updateNotifications(context: Context, notifications: Array<StatusBarNotification>?) {
        val mediaNotifications = notifications.orEmpty().filter(::isMediaNotification)
        notificationLyrics = mediaNotifications.mapNotNull { item ->
            extractNotificationLyric(item.notification.extras)?.let { item.packageName to it }
        }.toMap()
        val nextArtwork = mediaNotifications.mapNotNull { item ->
            encodeNotificationArtwork(context, item)?.let { item.packageName to it }
        }.toMap()
        if (nextArtwork != notificationArtwork) {
            notificationArtwork = nextArtwork
            cachedArtworkKey = ""
            cachedArtwork = ""
        }
        safeRefresh("notification")
    }

    fun play() = controller?.transportControls?.play()
    fun pause() = controller?.transportControls?.pause()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun next() = controller?.transportControls?.skipToNext()
    fun seek(positionMs: Long) = controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0))

    fun setVolume(percent: Int) {
        val targetPercent = percent.coerceIn(0, 100)
        val active = controller
        val info = runCatching { active?.playbackInfo }.getOrNull()
        if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && info.maxVolume > 0) {
            val target = (info.maxVolume * targetPercent / 100f).roundToInt()
            active?.setVolumeTo(target, 0)
        } else {
            val audio = audioManager() ?: return
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (maximum * targetPercent / 100f).roundToInt(), 0)
        }
        if (targetPercent > 0) previousVolume = targetPercent
        safeRefresh("setVolume")
    }

    fun adjustVolume(delta: Int) {
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        safeRefresh("volume")
    }

    fun toggleMute() {
        val current = systemVolumePercent()
        if (current == 0) setVolume(previousVolume.coerceAtLeast(5))
        else { previousVolume = current; setVolume(0) }
    }

    fun currentState(): SystemMediaState { safeRefresh("poll"); return _state.value }

    private fun refreshControllers(sessions: List<MediaController>) {
        val currentTokens = sessions.mapTo(HashSet()) { it.sessionToken }
        val removed = controllers.keys.filter { it !in currentTokens }
        removed.forEach { token -> controllers.remove(token)?.let { (item, callback) -> item.unregisterCallback(callback) } }
        sessions.filter { BuildConfig.DEBUG || safePackageName(it) != appContext?.packageName }.forEach(::attachController)
        selectController()
    }

    private fun attachController(item: MediaController) {
        if (controllers.containsKey(item.sessionToken)) return
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { selectController(); safeRefresh("metadata") }
            override fun onPlaybackStateChanged(state: PlaybackState?) { selectController(); safeRefresh("playback") }
            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = safeRefresh("audio")
            override fun onSessionDestroyed() {
                controllers.remove(item.sessionToken)
                selectController()
                safeRefresh("destroyed")
            }
        }
        item.registerCallback(callback)
        controllers[item.sessionToken] = item to callback
    }

    private fun selectController() {
        val candidates = controllers.values.map { it.first }.filter(::isUsableSession)
        val selected = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: candidates.firstOrNull()
        if (selected?.sessionToken == controller?.sessionToken) return
        controller = selected
        cachedArtworkKey = ""
        cachedArtwork = ""
    }

    private fun isUsableSession(session: MediaController): Boolean {
        val state = runCatching { session.playbackState?.state }.getOrNull()
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_PAUSED) return true
        val metadata = runCatching { session.metadata }.getOrNull() ?: return false
        return metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotBlank()
    }

    private fun safeRefresh(reason: String) {
        try { refresh() } catch (error: Throwable) {
            Log.e(TAG, "Media refresh failed: $reason", error)
            val key = error.javaClass.name + "|" + error.message.orEmpty()
            val now = System.currentTimeMillis()
            if (key != lastRefreshErrorKey || now - lastRefreshErrorAt >= 30_000L) {
                lastRefreshErrorKey = key
                lastRefreshErrorAt = now
                CommunicationLog.error("MEDIA", "媒体读取失败，已保留上一状态 reason=$reason", error)
            }
        }
    }

    private fun refresh() {
        val active = controller ?: run { _state.value = SystemMediaState(); return }
        val packageName = safePackageName(active)
        val metadata = runCatching { active.metadata }.getOrNull()
        val playback = runCatching { active.playbackState }.getOrNull()
        val duration = metadata.longValue(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val position = extrapolatedPosition(playback, duration)
        val lyrics = extractLyrics(metadata, notificationLyrics[packageName])
        val volume = systemVolumePercent()
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).ifBlank { "未知曲目" }
        _state.value = SystemMediaState(
            packageName = packageName,
            sourceName = sourceName(packageName),
            title = title,
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).ifBlank { packageName },
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            albumArt = artworkFor(packageName, title, metadata),
            durationMs = duration,
            positionMs = position,
            playbackState = playbackName(playback?.state),
            volume = volume,
            muted = volume == 0,
            lyrics = lyrics,
            activeLyricIndex = activeLyricIndex(lyrics, position),
        )
    }

    private fun safePackageName(active: MediaController): String =
        runCatching { active.packageName }.getOrNull().orEmpty()

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun systemVolumePercent(): Int {
        val active = controller
        val info = runCatching { active?.playbackInfo }.getOrNull()
        if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && info.maxVolume > 0) {
            return (info.currentVolume * 100f / info.maxVolume).roundToInt().coerceIn(0, 100)
        }
        val audio = audioManager() ?: return _state.value.volume
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) return _state.value.volume
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum).roundToInt().coerceIn(0, 100)
    }

    private fun sourceName(packageName: String): String = try {
        val pm = appContext?.packageManager ?: return packageName
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) { packageName }

    private fun artworkFor(packageName: String, title: String, metadata: MediaMetadata?): String {
        val notificationArt = notificationArtwork[packageName].orEmpty()
        val key = packageName + "|" + title + "|" + notificationArt.hashCode()
        if (key == cachedArtworkKey) return cachedArtwork
        cachedArtworkKey = key
        cachedArtwork = encodeArtwork(metadata).ifBlank { encodeArtworkUri(metadata) }.ifBlank { notificationArt }
        return cachedArtwork
    }

    private fun extrapolatedPosition(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0
        var position = state.position.coerceAtLeast(0)
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        return if (duration > 0) position.coerceIn(0, duration) else position
    }

    private fun playbackName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
        else -> "stopped"
    }

    private fun extractLyrics(metadata: MediaMetadata?, notificationLyric: String?): List<LyricLine> {
        val candidates = buildList {
            if (metadata != null) for (key in runCatching { metadata.keySet() }.getOrDefault(emptySet())) {
                if (key?.contains("lyric", ignoreCase = true) == true) {
                    runCatching { metadata.getText(key)?.toString() }.getOrNull()?.let(::add)
                }
            }
            notificationLyric?.let(::add)
        }
        return parseLyrics(candidates.firstOrNull { it.isNotBlank() }.orEmpty())
    }

    private fun parseLyrics(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val timed = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]([^\n\r]*)""")
        val parsed = timed.findAll(raw).mapNotNull { match ->
            val text = match.groupValues[4].trim()
            if (text.isBlank()) null else {
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) { 1 -> fraction.toLongOrNull()?.times(100); 2 -> fraction.toLongOrNull()?.times(10); else -> fraction.toLongOrNull() } ?: 0
                LyricLine(match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1000 + millis, text)
            }
        }.sortedBy { it.startMs }.toList()
        if (parsed.isNotEmpty()) return parsed
        return raw.lines().map(String::trim).filter(String::isNotBlank).distinct().map { LyricLine(null, it) }
    }

    private fun activeLyricIndex(lines: List<LyricLine>, position: Long): Int {
        val timed = lines.indexOfLast { it.startMs != null && it.startMs <= position }
        return if (timed >= 0) timed else if (lines.isNotEmpty()) 0 else -1
    }

    private fun encodeArtwork(metadata: MediaMetadata?): String {
        val bitmap = metadata.bitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.bitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.bitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: runCatching { metadata?.description?.iconBitmap }.getOrNull()
            ?: return ""
        return encodeBitmap(bitmap)
    }

    private fun encodeArtworkUri(metadata: MediaMetadata?): String {
        val value = metadata.text(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).ifBlank { runCatching { metadata?.description?.iconUri?.toString() }.getOrNull().orEmpty() }
        if (value.isBlank()) return ""
        val resolver = appContext?.contentResolver ?: return ""
        return runCatching {
            resolver.openInputStream(Uri.parse(value))?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)?.let(::encodeBitmap)
            }.orEmpty()
        }.getOrElse {
            Log.w(TAG, "Unable to read artwork URI", it)
            ""
        }
    }

    private fun encodeBitmap(bitmap: Bitmap): String {
        return try {
            val candidates = listOf(256 to 90, 240 to 88, 230 to 86, 220 to 84, 208 to 82, 196 to 80)
            var best = ""
            for ((edge, quality) in candidates) {
                val scale = minOf(1f, edge.toFloat() / maxOf(bitmap.width, bitmap.height))
                val width = maxOf(1, (bitmap.width * scale).roundToInt())
                val height = maxOf(1, (bitmap.height * scale).roundToInt())
                val scaled = if (width != bitmap.width || height != bitmap.height) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                if (scaled !== bitmap) scaled.recycle()
                best = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                if (best.length + "data:image/jpeg;base64,".length <= MAX_ARTWORK_DATA_URI_CHARS) break
            }
            val value = "data:image/jpeg;base64," + best
            if (value.length <= MAX_ARTWORK_DATA_URI_CHARS) value else {
                CommunicationLog.warn("MEDIA", "封面压缩后仍超限 chars=${value.length}")
                ""
            }
        } catch (error: Throwable) { Log.w(TAG, "Unable to encode album art", error); CommunicationLog.error("MEDIA", "artwork encoding failed", error); "" }
    }

    private fun MediaMetadata?.text(vararg keys: String): String {
        if (this == null) return ""
        return keys.firstNotNullOfOrNull { key ->
            runCatching { getText(key)?.toString() }.getOrNull()?.takeIf(String::isNotBlank)
        }.orEmpty()
    }

    private fun MediaMetadata?.longValue(key: String): Long =
        if (this == null) 0L else runCatching { getLong(key) }.getOrDefault(0L)

    private fun MediaMetadata?.bitmap(key: String): Bitmap? =
        if (this == null) null else runCatching { getBitmap(key) }.getOrNull()

    private fun extractNotificationLyric(extras: Bundle): String? {
        for (key in extras.keySet()) if (key.contains("lyric", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            val value = extras.get(key)?.toString()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun isMediaNotification(item: StatusBarNotification): Boolean {
        val notification = item.notification
        return notification.category == android.app.Notification.CATEGORY_TRANSPORT ||
            notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
    }

    private fun encodeNotificationArtwork(context: Context, item: StatusBarNotification): String? {
        val extras = item.notification.extras
        val bitmapKeys = arrayOf(
            android.app.Notification.EXTRA_PICTURE,
            android.app.Notification.EXTRA_LARGE_ICON_BIG,
            android.app.Notification.EXTRA_LARGE_ICON,
        )
        for (key in bitmapKeys) {
            @Suppress("DEPRECATION")
            val value = extras.get(key)
            val bitmap = when (value) {
                is Bitmap -> value
                is Icon -> runCatching { drawableToBitmap(value.loadDrawable(context)) }.getOrNull()
                is BitmapDrawable -> value.bitmap
                else -> null
            }
            if (bitmap != null) return encodeBitmap(bitmap).takeIf(String::isNotBlank)
        }
        return try {
            val icon = item.notification.getLargeIcon() ?: item.notification.smallIcon
            drawableToBitmap(icon?.loadDrawable(context))?.let(::encodeBitmap)?.takeIf(String::isNotBlank)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to read notification artwork", error)
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }
}

class StarryNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SystemMediaMonitor.refreshPermission(applicationContext)
        SystemMediaMonitor.refreshFromListener(applicationContext)
        SystemMediaMonitor.updateNotifications(applicationContext, activeNotifications)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = SystemMediaMonitor.updateNotifications(applicationContext, activeNotifications)
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = SystemMediaMonitor.updateNotifications(applicationContext, activeNotifications)
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        SystemMediaMonitor.refreshPermission(applicationContext)
    }
}
