package com.vt.starry.netease

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.vt.starry.sync.LyricLine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网易云音乐 HTTP 接口封装（无第三方依赖，使用 HttpURLConnection）。
 * 参考 APK 反编译确认的稳定接口。所有网络调用在 IO 线程执行。
 */
object NetEaseApi {
    private const val TAG = "NetEaseApi"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val REFERER = "https://music.163.com/"

    data class Song(
        val id: Long,
        val name: String,
        val artist: String,
        val album: String,
        val picUrl: String,
    )

    /** 综合搜索：关键词 -> 单曲列表（最多 limit 首） */
    suspend fun search(keyword: String, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        try {
            val body = "s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&total=true&limit=$limit"
            val root = JSONObject(post("https://music.163.com/api/search/get/web?csrf_token=", body))
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
            val list = ArrayList<Song>()
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val album = s.optJSONObject("album")
                list.add(
                    Song(
                        id = s.optLong("id", 0),
                        name = s.optString("name", ""),
                        artist = buildArtist(s.optJSONArray("artists")),
                        album = album?.optString("name", "") ?: "",
                        picUrl = album?.optString("picUrl", "") ?: "",
                    )
                )
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 按评分排序返回候选列表（原唱/精确名优先，带"版/翻唱/Live/钢琴"等后缀的搬运版本降权）。
     * lookupEnrichment 会取前几名依次尝试，直到某首能拿到歌词/封面，避免一上来就选中无歌词的翻唱。
     */
    suspend fun searchCandidates(title: String, artist: String, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        val keyword = if (artist.isNotBlank()) "$title $artist" else title
        val list = search(keyword, limit)
        if (list.isEmpty()) return@withContext emptyList()
        list.sortedByDescending { scoreCandidate(it, title, artist) }
    }

    /** 取最佳一首（向后兼容：独立播放器等处仍用单首） */
    suspend fun searchBest(title: String, artist: String): Song? = searchCandidates(title, artist, 10).firstOrNull()

    /**
     * 候选评分：精确标题 +50；歌手命中 +40；精确且无版本后缀 +20、精确但带版本后缀 -15；
     * 标题包含/被包含 +10。这样原唱（如"稻香"）会排在"稻香(深情版)"等翻唱前面。
     */
    private fun scoreCandidate(s: Song, title: String, artist: String): Int {
        val cn = normalizeTitle(s.name)
        val target = normalizeTitle(title)
        val nameExact = cn == target
        val hasVersionSuffix = s.name.contains(
            Regex("""(版|cover|翻唱|live|伴奏|纯音乐|instrumental|钢琴|吉他|女声|男声|dj|remix)""", RegexOption.IGNORE_CASE)
        )
        var score = 0
        if (nameExact) score += 50
        if (artist.isNotBlank() && (s.artist.contains(artist, ignoreCase = true) || artist.contains(s.artist, ignoreCase = true))) score += 40
        score += when {
            nameExact && !hasVersionSuffix -> 20
            nameExact && hasVersionSuffix -> -15
            cn.contains(target) || target.contains(cn) -> 10
            else -> 0
        }
        return score
    }

    /** 归一化标题：忽略大小写、空白与常见标点，便于比对 "歌名(Live)" 之类的差异 */
    private fun normalizeTitle(value: String): String {
        val sb = StringBuilder()
        for (ch in value.lowercase()) if (!ch.isWhitespace() && ch !in IGNORED_TITLE_CHARS) sb.append(ch)
        return sb.toString()
    }

    private val IGNORED_TITLE_CHARS = setOf(
        '(', ')', '[', ']', '（', '）', '【', '】', '《', '》',
        ',', '，', '.', '。', '!', '！', '?', '？', '\'', '"', '-', '—', '、'
    )

    /**
     * 歌词：取 lrc.lyric，解析成带时间轴的 LyricLine(startMs, text)。
     * 时间轴（startMs）必须保留——手环端用它按播放进度计算 activeLyricIndex 来滚动/高亮，
     * 但手环只渲染 text、不显示任何时间（见 detail.ux / app.ux）。与 SystemMediaMonitor.parseLyrics 格式一致。
     */
    suspend fun getLyrics(id: Long): List<LyricLine> = withContext(Dispatchers.IO) {
        try {
            // 注意：必须带 lv=1&kv=1&tv=-1，否则网易云返回的 JSON 里根本没有 lrc 字段（仅剩 sgc/sfy/qfy/code），歌词永远为空。
            val root = JSONObject(get("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1"))
            val lrc = root.optJSONObject("lrc")?.optString("lyric", "") ?: ""
            if (lrc.isBlank()) emptyList() else parseLrc(lrc)
        } catch (e: Throwable) {
            Log.w(TAG, "lyric failed id=$id: ${e.message}")
            emptyList()
        }
    }

    /** 封面：下载为 Bitmap（已按 640x640 缩放参数请求） */
    suspend fun getCoverBitmap(picUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        if (picUrl.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val url = if (picUrl.startsWith("http://")) "https://${picUrl.substring(7)}" else picUrl
            conn = (URL("$url?param=640y640").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "cover failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 播放地址：enhance 接口优先（直接 mp3 地址），失败兜底 outer 外链 */
    suspend fun getPlayUrl(id: Long): String? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(get("https://music.163.com/api/song/enhance/player/url?id=$id&br=320000"))
            val url = root.optJSONArray("data")?.optJSONObject(0)?.optString("url", "") ?: ""
            if (url.isNotBlank()) return@withContext url
        } catch (e: Throwable) {
            Log.w(TAG, "enhance url failed id=$id: ${e.message}")
        }
        "https://music.163.com/song/media/outer/url?id=$id.mp3"
    }

    /**
     * 封面地址：搜索接口（web / cloudsearch）返回的 album 已不再携带 picUrl，
     * 必须单独走 /api/song/detail 才能拿到 album.picUrl。返回 null 表示取不到。
     */
    suspend fun getAlbumPicUrl(id: Long): String? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        try {
            val url = "https://music.163.com/api/song/detail/?id=$id&ids=%5B$id%5D"
            val root = JSONObject(get(url))
            val songs = root.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val album = s.optJSONObject("album")
                val pu = album?.optString("picUrl", "")?.takeIf { it.isNotBlank() }
                    ?: s.optString("picUrl", "").takeIf { it.isNotBlank() }
                if (pu != null) return@withContext pu
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "album pic failed id=$id: ${e.message}")
            null
        }
    }

    /**
     * 解析 LRC 文本为带时间轴的歌词行。与 SystemMediaMonitor.parseLyrics 的解析规则保持一致：
     * 识别 [mm:ss.xx] 时间标签并转换为毫秒；同一行多个时间标签会展开为多行；无时间标签的行降级为 startMs=null。
     */
    private fun parseLrc(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val timed = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]([^\n\r]*)""")
        val parsed = timed.findAll(lrc).mapNotNull { match ->
            val text = match.groupValues[4].trim()
            if (text.isBlank()) null else {
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100)
                    2 -> fraction.toLongOrNull()?.times(10)
                    else -> fraction.toLongOrNull()
                } ?: 0L
                LyricLine(
                    match.groupValues[1].toLongOrNull()?.times(60_000)?.plus(
                        match.groupValues[2].toLongOrNull()?.times(1000)?.plus(millis) ?: millis
                    ),
                    text
                )
            }
        }.sortedBy { it.startMs }.toList()
        if (parsed.isNotEmpty()) return parsed
        return lrc.lines().map(String::trim).filter(String::isNotBlank)
            .distinct().map { LyricLine(null, it) }
    }

    private fun buildArtist(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            if (sb.isNotEmpty()) sb.append("/")
            sb.append(a.optString("name", ""))
        }
        return sb.toString()
    }

    private fun post(url: String, body: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return read(conn)
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
                connectTimeout = 8000
                readTimeout = 8000
            }
            return read(conn)
        } finally {
            conn?.disconnect()
        }
    }

    private fun read(conn: HttpURLConnection): String {
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.use { s ->
            BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { r ->
                val sb = StringBuilder()
                var line = r.readLine()
                while (line != null) {
                    sb.append(line)
                    line = r.readLine()
                }
                return sb.toString()
            }
        }
        return ""
    }
}
