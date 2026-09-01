import router from "@system.router"
import { readInterfaceOrientation } from "./interface.js"
import { readIsRing10 } from "./device.js"

export function replacePage(uri, params) {
  router.push({ uri, params: params || {} })
}

export function backPage() { router.back() }
export function backOnRightSwipe(event) { if (event && event.direction === "right") router.back() }

export function openPlayerRoot() {
  readIsRing10(isRing10 => {
    readInterfaceOrientation(value => {
      const uri = value === "landscape" ? "/pages/landscape" : "/pages/index"
      router.push({ uri, params: { ___PARAM_LAUNCH_FLAG___: "clearTask" } })
    })
  })
}
