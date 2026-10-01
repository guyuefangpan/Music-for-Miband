package com.vt.starry.qqmusic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
 * QQ 音乐 HTTP 接口封装（无第三方依赖，使用 HttpURLConnection）。
 *
 * 用途：网易云因版权缺失拿不到歌词/封面时（如周杰伦），用 QQ 音乐作兜底音源。
 *
 * 关键：本类只用三个免签名、免登录的接口，因此接入 QQ 兜底**不需要用户填 Cookie**：
 *   1) 点歌联想 smartbox_new.fcg         -> songmid
 *   2) 单曲详情 fcg_play_single_song.fcg -> albummid（封面需要）
 *   3) 歌词     fcg_query_lyric_new.fcg  -> base64 LRC（需带 Referer，返回 code=0 直接给 base64）
 *   封面       y.gtimg.cn/.../T002R300x300M000{albummid}.jpg -> 直接下载
 * 真正需要登录态的是 musics.fcg 搜索；这里不依赖它，故无需任何登录信息。
 */
object QmApi {
    private const val TAG = "QmApi"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val REFERER = "https://y.qq.com/"

    data class Song(
        val songmid: String,
        val name: String,
        val artist: String,
        val albummid: String,
    )

    /** 点歌联想：关键词 -> 单曲列表（最多 limit 首）。免签名、免登录。 */
    suspend fun suggest(keyword: String, limit: Int = 5): List<Song> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        try {
            val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?is_xml=0&key=" +
                URLEncoder.encode(keyword, "UTF-8") +
                "&g_tk=5381&format=json&inCharset=utf-8&outCharset=utf-8"
            val root = JSONObject(get(url))
            val songs = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist") ?: JSONArray()
            val list = ArrayList<Song>()
            val n = minOf(songs.length(), limit)
            for (i in 0 until n) {
                val s = songs.optJSONObject(i) ?: continue
                val mid = s.optString("mid", "")
                if (mid.isBlank()) continue
                list.add(
                    Song(
                        songmid = mid,
                        name = s.optString("name", ""),
                        artist = s.optString("singer", ""),
                        albummid = "",
                    )
                )
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "suggest failed: ${e.message}")
            emptyList()
        }
    }

    /** 单曲详情：取 album.mid（封面需要）。免签名。 */
    suspend fun resolveAlbumMid(songmid: String): String? = withContext(Dispatchers.IO) {
        if (songmid.isBlank()) return@withContext null
        try {
            val url = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=$songmid" +
                "&platform=yqq&format=json&outCharset=utf-8&hostUin=0&g_tk=5381"
            val root = JSONObject(get(url))
            val data = root.opt("data")
            val song = when (data) {
                is JSONObject -> data
                is JSONArray -> data.optJSONObject(0)
                else -> null
            }
            song?.optJSONObject("album")?.optString("mid", "")?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            Log.w(TAG, "resolveAlbumMid failed mid=$songmid: ${e.message}")
            null
        }
    }

    /** 歌词：fcg_query_lyric_new.fcg 返回 base64 LRC，解码后解析成带时间轴的 LyricLine。需带 Referer。 */
    suspend fun getLyrics(songmid: String): List<LyricLine> = withContext(Dispatchers.IO) {
        if (songmid.isBlank()) return@withContext emptyList()
        try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&format=json"
            val root = JSONObject(get(url))
            val b64 = root.optString("lyric", "")
            if (b64.isBlank()) return@withContext emptyList()
            val lrc = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            if (lrc.isBlank()) emptyList() else parseLrc(lrc)
        } catch (e: Throwable) {
            Log.w(TAG, "lyric failed mid=$songmid: ${e.message}")
            emptyList()
        }
    }

    /** 封面：按 300x300 下载为 Bitmap。 */
    suspend fun getCoverBitmap(albummid: String): Bitmap? = withContext(Dispatchers.IO) {
        if (albummid.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val url = "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albummid.jpg"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "cover failed albummid=$albummid: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // 解析 LRC 与 NetEaseApi 规则一致：识别 [mm:ss.xx] 时间标签并转毫秒；多标签展开；无标签降级 startMs=null。
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
