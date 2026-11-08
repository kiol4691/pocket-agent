package com.dsh.android.runtime;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs bash commands inside the Ubuntu rootfs using proroot (the LD_PRELOAD-based,
 * zero-ptrace proot replacement). Requires arm64-v8a and Android 8.0+.
 *
 * The launcher {@code libproroot.so} and its companions are exec'd straight from the APK
 * native library directory ({@code /data/app/.../lib/arm64}, SELinux type {@code apk_data_file}).
 * That is the only app-accessible location where exec() is still allowed on Android 10+
 * (targetSdk 29+): executing a binary that lives in the app's own writable data directory
 * ({@code app_data_file}) is blocked by the W^X hardening introduced in Android 10.
 *
 * Commands are launched via {@code exec setsid ...} so we can kill the whole process group
 * on timeout, avoiding orphaned children.
 */
public class ProrootRuntime {

    /* 启动器是这一层唯一的可变量：proroot 用一个可执行的 launcher，pocketroot 用
     * 「ld.so + LD_PRELOAD=libzng.so」。三个钩子（launcherFile / launchCommand /
     * applyLauncherEnv）把差异收干，会话、超时、进程组回收等逻辑两边共用。 */

    public static final long MAX_OUTPUT_BYTES = 256 * 1024L;

    /* Persistent bash session (minimal-mode "persistent bash"): one long-lived proroot bash keeps
     * cwd/env/state across tool calls. Each call writes the command then a unique marker echo line;
     * the reader thread returns the output accumulated up to the marker. On per-call timeout the
     * whole session process group is killed and restarted lazily on the next call. */
    /**
     * 持久 shell 的启动前置脚本：**在 shell 里**拦住"删掉 rootfs 骨架"的 rm。
     *
     * 为什么必须放在 shell 里：`cd / && rm -rf *` 的真实目标由 shell 展开，静态分析
     * 只看到字面量 `*`，永远猜不准；而函数拿到的是**展开之后**的参数，逐个 `pwd -P`
     * 解析成真实路径再比对，才是可靠的。
     *
     * 保护范围：根目录本身 + 所有顶层目录 + /root（家目录）。`rm -rf /root/workspace/x`、
     * `rm -rf /var/lib/apt/lists/*` 这类正常清理不受影响。
     *
     * 边界：它只是"减速带"。`command rm`、`\rm`、`sh -c 'rm ...'`（dash 没有这个函数）、
     * 静态二进制仍能绕过 —— 真正的兜底是工具层检测到 rootfs 坏了之后的自愈。
     */
    private static final String GUARD_PRELUDE =
            "__pkr_guard_rm() {\n"
            + "  local a p\n"
            + "  for a in \"$@\"; do\n"
            + "    case \"$a\" in -*) continue;; esac\n"
            + "    if [ -d \"$a\" ] && [ ! -L \"$a\" ]; then\n"
            + "      p=$(cd -- \"$a\" 2>/dev/null && pwd -P) || p=\"$a\"\n"
            + "    else\n"
            + "      p=\"$a\"\n"
            + "    fi\n"
            + "    case \"$p\" in\n"
            + "      /|/bin|/boot|/dev|/etc|/home|/lib|/lib64|/media|/mnt|/opt|/proc|/root|/root/workspace"
            + "|/run|/sbin|/sdcard|/srv|/storage|/sys|/usr|/var)\n"
            + "        printf 'rm: 拒绝删除受保护目录 %s（Pocket Agent 护栏）\\n' \"$p\" >&2; return 1;;\n"
            + "    esac\n"
            + "  done\n"
            + "  command rm \"$@\"\n"
            + "}\n"
            + "rm() { __pkr_guard_rm \"$@\"; }\n"
            + "export -f __pkr_guard_rm rm 2>/dev/null || true\n";

    private static final long PB_DEFAULT_TIMEOUT_MS = 300_000L;
    private final Object pbLock = new Object();
    private Process pbProc;
    private java.io.Writer pbIn;
    private long pbPid;
    private String pbMarker;
    private StringBuilder pbOut;
    private boolean pbWait;
    private boolean pbDone;
    /** 新起的 shell 还没灌过护栏前置脚本。 */
    private volatile boolean pbNeedsGuard = true;
    private String pbRc;
    private volatile boolean pbDead = true;

    protected final File runDir;
    protected final File rootfsDir;
    protected final File workspaceDir;
    protected final File tmpDir;
    protected final File nativeLibDir;

    private static final String RESOLV_CONF =
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 114.114.114.114\n"
                    + "options timeout:2 attempts:2 rotate\n";
    private volatile boolean netTuned;

    private final Context appContext;

    public ProrootRuntime(Context context) {
        this.appContext = context.getApplicationContext();
        File filesDir = context.getFilesDir();
        this.runDir = new File(filesDir, "run");
        this.rootfsDir = new File(filesDir, "rootfs");
        this.workspaceDir = new File(filesDir, "workspace");
        this.tmpDir = filesDir;
        this.nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public File rootfsDir() {
        return rootfsDir;
    }

    public File workspaceDir() {
        return workspaceDir;
    }

    /**
     * True when the user granted All-Files-Access (MANAGE_EXTERNAL_STORAGE). Only then can the
     * app process stat/read /storage/emulated/0 (Huawei's scoped storage denies even statfs
     * without it), so that's when we bind the shared storage into the rootfs at /sdcard.
     * Without it, Ubuntu still sees the phone's /data partition via {@code df -h /} because the
     * rootfs lives on /data.
     */
    public static boolean storageAccessGranted() {
        return android.os.Environment.isExternalStorageManager();
    }

    /** Bind points for the phone's shared storage, added only when All-Files-Access is granted. */
    private static final String HOST_STORAGE = "/storage/emulated/0";
    private static final String GUEST_STORAGE = "/sdcard";
    /** Rootfs-relative dir backing the guest mount point (a leading '/' would escape rootfsDir). */
    private static final String GUEST_STORAGE_REL = "sdcard";

    public void ensureDirectories() throws IOException {
        for (File d : new File[]{runDir, rootfsDir, workspaceDir}) {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot create directory " + d);
        }
        // 当前 Android uid 不在 /etc/passwd 时，stat %U / id -un 会显示 UNKNOWN；
        // 每次起会话前补一条（幂等，已存在就不动）。
        ContainerRunner.ensureAccount(rootfsDir);
        GuestTools.install(appContext, rootfsDir);
    }

    /** Verify the proroot launcher and its companions exist in the APK native library dir. */
    public synchronized void verifyBinaries() throws IOException {
        ensureDirectories();
        File launcher = launcherFile();
        if (!launcher.isFile()) {
            throw new IOException(launcherName() + " libraries not found in " + nativeLibDir
                    + ". Drop the 5 libproroot*.so files from the proroot release "
                    + "into app/src/main/jniLibs/arm64-v8a/ and rebuild, then retry setup.");
        }
    }

    public boolean binariesInstalled() {
        return launcherFile().isFile();
    }

    /** 启动器文件：proroot 是可执行的 launcher 本体，pocketroot 是 shim 的 .so。 */
    protected File launcherFile() {
        return new File(nativeLibDir, "libproroot.so");
    }

    /** 出错提示里显示的名字。 */
    protected String launcherName() {
        return "proroot";
    }

    /** 构造完整 argv（含启动器自身、bind、工作目录与 shell）。 */
    protected List<String> launchCommand(String shell) {
        List<String> args = new ArrayList<>();
        args.add(launcherFile().getAbsolutePath());
        args.add("-r");
        args.add(rootfsDir.getAbsolutePath());
        args.add("-0");
        args.add("--link2symlink");
        args.add("-b");
        args.add("/proc:/proc");
        args.add("-b");
        args.add("/dev:/dev");
        args.add("-b");
        args.add("/sys:/sys");
        args.add("-b");
        args.add(workspaceDir.getAbsolutePath() + ":/root/workspace");
        if (storageAccessGranted()) {
            File guestSdcard = new File(rootfsDir, GUEST_STORAGE_REL);
            if (guestSdcard.isDirectory() || guestSdcard.mkdirs()) {
                args.add("-b");
                args.add(HOST_STORAGE + ":" + GUEST_STORAGE);
            }
        }
        args.add("-w");
        args.add("/root/workspace");
        args.add(shell);
        return args;
    }

    /** 启动器需要的环境变量。 */
    protected void applyLauncherEnv(java.util.Map<String, String> env) {
        env.put("PROROOT_TMP_DIR", tmpDir.getAbsolutePath());
    }

    /**
     * 进程初始工作目录（null = 沿用调用方 cwd）。
     *
     * proroot 用 {@code -w /root/workspace} 指定 guest cwd；pocketroot 的 shim 对相对
     * 路径是原样透传、由内核按真实 cwd 解析，所以必须把进程 cwd 设到 rootfs 里对应的
     * 目录，否则相对路径会落到 Android 的 / 上。
     */
    protected File launchCwd() {
        return null;
    }

    /** Run a bash command; returns combined stdout+stderr (capped) with the exit code appended. */
    public synchronized String runBash(String command, long timeoutMs) {
        try {
            return runInternal(command, timeoutMs, "/bin/bash");
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Run via /bin/sh (dash). Bash needs libtinfo6 which is not in the base rootfs, so
     * first-time apt bootstrap must go through dash. */
    public synchronized String runSh(String command, long timeoutMs) {
        try {
            return runInternal(command, timeoutMs, "/bin/sh");
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Run a command in the shared persistent bash session; state (cwd, exports, packages) persists
     * across calls. Timeout kills and restarts the session. */
    public synchronized String runBashPersistent(String command, long timeoutMs) {
        try {
            return runPersistentInternal(command, timeoutMs);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Abort an in-flight command: kill the persistent bash process group so the blocked
     * caller returns immediately. The session restarts lazily on the next call. */
    public void abortWork() {
        killPersistentBash();
    }

    private String runPersistentInternal(String command, long timeoutMs) throws IOException {
        if (command.trim().isEmpty()) return "Error: empty command";
        if (timeoutMs <= 0) timeoutMs = PB_DEFAULT_TIMEOUT_MS;
        ensurePersistentBash();

        StringBuilder out = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean done, timedOut;
        synchronized (pbLock) {
            pbOut = out;
            pbRc = null;
            pbDone = false;
            pbWait = true;
            try {
                // 新 shell 的第一条命令前先灌护栏（见 GUARD_PRELUDE）。
                if (pbNeedsGuard) {
                    pbIn.write(GUARD_PRELUDE);
                    pbNeedsGuard = false;
                }
                // 命令包进 { } 并把 stdin 接到 /dev/null（v0.1.2 那条线的修法，我们这条线一直没有）：
                // 读 stdin 的命令（adb pair / sudo / ssh / read / 交互式提示）会把排队中的
                // 结束标记当成输入吃掉，于是这次调用挂到超时；常驻子进程（adb fork-server）
                // 更会吃掉之后所有标记，表现为所有调用永远 "Running"。
                // 用花括号而不是圆括号：花括号在当前 shell 里执行，cd / export 仍然跨调用保留。
                pbIn.write("{\n");
                pbIn.write(command);
                pbIn.write("\n} </dev/null\n");
                // 结束标记必须**自成一行**：命令的最后一段输出可能没有换行
                // （printf abc / echo -n / 进度条用 \r 刷新），直接 echo 标记会拼成
                // "abc__DSH_RC_x__=0"，读取端按行首匹配就永远等不到标记 —— 表现就是
                // 一条 1 ms 的命令被报成 TIMEOUT。所以先输出一个换行再打标记。
                // 多出来的空行在返回前被 trim 掉，不影响结果。
                pbIn.write("printf '\\n%s=%s\\n' '");
                pbIn.write(pbMarker);
                pbIn.write("' \"$?\"\n");
                pbIn.flush();
            } catch (IOException e) {
                pbWait = false;
                pbDead = true;
                return "Error: could not write to persistent shell: " + e.getMessage();
            }
            while (!pbDone && System.currentTimeMillis() < deadline) {
                try {
                    pbLock.wait(Math.max(20L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            done = pbDone;
            timedOut = !done;
            pbWait = false;
        }

        String text = out.toString().trim();
        // 自愈提示：命令把 rootfs 关键文件删了（rm -rf / 之类）时，别让后续调用一头雾水。
        if (done && !new File(rootfsDir, "bin/bash").isFile()) {
            killPersistentBash();
            return (text.isEmpty() ? "(no output)" : text)
                    + "\n[⚠ rootfs 已被破坏：bin/bash 不存在。重启应用会自动重新解包（约 12 秒）。]";
        }
        if (done && pbRc != null) {
            String rc = pbRc.startsWith("=") ? pbRc.substring(1) : pbRc;
            return (text.isEmpty() ? "(no output)" : text) + "\n[exit code: " + rc + "]";
        }
        if (done) {
            killPersistentBash();
            return (text.isEmpty() ? "(no output)" : text) + "\n[persistent shell exited; it will restart on the next call]";
        }
        killPersistentBash();
        return "TIMEOUT after " + timeoutMs + " ms; the persistent shell was killed and restarts on the next call.\n"
                + (text.isEmpty() ? "(no output)" : text);
    }

    /** Start the loopback proxy and (once per process) tune the rootfs network files: CN-only
     * DNS with fast retries, apt/pip/git proxy wiring, proxy env exports for the shell. */
    private void ensureNetTuning() {
        LocalProxy.ensure();
        int p = LocalProxy.port();
        if (netTuned || p <= 0) return;
        java.nio.file.Path rcPath = new File(rootfsDir, "etc/resolv.conf").toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(rcPath)) {
                java.nio.file.Files.delete(rcPath);
            }
        } catch (IOException ignored) {
        }
        writeIfChanged(new File(rootfsDir, "etc/resolv.conf"), RESOLV_CONF);
        writeIfChanged(new File(rootfsDir, "etc/apt/apt.conf.d/99-dsh-proxy"),
                "Acquire::http::Proxy \"http://127.0.0.1:" + p + "\";\n"
                        + "Acquire::https::Proxy \"http://127.0.0.1:" + p + "\";\n");
        writeIfChanged(new File(rootfsDir, "etc/pip.conf"),
                "[global]\nproxy = http://127.0.0.1:" + p + "\n");
        writeIfChanged(new File(rootfsDir, "etc/profile.d/dsh-proxy.sh"),
                "export http_proxy=\"http://127.0.0.1:" + p + "\"\n"
                        + "export https_proxy=\"http://127.0.0.1:" + p + "\"\n"
                        + "export HTTP_PROXY=\"$http_proxy\"\n"
                        + "export HTTPS_PROXY=\"$https_proxy\"\n"
                        + "export no_proxy=\"localhost,127.0.0.1,::1\"\n"
                        + "export NO_PROXY=\"$no_proxy\"\n");
        File gitcfg = new File(rootfsDir, "root/.gitconfig");
        try {
            byte[] old = gitcfg.isFile()
                    ? java.nio.file.Files.readAllBytes(gitcfg.toPath()) : new byte[0];
            String cur = new String(old, java.nio.charset.StandardCharsets.UTF_8);
            if (!cur.contains("dsh-local-proxy")) {
                String body = cur
                        + (cur.endsWith("\n") || cur.isEmpty() ? "" : "\n")
                        + "# dsh-local-proxy\n"
                        + "[http]\n\tproxy = http://127.0.0.1:" + p + "\n"
                        + "[https]\n\tproxy = http://127.0.0.1:" + p + "\n";
                gitcfg.getParentFile().mkdirs();
                java.nio.file.Files.write(gitcfg.toPath(),
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
        netTuned = true;
    }

    private static void writeIfChanged(File f, String content) {
        try {
            f.getParentFile().mkdirs();
            byte[] cur = f.isFile() ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
            if (cur == null || !content.equals(new String(cur, java.nio.charset.StandardCharsets.UTF_8))) {
                java.nio.file.Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private static void addProxyEnv(java.util.Map<String, String> env) {
        int p = LocalProxy.port();
        if (p <= 0) return;
        String px = "http://127.0.0.1:" + p;
        env.put("http_proxy", px);
        env.put("https_proxy", px);
        env.put("HTTP_PROXY", px);
        env.put("HTTPS_PROXY", px);
        env.put("no_proxy", "localhost,127.0.0.1,::1");
        env.put("NO_PROXY", "localhost,127.0.0.1,::1");
    }

    /** Spawn the single persistent bash (setsid'd so the whole group can be killed on timeout). */
    private void ensurePersistentBash() throws IOException {
        if (!pbDead && pbProc != null && pbProc.isAlive()) return;
        ensureNetTuning();
        ensureDirectories();
        File launcher = launcherFile();
        if (!launcher.isFile()) throw new IOException(launcherName() + " not installed; run runtime setup first.");
        if (!rootfsDir.isDirectory()) throw new IOException("rootfs not installed; run runtime setup first.");

        File guestWorkspace = new File(rootfsDir, "root/workspace");
        if (!guestWorkspace.isDirectory() && !guestWorkspace.mkdirs()) {
            throw new IOException("Cannot create " + guestWorkspace);
        }

        List<String> args = launchCommand("/bin/bash");
        String quoted = q(args);

        File pidFile = new File(runDir, "persistent.pid");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                "printf '%s\\n' \"$$\" > " + shq(pidFile.getAbsolutePath()) + "; exec setsid " + quoted);
        pb.redirectErrorStream(true);
        File cwd = launchCwd();
        if (cwd != null) pb.directory(cwd);
        java.util.Map<String, String> env = pb.environment();
        applyLauncherEnv(env);
        env.put("HOME", "/root");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        addProxyEnv(env);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("Error launching persistent proroot bash: " + e.getMessage());
        }
        long pid = readPidSoon(pidFile);

        pbProc = proc;
        pbPid = pid;
        pbDead = false;
        pbNeedsGuard = true;
        pbMarker = "__DSH_RC_" + Integer.toHexString((int) (Math.random() * 0xffffff)) + "__";
        pbIn = new java.io.OutputStreamWriter(proc.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
        startPersistentReader(proc);
    }

    private void startPersistentReader(Process proc) {
        Thread t = new Thread(() -> {
            InputStream is = proc.getInputStream();
            byte[] line = new byte[16384];
            int used = 0;
            try {
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\n') {
                        deliverPersistentLine(proc, line, used);
                        used = 0;
                    } else if (used < line.length) {
                        line[used++] = (byte) c;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
                synchronized (pbLock) {
                    // 世代检查：这条线程可能是上一次 TIMEOUT 后被 kill 的旧 shell，
                    // 它的 finally 迟到时会踩掉新 shell 的状态（pbDead/pbDone 是共享字段），
                    // 表现是下一条命令莫名报 "persistent shell exited" 或丢掉 cwd/变量。
                    if (pbProc != proc) return;
                    if (used > 0) {
                        String s = new String(line, 0, used, java.nio.charset.StandardCharsets.UTF_8);
                        if (pbWait && !s.startsWith(pbMarker)) appendCappedLine(pbOut, s);
                        else if (pbWait && s.startsWith(pbMarker)) {
                            pbRc = s.substring(pbMarker.length());
                            pbDone = true;
                        }
                    }
                    pbDead = true;
                    pbDone = true;
                    pbLock.notifyAll();
                }
            }
        }, "proroot-persistent-reader");
        t.setDaemon(true);
        t.start();
    }

    private void deliverPersistentLine(Process proc, byte[] line, int used) {
        String s = new String(line, 0, used, java.nio.charset.StandardCharsets.UTF_8);
        synchronized (pbLock) {
            if (pbProc != proc || !pbWait) return;
            if (s.startsWith(pbMarker)) {
                pbRc = s.substring(pbMarker.length());
                pbDone = true;
                pbLock.notifyAll();
            } else {
                appendCappedLine(pbOut, s);
            }
        }
    }

    private static void appendCappedLine(StringBuilder out, String line) {
        if (out.length() >= MAX_OUTPUT_BYTES) return;
        int allowed = (int) Math.min(line.length(), MAX_OUTPUT_BYTES - out.length());
        out.append(line, 0, allowed);
        if (out.length() >= MAX_OUTPUT_BYTES) out.append("\n…[output truncated]");
        out.append('\n');
    }

    private void killPersistentBash() {
        if (pbPid > 0) killProcessGroup(pbPid);
        if (pbProc != null) {
            try {
                pbProc.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        try {
            if (pbIn != null) pbIn.close();
        } catch (IOException ignored) {
        }
        pbProc = null;
        pbPid = 0;
        pbDead = true;
    }

    private String runInternal(String command, long timeoutMs, String shell) throws IOException {
        ensureNetTuning();
        ensureDirectories();
        File launcher = launcherFile();
        if (!launcher.isFile()) throw new IOException(launcherName() + " not installed; run runtime setup first.");
        if (!rootfsDir.isDirectory()) throw new IOException("rootfs not installed; run runtime setup first.");

        File guestWorkspace = new File(rootfsDir, "root/workspace");
        if (!guestWorkspace.isDirectory() && !guestWorkspace.mkdirs()) {
            throw new IOException("Cannot create " + guestWorkspace);
        }

        List<String> args = launchCommand(shell);
        args.add("-c");
        args.add(command);
        String quoted = q(args);

        // exec setsid: the launcher keeps sh's pid, becomes session+group leader, and the
        // whole group (bash + descendants) can be killed on timeout with kill -9 -<pid>.
        // The launcher pid is captured in a pidfile by the wrapper shell (Process.pid()
        // needs core library desugaring and is deliberately avoided).
        File pidFile = new File(runDir, "last.pid");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                "printf '%s\\n' \"$$\" > " + shq(pidFile.getAbsolutePath()) + "; exec setsid " + quoted);
        pb.redirectErrorStream(true);
        File cwd = launchCwd();
        if (cwd != null) pb.directory(cwd);
        java.util.Map<String, String> env = pb.environment();
        applyLauncherEnv(env);
        env.put("HOME", "/root");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        addProxyEnv(env);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return "Error launching proroot: " + e.getMessage();
        }
        long pid = readPidSoon(pidFile);

        StringBuilder out = new StringBuilder();
        AtomicBoolean done = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (InputStream is = proc.getInputStream()) {
                int n;
                while ((n = is.read(buf)) != -1) appendCapped(out, buf, n);
            } catch (IOException ignored) {
            } finally {
                done.set(true);
            }
        }, "proroot-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean timedOut = false;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                timedOut = true;
                break;
            }
        }
        if (!done.get()) timedOut = true;

        long grace = Math.min(timeoutMs, 30_000L);
        if (timedOut) {
            proc.destroy();
            try {
                proc.waitFor(grace, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            if (proc.isAlive()) {
                proc.destroyForcibly();
                if (pid > 0) killProcessGroup(pid);
            }
        } else {
            try {
                proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int exit = proc.exitValue();
        StringBuilder result = new StringBuilder();
        if (timedOut) result.append("TIMEOUT after ").append(timeoutMs).append(" ms; process group was killed.\n");
        final String text;
        synchronized (out) {
            text = out.toString().trim();
        }
        result.append(text.isEmpty() ? "(no output)" : text);
        result.append("\n[exit code: ").append(exit).append("]");
        return result.toString();
    }

    private static void appendCapped(StringBuilder out, byte[] buf, int n) {
        synchronized (out) {
            if (out.length() < MAX_OUTPUT_BYTES) {
                int allowed = (int) Math.min(n, MAX_OUTPUT_BYTES - out.length());
                out.append(new String(buf, 0, allowed, java.nio.charset.StandardCharsets.UTF_8));
                if (out.length() >= MAX_OUTPUT_BYTES) {
                    out.append("\n…[output truncated]");
                }
            }
        }
    }

    private static void killProcessGroup(long pid) {
        runQuiet(new String[]{"/system/bin/toybox", "kill", "-9", "-" + pid});
        runQuiet(new String[]{"/system/bin/sh", "-c", "kill -9 -" + pid + " 2>/dev/null"});
    }

    private static void runQuiet(String[] cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static long readPidSoon(File pidFile) {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            String s = readFirstLine(pidFile);
            if (s != null) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    private static String readFirstLine(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return r.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String q(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(shq(a));
        }
        return sb.toString();
    }
}