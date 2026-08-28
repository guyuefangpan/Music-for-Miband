# Band Media Android 同步接口 v1

Android 同步器通过 Vela storage 与手表端交换两类 JSON 数据。

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
