# Changelog

## v0.5.1 — 2026-11-09

在 v0.5.0 基础上修复 AI 实测反馈的一批环境与渲染问题，并在真机逐条验证。

### 运行时 / shim（pocketroot）

- 静态 ELF（无 PT_INTERP，如 Debian 的 `ldconfig`）改为直接 exec，不再误用未初始化的
  interp；libc-bin 的 postinst 因此不再卡在 half-configured（`dpkg --audit` 为空）。
- `readlink` 对 bind 目标（`/root/workspace`、`/images`、`/sdcard`）也反解，
  不再泄漏 `/data/user/0/...`；内部读满再反解，修掉 20 字节截断与 1 字节歧义。
- xattr 一族改为翻译路径后调用真实 syscall（`setxattr/getxattr/listxattr` 可用），
  仅把 ACL/SELinux 探测的 EACCES 映射成 ENOTSUP，避免 `ls -l` 权限串带 `?`。
- `inotify_add_watch` 补路径翻译，绝对路径不再 ENOENT。
- 新增 `zng_sem.c`：自己实现 `sem_open/sem_close/sem_unlink`，POSIX 命名信号量可用，
  `multiprocessing.Pool` / `ProcessPoolExecutor` 恢复（`/dev/shm` 建为可写目录）。
- `--library-path` 增加 `/usr/local/lib`，跨目录符号链接的程序（venv 的 `bin/python3`）
  不再因 `$ORIGIN` RUNPATH 找不到 `libpython3.13.so.1.0`。
- 硬链接：Android SELinux 禁止 app 数据目录建硬链接（无 shim 的 `ln` 同样 EACCES），
  复制回退保留但不再静默（写 zng.log + 一次 stderr 提示）。
- 宿主 wrapper `/system/bin/sh` 不再继承 guest 的 `LD_*`，装上 `libc6-dev` 后主 shell
  也不会 `CANNOT LINK EXECUTABLE`。

### 工具 / 环境

- 文件工具加挂载表：可读工作区外的绝对路径（`/etc/os-release`、`/tmp`、`/images`、
  `/sdcard`），宿主 app 私有路径反解为 guest 路径且不回显。
- `apt-get` 自动带 `--force-not-root`（`99-pocketroot`），`dpkg` 装完要求
  `dpkg --audit` + 实际执行二进制验证；`ldconfig` 包装脚本处理静态二进制路径。
- libc 常量改写让 `id -un` / `getent passwd` / `ls -l` 显示 `pocket` 而非数字 uid。
- 主 shell 的 `/etc/shm`、SysV shm（`zng_sysv.c`）等已验证源码一并入库。

### 应用

- 系统提示词按官方 harness 的模块化结构重写（一条规则一段、注入真实模型名、外部内容
  不可信声明）。
- 上下文压缩改为 60k token 估算触发，移除加载/窗口的双重截断，迭代中膨胀也会再压。

`make -C tests check` 235 项通过；release 为签名包（versionCode 11）。

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
