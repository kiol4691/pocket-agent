package com.dsh.android.runtime;

import android.content.Context;

import com.dsh.android.util.DebugLog;
import com.dsh.android.util.IOUtil;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs the Debian rootfs that ships inside the APK.
 *
 * 与 {@link UbuntuRootfs} 的区别：
 *
 *  - 不分发「裸 base + 装包」两段式：这里带的是已经装好工具链的 Debian 13 轻量 rootfs
 *    （python3 / git / numpy / requests / openpyxl / python-docx / jq / procps …），
 *    所以没有 apt 阶段，解包完就能用；
 *  - 不做在线下载兜底：包体大，且用户拿到的 APK 里本来就带着它。
 *
 * 资产名故意用 .bin 而不是 .tar.gz：AGP 8 的 asset 合并会重新编码 .gz 扩展名，
 * 解出来的流就不是 gzip 了（UbuntuRootfs 里踩过同样的坑）。
 */
public final class DebianRootfs {

    public interface Progress {
        void onProgress(String message);
    }

    public static final String BUNDLED_ASSET = "debian-rootfs-arm64.bin";
    /** 解包完成后写入，用来区分「解了一半」与「可用」。 */
    public static final String MARKER = ".dsh-rootfs-ok";
    private static final String LD_SO = "lib/ld-linux-aarch64.so.1";

    private static final String RESOLV_CONF =
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\n"
                    + "nameserver 114.114.114.114\noptions timeout:2 attempts:2\n";

    private final Context context;
    private final File rootfsDir;

    public DebianRootfs(ProrootRuntime runtime, Context context) {
        this.context = context.getApplicationContext();
        this.rootfsDir = runtime.rootfsDir();
    }

    /**
     * 用系统自带 toybox tar 解包。返回 false 表示不可用/失败，调用方走 Java 后备。
     */
    /** xz 魔数：FD 37 7A 58 5A 00。 */
    private static boolean isXz(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] m = new byte[6];
            if (in.read(m) != 6) return false;
            return (m[0] & 0xff) == 0xfd && m[1] == 0x37 && m[2] == 0x7a
                    && m[3] == 0x58 && m[4] == 0x5a && m[5] == 0x00;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean extractWithToybox(File tarball, File destDir) {
        // 已经是 .tar（xz 解出来的）就不带压缩标志；否则按 gzip 交给 toybox。
        String mode = tarball.getName().endsWith(".tar") ? "xf" : "xzf";
        try {
            Process p = new ProcessBuilder("/system/bin/toybox", "tar", mode,
                    tarball.getAbsolutePath(), "-C", destDir.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() < 2000) sb.append(line).append("\n");
                }
            }
            int rc = p.waitFor();
            if (rc != 0) {
                DebugLog.w("toybox tar rc=" + rc + ": " + sb);
                return false;
            }
            // toybox 对个别条目报错仍可能返回 0（例如硬链接），用关键文件兜底判断
            return new File(destDir, "bin/bash").isFile();
        } catch (Exception e) {
            DebugLog.w("toybox tar unavailable: " + e);
            return false;
        }
    }

    /** 关键文件齐了没有（没齐说明解包失败或被清过）。 */
    public boolean installed() {
        // python3 在 Debian 的 python 镜像里装到 /usr/local/bin（不是 /usr/bin），
        // 只查后者会让「解包成功」被判成失败，于是每次启动都重解一遍 346 MB。
        boolean py = new File(rootfsDir, "usr/local/bin/python3").exists()
                || new File(rootfsDir, "usr/bin/python3").exists();
        return new File(rootfsDir, "bin/bash").isFile() && py
                && new File(rootfsDir, LD_SO).exists();
    }

    public boolean fullyInstalled() {
        return installed() && new File(rootfsDir, MARKER).isFile();
    }

    public synchronized void bootstrap(Progress progress) throws IOException {
        if (fullyInstalled()) {
            progress.onProgress("Runtime ready.");
            return;
        }
        if (rootfsDir.isDirectory()) {
            DebugLog.i("bootstrap: discarding stale rootfs at " + rootfsDir);
            IOUtil.deleteRecursively(rootfsDir);
        }
        if (!rootfsDir.mkdirs() && !rootfsDir.isDirectory()) {
            throw new IOException("Cannot create " + rootfsDir);
        }
        progress.onProgress("Extracting bundled Debian rootfs (about 350 MB, one minute)…");
        // 先把资产落到磁盘再解：AssetManager 的流是压缩条目的解码流，
        // 直接喂给 tar 解包器时行为依赖实现细节（本机实测读到 0 字节就 EOF）。
        // 落盘还有一个好处：解包失败时能看见真实字节数。
        File tarball = new File(rootfsDir.getParentFile(), "debian-rootfs.tar.gz");
        long t0 = System.currentTimeMillis();
        try (InputStream in = context.getAssets().open(BUNDLED_ASSET);
             java.io.FileOutputStream out = new java.io.FileOutputStream(tarball)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            DebugLog.i("bootstrap: copied asset " + total + " bytes in "
                    + (System.currentTimeMillis() - t0) + "ms");
        }
        if (tarball.length() < (1 << 20)) {
            throw new IOException("bundled asset too small: " + tarball.length() + " bytes");
        }

        // 压缩格式按魔数判断，不写死：xz 先由 Java 解到 .tar 再交给 toybox，
        // gzip 直接交给 toybox（它的 -z 是内置 zlib）。
        // 设备上没有 xz 二进制 —— toybox 的 -J 只是 exec "xz"，实测报
        // "tar: exec xz: No such file or directory"，所以 xz 这一层只能自己解。
        File payload = tarball;
        if (isXz(tarball)) {
            payload = new File(rootfsDir.getParentFile(), "debian-rootfs.tar");
            long t1 = System.currentTimeMillis();
            long total = 0;
            try (XZInputStream in = new XZInputStream(
                         new BufferedInputStream(new FileInputStream(tarball), 1 << 16));
                 OutputStream out = new BufferedOutputStream(
                         new FileOutputStream(payload), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            }
            DebugLog.i("bootstrap: xz -> " + total + " bytes in "
                    + (System.currentTimeMillis() - t1) + "ms");
        }

        // 优先用系统的 toybox tar：C 实现，几十万条目的包几秒解完，
        // 符号链接/权限按 tar 头处理。IOUtil 的 Java 解包器作为后备。
        try {
            if (!extractWithToybox(payload, rootfsDir)) {
                DebugLog.w("bootstrap: toybox tar failed, falling back to Java extractor");
                IOUtil.extractTarGz(payload, rootfsDir);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (payload != tarball) payload.delete();
            //noinspection ResultOfMethodCallIgnored
            tarball.delete();
        }
        DebugLog.i("bootstrap: extraction took " + (System.currentTimeMillis() - t0) + "ms");
        if (!installed()) {
            throw new IOException("rootfs extraction incomplete: " + rootfsDir);
        }
        // DNS：guest 里的 glibc 直接读这个文件（不经过 shim），必须在 rootfs 内写好。
        IOUtil.writeText(new File(rootfsDir, "etc/resolv.conf"), RESOLV_CONF);
        File ws = new File(rootfsDir, "root/workspace");
        if (!ws.isDirectory() && !ws.mkdirs()) {
            throw new IOException("Cannot create " + ws);
        }
        IOUtil.writeText(new File(rootfsDir, MARKER), "ok\n");
        progress.onProgress("Runtime ready.");
        DebugLog.i("bootstrap: rootfs ready at " + rootfsDir);
    }
}
