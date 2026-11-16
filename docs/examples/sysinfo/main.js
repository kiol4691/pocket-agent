// sysinfo —— 最小工具插件示例。
// 只读设备信息，不需要任何危险权限。

function runSysinfo(ctx) {
  return [
    "model: " + bridge.device.model(),
    "android sdk: " + bridge.device.sdk(),
    "density: " + bridge.device.density(),
    "language: " + bridge.device.language(),
    "storage: " + bridge.device.freeStorage()
  ].join("\n");
}
