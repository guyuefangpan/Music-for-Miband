package com.vt.starry.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.DataSubscribeResult
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.Adler32

enum class WearState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

class WearBridge private constructor(private val context: Context) {
    companion object {
        private const val WATCH_ENTRY = "/pages/index"
        // Shell++ uses 4096-byte interconnect messages successfully. Keeping realtime
        // snapshots in one message avoids reassembly latency on the watch.
        private const val MAX_PACKET_BYTES = 4096
        private const val ARTWORK_PART_SIZE = 40_000
        private const val MAX_ARTWORK_CHARS = ARTWORK_PART_SIZE * 3
        private const val PNG_DATA_URI_PREFIX = "data:image/png;base64,"
        private const val HEARTBEAT_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val MAX_LAUNCH_ATTEMPTS = 10
        private const val LAUNCH_RETRY_MS = 1_000L
        private const val LYRICS_BATCH_LINES = 8
        private const val LYRICS_ACK_TIMEOUT_MS = 3_000L
        private const val MAX_LYRICS_SEND_ATTEMPTS = 3
        private const val ARTWORK_ACK_TIMEOUT_MS = 4_000L
        private const val ARTWORK_PROBE_TIMEOUT_MS = 2_000L
        private const val MAX_ARTWORK_PROBE_ATTEMPTS = 2
        private const val MAX_ARTWORK_SEND_ATTEMPTS = 2
        private const val MEDIA_PROTOCOL_VERSION = 2
        private const val SOFT_RESTART_DELAY_MS = 750L
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val RECONNECT_CYCLE_MS = 5_000L
        @Volatile private var instance: WearBridge? = null
        fun get(context: Context): WearBridge = instance ?: synchronized(this) {
            instance ?: WearBridge(context.applicationContext).also { instance = it }
        }
    }

    private val _state = MutableStateFlow(WearState.DISCONNECTED)
    val state: StateFlow<WearState> = _state.asStateFlow()
    var onControl: ((String, Long?) -> Unit)? = null
    var onMusicRequest: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var authApi: AuthApi? = null
    private var node: Node? = null
    private var listenerInstalled = false
    private var listenerNodeId: String? = null
    private var connectionSubscribed = false
    private var connectionNodeId: String? = null
    private var handshaked = false
    private var deviceInfoReceived = false
    private var appLaunchedForSession = false
    private var sessionId = ""
    private var handshakeSent = false
    private var lastHeartbeatAck = 0L
    private val incomingChunks = HashMap<String, Array<String?>>()
    private val outgoing = ArrayDeque<OutboundMessage>()
    private var sendInFlight = false
    private var lastArtworkKey = ""
    private var lastLyricsKey = ""
    private var pendingLyricsAckId = ""
    private var pendingLyrics: List<LyricLine> = emptyList()
    private var lyricsSendAttempt = 0
    private var pendingArtworkAckId = ""
    private var pendingArtwork = ""
    private var pendingArtworkMediaKey = ""
    private var pendingArtworkTransferId = ""
    private var pendingArtworkProbeId = ""
    private var artworkProbeAttempt = 0
    private var artworkSendAttempt = 0
    private var artworkLocallySent = false
    private var artworkWatchConfirmed = false
    private var blockedTransferMediaKey = ""
    private var realtimeSequence = 0L
    private var lastLatencyLogAt = 0L
    private var discoveryAttempt = 0
    private var launchAttempt = 0
    private val launchRetry = object : Runnable {
        override fun run() = launchQuickAppUntilReady()
    }
    private val reconnect = object : Runnable {
        override fun run() {
            if (deviceInfoReceived) return
            beginReconnectSession()
        }
    }
    private val softRestart = object : Runnable {
        override fun run() = start()
    }
    private val lyricsAckTimeout = object : Runnable {
        override fun run() {
            if (!deviceInfoReceived || pendingLyricsAckId.isBlank()) return
            if (lyricsSendAttempt >= MAX_LYRICS_SEND_ATTEMPTS) {
                CommunicationLog.warn("TX", "完整歌词连续多次未收到手环确认 id=" + pendingLyricsAckId)
                return
            }
            CommunicationLog.warn("TX", "完整歌词未确认，正在重发 id=" + pendingLyricsAckId)
            sendLyrics(pendingLyricsAckId, pendingLyrics, true)
        }
    }
    private val artworkAckTimeout = object : Runnable {
        override fun run() {
            if (!deviceInfoReceived || pendingArtworkAckId.isBlank() || pendingArtwork.isBlank()) return
            retryArtworkOrAbort("手环确认超时")
        }
    }
    private val artworkProbeTimeout = object : Runnable {
        override fun run() {
            if (!deviceInfoReceived || pendingArtworkAckId.isBlank() || pendingArtwork.isBlank()) return
            if (artworkProbeAttempt < MAX_ARTWORK_PROBE_ATTEMPTS) {
                CommunicationLog.warn("TX", "封面预检未响应，正在重试 id=$pendingArtworkAckId")
                probeArtworkOnWatch()
            } else {
                CommunicationLog.error("TX", "封面预检连续未响应，已停止上传 id=$pendingArtworkAckId")
                pendingArtworkProbeId = ""
            }
        }
    }

    private data class OutboundMessage(
        val type: String,
        val packets: List<ByteArray>,
        val onSent: (() -> Unit)? = null
    )

    private val listener = OnMessageReceivedListener { nodeId, bytes ->
        if (node?.id != nodeId) return@OnMessageReceivedListener
        runCatching { handleIncoming(String(bytes, Charsets.UTF_8)) }
            .onFailure { CommunicationLog.error("RX", "消息解析失败", it) }
    }

    fun start() {
        if (_state.value == WearState.CONNECTING || _state.value == WearState.CONNECTED) return
        appLaunchedForSession = false
        deviceInfoReceived = false
        handshaked = false
        sessionId = UUID.randomUUID().toString()
        handshakeSent = false
        launchAttempt = 0
        handler.removeCallbacks(reconnect)
        if (nodeApi == null) {
            nodeApi = Wearable.getNodeApi(context)
            messageApi = Wearable.getMessageApi(context)
            authApi = Wearable.getAuthApi(context)
        }
        if (node != null && listenerInstalled && listenerNodeId == node?.id) {
            updateState(WearState.CONNECTING, "正在重新连接 Quick App")
            launchQuickAppUntilReady()
            return
        }
        updateState(WearState.CONNECTING, "正在查找手环")
        val attempt = ++discoveryAttempt
        handler.postDelayed({
            if (attempt == discoveryAttempt && _state.value == WearState.CONNECTING) {
                updateState(WearState.DISCONNECTED, "未发现小米运动健康互联节点")
                scheduleReconnect(RECONNECT_CYCLE_MS)
            }
        }, DISCOVERY_TIMEOUT_MS)
        nodeApi!!.connectedNodes.addOnSuccessListener { nodes ->
            if (attempt != discoveryAttempt) return@addOnSuccessListener
            nodes.firstOrNull()?.let(::attach) ?: run {
                updateState(WearState.DISCONNECTED, "未发现互联互通节点")
                scheduleReconnect(RECONNECT_CYCLE_MS)
            }
        }.addOnFailureListener {
            if (attempt == discoveryAttempt) {
                updateState(WearState.ERROR, "获取手环节点失败: ${it.message.orEmpty()}")
                scheduleReconnect(RECONNECT_CYCLE_MS)
            }
        }
    }

    private fun attach(found: Node) {
        if (node?.id == found.id && listenerInstalled && listenerNodeId == found.id) {
            node = found
            subscribeConnection(found)
            launchQuickAppUntilReady()
            return
        }
        val oldListenerNodeId = listenerNodeId
        if (oldListenerNodeId != null && oldListenerNodeId != found.id && listenerInstalled) {
            messageApi?.removeListener(oldListenerNodeId)
                ?.addOnSuccessListener {
                    listenerInstalled = false
                    listenerNodeId = null
                    connectionSubscribed = false
                    connectionNodeId = null
                    attach(found)
                }
                ?.addOnFailureListener {
                    listenerInstalled = false
                    listenerNodeId = null
                    connectionSubscribed = false
                    connectionNodeId = null
                    attach(found)
                }
            return
        }
        node = found
        ensureDeviceManagerPermission(found)
    }

    private fun ensureDeviceManagerPermission(found: Node) {
        val auth = authApi ?: Wearable.getAuthApi(context).also { authApi = it }
        updateState(WearState.CONNECTING, "正在检查小米运动健康互联权限")
        auth.checkPermission(found.id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                if (granted) {
                    CommunicationLog.info("AUTH", "DEVICE_MANAGER 权限已授予")
                    installMessageListener(found)
                } else {
                    CommunicationLog.warn("AUTH", "DEVICE_MANAGER 权限未授予，正在请求")
                    requestDeviceManagerPermission(found)
                }
            }
            .addOnFailureListener { error ->
                CommunicationLog.warn("AUTH", "检查 DEVICE_MANAGER 权限失败，尝试请求: ${error.message.orEmpty()}")
                requestDeviceManagerPermission(found)
            }
    }

    private fun requestDeviceManagerPermission(found: Node) {
        val auth = authApi ?: return
        updateState(WearState.CONNECTING, "请在小米运动健康中允许互联权限")
        auth.requestPermission(found.id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                val allowed = granted.any { it.name == Permission.DEVICE_MANAGER.name }
                if (allowed) {
                    CommunicationLog.info("AUTH", "DEVICE_MANAGER 权限授权成功")
                    installMessageListener(found)
                } else {
                    CommunicationLog.error("AUTH", "DEVICE_MANAGER 权限未被用户授予")
                    updateState(WearState.ERROR, "小米运动健康未授予互联权限")
                }
            }
            .addOnFailureListener { error ->
                CommunicationLog.error("AUTH", "请求 DEVICE_MANAGER 权限失败: ${error.message.orEmpty()}", error)
                updateState(WearState.ERROR, "互联权限请求失败: ${error.message.orEmpty()}")
            }
    }

    private fun installMessageListener(found: Node) {
        val install = {
            messageApi!!.addListener(found.id, listener).addOnSuccessListener {
                listenerInstalled = true
                listenerNodeId = found.id
                CommunicationLog.info("CONN", "消息通道已就绪")
                subscribeConnection(found)
                launchQuickAppUntilReady()
            }.addOnFailureListener { error ->
                listenerInstalled = false
                listenerNodeId = null
                if (error.message.orEmpty().contains("registered", ignoreCase = true)) {
                    CommunicationLog.warn("CONN", "旧消息监听仍存在，稍后重新清理并注册")
                } else {
                    CommunicationLog.error("CONN", "注册消息监听失败: ${error.message.orEmpty()}", error)
                }
                updateState(WearState.DISCONNECTED, "消息监听未就绪，正在重试")
                scheduleReconnect(RECONNECT_DELAY_MS)
            }
        }
        // This is the ordering used by the last known-good implementation. The
        // SDK registration survives reconnect attempts, so always clear the
        // node registration before installing this process' listener.
        messageApi!!.removeListener(found.id)
            .addOnSuccessListener { install() }
            .addOnFailureListener { install() }
    }

    private fun subscribeConnection(found: Node) {
        if (connectionSubscribed && connectionNodeId == found.id) return
        if (connectionNodeId != null && connectionNodeId != found.id) {
            runCatching { nodeApi?.unsubscribe(connectionNodeId!!, DataItem.ITEM_CONNECTION) }
            connectionSubscribed = false
            connectionNodeId = null
        }
        nodeApi?.subscribe(found.id, DataItem.ITEM_CONNECTION) { _, item, data ->
            if (item.type == DataItem.ITEM_CONNECTION.type) {
                if (data.connectedStatus == DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                    if (!deviceInfoReceived) sendHandshake()
                } else {
                    markQuickAppDisconnected("手环已断开")
                }
            }
        }?.addOnSuccessListener {
            connectionSubscribed = true
            connectionNodeId = found.id
        }?.addOnFailureListener { error ->
            if (error.message.orEmpty().contains("registered", ignoreCase = true)) {
                connectionSubscribed = true
                connectionNodeId = found.id
                CommunicationLog.warn("CONN", "连接状态监听已注册，继续使用")
            } else {
                CommunicationLog.warn("CONN", "连接状态监听失败: ${error.message.orEmpty()}")
            }
        }
    }

    private fun sendHandshake() {
        if (!listenerInstalled || deviceInfoReceived) return
        handshakeSent = true
        // A handshake must not wait behind a stale artwork/lyrics transfer from
        // the previous session. Put it at the head of the transport queue.
        sendJson(JSONObject().put("type", "__hs__").put("count", 0).put("version", 1).put("session", sessionId), false, true)
    }

    private fun launchQuickAppUntilReady() {
        if (deviceInfoReceived || launchAttempt >= MAX_LAUNCH_ATTEMPTS) {
            if (!deviceInfoReceived && launchAttempt >= MAX_LAUNCH_ATTEMPTS) {
                updateState(WearState.DISCONNECTED, "Quick App 连续 10 次未响应，5 秒后重新连接")
                scheduleReconnect(RECONNECT_CYCLE_MS)
            }
            return
        }
        val target = node ?: return
        appLaunchedForSession = true
        launchAttempt++
        CommunicationLog.info("CONN", "拉起 Quick App $launchAttempt/$MAX_LAUNCH_ATTEMPTS")
        nodeApi?.launchWearApp(target.id, WATCH_ENTRY)
            ?.addOnSuccessListener { sendHandshake() }
            ?.addOnFailureListener {
                CommunicationLog.warn("CONN", "Quick App 拉起失败 ${launchAttempt}/$MAX_LAUNCH_ATTEMPTS: ${it.message.orEmpty()}")
                sendHandshake()
            }
        // launchWearApp may complete asynchronously or report success before the
        // Quick App message endpoint is ready; send the handshake immediately too.
        sendHandshake()
        handler.removeCallbacks(launchRetry)
        handler.postDelayed(launchRetry, LAUNCH_RETRY_MS)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (handshaked && now - lastHeartbeatAck > HEARTBEAT_TIMEOUT_MS) {
                markQuickAppDisconnected("Quick App 心跳超时")
                return
            }
            if (listenerInstalled && deviceInfoReceived) {
                sendJson(JSONObject().put("type", "heartbeat").put("timestamp", now), false)
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    fun sendMusicState(media: SystemMediaState) {
        if (!deviceInfoReceived) return
        if (media.title.isBlank()) {
            sendJson(JSONObject().put("type", "music_clear"), false)
            return
        }
        val mediaKey = listOf(media.packageName, media.title, media.artist, media.album).joinToString("|")
        if (blockedTransferMediaKey.isNotBlank() && blockedTransferMediaKey != mediaKey) {
            blockedTransferMediaKey = ""
        }
        val pngArtwork = normalizeArtworkToPng(media.albumArt)
        val artworkId = if (pngArtwork.isBlank()) "" else Integer.toHexString(pngArtwork.hashCode())
        val lyricsId = if (media.lyrics.isEmpty()) "" else Integer.toHexString(media.lyrics.hashCode())
        sendJson(JSONObject().apply {
            put("type", "music_info")
            put("title", media.title); put("artist", media.artist); put("album", media.album)
            put("mediaKey", mediaKey)
            put("artworkId", artworkId); put("lyricsId", lyricsId); put("state", if (media.playbackState == "playing") 1 else 0)
            put("playbackState", media.playbackState); put("positionMs", media.positionMs); put("durationMs", media.durationMs)
            put("volume", media.volume); put("muted", media.muted)
            put("activeLyricIndex", media.activeLyricIndex); put("sourcePackage", media.packageName)
            put("sourceName", media.sourceName); put("updatedAt", System.currentTimeMillis())
        }, false)
        if (lyricsId != lastLyricsKey) {
            lastLyricsKey = lyricsId
            pendingLyricsAckId = lyricsId
            pendingLyrics = media.lyrics.toList()
            lyricsSendAttempt = 0
            sendLyrics(lyricsId, pendingLyrics, true)
        }
        val artworkKey = if (artworkId.isBlank()) "" else "$mediaKey|$artworkId"
        if (artworkKey != lastArtworkKey) {
            lastArtworkKey = artworkKey
            handler.removeCallbacks(artworkAckTimeout)
            pendingArtworkAckId = artworkId
            pendingArtwork = pngArtwork
            pendingArtworkMediaKey = mediaKey
            artworkSendAttempt = 0
            artworkProbeAttempt = 0
            pendingArtworkProbeId = ""
            artworkLocallySent = false
            artworkWatchConfirmed = false
            if (artworkId.isNotBlank() && pngArtwork.isNotBlank()) {
                probeArtworkOnWatch()
            }
        }
    }

    private fun probeArtworkOnWatch() {
        val id = pendingArtworkAckId
        val artwork = pendingArtwork
        if (!deviceInfoReceived || id.isBlank() || artwork.isBlank()) return
        val bytes = runCatching { Base64.decode(artwork.substringAfter(',', artwork), Base64.DEFAULT) }.getOrNull()
        if (bytes == null || !isCompletePng(bytes)) {
            retryArtworkOrAbort("封面预检前 PNG 解码失败")
            return
        }
        val probeId = UUID.randomUUID().toString()
        pendingArtworkProbeId = probeId
        artworkProbeAttempt++
        val checksum = Adler32().apply { update(bytes) }.value
        handler.removeCallbacks(artworkProbeTimeout)
        sendJson(JSONObject().put("type", "artwork_probe")
            .put("requestId", probeId).put("id", id).put("format", "png")
            .put("bytes", bytes.size).put("sum", checksum), false, true)
        handler.postDelayed(artworkProbeTimeout, ARTWORK_PROBE_TIMEOUT_MS)
        CommunicationLog.info("TX", "上传前校验手环封面 id=$id attempt=$artworkProbeAttempt/$MAX_ARTWORK_PROBE_ATTEMPTS")
    }

    private fun normalizeArtworkToPng(artwork: String): String {
        if (artwork.isBlank()) return ""
        val encoded = artwork.substringAfter(',', "")
        if (encoded.isBlank()) {
            CommunicationLog.warn("MEDIA", "封面缺少 Base64 数据，已阻止发送")
            return ""
        }
        val source = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrElse {
            CommunicationLog.warn("MEDIA", "封面 Base64 解码失败，已阻止发送")
            return ""
        }
        if (isCompletePng(source)) {
            val canonical = PNG_DATA_URI_PREFIX + Base64.encodeToString(source, Base64.NO_WRAP)
            if (canonical.length <= MAX_ARTWORK_CHARS) return canonical
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size) ?: run {
            CommunicationLog.warn("MEDIA", "封面无法转换为 PNG，已阻止发送")
            return ""
        }
        return try {
            val edges = listOf(384, 352, 320, 288, 256, 224, 192, 160)
            for (edge in edges) {
                val scale = minOf(1f, edge.toFloat() / maxOf(bitmap.width, bitmap.height))
                val width = maxOf(1, (bitmap.width * scale).toInt())
                val height = maxOf(1, (bitmap.height * scale).toInt())
                val scaled = if (width == bitmap.width && height == bitmap.height) bitmap
                    else Bitmap.createScaledBitmap(bitmap, width, height, true)
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
                if (scaled !== bitmap) scaled.recycle()
                val bytes = output.toByteArray()
                val value = PNG_DATA_URI_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (isCompletePng(bytes) && value.length <= MAX_ARTWORK_CHARS) {
                    CommunicationLog.info("MEDIA", "封面已在 Android 发送前转换为 PNG ${width}x$height bytes=${bytes.size}")
                    return value
                }
            }
            CommunicationLog.warn("MEDIA", "PNG 封面转换后仍超出传输上限，已阻止发送")
            ""
        } finally {
            bitmap.recycle()
        }
    }

    private fun isCompletePng(bytes: ByteArray): Boolean =
        bytes.size >= 16 &&
            bytes[0].toInt() and 0xff == 137 && bytes[1].toInt() == 80 &&
            bytes[2].toInt() == 78 && bytes[3].toInt() == 71 &&
            bytes[4].toInt() == 13 && bytes[5].toInt() == 10 &&
            bytes[6].toInt() == 26 && bytes[7].toInt() == 10 &&
            bytes[bytes.size - 8].toInt() == 73 && bytes[bytes.size - 7].toInt() == 69 &&
            bytes[bytes.size - 6].toInt() == 78 && bytes[bytes.size - 5].toInt() == 68 &&
            bytes[bytes.size - 4].toInt() and 0xff == 174 && bytes[bytes.size - 3].toInt() == 66 &&
            bytes[bytes.size - 2].toInt() == 96 && bytes[bytes.size - 1].toInt() and 0xff == 130

    fun forceSyncArtwork(media: SystemMediaState): Boolean {
        if (!deviceInfoReceived) {
            CommunicationLog.warn("DEBUG", "手动同步封面失败：Quick App 未连接")
            return false
        }
        if (media.title.isBlank() || media.albumArt.isBlank()) {
            CommunicationLog.warn("DEBUG", "手动同步封面失败：当前媒体没有封面")
            return false
        }
        blockedTransferMediaKey = ""
        lastArtworkKey = ""
        CommunicationLog.info("DEBUG", "手动同步封面：${media.title}")
        sendMusicState(media)
        return true
    }

    fun forceSyncLyrics(media: SystemMediaState): Boolean {
        if (!deviceInfoReceived) {
            CommunicationLog.warn("DEBUG", "手动同步歌词失败：Quick App 未连接")
            return false
        }
        if (media.title.isBlank() || media.lyrics.isEmpty()) {
            CommunicationLog.warn("DEBUG", "手动同步歌词失败：当前媒体没有歌词")
            return false
        }
        blockedTransferMediaKey = ""
        lastLyricsKey = ""
        CommunicationLog.info("DEBUG", "手动同步完整歌词：${media.title} lines=${media.lyrics.size}")
        sendMusicState(media)
        return true
    }

    private fun sendLyrics(lyricsId: String, lyrics: List<LyricLine>, trackAck: Boolean) {
        if (trackAck) {
            lyricsSendAttempt++
            handler.removeCallbacks(lyricsAckTimeout)
            handler.postDelayed(lyricsAckTimeout, LYRICS_ACK_TIMEOUT_MS)
        }
        val batches = lyrics.chunked(LYRICS_BATCH_LINES)
        if (batches.isEmpty()) {
            sendJson(JSONObject().put("type", "lyrics_batch").put("id", lyricsId).put("i", 0).put("t", 1).put("lines", JSONArray()), false)
            return
        }
        batches.forEachIndexed { index, batch ->
            val lines = JSONArray().apply { batch.forEach { line -> put(JSONObject().apply {
                line.startMs?.let { put("startMs", it) }
                put("text", line.text.take(500))
            }) } }
            sendJson(JSONObject().put("type", "lyrics_batch").put("id", lyricsId).put("i", index).put("t", batches.size).put("lines", lines), false)
        }
    }

    fun sendProgressState(media: SystemMediaState, urgent: Boolean = false) {
        if (!deviceInfoReceived || media.title.isBlank()) return
        val sequence = ++realtimeSequence
        val sentAt = System.currentTimeMillis()
        sendJson(JSONObject().apply {
            put("type", "media_progress")
            put("sequence", sequence)
            put("sentAt", sentAt)
            put("positionMs", media.positionMs)
            put("durationMs", media.durationMs)
            put("playbackState", media.playbackState)
            put("activeLyricIndex", media.activeLyricIndex)
            // Full lyrics are transferred once per song. Realtime packets only select
            // the line already cached by the watch.
            put("volume", media.volume)
            put("muted", media.muted)
            put("updatedAt", System.currentTimeMillis())
        }, false, urgent)
    }

    private fun sendAlbumArt(artworkId: String, artwork: String, trackAck: Boolean) {
        val value = artwork.takeIf { it.length <= MAX_ARTWORK_CHARS }.orEmpty()
        if (artworkId.isBlank() || value.isEmpty()) return
        if (!value.startsWith(PNG_DATA_URI_PREFIX)) {
            retryArtworkOrAbort("封面不是 PNG")
            return
        }
        if (trackAck) artworkSendAttempt++
        artworkLocallySent = false
        artworkWatchConfirmed = false
        handler.removeCallbacks(artworkAckTimeout)
        handler.removeCallbacks(artworkProbeTimeout)
        pendingArtworkProbeId = ""
        val transferId = UUID.randomUUID().toString()
        pendingArtworkTransferId = transferId
        val encoded = value.substringAfter(',', value)
        val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrElse {
            retryArtworkOrAbort("Android 封面解码失败")
            return
        }
        val checksum = Adler32().apply { update(decoded) }.value
        val parts = value.chunked(ARTWORK_PART_SIZE)
        if (parts.size > 3) return
        val packets = parts.flatMapIndexed { index, part ->
            packetsFor(JSONObject().put("type", "album_art").put("id", artworkId)
                .put("tx", transferId).put("i", index).put("t", parts.size)
                .put("format", "png")
                .put("chars", value.length).put("bytes", decoded.size).put("sum", checksum)
                .put("d", part).toString())
        }
        synchronized(outgoing) {
            outgoing.removeAll { it.type == "album_art" }
            outgoing.addLast(OutboundMessage("album_art", packets) {
                if (pendingArtworkAckId == artworkId && pendingArtworkTransferId == transferId && pendingArtwork.isNotBlank()) {
                    artworkLocallySent = true
                    CommunicationLog.info("TX", "专辑图已由 Android 完整发出，等待手环确认 id=$artworkId attempt=$artworkSendAttempt/$MAX_ARTWORK_SEND_ATTEMPTS")
                    completeArtworkIfConfirmed()
                    if (pendingArtworkAckId.isNotBlank()) {
                        handler.removeCallbacks(artworkAckTimeout)
                        handler.postDelayed(artworkAckTimeout, ARTWORK_ACK_TIMEOUT_MS)
                    }
                }
            })
        }
        drainOutgoing()
    }

    private fun retryArtworkOrAbort(reason: String) {
        handler.removeCallbacks(artworkAckTimeout)
        handler.removeCallbacks(artworkProbeTimeout)
        if (pendingArtworkAckId.isBlank() || pendingArtwork.isBlank()) return
        if (artworkSendAttempt < MAX_ARTWORK_SEND_ATTEMPTS) {
            CommunicationLog.warn("TX", "$reason，完整重试专辑图 id=$pendingArtworkAckId")
            sendAlbumArt(pendingArtworkAckId, pendingArtwork, true)
            return
        }
        val failedId = pendingArtworkAckId
        blockedTransferMediaKey = pendingArtworkMediaKey
        synchronized(outgoing) { outgoing.removeAll { it.type == "album_art" } }
        pendingArtworkAckId = ""
        pendingArtwork = ""
        pendingArtworkMediaKey = ""
        pendingArtworkTransferId = ""
        pendingArtworkProbeId = ""
        artworkProbeAttempt = 0
        artworkSendAttempt = 0
        artworkLocallySent = false
        artworkWatchConfirmed = false
        CommunicationLog.error("TX", "$reason，重试仍失败；保留手环现有封面，停止当前歌曲封面传输 id=$failedId")
    }

    private fun completeArtworkIfConfirmed() {
        if (!artworkLocallySent || !artworkWatchConfirmed || pendingArtworkAckId.isBlank()) return
        val completedId = pendingArtworkAckId
        handler.removeCallbacks(artworkAckTimeout)
        handler.removeCallbacks(artworkProbeTimeout)
        pendingArtworkAckId = ""
        pendingArtwork = ""
        pendingArtworkMediaKey = ""
        pendingArtworkTransferId = ""
        pendingArtworkProbeId = ""
        artworkProbeAttempt = 0
        artworkSendAttempt = 0
        artworkLocallySent = false
        artworkWatchConfirmed = false
        CommunicationLog.info("RX", "专辑图双端确认成功 id=$completedId")
    }

    private fun sendJson(json: JSONObject, logTraffic: Boolean = true, urgent: Boolean = false) {
        if (node == null || messageApi == null) return
        val raw = json.toString()
        val type = json.optString("type")
        if (!deviceInfoReceived && type != "__hs__") {
            CommunicationLog.info("TX", "等待 Quick App device_info，跳过 $type")
            return
        }
        val packets = packetsFor(raw)
        synchronized(outgoing) {
            if (type == "music_info" || type == "music_clear") outgoing.removeAll { it.type == "music_info" || it.type == "music_clear" }
            else if (type == "heartbeat") outgoing.removeAll { it.type == "heartbeat" }
            else if (type == "media_progress") outgoing.removeAll { it.type == "media_progress" }
            else if (type == "lyrics_batch" && json.optInt("i") == 0) outgoing.removeAll { it.type == "lyrics_batch" }
            val item = OutboundMessage(type, packets)
            if (urgent) {
                // Handshake and playback-state transitions must not wait behind
                // bulk lyrics/artwork transfers.
                outgoing.addFirst(item)
            } else if (type == "media_progress") {
                    // Never starve full lyrics during ordinary position updates.
                    val lyricTail = outgoing.indexOfLast { it.type == "lyrics_batch" }
                    if (lyricTail >= 0) outgoing.add(lyricTail + 1, item) else outgoing.addFirst(item)
            } else outgoing.addLast(item)
        }
        drainOutgoing()
        if (logTraffic) CommunicationLog.info("TX", json.optString("type"))
    }

    private fun packetsFor(raw: String): List<ByteArray> {
        val rawBytes = raw.toByteArray(Charsets.UTF_8)
        if (rawBytes.size <= MAX_PACKET_BYTES) return listOf(rawBytes)
        val id = UUID.randomUUID().toString()
        val parts = chunkForPacketBudget(raw, id)
        return parts.mapIndexed { index, part ->
            chunkPacket(id, index, parts.size, part).also { packet ->
                check(packet.size <= MAX_PACKET_BYTES) {
                    "Chunk packet exceeds limit: ${packet.size} > $MAX_PACKET_BYTES"
                }
            }
        }
    }

    private fun chunkForPacketBudget(value: String, id: String): List<String> {
        if (value.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        var start = 0
        while (start < value.length) {
            var low = start + 1
            var high = value.length
            var best = start
            while (low <= high) {
                var end = (low + high) ushr 1
                if (end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
                if (end <= start) { low = start + 2; continue }
                val candidate = value.substring(start, end)
                // Placeholder indices are deliberately wider than any real artwork or lyrics transfer.
                if (chunkPacket(id, 9999, 9999, candidate).size <= MAX_PACKET_BYTES) {
                    best = end
                    low = end + 1
                } else {
                    high = end - 1
                }
            }
            check(best > start) { "Unable to fit a code point in an interconnect packet" }
            result.add(value.substring(start, best))
            start = best
        }
        return result
    }

    private fun chunkPacket(id: String, index: Int, total: Int, data: String): ByteArray =
        JSONObject().put("__c", true).put("id", id).put("i", index).put("t", total).put("d", data)
            .toString().toByteArray(Charsets.UTF_8)

    private fun drainOutgoing() {
        if (sendInFlight) return
        val message = synchronized(outgoing) { if (outgoing.isEmpty()) null else outgoing.removeFirst() } ?: return
        sendInFlight = true
        sendPacket(message, 0)
    }

    private fun sendPacket(message: OutboundMessage, index: Int) {
        val target = node
        val api = messageApi
        if (target == null || api == null) { finishSend(); return }
        val packet = message.packets[index]
        if (packet.size > MAX_PACKET_BYTES) {
            CommunicationLog.error("TX", message.type + " 数据包超限 bytes=" + packet.size)
            finishSend()
            return
        }
        api.sendMessage(target.id, packet)
            .addOnSuccessListener {
                if (index + 1 < message.packets.size && message.type == "album_art" && hasRealtimeWorkWaiting()) {
                    synchronized(outgoing) {
                        outgoing.removeAll { it.type == "album_art" }
                        outgoing.addLast(OutboundMessage("album_art", message.packets.subList(index + 1, message.packets.size), message.onSent))
                    }
                    finishSend()
                } else if (index + 1 < message.packets.size) sendPacket(message, index + 1)
                else {
                    message.onSent?.invoke()
                    finishSend()
                }
            }
            .addOnFailureListener {
                CommunicationLog.error("TX", message.type + " 发送失败 packet=" + (index + 1) + "/" + message.packets.size, it)
                if (message.type == "album_art") {
                    retryArtworkOrAbort("Android 物理发送失败")
                    finishSend()
                } else if (message.type == "__hs__") {
                    // A launch can succeed before the Quick App message endpoint is
                    // ready. Keep the registered listener and let launchRetry resend.
                    finishSend()
                } else {
                    markQuickAppDisconnected("Quick App 消息通道不可用")
                }
            }
    }

    private fun hasRealtimeWorkWaiting(): Boolean = synchronized(outgoing) {
        outgoing.any { it.type == "media_progress" || it.type == "lyrics_batch" || it.type == "music_info" }
    }

    private fun finishSend() {
        sendInFlight = false
        drainOutgoing()
    }

    private fun markQuickAppDisconnected(reason: String) {
        handshaked = false
        deviceInfoReceived = false
        lastHeartbeatAck = 0L
        handler.removeCallbacks(heartbeat)
        synchronized(outgoing) { outgoing.clear() }
        sendInFlight = false
        lastArtworkKey = ""
        lastLyricsKey = ""
        pendingLyricsAckId = ""
        pendingLyrics = emptyList()
        lyricsSendAttempt = 0
        handler.removeCallbacks(lyricsAckTimeout)
        pendingArtworkAckId = ""
        pendingArtwork = ""
        pendingArtworkMediaKey = ""
        pendingArtworkTransferId = ""
        pendingArtworkProbeId = ""
        artworkProbeAttempt = 0
        artworkSendAttempt = 0
        artworkLocallySent = false
        artworkWatchConfirmed = false
        handler.removeCallbacks(artworkAckTimeout)
        handler.removeCallbacks(artworkProbeTimeout)
        updateState(WearState.DISCONNECTED, reason)
        scheduleReconnect(RECONNECT_DELAY_MS)
    }

    private fun softRestartConnectionLayer(reason: String) {
        CommunicationLog.warn("CONN", "连接层软重启：$reason")
        discoveryAttempt++
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(launchRetry)
        handler.removeCallbacks(lyricsAckTimeout)
        handler.removeCallbacks(artworkAckTimeout)
        handler.removeCallbacks(artworkProbeTimeout)
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(softRestart)
        val oldNodeId = node?.id
        if (oldNodeId != null) {
            runCatching { messageApi?.removeListener(oldNodeId) }
            runCatching { nodeApi?.unsubscribe(oldNodeId, DataItem.ITEM_CONNECTION) }
        }
        synchronized(outgoing) { outgoing.clear() }
        incomingChunks.clear()
        sendInFlight = false
        node = null
        nodeApi = null
        messageApi = null
        listenerInstalled = false
        listenerNodeId = null
        connectionSubscribed = false
        connectionNodeId = null
        handshaked = false
        deviceInfoReceived = false
        appLaunchedForSession = false
        sessionId = ""
        handshakeSent = false
        lastHeartbeatAck = 0L
        launchAttempt = 0
        lastArtworkKey = ""
        lastLyricsKey = ""
        pendingLyricsAckId = ""
        pendingLyrics = emptyList()
        lyricsSendAttempt = 0
        pendingArtworkAckId = ""
        pendingArtwork = ""
        pendingArtworkMediaKey = ""
        pendingArtworkTransferId = ""
        pendingArtworkProbeId = ""
        artworkProbeAttempt = 0
        artworkSendAttempt = 0
        artworkLocallySent = false
        artworkWatchConfirmed = false
        blockedTransferMediaKey = ""
        updateState(WearState.DISCONNECTED, "连接层已重置，正在重新发现设备")
        handler.postDelayed(softRestart, SOFT_RESTART_DELAY_MS)
    }

    private fun scheduleReconnect(delayMs: Long) {
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, delayMs)
    }

    private fun beginReconnectSession() {
        if (deviceInfoReceived) return
        handshaked = false
        appLaunchedForSession = false
        sessionId = UUID.randomUUID().toString()
        handshakeSent = false
        launchAttempt = 0
        incomingChunks.clear()
        updateState(WearState.CONNECTING, "检测到断开，正在重新打开 Quick App")
        if (node != null && listenerInstalled) {
            launchQuickAppUntilReady()
        } else {
            updateState(WearState.DISCONNECTED, "互联节点不可用，正在重新发现")
            start()
        }
    }

    private fun handleIncoming(raw: String) {
        val json = JSONObject(raw)
        if (json.optBoolean("__c")) {
            val id = json.optString("id"); val total = json.optInt("t"); val index = json.optInt("i")
            if (total !in 1..512 || index !in 0 until total) return
            val parts = incomingChunks.getOrPut(id) { arrayOfNulls(total) }
            parts[index] = json.optString("d")
            if (parts.all { it != null }) { incomingChunks.remove(id); handleIncoming(parts.joinToString("")) }
            return
        }
        when (json.optString("type")) {
            "__hs__" -> {
                val count = json.optInt("count", -1)
                if (count >= 0 && json.optString("session") == sessionId) {
                    handshaked = true; lastHeartbeatAck = System.currentTimeMillis()
                    updateState(WearState.CONNECTING, "Quick App 已响应，等待设备信息")
                    if (count < 2) sendJson(JSONObject().put("type", "__hs__").put("count", count + 1).put("version", 1).put("session", sessionId), false)
                }
            }
            "heartbeat" -> { lastHeartbeatAck = System.currentTimeMillis(); sendJson(JSONObject().put("type", "heartbeatAck").put("timestamp", lastHeartbeatAck), false) }
            "heartbeatAck" -> {
                lastHeartbeatAck = System.currentTimeMillis()
            }
            "device_info" -> {
                if (!handshaked || json.optString("session") != sessionId) return
                val firstReady = !deviceInfoReceived
                deviceInfoReceived = true
                handler.removeCallbacks(launchRetry)
                handler.removeCallbacks(reconnect)
                val model = json.optString("model").ifBlank { json.optString("product") }
                if (firstReady) {
                    CommunicationLog.info("CONN", "收到 Quick App 设备信息，通信就绪${if (model.isBlank()) "" else ": $model"}")
                }
                // device_info is retried until this ACK arrives. Always ACK duplicate
                // packets so a lost first response cannot strand either side.
                sendJson(JSONObject().put("type", "device_info_ack").put("session", sessionId).put("version", 1), false)
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, HEARTBEAT_MS)
                updateState(WearState.CONNECTED, "Quick App 已连接")
                if (firstReady) onConnected?.invoke()
            }
            "app_ready" -> {
                if (!deviceInfoReceived) sendHandshake()
                else sendJson(JSONObject().put("type", "heartbeat").put("origin", "android_recover").put("timestamp", System.currentTimeMillis()), false)
            }
            "asset_ack" -> {
                val asset = json.optString("asset")
                val id = json.optString("id")
                val transferId = json.optString("tx")
                val ok = json.optBoolean("ok")
                val code = json.optString("code").lowercase()
                var artworkAckVerified = false
                if (!ok && asset == "artwork" && (code == "invalid_jpeg" || code == "invalid_jepg")) {
                    CommunicationLog.error("RX", "检测到旧版 JPEG 快应用协议 code=$code；当前 Android 仅发送 PNG，请更新 RPK")
                    softRestartConnectionLayer("检测到旧版封面协议 $code")
                    return
                }
                if (asset == "artwork" && id == pendingArtworkAckId && transferId == pendingArtworkTransferId) {
                    if (ok) {
                        val pendingBytes = runCatching {
                            Base64.decode(pendingArtwork.substringAfter(',', pendingArtwork), Base64.DEFAULT)
                        }.getOrNull()
                        val expectedSum = pendingBytes?.let { Adler32().apply { update(it) }.value }
                        val ackVerified = json.optString("verified") == "png_structure_v1"
                        val ackFormat = json.optString("format") == "png"
                        val ackUri = json.optString("uri")
                        val ackMatches = pendingBytes != null &&
                            json.optInt("bytes", -1) == pendingBytes.size &&
                            json.optLong("sum", -1L) == expectedSum &&
                            ackVerified && ackFormat && ackUri.endsWith(".png")
                        if (ackMatches) {
                            artworkAckVerified = true
                            artworkWatchConfirmed = true
                            completeArtworkIfConfirmed()
                        } else {
                            retryArtworkOrAbort("手环封面确认内容不匹配或未完成 PNG 发布验证")
                        }
                    } else retryArtworkOrAbort("手环写入确认失败 code=${json.optString("code")}")
                }
                if (ok && asset != "artwork") CommunicationLog.info("RX", "$asset 已写入手环 id=$id")
                else if (artworkAckVerified) CommunicationLog.info("RX", "手环已校验并发布 PNG 封面 id=$id")
                else if (ok) CommunicationLog.warn("RX", "忽略未通过完整性校验的封面成功回执 id=$id")
                else CommunicationLog.warn("RX", "$asset 写入失败 id=$id code=${json.optString("code")}")
            }
            "artwork_probe_result" -> {
                val requestId = json.optString("requestId")
                val id = json.optString("id")
                if (requestId != pendingArtworkProbeId || id != pendingArtworkAckId || pendingArtwork.isBlank()) return
                handler.removeCallbacks(artworkProbeTimeout)
                pendingArtworkProbeId = ""
                val pendingBytes = runCatching { Base64.decode(pendingArtwork.substringAfter(',', pendingArtwork), Base64.DEFAULT) }.getOrNull() ?: return
                val expectedSum = Adler32().apply { update(pendingBytes) }.value
                val metadataMatches = json.optString("format") == "png" &&
                    json.optInt("bytes", -1) == pendingBytes.size &&
                    json.optLong("sum", -1L) == expectedSum
                if (json.optBoolean("cached") && metadataMatches) {
                    artworkLocallySent = true
                    artworkWatchConfirmed = true
                    CommunicationLog.info("RX", "手环已显示相同封面，跳过上传 id=$id")
                    completeArtworkIfConfirmed()
                } else if (!json.optBoolean("cached") && json.optBoolean("retained") && metadataMatches) {
                    CommunicationLog.info("RX", "手环未命中相同封面，旧封面继续保留；开始上传替换图 id=$id")
                    sendAlbumArt(pendingArtworkAckId, pendingArtwork, true)
                } else {
                    CommunicationLog.warn("RX", "封面预检响应无效，重新校验 id=$id")
                    if (artworkProbeAttempt < MAX_ARTWORK_PROBE_ATTEMPTS) probeArtworkOnWatch()
                    else CommunicationLog.error("TX", "封面预检失败，已停止上传 id=$id")
                }
            }
            "lyrics_ack" -> {
                val id = json.optString("id")
                val lines = json.optInt("lines")
                if (id == pendingLyricsAckId) {
                    handler.removeCallbacks(lyricsAckTimeout)
                    pendingLyricsAckId = ""
                    pendingLyrics = emptyList()
                    lyricsSendAttempt = 0
                }
                CommunicationLog.info("RX", "手环已接收完整歌词 id=$id lines=$lines")
            }
            "cache_clear_ack" -> {
                CommunicationLog.warn("RX", "手环已清空媒体缓存 reason=${json.optString("reason")}")
                softRestartConnectionLayer("手环媒体缓存已清空")
            }
            "sync_ack" -> {
                val sentAt = json.optLong("sentAt")
                if (sentAt <= 0L) return
                val latency = (System.currentTimeMillis() - sentAt).coerceAtLeast(0L)
                val now = System.currentTimeMillis()
                if (latency > 300L) {
                    CommunicationLog.warn("LATENCY", "端到端同步超时 " + latency + "ms seq=" + json.optLong("sequence"))
                } else if (now - lastLatencyLogAt >= 10_000L) {
                    lastLatencyLogAt = now
                    CommunicationLog.info("LATENCY", "端到端同步 " + latency + "ms seq=" + json.optLong("sequence"))
                }
            }
            "control" -> {
                if (!deviceInfoReceived) return
                val action = json.optString("action")
                CommunicationLog.info("CONTROL", action)
                onControl?.invoke(action, if (json.has("value")) json.optLong("value") else null)
            }
            "get_music" -> if (deviceInfoReceived) onMusicRequest?.invoke()
        }
    }

    fun stop() {
        discoveryAttempt++
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(launchRetry)
        handler.removeCallbacks(lyricsAckTimeout)
        handler.removeCallbacks(artworkAckTimeout)
        handler.removeCallbacks(artworkProbeTimeout)
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(softRestart)
        handler.removeCallbacksAndMessages(null)
        node?.id?.let { id -> messageApi?.removeListener(id); nodeApi?.unsubscribe(id, DataItem.ITEM_CONNECTION) }
        synchronized(outgoing) { outgoing.clear() }
        sendInFlight = false
        lastArtworkKey = ""
        lastLyricsKey = ""
        pendingLyricsAckId = ""
        pendingLyrics = emptyList()
        lyricsSendAttempt = 0
        pendingArtworkAckId = ""
        pendingArtwork = ""
        pendingArtworkMediaKey = ""
        pendingArtworkTransferId = ""
        pendingArtworkProbeId = ""
        artworkProbeAttempt = 0
        artworkSendAttempt = 0
        artworkLocallySent = false
        artworkWatchConfirmed = false
        blockedTransferMediaKey = ""
        realtimeSequence = 0L
        lastLatencyLogAt = 0L
        node = null; listenerInstalled = false; listenerNodeId = null; connectionSubscribed = false; connectionNodeId = null; handshaked = false; deviceInfoReceived = false; appLaunchedForSession = false; sessionId = ""; handshakeSent = false
        updateState(WearState.DISCONNECTED, "已断开")
    }

    private fun updateState(value: WearState, message: String) {
        if (_state.value == value && value != WearState.ERROR) return
        _state.value = value
        when (value) {
            WearState.ERROR -> CommunicationLog.error("CONN", message)
            WearState.DISCONNECTED -> CommunicationLog.warn("CONN", message)
            else -> CommunicationLog.info("CONN", message)
        }
    }
}
