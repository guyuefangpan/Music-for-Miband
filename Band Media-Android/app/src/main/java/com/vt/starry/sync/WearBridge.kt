package com.vt.starry.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xiaomi.xms.wearable.Wearable
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
import java.util.UUID

enum class WearState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

class WearBridge private constructor(private val context: Context) {
    companion object {
        private const val WATCH_ENTRY = "/pages/index"
        // Shell++ uses 4096-byte interconnect messages successfully. Keeping realtime
        // snapshots in one message avoids reassembly latency on the watch.
        private const val CHUNK_SIZE = 4096
        private const val MAX_PACKET_BYTES = 4600
        private const val ARTWORK_PART_SIZE = 20_000
        private const val MAX_ARTWORK_CHARS = ARTWORK_PART_SIZE * 3
        private const val HEARTBEAT_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val MAX_LAUNCH_ATTEMPTS = 10
        private const val LAUNCH_RETRY_MS = 1_000L
        private const val LYRICS_BATCH_LINES = 8
        private const val LYRICS_ACK_TIMEOUT_MS = 3_000L
        private const val MAX_LYRICS_SEND_ATTEMPTS = 3
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
    private var node: Node? = null
    private var listenerInstalled = false
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
    private var realtimeSequence = 0L
    private var lastLatencyLogAt = 0L
    private var discoveryAttempt = 0
    private var launchAttempt = 0
    private val launchRetry = object : Runnable {
        override fun run() = launchQuickAppUntilReady()
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

    private data class OutboundMessage(val type: String, val packets: List<ByteArray>)

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
        if (nodeApi == null) {
            nodeApi = Wearable.getNodeApi(context)
            messageApi = Wearable.getMessageApi(context)
        }
        updateState(WearState.CONNECTING, "正在查找手环")
        val attempt = ++discoveryAttempt
        handler.postDelayed({
            if (attempt == discoveryAttempt && _state.value == WearState.CONNECTING) {
                updateState(WearState.DISCONNECTED, "未发现小米运动健康互联节点")
            }
        }, DISCOVERY_TIMEOUT_MS)
        nodeApi!!.connectedNodes.addOnSuccessListener { nodes ->
            if (attempt != discoveryAttempt) return@addOnSuccessListener
            nodes.firstOrNull()?.let(::attach)
                ?: updateState(WearState.DISCONNECTED, "未发现互联互通节点")
        }.addOnFailureListener {
            if (attempt == discoveryAttempt) updateState(WearState.ERROR, "获取手环节点失败: ${it.message.orEmpty()}")
        }
    }

    private fun attach(found: Node) {
        node = found
        val install = {
            messageApi!!.addListener(found.id, listener).addOnSuccessListener {
                listenerInstalled = true
                CommunicationLog.info("CONN", "消息通道已就绪")
                subscribeConnection(found)
                launchQuickAppUntilReady()
            }.addOnFailureListener { updateState(WearState.ERROR, "注册消息监听失败: ${it.message.orEmpty()}") }
        }
        messageApi!!.removeListener(found.id)
            .addOnSuccessListener { install() }
            .addOnFailureListener { install() }
    }

    private fun subscribeConnection(found: Node) {
        nodeApi?.subscribe(found.id, DataItem.ITEM_CONNECTION) { _, item, data ->
            if (item.type == DataItem.ITEM_CONNECTION.type) {
                if (data.connectedStatus == DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                    if (!handshaked && !appLaunchedForSession) sendHandshake()
                } else {
                    markQuickAppDisconnected("手环已断开")
                }
            }
        }
    }

    private fun sendHandshake() {
        if (!listenerInstalled || deviceInfoReceived) return
        handshakeSent = true
        sendJson(JSONObject().put("type", "__hs__").put("count", 0).put("version", 1).put("session", sessionId), false)
    }

    private fun launchQuickAppUntilReady() {
        if (deviceInfoReceived || launchAttempt >= MAX_LAUNCH_ATTEMPTS) {
            if (!deviceInfoReceived && launchAttempt >= MAX_LAUNCH_ATTEMPTS) {
                updateState(WearState.DISCONNECTED, "Quick App 连续 10 次未响应，已暂停重试")
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
        val artworkId = Integer.toHexString(media.albumArt.hashCode())
        val lyricsId = Integer.toHexString(media.lyrics.hashCode())
        sendJson(JSONObject().apply {
            put("type", "music_info")
            put("title", media.title); put("artist", media.artist); put("album", media.album)
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
        if (artworkId != lastArtworkKey) {
            lastArtworkKey = artworkId
            sendAlbumArt(artworkId, media.albumArt)
        }
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

    private fun sendAlbumArt(artworkId: String, artwork: String) {
        val value = artwork.takeIf { it.length <= MAX_ARTWORK_CHARS }.orEmpty()
        val parts = if (value.isEmpty()) listOf("") else value.chunked(ARTWORK_PART_SIZE)
        if (parts.size > 3) return
        val packets = parts.flatMapIndexed { index, part ->
            packetsFor(JSONObject().put("type", "album_art").put("id", artworkId).put("i", index).put("t", parts.size).put("d", part).toString())
        }
        synchronized(outgoing) {
            outgoing.removeAll { it.type == "album_art" }
            outgoing.addLast(OutboundMessage("album_art", packets))
        }
        drainOutgoing()
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
            if (type == "media_progress") {
                if (urgent) {
                    // Playback-state transitions and command confirmations must not
                    // wait behind bulk lyrics/artwork transfers.
                    outgoing.addFirst(item)
                } else {
                    // Never starve full lyrics during ordinary position updates.
                    val lyricTail = outgoing.indexOfLast { it.type == "lyrics_batch" }
                    if (lyricTail >= 0) outgoing.add(lyricTail + 1, item) else outgoing.addFirst(item)
                }
            } else outgoing.addLast(item)
        }
        drainOutgoing()
        if (logTraffic) CommunicationLog.info("TX", json.optString("type"))
    }

    private fun packetsFor(raw: String): List<ByteArray> {
        val rawBytes = raw.toByteArray(Charsets.UTF_8)
        if (rawBytes.size <= CHUNK_SIZE) return listOf(rawBytes)
        val id = UUID.randomUUID().toString()
        val parts = chunkUtf8(raw, CHUNK_SIZE)
        return parts.mapIndexed { index, part ->
            JSONObject().put("__c", true).put("id", id).put("i", index).put("t", parts.size).put("d", part).toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun chunkUtf8(value: String, maxBytes: Int): List<String> {
        if (value.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        val part = StringBuilder()
        var partBytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (partBytes > 0 && partBytes + bytes > maxBytes) {
                result.add(part.toString())
                part.setLength(0)
                partBytes = 0
            }
            part.append(text)
            partBytes += bytes
            offset += Character.charCount(codePoint)
        }
        if (part.isNotEmpty()) result.add(part.toString())
        return result
    }

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
                        outgoing.addLast(OutboundMessage("album_art", message.packets.subList(index + 1, message.packets.size)))
                    }
                    finishSend()
                } else if (index + 1 < message.packets.size) sendPacket(message, index + 1)
                else finishSend()
            }
            .addOnFailureListener {
                CommunicationLog.error("TX", message.type + " 发送失败 packet=" + (index + 1) + "/" + message.packets.size, it)
                markQuickAppDisconnected("Quick App 消息通道不可用")
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
        updateState(WearState.DISCONNECTED, reason)
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
                if (!handshaked || deviceInfoReceived || json.optString("session") != sessionId) return
                deviceInfoReceived = true
                handler.removeCallbacks(launchRetry)
                val model = json.optString("model").ifBlank { json.optString("product") }
                CommunicationLog.info("CONN", "收到 Quick App 设备信息，通信就绪${if (model.isBlank()) "" else ": $model"}")
                sendJson(JSONObject().put("type", "device_info_ack").put("session", sessionId).put("version", 1), false)
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, HEARTBEAT_MS)
                updateState(WearState.CONNECTED, "Quick App 已连接")
                onConnected?.invoke()
            }
            "app_ready" -> {
                if (!deviceInfoReceived) sendHandshake()
                else sendJson(JSONObject().put("type", "heartbeat").put("origin", "android_recover").put("timestamp", System.currentTimeMillis()), false)
            }
            "asset_ack" -> {
                val asset = json.optString("asset")
                val id = json.optString("id")
                if (json.optBoolean("ok")) CommunicationLog.info("RX", "$asset 已写入手环 id=$id")
                else CommunicationLog.warn("RX", "$asset 写入失败 id=$id code=${json.optString("code")}")
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
        handler.removeCallbacksAndMessages(null)
        node?.id?.let { id -> messageApi?.removeListener(id); nodeApi?.unsubscribe(id, DataItem.ITEM_CONNECTION) }
        synchronized(outgoing) { outgoing.clear() }
        sendInFlight = false
        lastArtworkKey = ""
        lastLyricsKey = ""
        pendingLyricsAckId = ""
        pendingLyrics = emptyList()
        lyricsSendAttempt = 0
        realtimeSequence = 0L
        lastLatencyLogAt = 0L
        node = null; listenerInstalled = false; handshaked = false; deviceInfoReceived = false; appLaunchedForSession = false; sessionId = ""; handshakeSent = false
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
