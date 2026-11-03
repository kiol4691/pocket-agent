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
| APK / 安装体积 | **APK 仅 ~36 MB** | 100–500 MB+ | 桌面端，不适用 |
| 落盘占用 | ~493 MB（rootfs 488 MB） | 通常 1–3 GB | 不适用 |
| 常驻内存 | ~128 MB | 通常 300 MB+ | 不适用 |
| 需要 root | **否**（LD_PRELOAD，零 ptrace） | 否 | 否 |
| Android 8+ / arm64 | ✅ | ✅ | ❌ |

## 特性

- **原生 UI、零 WebView**：流式对话、工具调用卡片（`Bash` / `Edit` / `Filesystem`）、
  可折叠 `▸ Think` 思考卡、舒适字号的内联代码、真正的 Markdown 表格与 LaTeX 公式渲染
- **持久 Ubuntu Shell**：智能体写代码、跑测试、`apt-get install -y` 装包；
  会话跨对话保留，环境不丢
- **会话历史**：append-only JSONL 转录，重开会话完整回放（含工具输出与思考卡）；
  侧边栏按日期分组
- **OpenAI 兼容**：改 base URL + key 即可用 DeepSeek / OpenRouter / vLLM / one-api 等
  （密钥经 Android Keystore 加密存储）
- **内置 rootfs**：Debian 13 arm64 轻量根文件系统（约 350 MB）已打进 APK，自带
  python3 / git / curl / jq / numpy / requests / openpyxl / python-docx，首次 Setup 无需联网
- **手势友好**：边缘滑动开抽屉，无按钮可点错

## 截图与演示

*（即将补充：首屏、设置页、工具卡瀑布、侧边抽屉）*

## 快速开始

1. 在 **arm64 Android 8.0+** 设备安装 APK
2. 打开 **设置** → 粘贴 API key（DeepSeek 或任意 OpenAI 兼容端点，
   如 OpenRouter / vLLM / one-api 请改 `base_url`）→ 选择模型
3. 点 **Setup**：解压内置 Debian 13 rootfs（约 350 MB，免下载、免 root）。
   工具链已预装，没有 apt 安装阶段
4. 输入任务，点**发送**。智能体进入多轮循环：规划 → bash → 观察 → 迭代，
   每次工具调用以卡片形式渲染在对话流中

## 从源码构建

1. JDK 17 + Android SDK（compileSdk 35，minSdk 26）
2. 无需额外下载：shim（`libzng.so`）与 Debian rootfs（`assets/debian-rootfs-arm64.bin`）
   都在仓库里。旧的 proroot 二进制是可选的 —— 没有它只是 proroot 后端不可用
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
- 单循环 + 三工具（上游的 subagent / LSP / workflow / Web UI 未移植）
- 内置的 Debian rootfs 是**修改版**（打了 seccomp 补丁并加了修改版告知），不是 Debian 官方镜像；
  发行版名称与商标归各自权利人所有
- 旧的 proroot 后端仍留在仓库里，但默认没有任何路径依赖它
- 需要自带 API key 驱动模型，无内置推理

## 许可证

[MIT](LICENSE)——随附第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
（Markwon、JLaTeXMath、OkHttp、AndroidX、pocketroot shim（MIT）、Debian rootfs）。

*与 DeepSeek 无隶属或背书关系。「DeepSeek Harness」为 DeepSeek 的产品（MIT 协议）。*