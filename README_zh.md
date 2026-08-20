# Pocket Agent · 口袋智能体

<p align="center"><strong>Android 上最轻量的端侧编码智能体</strong></p>
<p align="center"><a href="README.md">🇬🇧 English</a> · 🇨🇳 中文</p>

纯 Java 原生、完全跑在手机上的工具调用型 AI 智能体（minimal mode）。其 `bash` 工具通过
**proroot**（基于 LD_PRELOAD、零 ptrace 开销的免 root proot 替代品）在应用内运行完整的
**Ubuntu arm64** 用户态。无需 root、无 Node.js、无内嵌 WebView。

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
- **内置 rootfs**：Ubuntu 24.04 arm64 基础根文件系统已打进 APK
  （首次 Setup 无需下载；清华 / 中科大 / cdimage 镜像作后备）
- **手势友好**：边缘滑动开抽屉，无按钮可点错

## 截图与演示

*（即将补充：首屏、设置页、工具卡瀑布、侧边抽屉）*

## 快速开始

1. 在 **arm64 Android 8.0+** 设备安装 APK
2. 打开 **设置** → 粘贴 API key（DeepSeek 或任意 OpenAI 兼容端点，
   如 OpenRouter / vLLM / one-api 请改 `base_url`）→ 选择模型
3. 点 **Setup**：解压内置 Ubuntu rootfs（免下载、免 root），
   写入 `/etc/resolv.conf`，并安装 `bash coreutils git ca-certificates curl`
4. 输入任务，点**发送**。智能体进入多轮循环：规划 → bash → 观察 → 迭代，
   每次工具调用以卡片形式渲染在对话流中

## 从源码构建

1. JDK 17 + Android SDK（compileSdk 35，minSdk 26）
2. **放入 proroot 二进制**：仓库有意不内置 `libproroot*.so`
   （见 `app/src/main/jniLibs/arm64-v8a/README.txt`）。从
   [coderredlab/proroot releases](https://github.com/coderredlab/proroot/releases) 下载 5 个 `.so`
   放入 `app/src/main/jniLibs/arm64-v8a/`（缺了无法编译）
3. `./gradlew :app:assembleDebug`
4. 安装到 arm64 真机/模拟器

## 架构

```
MainActivity（原生 UI：对话流 + 工具卡片）
   └── AgentLoop              工具调用循环：模型 → 工具 → 结果 → 模型 …
        ├── ChatClient        OpenAI 兼容 chat-completions，SSE 流式，工具调用（OkHttp）
        ├── ToolRegistry
        │    ├── bash         运行于 Ubuntu / proroot（ProrootRuntime）
        │    ├── filesystem   应用工作区上的纯 Java NIO
        │    └── todo         内存待办清单
        ├── JsonlSessionLog   append-only JSONL 会话历史
        └── RuntimeManager    proroot 二进制 + Ubuntu rootfs 生命周期
```

应用私有存储布局：

```
files/run/        拷贝的 libproroot*.so（在此执行）
files/rootfs/     解压出的 Ubuntu arm64 基础 rootfs
files/workspace/  智能体工作区（Linux 内映射为 /root/workspace）
files/sessions/   *.jsonl 对话转录
```

bash 工具通过 `exec setsid` 启动
`libproroot.so -r rootfs -0 --link2symlink -b /proc -b /dev -b /sys
-b <workspace>:/root/workspace -w /root/workspace /bin/bash -c '<command>'`，
超时即杀整组进程，不留孤儿；stdout+stderr 合并输出并截断 256 KB，退出码附给模型。

## 已知限制

- **仅 arm64**（proroot 约束）；x86_64 模拟器不可用
- 单循环 + 三工具（上游的 subagent / LSP / workflow / Web UI 未移植）
- proroot 为**专有协议**：可免费使用，禁止再分发*修改过*的二进制；
  仓库按原样捆绑上游未经修改的二进制。详见 `THIRD_PARTY_NOTICES.md`
- 需要自带 API key 驱动模型，无内置推理

## 许可证

[MIT](LICENSE)——随附第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
（Markwon、JLaTeXMath、OkHttp、AndroidX、proroot、Ubuntu base）。

*与 DeepSeek 无隶属或背书关系。「DeepSeek Harness」为 DeepSeek 的产品（MIT 协议）。*