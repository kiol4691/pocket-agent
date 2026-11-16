# pluginrt — QuickJS 运行时 spike

独立模块，只用来验证"QuickJS + 资源闸门能否替代 Rhino 作为插件运行时"，**不接主工程逻辑**。

`qjsfork/` 是同仓库的 fork：从 taoweiji/quickjs-android 1.4.6（Apache-2.0）拉源码，
只编 arm64，补了三个 native 入口。`pluginrt` 依赖 `project(':qjsfork')`。

## 跑法

```bash
export ANDROID_SERIAL=<设备序列号>
./gradlew :pluginrt:connectedDebugAndroidTest
# 或手动（结果更稳，华为会吞 logcat）：
./gradlew :pluginrt:assembleDebug :pluginrt:assembleDebugAndroidTest
adb install -r pluginrt/build/outputs/apk/debug/pluginrt-debug.apk
adb install -r pluginrt/build/outputs/apk/androidTest/debug/pluginrt-debug-androidTest.apk
adb shell am instrument -w -e class com.dsh.android.pluginrt.QuickJsBenchTest \
  com.dsh.android.pluginrt.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/com.dsh.android.pluginrt/files/qjs-bench.txt
```

## 资源闸门（fork 新增）

```java
QuickJS rt = QuickJS.createRuntime();
rt.setMemoryLimit(16L * 1024 * 1024);   // runtime 堆上限，<=0 取消
rt.setMaxStackSize(512 * 1024);         // C 栈上限，0 用默认
rt.setTimeoutMs(300);                   // 从现在起 300ms 没跑完就中断，<=0 取消
```

- `setMemoryLimit` / `setMaxStackSize` 直接转 `JS_SetMemoryLimit` / `JS_SetMaxStackSize`。
- `setTimeoutMs` 走 `JS_SetInterruptHandler` + 单调时钟的截止时间（每 runtime 一个状态，
  在 `createRuntime` 建立、`releaseRuntime` 释放）。每次调用脚本前重新设置即可。
- 越限抛 `QuickJSException: InternalError: out of memory / interrupted`，可以被 Java 捕获；
  中断后同一个 runtime/context 仍可继续执行。

## 实测（JDY-AN00 / Android 14，2026-11-13，Debug native）

| 项目 | 结果 |
|---|---|
| 冷启动（runtime+context+首次 eval） | 11ms 首次，之后 1ms |
| 小脚本调用（1 万次） | 37µs/call |
| JS→Java 回调（1 万次） | 91µs/call（线性） |
| 新建 context（runtime 复用，100 次） | 917µs/call |
| 5KB 脚本编译+执行 | 686µs |
| ES2020 | 可选链 ✓ `??` ✓ `async` ✓ BigInt ✓ |
| `delete globalThis.Date` | 生效（`undefined`） |
| 8MB 上限 + 无限 push | 43ms 抛 `out of memory`，进程存活 |
| 300ms 超时 + `while(true){}` | 300ms 抛 `interrupted`，之后 `2+2` 正常 |

对比 Rhino（现状）：每次调用重建标准对象，冷路径几十~几百 ms；无内存/中断闸门；
1.7.15 缺可选链与 async。

> 这轮是 Debug native 构建（CMake 未开优化），release 会更快；绝对数值以 release 复测为准。

## 体积

arm64 两个 so：`libquickjs.so` ≈ 1.1MB + `libquickjs-android.so` ≈ 0.5MB（Debug，未压缩），
release strip 后小于此值；Java 侧仅几十 KB，无 Kotlin 依赖。

## 待办

- release 构建再测一次性能与体积（Debug 数字偏保守）
- 把 `qjsfork` 的 `.so` 纳入 app 打包（目前只在 spike 模块里）
- 用这套闸门替换 `HookEngine`（API 不变，只换执行层）
- 决定内存上限的默认值（按插件页可调？还是固定 16MB/32MB）
