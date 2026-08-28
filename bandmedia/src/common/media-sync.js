import storage from "@system.storage"

export const MEDIA_STATE_KEY = "bandmedia_media_state_v1"
export const CONTROL_COMMAND_KEY = "bandmedia_control_command_v1"

export function readMediaState(success, fail) {
  storage.get({
    key: MEDIA_STATE_KEY,
    success: data => {
      try {
        const source = typeof data === "string" ? JSON.parse(data) : data
        success(normalizeState(source || {}))
      } catch (error) {
        fail && fail(error)
      }
    },
    fail
  })
}

export function sendControlCommand(action, value) {
  storage.set({
    key: CONTROL_COMMAND_KEY,
    value: JSON.stringify({
      version: 1,
      action,
      value,
      requestId: String(Date.now()),
      createdAt: Date.now()
    })
  })
}

function normalizeState(source) {
  const durationMs = numberValue(source.durationMs)
  const positionMs = numberValue(source.positionMs)
  const progress = durationMs > 0 ? Math.min(1, positionMs / durationMs) : 0
  return {
    version: source.version || 1,
    title: source.title || "等待同步",
    artist: source.artist || "未知艺人",
    album: source.album || "",
    albumArt: source.albumArt || "",
    lyrics: Array.isArray(source.lyrics) ? source.lyrics : [],
    activeLyricIndex: numberValue(source.activeLyricIndex),
    playbackState: source.playbackState || "stopped",
    playing: source.playbackState === "playing",
    volume: Math.max(0, Math.min(100, numberValue(source.volume))),
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
    ,connectionState: source.updatedAt && Date.now() - numberValue(source.updatedAt) < 10000 ? "已连接" : "未连接"
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
