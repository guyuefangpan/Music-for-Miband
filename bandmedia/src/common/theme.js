import storage from "@system.storage"

export const THEME_KEY = "bandmedia_theme_v1"

export function readTheme(success) {
  storage.get({
    key: THEME_KEY,
    success: value => success(value === "day" || value === "cover" || value === "night" ? value : "night"),
    fail: () => success("night"),
    complete: () => {}
  })
}
