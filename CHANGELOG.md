# Changelog

## v0.5.0 — 2026-11-08

首个可直接分发（signed release）的版本。运行时从 proroot 切到自研 **pocketroot**，不再需要闭源
`libproroot*.so`，也不再下载 Ubuntu rootfs。

### 运行时

- **pocketroot**（默认）：`LD_PRELOAD` shim `libzng.so` + Debian 13 arm64 rootfs，
  免 root、零 ptrace，path/bind 翻译、seccomp SIGSYS 模拟、SysV shm、unix socket 翻译。
- 内置 rootfs 改为 **分段 xz 资产**（48 MB），首启并行解码，无需联网下载。
- 修复 `mkdir -p` 落到 rootfs 影子副本：相对路径也会按 cwd/dirfd 反查并参与 bind 匹配
  （同时覆盖 `install -D`、`cp --parents`、`cd` 后的相对路径，以及 `/images`、`/sdcard` 等 bind 点）。
- 修复 `git init` 的 `mkdirat` 尾斜杠、`tar -C` 的 `split_parent` dirfd 丢失、`mkdtemp`、`linkat` 跨设备复制回退。

### 类 Docker 部署

- `pimg`：OCI 镜像生命周期（`pull / ls / info / patch / rm / run`），arm64 manifest 选择、
  whiteout 处理、硬链接回退，支持外部 Debian 系 glibc 镜像。
- `container` 工具：AI 可直接在镜像里执行命令。
- 真机验证过的服务：**PostgreSQL 16 / MySQL 8.0 / Redis 7**（含 TCP 与 unix socket、中文数据、重启持久化）。
  已知不支持：MariaDB（io_uring 触发 SIGSEGV）、Alpine/musl。

### 工具链

- dpkg/apt 打通：`ar` / `xz` / `zstd` 随包安装，`dpkg-deb`、`apt-get download`、
  `dpkg -i --force-not-root` 可用。
- 新增 `Read` / `Edit` / `Glob` / `Grep` / `WebFetch` / `WebSearch` / `ReadImage` / `AskUser` / `Container`。
- 长会话上下文压缩、CommandGuard 危险命令拦截、Rhino 插件钩子、前台保活服务。

### UI

- 顶栏对齐官方应用：双横线汉堡、细体标题、线性矢量图标（⚙ / ⊕），图标按钮 48dp 触摸区。
- 首页重做：垂直居中，`Into the Unknown` + 中文副标题 + 环境胶囊，去掉 Preview/工具名罗列。
- 动效：新行淡入、流式文字淡入、状态行淡出、连续贴底跟随（尊重用户回看）、侧栏悬停渐变。
- Markdown 标题倍数收窄（H1 2.0× → 1.25×）；修复 LaTeX 完全不可用（改走 `setMarkdown` 触发
  `AsyncDrawableScheduler`，块级 `$$` 可渲染，行内公式会升级为独立成行）。

### 分发

- `assembleRelease` 现在可产出签名包（`versionCode 10` / `0.5.0`）；
  lint 对 `targetSdk 28` 的过期检查已按功能需求关闭。
- **targetSdk 仍是 28**：Android 10+ 禁止 execve 应用数据目录里的文件（W^X），
  而 guest 的每条命令都在 rootfs 里，因此不能上架 Google Play，仅侧载分发。

### 已知限制

- 无 namespace / cgroup：容器只是文件系统与进程视图隔离，不是真 Docker。
- 端口需 ≥ 1024；服务随 app 进程退出。
- MariaDB 11 不可用（io_uring）；Alpine/musl 不可用。
- 行内 LaTeX 会独立成行（Markwon 4.6.2 的 inline latex 实测不工作）。
- 首次启动需解包 rootfs（约 25 s）。
