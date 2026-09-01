import storage from "@system.storage"

export const DEBUG_KEY = "bandmedia_debug_connected_v1"

// Vela simulator UI override. Change only this value to "pro" or "o66".
// It is ignored on physical devices. o66 is Xiaomi Smart Band 10.

// export const DEBUG_SIMULATOR_MODEL = "pro"
export const DEBUG_SIMULATOR_MODEL = "o66"

const DEBUG_SIMULATOR_SERIAL = "emulator-5554"

export function isSimulatorDevice(info) {
  const value = info || {}
  const identity = [
    value.serial, value.serialNumber, value.sn, value.deviceId, value.deviceID,
    value.model, value.product, value.device, value.deviceName, value.brand,
    value.manufacturer, value.hardware
  ].map(item => String(item || "").toLowerCase()).join("|")
  return identity.indexOf(DEBUG_SIMULATOR_SERIAL) >= 0 ||
    identity.indexOf("emulator-") >= 0 ||
    identity.indexOf("sdk_gphone") >= 0 ||
    identity.indexOf("android sdk built for") >= 0 ||
    identity.indexOf("generic_x86") >= 0 ||
    identity.indexOf("generic_x86_64") >= 0 ||
    identity.indexOf("ranchu") >= 0 ||
    identity.indexOf("goldfish") >= 0 ||
    value.isEmulator === true || value.isSimulator === true
}

export function readSimulatorModel(info) {
  if (!isSimulatorDevice(info)) return ""
  return DEBUG_SIMULATOR_MODEL === "o66" ? "o66" : "pro"
}

export function readDebugMode(success) {
  storage.get({ key: DEBUG_KEY, success: value => success(value === true || value === "true"), fail: () => success(false), complete: () => {} })
}

export function writeDebugMode(enabled) {
  storage.set({ key: DEBUG_KEY, value: enabled ? "true" : "false", success: () => {}, fail: () => {}, complete: () => {} })
}
