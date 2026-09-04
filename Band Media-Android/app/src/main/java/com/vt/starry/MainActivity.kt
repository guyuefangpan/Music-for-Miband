package com.vt.starry

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.PowerManager
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
        val connectivityPermissions = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missingConnectivity = connectivityPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingConnectivity.isNotEmpty()) requestPermissions(missingConnectivity.toTypedArray(), 1002)
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
                            3 -> SettingsPage(wear, permission, media,
                                { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                bridge::start, bridge::stop,
                                { bridge.forceSyncArtwork(SystemMediaMonitor.currentState()); Unit },
                                { bridge.forceSyncLyrics(SystemMediaMonitor.currentState()); Unit },
                                { page = 4 },
                                { page = 5 })
                            4 -> DiagnosticsPage(this@MainActivity, wear, permission, logs, bridge, { page = 3 })
                            5 -> OpenSourceLicensesPage { page = 3 }
                            else -> NowPlayingPage(media, permission)
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
private fun SettingsPage(wear: WearState, permission: Boolean, media: SystemMediaState, openPermission: () -> Unit,
    reconnect: () -> Unit, disconnect: () -> Unit, syncArtwork: () -> Unit, syncLyrics: () -> Unit,
    openDiagnostics: () -> Unit, openLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(openDiagnostics, Modifier.fillMaxWidth()) { Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(8.dp)); Text("设备自检与兼容性诊断") }
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
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Debug", color = Color.White, fontWeight = FontWeight.Bold)
                Text("当前：" + media.title.ifBlank { "暂无播放" }, color = Muted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(syncArtwork, Modifier.weight(1f), enabled = wear == WearState.CONNECTED && media.albumArt.isNotBlank()) {
                        Icon(Icons.Default.Image, null)
                        Spacer(Modifier.width(6.dp))
                        Text("同步封面")
                    }
                    OutlinedButton(syncLyrics, Modifier.weight(1f), enabled = wear == WearState.CONNECTED && media.lyrics.isNotEmpty()) {
                        Icon(Icons.Default.Lyrics, null)
                        Spacer(Modifier.width(6.dp))
                        Text("同步歌词")
                    }
                }
                Text("封面 ${if (media.albumArt.isBlank()) "无" else "有"} · 歌词 ${media.lyrics.size} 行", color = Muted, fontSize = 12.sp)
            }
        }
        OutlinedButton(openLicenses, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Code, null)
            Spacer(Modifier.width(8.dp))
            Text("开源许可")
        }
        Text("starry 1.0.0\n读取系统媒体并同步到小米手环。", color = Muted, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

private data class OpenSourceComponent(val name: String, val version: String, val copyright: String)

private val openSourceComponents = listOf(
    OpenSourceComponent("AndroidX / Jetpack", "Core 1.16.0、Activity 1.9.0、Lifecycle 2.9.4、Media 1.7.0", "Copyright The Android Open Source Project"),
    OpenSourceComponent("Jetpack Compose", "UI 1.11.1、Material 3 1.4.0、Material Icons 1.7.8", "Copyright The Android Open Source Project"),
    OpenSourceComponent("Kotlin Standard Library", "2.4.10", "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"),
    OpenSourceComponent("Kotlin Coroutines", "1.11.0", "Copyright JetBrains s.r.o. and contributors")
)

@Composable
private fun OpenSourceLicensesPage(back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }
            Text("开源许可", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("starry 使用以下开源软件。除另有说明外，这些组件均依据 Apache License 2.0 提供。",
            color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        openSourceComponents.forEach { component ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(component.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(component.version, color = StarryGreen, fontSize = 12.sp)
                    Text(component.copyright, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("Apache License 2.0", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Text("Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/\n\n" + APACHE_LICENSE_2,
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private const val APACHE_LICENSE_2 = """
TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

\"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.

\"Licensor\" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.

\"Legal Entity\" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity.

\"You\" shall mean an individual or Legal Entity exercising permissions granted by this License.

\"Source\" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.

\"Object\" form shall mean any form resulting from mechanical transformation or translation of a Source form.

\"Work\" shall mean the work of authorship made available under the License.

\"Derivative Works\" shall mean any work based on the Work for which the editorial revisions, annotations, elaborations, or other modifications represent an original work of authorship.

\"Contribution\" shall mean any work of authorship intentionally submitted to Licensor for inclusion in the Work.

\"Contributor\" shall mean Licensor and any individual or Legal Entity on behalf of whom a Contribution has been received and incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works.

3. Grant of Patent License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work, where such license applies only to patent claims licensable by such Contributor that are necessarily infringed by their Contribution alone or by combination of their Contribution with the Work. If You institute patent litigation alleging that the Work or a Contribution incorporated within the Work constitutes direct or contributory patent infringement, then any patent licenses granted to You under this License for that Work shall terminate as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, provided that You meet the following conditions: You must give recipients a copy of this License; modified files must carry prominent notices; You must retain all copyright, patent, trademark, and attribution notices; and any NOTICE file must be included in a readable form.

5. Submission of Contributions. Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work shall be under the terms of this License.

6. Trademarks. This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor, except as required for reasonable use in describing the origin of the Work.

7. Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

8. Limitation of Liability. In no event shall any Contributor be liable for damages arising as a result of this License or use of the Work, unless required by applicable law.

9. Accepting Warranty or Additional Liability. You may offer support, warranty, indemnity, or other liability obligations only on Your own behalf and responsibility.

END OF TERMS AND CONDITIONS
"""

@Composable
private fun DiagnosticsPage(activity: MainActivity, wear: WearState, notificationAccess: Boolean, logs: List<CommunicationLogEntry>, bridge: WearBridge, back: () -> Unit) {
    var refreshed by remember { mutableIntStateOf(0) }
    val pm = activity.getSystemService(PowerManager::class.java)
    val batteryOk = pm?.isIgnoringBatteryOptimizations(activity.packageName) == true
    val notificationOk = notificationAccess
    val sdkOk = runCatching { com.xiaomi.xms.wearable.Wearable.getNodeApi(activity) }.isSuccess
    val bluetoothOk = if (android.os.Build.VERSION.SDK_INT >= 31) ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val checks = listOf(
        Triple("Android 系统", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / API " + android.os.Build.VERSION.SDK_INT, true),
        Triple("通知访问权限", if (notificationOk) "已授权，可读取系统媒体" else "未授权：无法读取歌曲、歌词和播放状态", notificationOk),
        Triple("通知权限", if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "已允许或系统不要求" else "未允许：可能无法显示服务状态", android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
        Triple("蓝牙/附近设备权限", if (bluetoothOk) "已授权，可发现和连接手环" else if (android.os.Build.VERSION.SDK_INT >= 31) "未授权：Android 12 需要“附近设备”权限" else "未授权：旧系统需要定位权限才能扫描蓝牙", bluetoothOk),
        Triple("电池优化", if (batteryOk) "已豁免，适合后台保持连接" else "未豁免：系统可能暂停后台重连", batteryOk),
        Triple("Xiaomi Wearable SDK", if (sdkOk) "SDK 可加载" else "SDK 加载失败或设备不兼容", sdkOk),
        Triple("手环互联节点", wear.name, wear == WearState.CONNECTED),
        Triple("Quick App 会话", wear.name + "；点击下方重试拉起与握手", wear != WearState.ERROR),
        Triple("最近通信日志", logs.size.toString() + " 条；请在日志页查看详细错误堆栈", logs.isNotEmpty())
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }; Text("设备自检", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text("用于排查不同手机、系统版本和小米运动健康互联差异。状态来自当前设备实时检测。", color = Muted, fontSize = 13.sp)
        checks.forEach { (title, detail, ok) -> StatusCard(title, detail, ok, if (ok) Icons.Default.CheckCircle else Icons.Default.Warning) }
        Button({ refreshed++; bridge.start() }, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重新检测并重试 Quick App") }
        OutlinedButton({ activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("打开通知访问设置") }
        OutlinedButton({ activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = android.net.Uri.parse("package:" + activity.packageName) }) }, Modifier.fillMaxWidth()) { Text("申请电池优化豁免") }
        Text("检测编号：" + refreshed + " · 包名：" + activity.packageName, color = Muted, fontSize = 11.sp)
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
