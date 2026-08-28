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
        private const val CHUNK_SIZE = 2048
        private const val CHUNK_GAP_MS = 40L
        private const val ARTWORK_PART_SIZE = 3000
        // Leave room for the data-URI prefix so the payload always fits in three parts.
        private const val MAX_ARTWORK_CHARS = ARTWORK_PART_SIZE * 3 - 300
        private const val MAX_LYRIC_LINES = 40
        private const val HEARTBEAT_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
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
    private var lastHeartbeatAck = 0L
    private val incomingChunks = HashMap<String, Array<String?>>()
    private val outgoing = ArrayDeque<OutboundMessage>()
    private var sendInFlight = false
    private var lastArtworkKey = ""
    private var discoveryAttempt = 0

    private data class OutboundMessage(val type: String, val packets: List<ByteArray>)

    private val listener = OnMessageReceivedListener { nodeId, bytes ->
        if (node?.id != nodeId) return@OnMessageReceivedListener
        runCatching { handleIncoming(String(bytes, Charsets.UTF_8)) }
            .onFailure { CommunicationLog.error("RX", "消息解析失败", it) }
    }

    fun start() {
        if (_state.value == WearState.CONNECTING || _state.value == WearState.CONNECTED) return
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
                nodeApi!!.launchWearApp(found.id, WATCH_ENTRY)
                    .addOnSuccessListener { sendHandshake() }
                    .addOnFailureListener {
                        CommunicationLog.warn("CONN", "Quick App 启动失败，继续握手: ${it.message.orEmpty()}")
                        sendHandshake()
                    }
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
                    if (!handshaked) sendHandshake()
                } else {
                    handshaked = false
                    updateState(WearState.DISCONNECTED, "手环已断开")
                }
            }
        }
    }

    private fun sendHandshake() {
        sendJson(JSONObject().put("type", "__hs__").put("count", 0).put("version", 1), false)
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (handshaked && now - lastHeartbeatAck > HEARTBEAT_TIMEOUT_MS) {
                handshaked = false
                updateState(WearState.DISCONNECTED, "Quick App 心跳超时")
            }
            if (listenerInstalled) {
                sendJson(JSONObject().put("type", "heartbeat").put("timestamp", now), false)
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    fun sendMusicState(media: SystemMediaState) {
        if (!handshaked) return
        if (media.title.isBlank()) {
            sendJson(JSONObject().put("type", "music_clear"), false)
            return
        }
        val lyrics = JSONArray().apply {
            media.lyrics.take(MAX_LYRIC_LINES).forEach { line -> put(JSONObject().apply {
                line.startMs?.let { put("startMs", it) }
                put("text", line.text.take(160))
            }) }
        }
        sendJson(JSONObject().apply {
            put("type", "music_info")
            put("title", media.title); put("artist", media.artist); put("album", media.album)
            put("mediaKey", media.packageName + "|" + media.title + "|" + media.artist + "|" + media.album); put("state", if (media.playbackState == "playing") 1 else 0)
            put("playbackState", media.playbackState); put("positionMs", media.positionMs); put("durationMs", media.durationMs)
            put("volume", media.volume); put("muted", media.muted); put("lyrics", lyrics)
            put("activeLyricIndex", media.activeLyricIndex); put("sourcePackage", media.packageName)
            put("sourceName", media.sourceName); put("updatedAt", System.currentTimeMillis())
        }, false)
        val artworkKey = media.packageName + "|" + media.title + "|" + media.artist + "|" + media.album + "|" + media.albumArt.hashCode()
        if (artworkKey != lastArtworkKey) {
            lastArtworkKey = artworkKey
            sendAlbumArt(media)
        }
    }

    private fun sendAlbumArt(media: SystemMediaState) {
        val value = media.albumArt.takeIf { it.length <= MAX_ARTWORK_CHARS }.orEmpty()
        val parts = if (value.isEmpty()) listOf("") else value.chunked(ARTWORK_PART_SIZE)
        if (parts.size > 3) return
        val mediaKey = media.packageName + "|" + media.title + "|" + media.artist + "|" + media.album
        val packets = parts.mapIndexed { index, part ->
            JSONObject().put("type", "album_art").put("key", mediaKey).put("i", index).put("t", parts.size).put("d", part).toString().toByteArray(Charsets.UTF_8)
        }
        synchronized(outgoing) {
            outgoing.removeAll { it.type == "album_art" }
            outgoing.addLast(OutboundMessage("album_art", packets))
        }
        drainOutgoing()
    }

    private fun sendJson(json: JSONObject, logTraffic: Boolean = true) {
        if (node == null || messageApi == null) return
        val raw = json.toString()
        val type = json.optString("type")
        val packets = if (raw.length <= CHUNK_SIZE) {
            listOf(raw.toByteArray(Charsets.UTF_8))
        } else {
            val id = UUID.randomUUID().toString()
            val parts = raw.chunked(CHUNK_SIZE)
            parts.mapIndexed { index, part -> JSONObject().put("__c", true).put("id", id).put("i", index).put("t", parts.size).put("d", part).toString().toByteArray(Charsets.UTF_8) }
        }
        synchronized(outgoing) {
            if (type == "music_info" || type == "music_clear") outgoing.removeAll { it.type == "music_info" || it.type == "music_clear" }
            else if (type == "heartbeat") outgoing.removeAll { it.type == "heartbeat" }
            outgoing.addLast(OutboundMessage(type, packets))
        }
        drainOutgoing()
        if (logTraffic) CommunicationLog.info("TX", json.optString("type"))
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
        api.sendMessage(target.id, message.packets[index])
            .addOnSuccessListener {
                if (index + 1 < message.packets.size) handler.postDelayed({ sendPacket(message, index + 1) }, CHUNK_GAP_MS)
                else finishSend()
            }
            .addOnFailureListener {
                CommunicationLog.error("TX", message.type + " 发送失败 packet=" + (index + 1) + "/" + message.packets.size, it)
                finishSend()
            }
    }

    private fun finishSend() {
        sendInFlight = false
        handler.postDelayed(::drainOutgoing, CHUNK_GAP_MS)
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
                if (count >= 0) {
                    val first = !handshaked
                    handshaked = true; lastHeartbeatAck = System.currentTimeMillis()
                    updateState(WearState.CONNECTED, "Quick App 已连接")
                    if (count < 2) sendJson(JSONObject().put("type", "__hs__").put("count", count + 1).put("version", 1), false)
                    if (first) onConnected?.invoke()
                }
            }
            "heartbeat" -> { lastHeartbeatAck = System.currentTimeMillis(); sendJson(JSONObject().put("type", "heartbeatAck").put("timestamp", lastHeartbeatAck), false) }
            "heartbeatAck" -> {
                lastHeartbeatAck = System.currentTimeMillis()
                if (!handshaked) { handshaked = true; updateState(WearState.CONNECTED, "Quick App 已连接"); onConnected?.invoke() }
            }
            "control" -> {
                val action = json.optString("action")
                CommunicationLog.info("CONTROL", action)
                onControl?.invoke(action, if (json.has("value")) json.optLong("value") else null)
            }
            "get_music" -> onMusicRequest?.invoke()
        }
    }

    fun stop() {
        discoveryAttempt++
        handler.removeCallbacks(heartbeat)
        node?.id?.let { id -> messageApi?.removeListener(id); nodeApi?.unsubscribe(id, DataItem.ITEM_CONNECTION) }
        synchronized(outgoing) { outgoing.clear() }
        sendInFlight = false
        node = null; listenerInstalled = false; handshaked = false
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
