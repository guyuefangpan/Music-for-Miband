import device from "@system.device"
import { readSimulatorModel } from "./debug.js"

export function readScreenType(success) {
  device.getInfo({
    success: info => {
      const value = info || {}
      const shape = String(value.screenShape || "").toLowerCase()
      const simulatorModel = readSimulatorModel(value)
      const isRing10 = simulatorModel ? simulatorModel === "o66" : shape === "pill-shaped" || (Number(value.screenWidth || value.width || value.windowWidth || 0) === 212 && Number(value.screenHeight || value.height || value.windowHeight || 0) === 520)
      if (simulatorModel) console.log("simulator ui model override: " + simulatorModel)
      success({
        isRing10: isRing10,
        simulatorModel: simulatorModel,
        screenShape: shape,
        width: Number(value.screenWidth || value.width || value.windowWidth || 0),
        height: Number(value.screenHeight || value.height || value.windowHeight || 0)
      })
    },
    fail: () => success({ isRing10: false, screenShape: "", width: 0, height: 0 }),
    complete: () => {}
  })
}

export function readIsRing10(success) {
  readScreenType(info => success(!!info.isRing10))
}
