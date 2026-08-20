# Pocket Agent

<p align="center"><strong>The lightest on-device coding agent for Android</strong></p>
<p align="center">🇬🇧 English · <a href="README_zh.md">🇨🇳 中文</a></p>

A Java-native, tool-calling AI agent that runs entirely on your phone. Its `bash` tool executes a full
**Ubuntu arm64** userspace inside the app via **proroot** — a rootless, LD_PRELOAD-based `proot` replacement
with zero ptrace overhead. No root, no Node, no embedded webview.

**Independent re-implementation.** Inspired by the minimal mode of
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT), but written from scratch in Java —
no shared code. Works with any OpenAI-compatible endpoint (DeepSeek, OpenRouter, vLLM, one-api, …).

## Why it's the lightest

| | **Pocket Agent** | Termux-based shells | Official harness |
|---|---|---|---|
| Runtime approach | Java native apps | Termux + Node + scripts | Node + browser |
| APK / install size | **~36 MB APK** | 100–500 MB+ | n/a (desktop) |
| On-disk footprint | ~493 MB (rootfs 488 MB) | 1–3 GB typical | n/a |
| RAM (idle) | ~128 MB | 300 MB+ typical | n/a |
| Root | **No** (LD_PRELOAD, zero ptrace) | No | No |
| Android 8+ / arm64 | ✅ | ✅ | ❌ |

## Features

- **Native UI, zero webview** — streaming chat, tool-call cards (`Bash` / `Edit` / `Filesystem`),
  collapsible `▸ Think` cards, inline code at comfortable size, real Markdown tables & LaTeX math
- **Persistent Ubuntu shell** — the agent writes code, runs tests, installs packages via
  `apt-get install -y …`; the session survives across conversations
- **Session history** — append-only JSONL transcripts, replayed faithfully on reopen
  (tool outputs + thinking cards included); sidebar groups by date
- **OpenAI-compatible** — swap base URL + key for DeepSeek / OpenRouter / vLLM / one-api / …
  (key encrypted via Android Keystore)
- **Built-in rootfs** — Ubuntu 24.04 arm64 base is bundled inside the APK (no first-run download;
  Tsinghua / USTC / cdimage mirrors as fallbacks)
- **Gesture-friendly** — edge-swipe drawer, no buttons to hunt

## Screenshots & demo

*(Coming soon — hero screen, settings, tool-call cards, side drawer)*

## Getting started

1. Install the APK on an **arm64 Android 8.0+** device
2. Open **Settings** → paste your API key (DeepSeek or any OpenAI-compatible endpoint,
   change `base_url` for OpenRouter / vLLM / one-api) → pick a model
3. Tap **Setup**: extracts the bundled Ubuntu rootfs (no download, no root),
   writes `/etc/resolv.conf`, installs `bash coreutils git ca-certificates curl`
4. Type a task and hit **Send**. The agent runs a multi-step loop: plan → bash → observe → iterate,
   each tool call rendered as a card in the transcript

## Building from source

1. JDK 17 + Android SDK (compileSdk 35, minSdk 26)
2. **Add the proroot binaries**: the repo intentionally does *not* ship `libproroot*.so`
   (see `app/src/main/jniLibs/arm64-v8a/README.txt`). Download the 5 `.so` files from
   [coderredlab/proroot releases](https://github.com/coderredlab/proroot/releases) and place them in
   `app/src/main/jniLibs/arm64-v8a/` (the build does not compile without them)
3. `./gradlew :app:assembleDebug`
4. Build & install on an arm64 device / emulator

## Architecture

```
MainActivity (native UI, transcript + tool cards)
   └── AgentLoop              tool-calling loop: model → tools → results → model …
        ├── ChatClient        OpenAI-compatible chat-completions, SSE streaming, tool calls (OkHttp)
        ├── ToolRegistry
        │    ├── bash         runs inside Ubuntu / proroot (ProrootRuntime)
        │    ├── filesystem   plain Java NIO on the app workspace
        │    └── todo         in-memory todo list
        ├── JsonlSessionLog   append-only JSONL session history
        └── RuntimeManager    proroot binaries + Ubuntu rootfs lifecycle
```

App-private storage layout:

```
files/run/        copied libproroot*.so (executed here)
files/rootfs/     extracted Ubuntu arm64 base rootfs
files/workspace/  agent workspace (mapped to /root/workspace inside Linux)
files/sessions/   *.jsonl transcripts
```

The bash tool launches `libproroot.so -r rootfs -0 --link2symlink -b /proc -b /dev -b /sys
-b <workspace>:/root/workspace -w /root/workspace /bin/bash -c '<command>'` via `exec setsid`,
so a timed-out call kills the whole process group — no orphaned children. Output is merged
stdout+stderr with a 256 KB cap, exit code appended for the model.

## Limitations

- **arm64 only** (proroot constraint); x86_64 emulators will not work
- Single agent loop, three tools (upstream harness's subagent / LSP / workflow / web UI are not ported)
- proroot is **proprietary** — free to use, redistribution of *modified* binaries not permitted;
  unmodified upstream binaries are bundled as-is. See `THIRD_PARTY_NOTICES.md`
- Building a model requires your own API key; no bundled inference

## License

[MIT](LICENSE) — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for bundled third-party works
(Markwon, JLaTeXMath, OkHttp, AndroidX, proroot, Ubuntu base).

*Not affiliated with or endorsed by DeepSeek. "DeepSeek Harness" is a product of DeepSeek (MIT).*