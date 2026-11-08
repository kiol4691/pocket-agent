package com.dsh.android.runtime;

import android.content.Context;

import com.dsh.android.util.DebugLog;
import com.dsh.android.util.IOUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在某个 OCI 镜像里跑一条命令（docker run 的最小等价物）。
 *
 * 和正常会话的区别只有一个：ZNG_ROOT 指向 files/oci/&lt;name&gt;/rootfs，
 * 并且用**镜像自己的** loader 启动 —— loader 与 libc 必须同源，混用会 SIGILL
 * （2.41 的 libldso.so + 2.36 的 libc 实测 exit=132）。
 *
 * 镜像自带的 libc 在 pimg pull 时已经打好 seccomp 补丁（见 patch-glibc-syscalls.py）。
 */
public final class ContainerRunner {

    private static final int MAX_OUTPUT = 200_000;

    private ContainerRunner() {
    }

    public static File rootfsDir(Context ctx, String image) {
        return new File(ctx.getFilesDir(), "oci/" + image + "/rootfs");
    }

    public static boolean exists(Context ctx, String image) {
        return rootfsDir(ctx, image).isDirectory();
    }

    public static String list(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "oci");
        File[] items = dir.listFiles();
        if (items == null || items.length == 0) return "(no images yet; run: pimg pull <repo>:<tag>)";
        StringBuilder sb = new StringBuilder();
        for (File f : items) {
            if (new File(f, "rootfs").isDirectory()) sb.append(f.getName()).append('\n');
        }
        return sb.length() == 0 ? "(no images yet)" : sb.toString();
    }

    /**
     * 起容器前把「不该依赖镜像作者」的东西补上（都幂等）：
     *
     *  1. /etc/resolv.conf ← 会话 rootfs 的那份（App 每次启动会按当前网络重写）
     *  2. /etc/hosts ← 缺就补一份 localhost（libc 里这条路径已被改写成 /dev/fd/4）
     *  3. CA 证书 ← 镜像没带就从基座 rootfs 复制，否则 https 一律失败
     *  4. merged-usr：/lib、/bin 必须是 → usr/* 的符号链接。种子镜像常把这条弄丢，
     *     表现为「/lib/ld-linux-aarch64.so.1 存在但找不到」，任何动态程序都起不来。
     */
    private static void prepare(Context ctx, File root) {
        File base = new File(ctx.getFilesDir(), "rootfs");
        GuestTools.install(ctx, root);
        try {
            File r = new File(root, "etc/resolv.conf");
            File b = new File(base, "etc/resolv.conf");
            if (b.isFile()) copy(b, r);

            File hosts = new File(root, "etc/hosts");
            if (!hosts.isFile()) {
                hosts.getParentFile().mkdirs();
                IOUtil.writeText(hosts, "127.0.0.1 localhost\n::1 localhost\n");
            }

            File ca = new File(root, "etc/ssl/certs/ca-certificates.crt");
            File baseCa = new File(base, "etc/ssl/certs/ca-certificates.crt");
            if (!ca.isFile() && baseCa.isFile()) {
                ca.getParentFile().mkdirs();
                copy(baseCa, ca);
            }

            ensureAccount(root);

            for (String n : new String[]{"lib", "lib64", "bin", "sbin"}) {
                File link = new File(root, n);
                File target = new File(root, "usr/" + n);
                if (!target.isDirectory()) continue;
                if (java.nio.file.Files.isSymbolicLink(link.toPath())) continue;
                if (!link.exists()) {
                    java.nio.file.Files.createSymbolicLink(link.toPath(),
                            java.nio.file.Paths.get("usr/" + n));
                    DebugLog.i("container: repaired merged-usr link /" + n);
                } else if (link.isDirectory()) {
                    String[] kids = link.list();
                    if (kids != null && kids.length == 0) {
                        //noinspection ResultOfMethodCallIgnored
                        link.delete();
                        java.nio.file.Files.createSymbolicLink(link.toPath(),
                                java.nio.file.Paths.get("usr/" + n));
                        DebugLog.i("container: repaired empty /" + n + " -> usr/" + n);
                    } else {
                        DebugLog.w("container: /" + n + " is a real dir with content, left alone");
                    }
                }
            }
        } catch (Exception e) {
            DebugLog.w("container: prepare failed: " + e);
        }
    }

    /**
     * 保证 /etc/passwd 与 /etc/group 里有当前 uid/gid 的条目。
     *
     * 我们不是 root，进程 euid 是 App 的 uid（如 10270），镜像里当然没有这个用户。
     * 很多程序第一件事就是 getpwuid(geteuid())：
     *   initdb: could not look up effective user ID 10270: user does not exist
     * 名字取 "pocket" 而不是 "root" —— PostgreSQL 一类程序见到 root 会直接拒绝运行。
     */
    /** 会话 rootfs 也会调用（见 DebianRootfs.installTools）。 */
    public static void ensureAccount(File root) {
        int uid = android.os.Process.myUid();
        int gid = android.os.Process.myUid();
        try {
            appendIfMissing(new File(root, "etc/passwd"),
                    "pocket:x:" + uid + ":" + gid + ":Pocket Agent:/root:/bin/sh", uid);
            appendIfMissing(new File(root, "etc/group"), "pocket:x:" + gid + ":", gid);
        } catch (Exception e) {
            DebugLog.w("container: ensureAccount failed: " + e);
        }
    }

    /** 文件里没有 id 字段等于 id 的行时追加一行（字段都以 ':' 分隔）。 */
    private static void appendIfMissing(File f, String line, int id) throws Exception {
        if (!f.isFile()) {
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            IOUtil.writeText(f, line + "\n");
            return;
        }
        String text = new String(IOUtil.readBytes(f, 1 << 20), java.nio.charset.StandardCharsets.UTF_8);
        for (String l : text.split("\n")) {
            String[] parts = l.split(":");
            if (parts.length > 2 && String.valueOf(id).equals(parts[2].trim())) return;
        }
        IOUtil.writeText(f, text + (text.endsWith("\n") || text.isEmpty() ? "" : "\n") + line + "\n");
    }

    private static void copy(File src, File dst) throws Exception {
        File p = dst.getParentFile();
        if (p != null) p.mkdirs();
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 跑一条命令；超时杀进程组。返回合并后的 stdout+stderr。 */
    public static String run(Context ctx, String image, String command, long timeoutMs) throws Exception {
        File root = rootfsDir(ctx, image);
        if (!root.isDirectory()) throw new Exception("no such image: " + image + " (see: pimg ls)");
        prepare(ctx, root);
        refreshLdCache(ctx, root);
        return exec(ctx, root, new File(root, "bin/sh"),
                new String[]{"-c", command}, timeoutMs);
    }

    /**
     * 跑一次 ldconfig：镜像里 apt 装完包后，新库不进 ld.so.cache 就找不到
     * （实测 `curl: librtmp.so.1: cannot open shared object file`）。
     * 没装 libc-bin 的镜像跳过。
     */
    private static void refreshLdCache(Context ctx, File root) {
        File lc = new File(root, "sbin/ldconfig");
        if (!lc.isFile()) lc = new File(root, "usr/sbin/ldconfig");
        if (!lc.isFile()) return;
        try {
            exec(ctx, root, lc, new String[]{}, 30_000L);
        } catch (Exception ignored) {
        }
    }

    private static String exec(Context ctx, File root, File program, String[] progArgs,
                               long timeoutMs) throws Exception {
        File lib = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File ldso = new File(root, "lib/ld-linux-aarch64.so.1");
        if (!ldso.isFile()) ldso = new File(root, "lib/ld-musl-aarch64.so.1");
        if (!ldso.isFile()) throw new Exception("image has no dynamic loader");

        List<String> args = new ArrayList<>();
        args.add(ldso.getAbsolutePath());
        args.add(program.getAbsolutePath());
        for (String a : progArgs) args.add(a);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        pb.directory(root);
        Map<String, String> env = pb.environment();
        String shim = new File(lib, "libzng.so").getAbsolutePath();
        env.put("LD_PRELOAD", shim);
        env.put("ZNG_PRELOAD", shim);
        env.put("ZNG_ROOT", root.getAbsolutePath());
        env.put("ZNG_BINDS", "/proc\t/proc\n/dev\t/dev\n/sys\t/sys\n"
                + new File(ctx.getFilesDir(), "workspace").getAbsolutePath() + "\t/root/workspace\n"
                + new File(ctx.getFilesDir(), "oci").getAbsolutePath() + "\t/images\n");
        env.put("LD_LIBRARY_PATH", root.getAbsolutePath() + "/lib:" + root.getAbsolutePath()
                + "/lib64:" + root.getAbsolutePath() + "/usr/lib/aarch64-linux-gnu");
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("HOME", "/root");
        //
        // LANG 用 C，不用 C.UTF-8：
        // glibc 的 setlocale 走**内部** open 读 /usr/lib/locale（不经 PLT，路径
        // 不翻译），在容器里必然失败；redis 7 直接因此退出：
        //   # Failed to configure LOCALE for invalid locale name.
        // LANG=C 是内建 locale，不需要任何文件。需要 UTF-8 的程序自己设 LANG。
        env.put("LANG", "C");
        env.put("TERM", "dumb");
        // Android 默认把 TMPDIR 指到 app cache（容器里不存在），mysqld 一上来就 stat 它
        env.put("TMPDIR", "/tmp");

        Process p = pb.start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream is = p.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = is.read(chunk)) != -1) {
                    synchronized (buf) {
                        if (buf.size() < MAX_OUTPUT) buf.write(chunk, 0, n);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "container-reader");
        reader.setDaemon(true);
        reader.start();

        boolean done = p.waitFor(Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!done) {
            p.destroyForcibly();
            reader.join(500);
            return "TIMEOUT after " + timeoutMs + " ms\n" + buf.toString("UTF-8");
        }
        reader.join(1000);
        String out = buf.toString("UTF-8");
        return (out.isEmpty() ? "(no output)" : out) + "\n[exit " + p.exitValue() + "]";
    }
}