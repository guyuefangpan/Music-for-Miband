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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.vt.starry.netease.NetEaseApi
import com.vt.starry.qqmusic.QmApi

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
    // Vela's JPEG decoder corrupts some album art. PNG is larger, so progressively
    // scale toward the watch's native cover size until it fits the transport budget.
    private const val MAX_ARTWORK_DATA_URI_CHARS = 120_000
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

    // 网易云补全：只在通知/系统确实读不到歌词或封面时才调用，避免每首歌都打接口。
    private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enrichmentCache = ConcurrentHashMap<String, Enrichment>()
    // 失败重试：记录已尝试次数与下次可重试时间，避免一次网络抖动导致永久缺失
    private val enrichmentAttempts = ConcurrentHashMap<String, Int>()
    private val enrichmentNextRetryAt = ConcurrentHashMap<String, Long>()
    private val enrichmentInflight = ConcurrentHashMap.newKeySet<String>()
    // 无 MediaSession 时的兜底音轨（来自通知）
    private var notificationTracks = emptyMap<String, NotificationTrack>()
    private data class Enrichment(val albumArt: String?, val lyrics: List<LyricLine>)
    private data class NotificationTrack(val title: String, val artist: String)

    private const val MAX_ENRICH_ATTEMPTS = 3
    private const val ENRICH_RETRY_BACKOFF_MS = 30_000L
    // 每条补全结果可能带着最多 12 万字符的封面，缓存条数直接决定常驻堆占用
    private const val MAX_ENRICH_CACHE = 20
    // 网易云拿不到歌词/封面、或只拿到翻唱/非原唱版本时，用 QQ 音乐兜底拿原唱。
    // 触发条件已从"彻底拿不到"扩展为"拿不到 或 拿到的是翻唱"，避免手环显示翻唱歌词/封面。
    // QQ 走免签名免登录接口，无需用户任何 Cookie；设为 false 可彻底关闭兜底。
    private const val QQ_FALLBACK_ENABLED = true

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
        notificationTracks = mediaNotifications.mapNotNull { item ->
            extractNotificationTrack(item.notification.extras)?.let { item.packageName to it }
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
        val active = controller ?: run { refreshFromNotificationsOnly(); return }
        val packageName = safePackageName(active)
        val metadata = runCatching { active.metadata }.getOrNull()
        val playback = runCatching { active.playbackState }.getOrNull()
        val duration = metadata.longValue(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val position = extrapolatedPosition(playback, duration)
        val baseLyrics = extractLyrics(metadata, notificationLyrics[packageName])
        val volume = systemVolumePercent()
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).ifBlank { "未知曲目" }
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).ifBlank { packageName }
        // 网易云/QQ 补全：网易云优先且只取原唱；网易云拿不到或只拿到翻唱时，由 QQ 兜底拿原唱（见 lookupEnrichment）。
        // 歌手缺失时系统会退化成包名，这里必须剔除，否则搜索关键词被污染会配错歌。
        val artistForSearch = artist.takeUnless { it.isBlank() || it == packageName }.orEmpty()
        val key = enrichmentKey(title, artistForSearch)
        val enr = if (key.isNotBlank()) enrichmentCache[key] else null
        val lyrics = if (enr != null && enr.lyrics.isNotEmpty()) enr.lyrics else baseLyrics
        val albumArt = if (enr != null && !enr.albumArt.isNullOrBlank()) enr.albumArt!! else artworkFor(packageName, title, metadata)
        _state.value = SystemMediaState(
            packageName = packageName,
            sourceName = sourceName(packageName),
            title = title,
            artist = artist,
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            albumArt = albumArt,
            durationMs = duration,
            positionMs = position,
            playbackState = playbackName(playback?.state),
            volume = volume,
            muted = volume == 0,
            lyrics = lyrics,
            activeLyricIndex = activeLyricIndex(lyrics, position),
        )
        // 仅当歌词或封面缺失时才请求网易云（通知里几乎从不带歌词，因此主要由歌词缺失触发）
        maybeEnrich(key, title, artistForSearch, lyrics.isEmpty() || albumArt.isBlank())
    }

    /** 无 MediaSession 时的兜底：用通知里的歌名/歌手/封面/歌词组装状态，仍可触发网易云补全。 */
    private fun refreshFromNotificationsOnly() {
        val entry = notificationTracks.entries.firstOrNull()
        val track = entry?.value
        if (track == null) {
            _state.value = SystemMediaState()
            return
        }
        val packageName = entry.key
        val baseLyrics = parseLyrics(notificationLyrics[packageName].orEmpty())
        val albumArt = notificationArtwork[packageName].orEmpty()
        val artistForSearch = track.artist.takeUnless { it.isBlank() || it == packageName }.orEmpty()
        val key = enrichmentKey(track.title, artistForSearch)
        val enr = if (key.isNotBlank()) enrichmentCache[key] else null
        val lyrics = if (enr != null && enr.lyrics.isNotEmpty()) enr.lyrics else baseLyrics
        val art = if (enr != null && !enr.albumArt.isNullOrBlank()) enr.albumArt!! else albumArt
        _state.value = SystemMediaState(
            packageName = packageName,
            sourceName = sourceName(packageName),
            title = track.title,
            artist = track.artist.ifBlank { artistForSearch.ifBlank { packageName } },
            album = "",
            albumArt = art,
            durationMs = 0,
            positionMs = 0,
            playbackState = "playing",
            volume = systemVolumePercent(),
            muted = false,
            lyrics = lyrics,
            activeLyricIndex = activeLyricIndex(lyrics, 0),
        )
        maybeEnrich(key, track.title, artistForSearch, lyrics.isEmpty() || art.isBlank())
    }

    /** 是否发起网易云补全：缺失才查、已有结果不查、失败退避重试、同 key 不并发。 */
    private fun maybeEnrich(key: String, title: String, artistForSearch: String, needed: Boolean) {
        if (!needed || key.isBlank()) return
        if (enrichmentCache.containsKey(key)) return
        if ((enrichmentAttempts[key] ?: 0) >= MAX_ENRICH_ATTEMPTS) return
        if (System.currentTimeMillis() < (enrichmentNextRetryAt[key] ?: 0L)) return
        if (!enrichmentInflight.add(key)) return
        enrichmentScope.launch {
            try {
                lookupEnrichment(key, title, artistForSearch)
            } finally {
                enrichmentInflight.remove(key)
            }
        }
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
        val metadataArt = metadata.bitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.bitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.bitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: runCatching { metadata?.description?.iconBitmap }.getOrNull()
        val metadataUri = metadata.text(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
        val key = listOf(packageName, title, metadataArt?.generationId, metadataArt?.width, metadataArt?.height, metadataUri, notificationArt.hashCode()).joinToString("|")
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
            val candidates = listOf(384, 352, 320, 288, 256, 224, 192)
            var best = ""
            for (edge in candidates) {
                val scale = minOf(1f, edge.toFloat() / maxOf(bitmap.width, bitmap.height))
                val width = maxOf(1, (bitmap.width * scale).roundToInt())
                val height = maxOf(1, (bitmap.height * scale).roundToInt())
                val scaled = if (width != bitmap.width || height != bitmap.height) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
                if (scaled !== bitmap) scaled.recycle()
                best = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                if (best.length + "data:image/png;base64,".length <= MAX_ARTWORK_DATA_URI_CHARS) break
            }
            val value = "data:image/png;base64," + best
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

    /** 从通知里取歌名/歌手，供无 MediaSession 时兜底使用。 */
    private fun extractNotificationTrack(extras: Bundle): NotificationTrack? {
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isBlank()) return null
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        return NotificationTrack(title, text.ifBlank { sub })
    }

    private fun extractNotificationLyric(extras: Bundle): String? {
        for (key in extras.keySet()) if (key.contains("lyric", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            val value = extras.get(key)?.toString()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    fun isMediaNotification(item: StatusBarNotification): Boolean {
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

    // 供独立网易云播放器复用封面编码（返回 data:image/png;base64, 形式）
    fun encodeBitmapDataUri(bitmap: Bitmap): String = encodeBitmap(bitmap)

    private fun enrichmentKey(title: String, artist: String): String {
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()
        return if (t.isBlank()) "" else "$t|$a"
    }

    private suspend fun lookupEnrichment(key: String, title: String, artist: String) {
        try {
            // 1) 先试网易云：版权完整的歌都从这里出。但只采用"原唱匹配"的候选
            //    （歌手命中且标题无翻唱词），跳过翻唱/伴奏版——否则会拿到翻唱的歌词与封面。
            //    候选按评分降序、翻唱沉底后遍历，原唱拿到歌词+封面即停止。
            var lyrics: List<LyricLine> = emptyList()
            var art: String? = null
            var neteaseProvidedOriginal = false
            val candidates = NetEaseApi.searchCandidates(title, artist, 10)
            val ordered = candidates.sortedBy { if (looksLikeCover(it.name, it.artist, artist)) 1 else 0 }
            for (song in ordered) {
                if (lyrics.isNotEmpty() && art != null) break
                if (looksLikeCover(song.name, song.artist, artist)) continue
                if (lyrics.isEmpty()) {
                    val l = NetEaseApi.getLyrics(song.id)
                    if (l.isNotEmpty()) { lyrics = l; neteaseProvidedOriginal = true }
                }
                if (art == null) {
                    val picUrl = NetEaseApi.getAlbumPicUrl(song.id)
                    if (!picUrl.isNullOrBlank()) {
                        val bmp = NetEaseApi.getCoverBitmap(picUrl)
                        if (bmp != null) { art = encodeBitmap(bmp).takeIf { it.isNotBlank() }; neteaseProvidedOriginal = true }
                    }
                }
            }

            // 2) 网易云拿不到、或只拿到翻唱（neteaseProvidedOriginal=false）、或任一项缺失时，
            //    用 QQ 音乐兜底拿原唱，只补缺失的部分。
            if (QQ_FALLBACK_ENABLED && (lyrics.isEmpty() || art.isNullOrBlank() || !neteaseProvidedOriginal)) {
                val qm = tryQqFallback(title, artist, haveLyrics = lyrics.isNotEmpty(), haveArt = !art.isNullOrBlank())
                if (lyrics.isEmpty() && qm.lyrics.isNotEmpty()) lyrics = qm.lyrics
                if (art.isNullOrBlank() && !qm.art.isNullOrBlank()) art = qm.art
            }

            if (lyrics.isEmpty() && art.isNullOrBlank()) {
                registerEnrichmentMiss(key, "网易云与QQ均无原唱匹配")
                return
            }
            enrichmentCache[key] = Enrichment(art, lyrics)
            enrichmentAttempts.remove(key)
            enrichmentNextRetryAt.remove(key)
            if (enrichmentCache.size > MAX_ENRICH_CACHE) {
                val iter = enrichmentCache.keys.iterator()
                val drop = enrichmentCache.size - MAX_ENRICH_CACHE
                repeat(drop) { if (iter.hasNext()) enrichmentCache.remove(iter.next()) }
            }
            // 若当前仍在同一首歌，重新刷新以把补全结果下发到手环
            val current = _state.value
            if (enrichmentKey(current.title, current.artist) == key) safeRefresh("enrichment")
        } catch (e: Throwable) {
            registerEnrichmentMiss(key, e.message.orEmpty())
            Log.w(TAG, "enrichment failed key=$key: ${e.message}")
        }
    }

    /**
     * QQ 音乐兜底：网易云没拿到歌词/封面时调用（如周杰伦无版权）。
     * 走免签名免登录的 smartbox 联想 -> 单曲详情 -> 歌词/封面 链路，只补 haveLyrics/haveArt 标记中缺失的部分。
     * 返回实际拿到的内容；拿不到则为空。
     */
    private suspend fun tryQqFallback(title: String, artist: String, haveLyrics: Boolean, haveArt: Boolean): QmResult {
        val keyword = if (artist.isNotBlank()) "$title $artist" else title
        val suggestions = QmApi.suggest(keyword, 5)
        if (suggestions.isEmpty()) return QmResult(emptyList(), null)
        var lyrics: List<LyricLine> = emptyList()
        var art: String? = null
        for (song in suggestions) {
            if ((haveLyrics && haveArt) || (lyrics.isNotEmpty() && art != null)) break
            // 跳过翻唱/非原唱，确保兜底也只拿原唱
            if (looksLikeCover(song.name, song.artist, artist)) continue
            if (!haveLyrics && lyrics.isEmpty()) {
                val l = QmApi.getLyrics(song.songmid)
                if (l.isNotEmpty()) lyrics = l
            }
            if (!haveArt && art == null) {
                val albummid = if (song.albummid.isNotBlank()) song.albummid else QmApi.resolveAlbumMid(song.songmid)
                if (!albummid.isNullOrBlank()) {
                    val bmp = QmApi.getCoverBitmap(albummid)
                    if (bmp != null) art = encodeBitmap(bmp).takeIf { it.isNotBlank() }
                }
            }
        }
        Log.i(TAG, "QQ fallback for '$keyword': lyrics=${lyrics.size}, art=${if (art.isNullOrBlank()) "no" else "yes"}")
        return QmResult(lyrics, art)
    }

    private data class QmResult(val lyrics: List<LyricLine>, val art: String?)

    /**
     * 判断一个候选是否"像翻唱/非原唱"：标题带翻唱演绎标志词，或歌手与原唱歌手不匹配。
     * 命中任一项即视为非原唱，在网易云/QQ 取词时会被跳过，改用原唱版本。
     */
    private fun looksLikeCover(name: String, singer: String, originalArtist: String): Boolean {
        val n = name.lowercase()
        val coverNameMarkers = listOf(
            "翻唱", "cover", "伴奏", "纯音乐", "instrumental",
            "钢琴", "吉他", "小提琴", "男声", "女声", "dj", "remix",
        )
        if (coverNameMarkers.any { n.contains(it) }) return true
        // 歌手不是原唱：候选歌手与原唱歌手互不包含，判定为非原唱演绎（合作/翻唱）
        if (originalArtist.isNotBlank() && singer.isNotBlank() &&
            !singer.contains(originalArtist, ignoreCase = true) &&
            !originalArtist.contains(singer, ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    /** 记录一次失败/未命中：按次数递增退避，交给后续 refresh 重试，避免一次抖动导致永久缺失。 */
    private fun registerEnrichmentMiss(key: String, reason: String) {
        val attempt = (enrichmentAttempts[key] ?: 0) + 1
        enrichmentAttempts[key] = attempt
        enrichmentNextRetryAt[key] = System.currentTimeMillis() + ENRICH_RETRY_BACKOFF_MS * attempt
        if (enrichmentAttempts.size > 200) enrichmentAttempts.clear()
        if (enrichmentNextRetryAt.size > 200) enrichmentNextRetryAt.clear()
        Log.w(TAG, "enrichment miss key=$key reason=$reason attempt=$attempt/$MAX_ENRICH_ATTEMPTS")
    }
}

class StarryNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SystemMediaMonitor.refreshPermission(applicationContext)
        SystemMediaMonitor.refreshFromListener(applicationContext)
        SystemMediaMonitor.updateNotifications(applicationContext, activeNotifications)
    }
    // 只处理媒体通知：任意 App 的通知都会回调这里，不过滤的话会频繁触发全量扫描与状态重建。
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && !SystemMediaMonitor.isMediaNotification(sbn)) return
        SystemMediaMonitor.updateNotifications(applicationContext, activeNotifications)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && !SystemMediaMonitor.isMediaNotification(sbn)) return
        SystemMediaMonitor.updateNotifications(applicationContext, activeNotifications)
    }
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        SystemMediaMonitor.refreshPermission(applicationContext)
        // Android 7+ 标准自愈手段：App 更新或系统回收后监听可能失效，
        // 不主动重新绑定就会一直读不到通知（连带"无 MediaSession 兜底"一起失效）。
        runCatching {
            requestRebind(ComponentName(this, StarryNotificationListener::class.java))
            Log.w("StarryNL", "通知监听已断开，已请求重新绑定")
        }.onFailure { Log.w("StarryNL", "通知监听重新绑定失败: ${it.message}") }
    }
}
