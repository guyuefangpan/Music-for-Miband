package com.vt.starry.sync

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.vt.starry.netease.NetEaseApi
import com.vt.starry.qqmusic.QmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 独立网易云播放器：App 内搜索/选歌、本机播放，并直接通过 WearBridge 把带时间轴的歌词与封面
 * 同步到手环。作为与 SystemMediaMonitor 并列的音源；激活时 MediaSyncService 改用本播放器状态。
 */
object NetEasePlayer {
    private const val TAG = "NetEasePlayer"
    private const val CACHE_MAX_ENTRIES = 12
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private val _state = MutableStateFlow(SystemMediaState())
    val state: StateFlow<SystemMediaState> = _state.asStateFlow()
    private var queue: List<NetEaseApi.Song> = emptyList()
    private var index = -1
    private var currentSong: NetEaseApi.Song? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    // 已推送到手环的歌曲（不要求正在播放）。非空时 MediaSyncService 以本播放器状态为准，
    // 避免系统媒体状态把刚推送的歌词/封面覆盖掉。
    private var selectedSong: NetEaseApi.Song? = null
    // 歌词/封面缓存必须有上限：每张封面 data-URI 可达数百 KB，无界缓存会让内存只涨不降。
    private val lyricsCache = lruCache<List<LyricLine>>(CACHE_MAX_ENTRIES)
    private val artCache = lruCache<String>(CACHE_MAX_ENTRIES)
    // QQ 音乐独立缓存：key 为 songmid（字符串），与网易云的 Long id 缓存分开。
    private val qqLyricsCache = lruCacheStr<List<LyricLine>>(CACHE_MAX_ENTRIES)
    private val qqArtCache = lruCacheStr<String>(CACHE_MAX_ENTRIES)

    private fun <T> lruCacheStr(max: Int): LinkedHashMap<String, T> =
        object : LinkedHashMap<String, T>(max + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > max
        }

    private fun <T> lruCache(max: Int): LinkedHashMap<Long, T> =
        object : LinkedHashMap<Long, T>(max + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, T>?): Boolean = size > max
        }

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    fun isActive(): Boolean = currentSong != null && mediaPlayer != null

    /** 是否有已推送/选中的网易云歌曲（不要求正在播放） */
    fun hasSelection(): Boolean = selectedSong != null

    fun selectedSong(): NetEaseApi.Song? = selectedSong

    /** 放弃网易云选中，回到系统媒体同步 */
    fun clearSelection() {
        selectedSong = null
        _state.value = SystemMediaState()
        WearBridge.get(context).sendMusicState(SystemMediaMonitor.currentState())
    }

    /**
     * 只推送歌词到手环：不播放、不依赖播放地址。
     * 网易云播放地址常被限制，因此推送链路必须与播放解耦。
     */
    fun pushLyrics(song: NetEaseApi.Song, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val (lyrics, art) = loadMeta(song)
                if (lyrics.isEmpty()) {
                    onResult(false, "网易云未返回歌词")
                    return@launch
                }
                val media = buildState(song, art, lyrics, activeLyricIndex = 0)
                publish(song, media)
                val ok = WearBridge.get(context).forceSyncLyrics(media)
                onResult(ok, if (ok) "歌词已推送 ${lyrics.size} 行" else "推送失败：手环未连接")
            } catch (e: Throwable) {
                CommunicationLog.error("NETEASE", "推送歌词失败 id=${song.id}", e)
                onResult(false, "推送失败：${e.message}")
            }
        }
    }

    /** 只推送封面到手环：不播放、不依赖播放地址。 */
    fun pushArtwork(song: NetEaseApi.Song, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val (lyrics, art) = loadMeta(song)
                if (art.isBlank()) {
                    onResult(false, "网易云未返回封面")
                    return@launch
                }
                val media = buildState(song, art, lyrics)
                publish(song, media)
                val ok = WearBridge.get(context).forceSyncArtwork(media)
                onResult(ok, if (ok) "封面已推送" else "推送失败：手环未连接")
            } catch (e: Throwable) {
                CommunicationLog.error("NETEASE", "推送封面失败 id=${song.id}", e)
                onResult(false, "推送失败：${e.message}")
            }
        }
    }

    private fun publish(song: NetEaseApi.Song, media: SystemMediaState) {
        selectedSong = song
        _state.value = media
        WearBridge.get(context).sendMusicState(media)
    }

    /**
     * QQ 音乐：只推歌词到手环。与网易云同一条 publish -> 手环链路，只是取数走 QmApi
     * （免签名免登录接口：歌词 base64 LRC + gtimg 封面）。不播放、不依赖播放地址。
     */
    fun pushQqLyrics(song: QmApi.Song, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val (lyrics, art) = loadQqMeta(song)
                if (lyrics.isEmpty()) {
                    onResult(false, "QQ音乐未返回歌词")
                    return@launch
                }
                val media = buildQqState(song, art, lyrics, activeLyricIndex = 0)
                // selectedSong 用伪 NetEase Song 占位（仅参与 hasSelection 判定，真实来源看 sourceName）。
                publish(qqPseudoSong(song), media)
                val ok = WearBridge.get(context).forceSyncLyrics(media)
                onResult(ok, if (ok) "歌词已推送 ${lyrics.size} 行" else "推送失败：手环未连接")
            } catch (e: Throwable) {
                CommunicationLog.error("QQMUSIC", "推送歌词失败 mid=${song.songmid}", e)
                onResult(false, "推送失败：${e.message}")
            }
        }
    }

    /** QQ 音乐：只推封面到手环。 */
    fun pushQqArtwork(song: QmApi.Song, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val (lyrics, art) = loadQqMeta(song)
                if (art.isBlank()) {
                    onResult(false, "QQ音乐未返回封面")
                    return@launch
                }
                val media = buildQqState(song, art, lyrics)
                publish(qqPseudoSong(song), media)
                val ok = WearBridge.get(context).forceSyncArtwork(media)
                onResult(ok, if (ok) "封面已推送" else "推送失败：手环未连接")
            } catch (e: Throwable) {
                CommunicationLog.error("QQMUSIC", "推送封面失败 mid=${song.songmid}", e)
                onResult(false, "推送失败：${e.message}")
            }
        }
    }

    private fun qqPseudoSong(song: QmApi.Song) = NetEaseApi.Song(
        id = -song.songmid.hashCode().toLong(),
        name = song.name, artist = song.artist, album = "", picUrl = "",
    )

    private suspend fun loadQqMeta(song: QmApi.Song): Pair<List<LyricLine>, String> {
        val lyrics = qqLyricsCache[song.songmid] ?: QmApi.getLyrics(song.songmid).also { qqLyricsCache[song.songmid] = it }
        val art = qqArtCache[song.songmid] ?: run {
            val albummid = song.albummid.ifBlank { QmApi.resolveAlbumMid(song.songmid).orEmpty() }
            if (albummid.isBlank()) "" else QmApi.getCoverBitmap(albummid)?.let { SystemMediaMonitor.encodeBitmapDataUri(it) }.orEmpty()
        }.also { qqArtCache[song.songmid] = it }
        return lyrics to art
    }

    private fun buildQqState(
        song: QmApi.Song,
        art: String,
        lyrics: List<LyricLine>,
        activeLyricIndex: Int = -1,
    ): SystemMediaState = SystemMediaState(
        packageName = context.packageName,
        sourceName = "QQ音乐",
        title = song.name,
        artist = song.artist,
        album = "",
        albumArt = art,
        durationMs = _state.value.durationMs,
        positionMs = _state.value.positionMs,
        playbackState = "paused",
        lyrics = lyrics,
        activeLyricIndex = activeLyricIndex,
    )

    private suspend fun loadMeta(song: NetEaseApi.Song): Pair<List<LyricLine>, String> {
        val lyrics = lyricsCache[song.id] ?: NetEaseApi.getLyrics(song.id).also { lyricsCache[song.id] = it }
        val art = artCache[song.id] ?: (if (song.picUrl.isBlank()) "" else {
            NetEaseApi.getCoverBitmap(song.picUrl)?.let { SystemMediaMonitor.encodeBitmapDataUri(it) }.orEmpty()
        }).also { artCache[song.id] = it }
        return lyrics to art
    }

    private fun buildState(
        song: NetEaseApi.Song,
        art: String,
        lyrics: List<LyricLine>,
        activeLyricIndex: Int = -1
    ): SystemMediaState = SystemMediaState(
        packageName = context.packageName,
        sourceName = "网易云音乐",
        title = song.name,
        artist = song.artist,
        album = song.album,
        albumArt = art,
        durationMs = _state.value.durationMs,
        positionMs = _state.value.positionMs,
        playbackState = if (mediaPlayer?.isPlaying == true) "playing" else "paused",
        lyrics = lyrics,
        activeLyricIndex = activeLyricIndex,
    )

    fun setQueue(songs: List<NetEaseApi.Song>, startAt: Int) {
        if (songs.isEmpty()) return
        queue = songs
        index = startAt.coerceIn(0, songs.size - 1)
        playCurrent()
    }

    fun playCurrent() {
        val song = queue.getOrNull(index) ?: return
        loadAndPlay(song)
    }

    fun next() {
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        playCurrent()
    }

    fun previous() {
        if (queue.isEmpty()) return
        index = (index - 1 + queue.size) % queue.size
        playCurrent()
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: run { if (currentSong != null) playCurrent(); return }
        if (mp.isPlaying) mp.pause() else mp.start()
        emitProgress(urgent = true)
    }

    fun seek(ms: Long) {
        mediaPlayer?.seekTo(ms.coerceAtLeast(0).toInt())
        emitProgress(urgent = true)
    }

    fun stop() {
        progressJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        currentSong = null
        selectedSong = null
        _state.value = SystemMediaState()
        WearBridge.get(context).sendMusicState(_state.value)
    }

    private fun loadAndPlay(song: NetEaseApi.Song) {
        scope.launch {
            try {
                val url = NetEaseApi.getPlayUrl(song.id) ?: run {
                    CommunicationLog.warn("NETEASE", "无法获取播放地址 id=${song.id}")
                    return@launch
                }
                val lyrics = NetEaseApi.getLyrics(song.id)
                val art = if (song.picUrl.isNotBlank()) {
                    NetEaseApi.getCoverBitmap(song.picUrl)?.let { SystemMediaMonitor.encodeBitmapDataUri(it) }.orEmpty()
                } else ""
                currentSong = song
                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    mp.setDataSource(url)
                    mp.prepareAsync()
                    mp.setOnPreparedListener {
                        it.start()
                        startProgress()
                        emitProgress(urgent = true)
                    }
                    mp.setOnCompletionListener { next() }
                    mp.setOnErrorListener { _, what, extra ->
                        CommunicationLog.error("NETEASE", "播放错误 what=$what extra=$extra")
                        true
                    }
                    _state.value = SystemMediaState(
                        packageName = context.packageName,
                        sourceName = "网易云音乐",
                        title = song.name,
                        artist = song.artist,
                        album = song.album,
                        albumArt = art,
                        durationMs = 0,
                        positionMs = 0,
                        playbackState = "buffering",
                        lyrics = lyrics,
                        activeLyricIndex = if (lyrics.isNotEmpty()) 0 else -1,
                    )
                    WearBridge.get(context).sendMusicState(_state.value)
                }
            } catch (e: Throwable) {
                CommunicationLog.error("NETEASE", "播放失败 id=${song.id}", e)
            }
        }
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                emitProgress(urgent = false)
                delay(1000)
            }
        }
    }

    private fun emitProgress(urgent: Boolean) {
        val mp = mediaPlayer ?: return
        val song = currentSong ?: return
        val pos = mp.currentPosition.toLong().coerceAtLeast(0)
        val dur = if (mp.duration > 0) mp.duration.toLong() else _state.value.durationMs
        val lyrics = _state.value.lyrics
        _state.value = _state.value.copy(
            positionMs = pos,
            durationMs = dur,
            playbackState = if (mp.isPlaying) "playing" else "paused",
            activeLyricIndex = activeLyricIndexFor(lyrics, pos),
        )
        val bridge = WearBridge.get(context)
        bridge.sendProgressState(_state.value, urgent)
    }

    private fun activeLyricIndexFor(lyrics: List<LyricLine>, position: Long): Int {
        val timed = lyrics.indexOfLast { it.startMs != null && it.startMs <= position }
        return if (timed >= 0) timed else if (lyrics.isNotEmpty()) 0 else -1
    }
}
