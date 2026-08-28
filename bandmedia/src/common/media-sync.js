import storage from "@system.storage"
import interconnect from "@system.interconnect"

export const MEDIA_STATE_KEY = "bandmedia_media_state_v1"
export const CONTROL_COMMAND_KEY = "bandmedia_control_command_v1"
export const CONNECTION_KEY = "starry_interconnect_seen_v1"
const conn = interconnect.instance()

export function readMediaState(success, fail) {
  storage.get({ key: CONNECTION_KEY, success: seen => {
    const connected = Number(seen || 0) > 0 && Date.now() - Number(seen) < 15000
    storage.get({
      key: MEDIA_STATE_KEY,
      success: data => {
        try {
          const source = typeof data === "string" ? JSON.parse(data) : data
          success(normalizeState(source || {}, connected))
        } catch (error) { fail && fail(error) }
      },
      fail: () => success(normalizeState({}, connected))
    })
  }, fail })
}

export function sendControlCommand(action, value) {
  try {
    conn.send({
      data: { type: "control", action, value, requestId: String(Date.now()) },
      success: () => {},
      fail: () => {}
    })
  } catch (error) {}
}

function normalizeState(source, connected) {
  const durationMs = numberValue(source.durationMs)
  const positionMs = numberValue(source.positionMs)
  const volumeValid = source.volume !== undefined && source.volume !== null && !isNaN(Number(source.volume))
  const progress = durationMs > 0 ? Math.min(1, positionMs / durationMs) : 0
  return {
    version: source.version || 1,
    title: source.title || (connected ? "暂无播放" : "等待同步"),
    artist: source.artist || (connected ? "请在手机上播放音乐" : "Android 同步器"),
    album: source.album || "",
    albumArt: source.albumArt || "",
    lyrics: Array.isArray(source.lyrics) ? source.lyrics : [],
    activeLyricIndex: numberValue(source.activeLyricIndex),
    playbackState: source.playbackState || "stopped",
    playing: source.playbackState === "playing",
    volume: Math.max(0, Math.min(100, numberValue(source.volume))),
    volumeValid,
    muted: source.muted === true,
    durationMs,
    positionMs,
    elapsed: formatTime(positionMs),
    remaining: durationMs > 0 ? formatTime(durationMs - positionMs) : "--:--",
    progressWidth: Math.round(198 * progress),
    progressKnobLeft: Math.round(181 * progress),
    ringProgressWidth: Math.round(124 * progress),
    ringProgressKnobLeft: Math.round(115 * progress),
    hasProgress: durationMs > 0 && positionMs > 0,
    updatedAt: numberValue(source.updatedAt)
    ,connectionState: connected ? "已连接" : "未连接"
  }
}

function numberValue(value) {
  const number = Number(value)
  return isNaN(number) ? 0 : number
}

function formatTime(milliseconds) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000))
  const minutes = Math.floor(seconds / 60)
  return (minutes < 10 ? "0" : "") + minutes + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60
}
