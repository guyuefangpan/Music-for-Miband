package com.vt.starry

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vt.starry.sync.*

private val StarryGreen = Color(0xFF1DB954)
private val AppBackground = Color(0xFF0E0E12)
private val Panel = Color(0xFF1C1C24)
private val Muted = Color(0xFF9AA0A6)

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        SystemMediaMonitor.refreshPermission(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = WearBridge.get(this)
        ContextCompat.startForegroundService(this, Intent(this, MediaSyncService::class.java))
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            val media by SystemMediaMonitor.state.collectAsState()
            val wear by bridge.state.collectAsState()
            val permission by SystemMediaMonitor.notificationAccess.collectAsState()
            val logs by CommunicationLog.entries.collectAsState()
            var page by remember { mutableIntStateOf(0) }
            MaterialTheme {
                Scaffold(containerColor = AppBackground, bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        NavigationBarItem(page == 0, { page = 0 }, { Icon(Icons.Default.MusicNote, null) }, label = { Text("正在播放") })
                        NavigationBarItem(page == 1, { page = 1 }, { Icon(Icons.Default.Lyrics, null) }, label = { Text("歌词") })
                        NavigationBarItem(page == 2, { page = 2 }, { Icon(Icons.Default.GraphicEq, null) }, label = { Text("日志") })
                        NavigationBarItem(page == 3, { page = 3 }, { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
                    }
                }) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding), color = AppBackground) {
                        when (page) {
                            0 -> NowPlayingPage(media, permission)
                            1 -> LyricsPage(media)
                            2 -> LogPage(logs)
                            else -> SettingsPage(wear, permission,
                                { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                bridge::start, bridge::stop)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPage(media: SystemMediaState) {
    val listState = rememberLazyListState()
    val active = media.activeLyricIndex.coerceIn(0, (media.lyrics.size - 1).coerceAtLeast(0))
    LaunchedEffect(active, media.lyrics.size) {
        if (media.lyrics.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(active, scrollOffset = -220)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(media.title.ifBlank { "歌词" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(media.artist.ifBlank { "暂无播放" }, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        if (media.lyrics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("当前媒体未提供歌词", color = Muted) }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 180.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(media.lyrics) { index, line ->
                    Text(line.text, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = if (index == active) Color.White else Muted.copy(alpha = 0.62f), fontSize = if (index == active) 21.sp else 16.sp, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, lineHeight = 28.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingPage(media: SystemMediaState, notificationAccess: Boolean) {
    val cover = remember(media.albumArt) { decodeCover(media.albumArt) }
    Box(Modifier.fillMaxSize()) {
        if (cover != null) Image(cover.asImageBitmap(), null,
            Modifier.fillMaxSize().blur(42.dp).alpha(0.26f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("starry", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.size(260.dp).clip(RoundedCornerShape(20.dp)).background(Panel), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover.asImageBitmap(), "媒体封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.MusicNote, "暂无封面", Modifier.size(82.dp), tint = Muted)
            }
            Spacer(Modifier.height(26.dp))
            Text(media.title.ifBlank { "暂无播放" }, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(media.artist.ifBlank {
                if (notificationAccess) "打开任意音乐 App 播放后自动显示"
                else "请先在设置中开启通知访问权限"
            }, color = Muted, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (media.album.isNotBlank()) Text(media.album, color = Muted.copy(alpha = 0.72f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressControl(media)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(SystemMediaMonitor::previous, Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton({ if (media.playbackState == "playing") SystemMediaMonitor.pause() else SystemMediaMonitor.play() },
                    Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(StarryGreen)) {
                    Icon(if (media.playbackState == "playing") Icons.Default.Pause else Icons.Default.PlayArrow,
                        "播放暂停", tint = Color.Black, modifier = Modifier.size(42.dp))
                }
                IconButton(SystemMediaMonitor::next, Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ SystemMediaMonitor.setVolume((media.volume - 10).coerceAtLeast(0)) }) {
                    Icon(Icons.Default.VolumeDown, "降低音量", tint = Muted)
                }
                Text("来源：" + media.sourceName.ifBlank { "—" }, Modifier.weight(1f), color = Muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                IconButton({ SystemMediaMonitor.setVolume((media.volume + 10).coerceAtMost(100)) }) {
                    Icon(Icons.Default.VolumeUp, "提高音量", tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun ProgressControl(media: SystemMediaState) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(media.positionMs.toFloat()) }
    LaunchedEffect(media.positionMs, dragging) { if (!dragging) value = media.positionMs.toFloat() }
    Spacer(Modifier.height(18.dp))
    val duration = media.durationMs.coerceAtLeast(1)
    Slider(value = value.coerceIn(0f, duration.toFloat()), onValueChange = { dragging = true; value = it },
        onValueChangeFinished = { dragging = false; SystemMediaMonitor.seek(value.toLong()) }, valueRange = 0f..duration.toFloat())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(value.toLong()), color = Muted, fontSize = 12.sp)
        Text(formatTime(media.durationMs), color = Muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LogPage(logs: List<CommunicationLogEntry>) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("通信日志", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("最近 " + logs.size + " 条 Android 与手环事件", color = Muted, fontSize = 13.sp)
            }
            OutlinedButton(CommunicationLog::clear) { Icon(Icons.Default.DeleteOutline, null); Text("清空") }
        }
        Spacer(Modifier.height(12.dp))
        if (logs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无通信日志", color = Muted) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs.asReversed()) { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(entry.direction + "  " + entry.level, color = logColor(entry.level), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(entry.message, color = Color.White, fontSize = 13.sp)
                        Text(entry.displayText().take(12), color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(wear: WearState, permission: Boolean, openPermission: () -> Unit,
    reconnect: () -> Unit, disconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        StatusCard("手环连接", wear.name, wear == WearState.CONNECTED, Icons.Default.Devices)
        StatusCard("媒体读取权限", if (permission) "已授权" else "未授权", permission, Icons.Default.Notifications)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("手环互联互通", color = Color.White, fontWeight = FontWeight.Bold)
                Text("使用 Xiaomi Wearable SDK 与同包名、同签名的 Vela 应用通信。", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(reconnect, Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Text("连接") }
                    OutlinedButton(disconnect, Modifier.weight(1f)) { Text("断开") }
                }
            }
        }
        Button(openPermission, Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (permission) "管理通知访问权限" else "开启通知访问权限")
        }
        Text("starry 1.0.0\n读取系统媒体并同步到小米手环。", color = Muted, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusCard(title: String, value: String, healthy: Boolean, icon: ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = StarryGreen)
            Text(title, Modifier.padding(start = 12.dp).weight(1f), color = Color.White)
            Text(value, color = if (healthy) StarryGreen else Muted, fontWeight = FontWeight.Bold)
        }
    }
}

private fun decodeCover(uri: String) = try {
    val encoded = uri.substringAfter(",", "")
    if (encoded.isBlank()) null else Base64.decode(encoded, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
} catch (_: Throwable) { null }

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return (seconds / 60).toString() + ":" + (seconds % 60).toString().padStart(2, '0')
}

private fun logColor(level: String) = when (level) {
    "E" -> Color(0xFFFF6B6B)
    "W" -> Color(0xFFFFC857)
    else -> StarryGreen
}
