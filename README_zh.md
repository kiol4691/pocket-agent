# Pocket Agent · 口袋智能体

<p align="center"><strong>Android 上最轻量的端侧编码智能体</strong></p>
<p align="center"><a href="README.md">🇬🇧 English</a> · 🇨🇳 中文</p>

纯 Java 原生、完全跑在手机上的工具调用型 AI 智能体（minimal mode）。其 `bash` 工具通过
**pocketroot**（MIT 开源、基于 LD_PRELOAD 的路径翻译 shim，零 ptrace 开销）在应用内运行完整的
**Debian arm64** 用户态。无需 root、无 Node.js、无内嵌 WebView。

> 运行时后端：**pocketroot**（默认，随 APK 分发 —— Debian 13 + `libzng.so`）与 **proroot**
> （旧方案，需要闭源的 `libproroot*.so` 和 Ubuntu rootfs）。

**独立重写，非官方产物。** 灵感来自 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
（MIT 协议）的 minimal mode，但代码为从头编写的 Java 实现，与上游无共享代码。兼容任意
OpenAI 兼容端点（DeepSeek / OpenRouter / vLLM / one-api 等）。

## 为什么是最轻量

| | **Pocket Agent** | Termux 系移植壳 | 官方 Harness |
|---|---|---|---|
| 实现方式 | Java 原生应用 | Termux + Node + 脚本 | Node + 浏览器 |
| APK / 安装体积 | **APK 约 53 MB**（其中 48 MB 是内置 rootfs，release 构建） | 100–500 MB+ | 桌面端，不适用 |
| 落盘占用 | ~340 MB（rootfs 解包后 260 MB） | 通常 1–3 GB | 不适用 |
| 常驻内存 | **约 80 MB PSS**（全新安装；长会话后约 195 MB） | 通常 300 MB+ | 不适用 |
| 需要 root | **否**（LD_PRELOAD，零 ptrace） | 否 | 否 |
| Android 8+ / arm64 | ✅ | ✅ | ❌ |

## 特性

- **原生 UI、零 WebView**：流式对话、工具调用卡片（`Bash` / `Edit` / `Glob` / `Grep` / `Search` / `Fetch` / `Ask`）、
  可折叠 `▸ Think` 思考卡、舒适字号的内联代码、真正的 Markdown 表格与 LaTeX 公式渲染
- **钩子（Type B）**：把 `manifest.json` + `main.js` 丢进 `files/plugins/`；脚本跑在封闭的 Rhino 沙箱
  （无 Java、无文件、无网络），能否决或改写工具调用，失败按 **fail-closed** 拦下，启用前先自检
- **能上网查**：`web_search`（Bing RSS，不需要额外 key）找页面，`web_fetch` 把页面读成纯文本
- **长会话不会崩**：上下文超预算时把较早的对话压成一条摘要，而不是直接爆掉或悄悄截断
- **该问就问**：`ask_user_question` 弹一张问题卡片，等你拍板再做有风险或有歧义的操作
- **持久 Debian Shell**：智能体写代码、跑测试、`apt-get install -y` 装包；
  会话跨对话保留，环境不丢
- **会话历史**：append-only JSONL 转录，重开会话完整回放（含工具输出与思考卡）；
  侧边栏按日期分组
- **OpenAI 兼容**：改 base URL + key 即可用 DeepSeek / OpenRouter / vLLM / one-api 等
  （密钥经 Android Keystore 加密存储）
- **内置 rootfs**：Debian 13 arm64 轻量根文件系统（解包后约 260 MB）已打进 APK，自带
  python3 / git / curl / jq / numpy / requests / openpyxl / python-docx，首次 Setup 无需联网
- **手势友好**：边缘滑动开抽屉，无按钮可点错

## 截图与演示

*（即将补充：首屏、设置页、工具卡瀑布、侧边抽屉）*

## 快速开始

1. 在 **arm64 Android 8.0+** 设备安装 APK
2. 打开 **设置** → 粘贴 API key（DeepSeek 或任意 OpenAI 兼容端点，
   如 OpenRouter / vLLM / one-api 请改 `base_url`）→ 选择模型
3. 点 **Setup**：解压内置 Debian 13 rootfs（约 260 MB，免下载、免 root）。
   工具链已预装，没有 apt 安装阶段
4. 输入任务，点**发送**。智能体进入多轮循环：规划 → bash → 观察 → 迭代，
   每次工具调用以卡片形式渲染在对话流中

## 从源码构建

1. JDK 17 + Android SDK（compileSdk 35，minSdk 26）
2. 无需额外下载：shim（`libzng.so`、`libldso.so`）已在仓库里；Debian rootfs 由
   `tools/make-debian-rootfs.sh` 生成到 `assets/debian-rootfs-arm64.N.bin`（六段独立 xz，
   合计约 48 MB，首次启动时并行解码，首次解包 25 s → 9.9 s；段数由脚本里的 `PARTS` 决定，
   app 按 assets 里实际有几段就开几个线程；不入库，见 `.gitignore`）。旧的 proroot 二进制是可选的 —— 没有它只是 proroot 后端不可用，
   而且它现在改为按需下载 Ubuntu rootfs，不再随包分发
3. `./gradlew :app:assembleDebug`
4. 安装到 arm64 真机/模拟器

## 架构

```
MainActivity（原生 UI：对话流 + 工具卡片）
   └── AgentLoop              工具调用循环：模型 → 工具 → 结果 → 模型 …
        ├── ChatClient        OpenAI 兼容 chat-completions，SSE 流式，工具调用（OkHttp）
        ├── ToolRegistry
        │    ├── bash         运行于 Debian / pocketroot（PocketrootRuntime）
        │    ├── filesystem   应用工作区上的纯 Java NIO
        │    └── todo         内存待办清单
        ├── JsonlSessionLog   append-only JSONL 会话历史
        └── RuntimeManager    shim + Debian rootfs 生命周期（PocketrootRuntime / DebianRootfs）
```

应用私有存储布局：

```
files/rootfs/     解压出的 Debian 13 arm64 rootfs（随 APK 分发）
files/zng.log     shim 日志：路径翻译与 fail-visible 诊断
files/workspace/  智能体工作区（Linux 内映射为 /root/workspace）
files/sessions/   *.jsonl 对话转录
```

bash 工具通过 `exec setsid` 启动 `<rootfs>/lib/ld-linux-aarch64.so.1 /bin/bash -c '<command>'`，
并以 `LD_PRELOAD=<apk>/lib/arm64/libzng.so` + `ZNG_ROOT` / `ZNG_BINDS` 描述 rootfs 视图；
超时即杀整组进程，不留孤儿；stdout+stderr 合并输出并截断 256 KB，退出码附给模型。

## 已知限制

- **仅 arm64**（shim 按 aarch64 编译）；x86_64 模拟器不可用
- **targetSdk 刻意留在 28**：Android 10 起禁止 execve app 数据目录下的文件（W^X），
  而 guest 的每条命令都在 rootfs 里；pocketroot 走真实 execve，绕不过去。
  代价是只能侧载，不能上 Google Play
- 单循环 + 三工具（上游的 subagent / LSP / workflow / Web UI 未移植）
- 内置的 Debian rootfs 是**修改版**（打了 seccomp 补丁并加了修改版告知），不是 Debian 官方镜像；
  发行版名称与商标归各自权利人所有
- 旧的 proroot 后端仍留在仓库里，但默认没有任何路径依赖它
- 需要自带 API key 驱动模型，无内置推理

## 许可证

[MIT](LICENSE)——随附第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
（Markwon、JLaTeXMath、OkHttp、AndroidX、pocketroot shim（MIT）、Debian rootfs）。

*与 DeepSeek 无隶属或背书关系。「DeepSeek Harness」为 DeepSeek 的产品（MIT 协议）。*