import storage from "@system.storage"

export const DEBUG_KEY = "bandmedia_debug_connected_v1"

export function readDebugMode(success) {
  storage.get({ key: DEBUG_KEY, success: value => success(value === true || value === "true"), fail: () => success(false) })
}

export function writeDebugMode(enabled) {
  storage.set({ key: DEBUG_KEY, value: enabled ? "true" : "false" })
}
