import storage from "@system.storage"

export const INTERFACE_KEY = "starry_interface_orientation_v1"

export function readInterfaceOrientation(success) {
  storage.get({
    key: INTERFACE_KEY,
    success: value => success(value === "landscape" ? "landscape" : "portrait"),
    fail: () => success("portrait"),
    complete: () => {}
  })
}

export function writeInterfaceOrientation(value, success) {
  storage.set({
    key: INTERFACE_KEY,
    value: value === "landscape" ? "landscape" : "portrait",
    success: () => { if (success) success() },
    fail: () => {},
    complete: () => {}
  })
}
