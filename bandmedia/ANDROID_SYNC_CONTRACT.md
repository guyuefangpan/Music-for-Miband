# Band Media Android 同步接口 v1

Android 同步器通过小米互联互通 SDK 与 Vela `interconnect` 交换 JSON 数据。每个 Android 连接会话只查询节点并拉起 Quick App 一次。

## 会话建立

业务数据必须等待以下流程完成，不能在握手阶段提前发送：

1. Android 注册消息监听、生成新的 `session`，并拉起 Quick App 一次。
2. 双方通过 `__hs__` 完成传输层握手，消息携带当前 `session`。
3. Quick App 调用 `device.getInfo` 一次，向 Android 返回同一 `session` 的 `device_info`。
4. Android 校验会话后返回 `device_info_ack`，双方进入 ready 状态。
5. Android 仅在首次进入 ready 时推送一次完整媒体快照，之后只推送媒体变化和播放进度。

在 ready 之前，媒体、封面、心跳、控制及 storage 代理消息都必须丢弃。旧会话或错误 `session` 的 `device_info`/`device_info_ack` 也必须丢弃。

## 媒体状态

键：`bandmedia_media_state_v1`

`albumArt` 是 Android 同步到手表本地后的可访问资源路径或 Vela 支持的图片 URI。界面本身仍由 UX 代码绘制，图片只用于专辑封面。

`lyrics` 每项至少包含 `text`，可选 `startMs`。Android 负责在播放进度变化时更新 `activeLyricIndex`。

```json
{
  "version": 1,
  "title": "歌曲名",
  "artist": "艺人",
  "album": "专辑名",
  "albumArt": "internal://files/bandmedia/cover.jpg",
  "durationMs": 285000,
  "positionMs": 126000,
  "playbackState": "playing",
  "volume": 62,
  "muted": false,
  "activeLyricIndex": 1,
  "lyrics": [
    { "startMs": 120000, "text": "上一句歌词" },
    { "startMs": 126000, "text": "当前歌词" },
    { "startMs": 132000, "text": "下一句歌词" }
  ],
  "updatedAt": 1787880000000
}
```

`playbackState` 支持 `playing`、`paused`、`stopped`、`buffering`。`volume` 范围为 0 到 100。

## 手表控制命令

键：`bandmedia_control_command_v1`

手表按钮写入命令，Android 同步器监听或轮询后执行，并用新的媒体状态回写确认结果。

`action` 支持：`play`、`pause`、`previous`、`next`、`seek`、`setVolume`、`toggleMute`、`requestSync`。

```json
{
  "version": 1,
  "action": "setVolume",
  "value": 50,
  "requestId": "1787880000000",
  "createdAt": 1787880000000
}
```
