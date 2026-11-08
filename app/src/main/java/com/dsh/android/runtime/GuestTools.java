package com.dsh.android.runtime;

import android.content.Context;

import com.dsh.android.util.DebugLog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把几样镜像里缺的小工具装进 rootfs（只做一次，用 marker 记）。
 *
 * Debian slim 镜像不带 ar / xz / zstd，手工解 .deb 时寸步难行；
 * trixie 的 deb 用 zstd 压缩，而 python 又没有 zstandard 模块。
 *   - ar / xz / unxz：纯 python 实现（stdlib 的 lzma）
 *   - zstd：从 Debian 官方 arm64 deb 里解出的真实二进制（依赖 libzstd/liblz4/
 *     liblzma，rootfs 里都有）
 */
public final class GuestTools {

    private static final String MARKER = ".dsh-tools-v1";

    private static final String[][] TOOLS = {
            {"ar.py", "ar"},
            {"xz.py", "xz"},
            {"xz.py", "unxz"},
            {"xz.py", "xzcat"},
            {"zstd.bin", "zstd"},
    };

    private GuestTools() {
    }

    public static void install(Context ctx, File root) {
        installCompat(ctx, root);
        if (new File(root, MARKER).isFile()) return;
        File bin = new File(root, "usr/local/bin");
        if (!bin.isDirectory() && !bin.mkdirs()) return;
        try {
            for (String[] t : TOOLS) {
                File dst = new File(bin, t[1]);
                try (InputStream in = ctx.getAssets().open(t[0]);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 14)) {
                    byte[] buf = new byte[1 << 14];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                dst.setExecutable(true, false);
            }
            //noinspection ResultOfMethodCallIgnored
            new File(root, MARKER).createNewFile();
            DebugLog.i("GuestTools: installed " + TOOLS.length + " tools");
        } catch (Exception e) {
            DebugLog.w("GuestTools: " + e);
        }
    }

    /**
     * 兼容层：**不随 marker 跳过**，已经解包过的 rootfs 也要补上。
     *
     * 1. etc/apt/apt.conf.d/99-pocketroot
     *    apt-get 自己调 dpkg，不继承上一条 dpkg 命令行的 --force-not-root，
     *    否则 apt-get install 直接报 "requested operation requires superuser privilege"。
     *    写进 apt 配置后，所有 apt 操作自动带上。
     *
     * 2. usr/local/bin/ldconfig
     *    Debian 的 ldconfig 是 static-pie（无 PT_INTERP），不加载 shim、路径不翻译，
     *    直接跑会对 Android 的真实 / 操作。包装脚本把 "-r /" 映射到 host rootfs。
     *    libc-bin 的 postinst 正是因为这个退出 127，长期停在 half-configured。
     */
    public static void installCompat(Context ctx, File root) {
        try {
            patchNssPaths(root);
            writeIfChanged(new File(root, "etc/apt/apt.conf.d/99-pocketroot"),
                    APT_CONF.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File lc = new File(root, "usr/local/bin/ldconfig");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = ctx.getAssets().open("ldconfig.sh")) {
                byte[] b = new byte[1 << 12];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            if (writeIfChanged(lc, bo.toByteArray())) {
                //noinspection ResultOfMethodCallIgnored
                lc.setExecutable(true, false);
            }
        } catch (Exception e) {
            DebugLog.w("GuestTools: compat " + e);
        }
    }

    /**
     * 让 guest 的用户名/组名解析走 shim 已经装好的 fd（5=passwd，6=group，7=nsswitch）。
     *
     * glibc 的 nss_files 用内部隐藏符号 __open64_nocancel 打开 /etc/passwd，LD_PRELOAD
     * 拦不到 —— 路径不会被翻译，实际读的是宿主 Android 的 /etc/passwd（不存在）。
     * 表现就是 id -un / getent passwd 全部失败，ls -l 只显示数字 uid。
     *
     * 镜像在 pimg 拉取时会做同样的字符串改写（patch-glibc.py），会话 rootfs 之前漏了。
     * 这里只替换 .rodata 里的常量，长度不变（尾部补 NUL），ELF 结构不受影响。
     */
    private static void patchNssPaths(File root) {
        File libc = new File(root, "usr/lib/aarch64-linux-gnu/libc.so.6");
        if (!libc.isFile()) libc = new File(root, "lib/aarch64-linux-gnu/libc.so.6");
        if (!libc.isFile()) return;
        File marker = new File(root, ".dsh-nss-v3");
        if (marker.isFile() && marker.lastModified() >= libc.lastModified()) return;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(libc.toPath());
            boolean changed = replacePath(b, "/etc/passwd", "/dev/fd/5")
                    | replacePath(b, "/etc/group", "/dev/fd/6")
                    | replacePath(b, "/etc/nsswitch.conf", "/dev/fd/7");
            // sem_open 走 glibc 内部 open；libc 里是 "/dev/shm/%s" 这类前缀，
            // 没有 NUL 结尾，只能做等长裸替换
            int shmHits = 0;
            while (replaceRaw(b, "/dev/shm/", "/dev/fd/8/")) { changed = true; shmHits++; }
            DebugLog.i("nss patch: len=" + b.length + " shmHits=" + shmHits + " changed=" + changed);
            if (changed) {
                File bak = new File(libc.getParentFile(), "libc.so.6.pocketbak");
                if (!bak.isFile()) java.nio.file.Files.copy(libc.toPath(), bak.toPath());
                File tmp = new File(libc.getParentFile(), "libc.so.6.new");
                java.nio.file.Files.write(tmp.toPath(), b);
                //noinspection ResultOfMethodCallIgnored
                tmp.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                tmp.setExecutable(true, false);
                java.nio.file.Files.move(tmp.toPath(), libc.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                libc.setExecutable(true, false);
                DebugLog.i("GuestTools: patched libc nss paths");
            }
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            marker.setLastModified(System.currentTimeMillis());
        } catch (Exception e) {
            DebugLog.w("GuestTools: nss patch " + e);
        }
    }

    /** 等长字节替换（不要求 NUL 结尾），用于 "/dev/shm/%s" 这种前缀常量。 */
    private static boolean replaceRaw(byte[] b, String from, String to) {
        byte[] f = from.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] t = to.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (f.length != t.length) return false;
        for (int i = 0; i + f.length <= b.length; i++) {
            boolean hit = true;
            for (int j = 0; j < f.length; j++) {
                if (b[i + j] != f[j]) { hit = false; break; }
            }
            if (!hit) continue;
            System.arraycopy(t, 0, b, i, t.length);
            return true;
        }
        return false;
    }

    /** 把 b 里的 from\0 原地换成 to\0（to 更短，剩余补 NUL）。返回是否替换。 */
    private static boolean replacePath(byte[] b, String from, String to) {
        byte[] fs = from.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ts = to.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] f = java.util.Arrays.copyOf(fs, fs.length + 1);   // 末尾补 NUL
        byte[] t = java.util.Arrays.copyOf(ts, ts.length + 1);
        if (t.length > f.length) return false;
        for (int i = 0; i + f.length <= b.length; i++) {
            boolean hit = true;
            for (int j = 0; j < f.length; j++) {
                if (b[i + j] != f[j]) { hit = false; break; }
            }
            if (!hit) continue;
            System.arraycopy(t, 0, b, i, t.length);
            for (int j = i + t.length; j < i + f.length; j++) b[j] = 0;
            return true;
        }
        return false;
    }

    /** 内容不同才写；返回是否真的写了。 */
    private static boolean writeIfChanged(File f, byte[] want) throws Exception {
        if (f.isFile() && f.length() == want.length) {
            byte[] have = new byte[want.length];
            try (InputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < have.length) {
                    int n = in.read(have, off, have.length - off);
                    if (n <= 0) break;
                    off += n;
                }
                if (off == have.length && java.util.Arrays.equals(have, want)) return false;
            }
        }
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) return false;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1 << 12)) {
            out.write(want);
        }
        return true;
    }

    private static final String APT_CONF =
            "// pocketroot: guest runs as an unprivileged Android uid, not root.\n"
            + "// apt-get invokes dpkg itself and does NOT inherit an earlier dpkg's flags.\n"
            + "Dpkg::Options { \"--force-not-root\"; };\n";
}
