package com.dsh.android.runtime;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Go 工具链的 proroot 兜底。
 *
 * pocketroot 是 LD_PRELOAD 路线：Go 的 go / compile / link 全是静态 ELF，不加载
 * shim，faccessat2(439) 直接撞 zygote seccomp（SIGSYS），路径也完全不翻译 ——
 * 任何 go build / run / vet 都编不了。
 *
 * proroot 走 ptrace，真机验证能兜住 faccessat2（库里有 faccessat2_trampoline），
 * 静态二进制也不再崩：proroot 下 go version 正常、go build 产物能运行。
 *
 * 但真机同时确认：proroot **不翻译静态二进制的绝对路径**（动态程序 cat / ls
 * 会翻译）。所以 Go 的 GOROOT / GOCACHE / GOPATH / TMPDIR 必须给**宿主绝对路径**，
 * 而用户命令里的路径仍是 guest 路径（shell 是动态的、会被翻译）。这个混用是
 * 有意为之，不是笔误。
 *
 * 已知代价：proroot 慢 3–7×、闭源、只认 glibc（当前 Debian trixie 满足）；
 * musl / Alpine 一律 failure rc=2。
 */
public final class ProrootFallback {

    /** 只对这些 Go 子命令兜底，其余仍走 pocketroot（快）。 */
    private static final String[] GO_SUBS = {
            "go build", "go run", "go test", "go vet", "go install",
            "go generate", "go mod ", "go get ", "go list", "go tool", "go clean",
    };
    private static final long MAX_OUTPUT = 256 * 1024L;

    private ProrootFallback() {
    }

    public static boolean needsFallback(String command) {
        if (command == null) return false;
        for (String sub : GO_SUBS) {
            if (command.contains(sub)) return true;
        }
        return false;
    }

    /** 用 proroot 跑一条命令；rootfsDir 直接复用 pocketroot 的 Debian rootfs。 */
    public static String run(Context ctx, File rootfsDir, File runDir,
                             String command, long timeoutMs) {
        File launcher = new File(ctx.getApplicationInfo().nativeLibraryDir, "libproroot.so");
        if (!launcher.isFile()) return "proroot launcher missing (libproroot.so)";

        String root = rootfsDir.getAbsolutePath();
        List<String> args = new ArrayList<>();
        args.add(launcher.getAbsolutePath());
        args.add("-r");
        args.add(root);
        args.add("-0");
        args.add("--link2symlink");
        for (String b : new String[]{"/proc:/proc", "/dev:/dev", "/sys:/sys"}) {
            args.add("-b");
            args.add(b);
        }
        args.add("-w");
        args.add("/");
        args.add("/bin/sh");
        args.add("-c");
        // Go 相关 env 必须在 **shell 内** export，不能放进 ProcessBuilder.environment()：
        // proroot 启动时会把继承环境里以 rootfs 前缀开头的路径「反翻译」回 guest
        // 路径（真机实测 TMPDIR=<root>/tmp 被改回 /tmp，Go 于是 stat 宿主的 /tmp
        // 报 "no such file or directory"），而 GOROOT 不在它的改写名单里、能保留。
        // 在 proroot 起来之后由 shell 自己 export，就不会被这层处理碰到了。
        args.add(goPrelude(root) + command);

        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.environment().put("PROROOT_TMP_DIR", runDir.getAbsolutePath());

            Process p = pb.start();
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try (InputStream is = p.getInputStream()) {
                    int n;
                    while ((n = is.read(chunk)) != -1) {
                        synchronized (buf) {
                            if (buf.size() > MAX_OUTPUT) break;
                            buf.write(chunk, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "proroot-fallback-reader");
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return "TIMEOUT after " + timeoutMs + " ms (proroot fallback)\n" + text(buf);
            }
            reader.join(2000);
            String out = text(buf);
            return (out.isEmpty() ? "(no output)" : out) + "\n[exit " + p.exitValue() + "]";
        } catch (Exception e) {
            return "Error: " + e;
        }
    }

    /** 宿主绝对路径的环境 + guest 路径的 shell 环境，两者混用是有意为之。 */
    private static String goPrelude(String root) {
        return "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                + "export HOME=/root LANG=C.UTF-8\n"
                + "export GOROOT=" + q(goRoot(root)) + "\n"
                + "export GOCACHE=" + q(root + "/root/.cache/go-build") + "\n"
                + "export GOPATH=" + q(root + "/root/go") + "\n"
                + "export TMPDIR=" + q(root + "/tmp") + "\n"
                // proroot 会在 execve 层把继承环境里的 TMPDIR 反翻译回 guest
                // 路径（/tmp），Go 于是去 stat 宿主的 /tmp。GOTMPDIR 是 Go 专用
                // 且不在 proroot 的改写名单里，优先级也高于 TMPDIR。
                + "export GOTMPDIR=" + q(root + "/tmp") + "\n"
                + "export GO111MODULE=auto\n";
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\''") + "'";
    }

    /** Debian 把 GOROOT 放在 /usr/lib/go-<version>；按目录名取最新的一个。 */
    private static String goRoot(String root) {
        File lib = new File(root, "usr/lib");
        String[] names = lib.list();
        String best = null;
        if (names != null) {
            for (String n : names) {
                if (n.startsWith("go-") && new File(lib, n + "/bin/go").isFile()
                        && (best == null || n.compareTo(best) > 0)) best = n;
            }
        }
        return best == null ? root + "/usr/lib/go" : root + "/usr/lib/" + best;
    }

    private static String text(java.io.ByteArrayOutputStream buf) {
        synchronized (buf) {
            return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
