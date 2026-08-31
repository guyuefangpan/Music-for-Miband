import device from "@system.device"

export function readScreenType(success) {
  device.getInfo({
    success: info => {
      const value = info || {}
      const shape = String(value.screenShape || "").toLowerCase()
      success({
        isRing10: shape === "pill-shaped",
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
