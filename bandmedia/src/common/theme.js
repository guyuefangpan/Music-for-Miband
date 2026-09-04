import storage from "@system.storage"

export const THEME_KEY = "bandmedia_theme_v1"
export const COVER_OVERLAY_KEY = "starry_cover_overlay_v1"
export const COVER_BLUR_KEY = "starry_cover_blur_v1"

export function readTheme(success) {
  storage.get({
    key: THEME_KEY,
    success: value => success(value === "day" || value === "cover" || value === "night" ? value : "night"),
    fail: () => success("night"),
    complete: () => {}
  })
}

function readNumber(key, fallback, min, max, success) {
  storage.get({
    key,
    success: value => {
      const parsed = Number(value)
      success(isNaN(parsed) ? fallback : Math.max(min, Math.min(max, parsed)))
    },
    fail: () => success(fallback),
    complete: () => {}
  })
}

export function readCoverBackground(success) {
  const result = { overlay: 20, blur: 50 }
  let completed = 0
  const finish = () => { if (++completed === 2) success(result) }
  readNumber(COVER_OVERLAY_KEY, 20, 0, 100, value => { result.overlay = value; finish() })
  readNumber(COVER_BLUR_KEY, 50, 0, 500, value => { result.blur = value; finish() })
}

export function writeCoverOverlay(value, success) {
  storage.set({ key: COVER_OVERLAY_KEY, value: String(Math.max(0, Math.min(100, value))), success: () => { if (success) success() }, fail: () => {}, complete: () => {} })
}

export function writeCoverBlur(value, success) {
  storage.set({ key: COVER_BLUR_KEY, value: String(Math.max(0, Math.min(500, value))), success: () => { if (success) success() }, fail: () => {}, complete: () => {} })
}
