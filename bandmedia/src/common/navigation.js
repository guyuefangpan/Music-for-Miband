import router from "@system.router"
import { readInterfaceOrientation } from "./interface.js"

export function replacePage(uri, params) {
  router.replace({ uri, params: params || {} })
}

export function openPlayerRoot() {
  readInterfaceOrientation(value => {
    const uri = value === "landscape" ? "/pages/landscape" : "/pages/index"
    router.push({ uri, params: { ___PARAM_LAUNCH_FLAG___: "clearTask" } })
  })
}
