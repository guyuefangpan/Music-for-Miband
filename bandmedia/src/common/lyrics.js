import storage from "@system.storage"

export const LYRICS_FONT_SIZE_KEY = "starry_lyrics_font_size_v1"
export const DEFAULT_LYRICS_FONT_SIZE = 20
export const MIN_LYRICS_FONT_SIZE = 14
export const MAX_LYRICS_FONT_SIZE = 30

export function normalizeLyricsFontSize(value) {
  const parsed = Math.round(Number(value))
  return isNaN(parsed) ? DEFAULT_LYRICS_FONT_SIZE : Math.max(MIN_LYRICS_FONT_SIZE, Math.min(MAX_LYRICS_FONT_SIZE, parsed))
}

export function readLyricsFontSize(success) {
  storage.get({
    key: LYRICS_FONT_SIZE_KEY,
    success: value => success(normalizeLyricsFontSize(value)),
    fail: () => success(DEFAULT_LYRICS_FONT_SIZE),
    complete: () => {}
  })
}

export function writeLyricsFontSize(value, success) {
  const normalized = normalizeLyricsFontSize(value)
  storage.set({ key: LYRICS_FONT_SIZE_KEY, value: String(normalized), success: () => { if (success) success(normalized) }, fail: () => {}, complete: () => {} })
}
