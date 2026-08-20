# Pocket Agent

<p align="center"><strong>超轻量端侧 AI 智能体 · The lightest on-device coding agent for Android</strong></p>

An independent, Java-native Android port in the spirit of [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness):
a tool-calling agent that runs entirely on your phone, with the `bash` tool executing a full **Ubuntu arm64** userspace
inside the app via **proroot** (a rootless, LD_PRELOAD-based `proot` replacement with zero ptrace overhead).

Target: **Android 8.0+ (API 26), arm64-v8a only** (proroot requirement). No root needed.

## Architecture

```
MainActivity (native UI, transcript + tool cards)
   └── AgentLoop                 tool-calling loop: model → tools → results → model …
        ├── DeepSeekClient       DeepSeek chat-completions, SSE streaming, tool calling (OkHttp)
        ├── ToolRegistry
        │    ├── bash            runs inside Ubuntu / proroot (ProrootRuntime)
        │    ├── filesystem      plain Java NIO on the app workspace
        │    └── todo            in-memory todo list
        ├── JsonlSessionLog      append-only JSONL session history
        └── RuntimeManager       proroot binaries + Ubuntu rootfs lifecycle
```

Layout inside the app's private storage (`getFilesDir()`):

```
files/run/        copied libproroot*.so (executed here)
files/rootfs/     extracted Ubuntu arm64 base rootfs
files/workspace/  agent workspace (mapped to /root/workspace inside Linux)
files/sessions/   *.jsonl transcripts
```

## How the bash tool works

```
PROROOT_TMP_DIR=<filesDir> \
  files/run/libproroot.so -r files/rootfs -0 --link2symlink \
    -b /proc -b /dev -b /sys \
    -b <workspace>:/root/workspace -w /root/workspace \
    /bin/bash -c '<command>'
```

Launched via `exec setsid …` so a timed-out call kills the whole process group
(`kill -9 -<pid>`), leaving no orphaned children. Output is merged stdout+stderr
with a 256 KB cap; the exit code is appended for the model.

## Building

1. Open in Android Studio (or run `./gradlew assembleDebug`).
2. **Add the proroot binaries**: download the 5 `.so` files from
   [coderredlab/proroot releases](https://github.com/coderredlab/proroot/releases) and place them in
   `app/src/main/jniLibs/arm64-v8a/`:

   - `libproroot.so`
   - `libproroot-runtime.so`
   - `libproroot-linker.so`
   - `libproroot-bridge.so`
   - `libproroot-stub-loader.so`

3. Build and install on an arm64 device / emulator (API 26+).

Prerequisites: JDK 17, Android SDK 34. The project does not compile without step 2.

## First run

1. Open **Settings** and paste your API key (DeepSeek or any OpenAI-compatible endpoint — change `base_url`
   for e.g. OpenRouter / vLLM / one-api), pick a model; the key is encrypted via Android Keystore.
2. Tap **Setup**. The Ubuntu `24.04` arm64 base rootfs is **bundled inside the APK**
   (`app/src/main/assets/ubuntu-base-arm64.bin`) and extracted in-app (no root needed, no download;
   Tsinghua / USTC / cdimage mirrors are listed as fallbacks in code). Setup writes `/etc/resolv.conf`
   for apt and installs `bash coreutils git ca-certificates curl`.
   Subsequent calls re-use the installed rootfs; add more packages from the agent itself via
   `apt-get install -y …` in the bash tool.
3. Type a task and hit **发送/Send**. The agent runs a multi-step loop with tool-call cards
   in the transcript.

## Notes / limitations

- proroot is **proprietary** (free to use, redistribution of modified binaries not permitted). Fine for personal
  builds; review before distributing publicly. Upstream proot (GPLv2) is a drop-in alternative if you
  prefer open source — swap `libproroot.so` for a static `proot` binary and adjust the flags.
- Only `arm64-v8a` is supported (proroot constraint). `x86_64` emulators will not work.
- V1 scope: single agent loop, three tools, one native UI. Upstream harness capabilities (persistent terminal,
  subagent, LSP, workflow, web UI) are not ported yet.
- The rootfs tarball (~30 MB) downloads once on first-run Setup; install on a wifi connection.