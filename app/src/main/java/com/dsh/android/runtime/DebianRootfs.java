package com.dsh.android.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

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
 * 资产分成若干段（debian-rootfs-arm64.0/1/…bin）而不是一整包，原因是解压：
 * 设备上没有 xz 二进制，只能用 org.tukaani 的 Java 解码器，而它是单线程的
 * （真机 345 MB 要 22 s）。构建时按子树把 rootfs 切成 N 份**各自独立的 tar**
 * 再分别 xz，这里就能 N 线程并行解压。真机（6×A55 + 2×A76）实测：
 * 1 段 22.3 s（单线程）、3 段 13.8 s、6 段 10.4 s，首次解包 25.0 → 12.7 s。
 * 再加段数就只剩争抢了：6 段时每线程只剩 6.5 MB/s（3 段时 8.3 MB/s）。
 * 每段解出来的都是完整合法的 tar，所以解包仍然一段一段喂 toybox，
 * 条目总数没变，解包耗时和整包一样（2 s 量级）。
 *
 * 资产名故意用 .bin 而不是 .tar.gz：AGP 8 的 asset 合并会重新编码 .gz 扩展名，
 * 解出来的流就不是 gzip 了（UbuntuRootfs 里踩过同样的坑）。
 */
public final class DebianRootfs {

    public interface Progress {
        void onProgress(String message);
    }

    /** 资产前缀：APK 里是 debian-rootfs-arm64.0.bin / .1.bin / …（段数由构建脚本决定）。 */
    public static final String BUNDLED_ASSET = "debian-rootfs-arm64";
    /** 解包完成后写入，用来区分「解了一半」与「可用」。 */
    public static final String MARKER = ".dsh-rootfs-ok";
    private static final String LD_SO = "lib/ld-linux-aarch64.so.1";

    /** 拿不到系统 DNS 时的兜底（无网络 / 无权限）。 */
    private static final String[] FALLBACK_DNS = {"223.5.5.5", "119.29.29.29", "114.114.114.114"};

    private final Context context;
    private final File rootfsDir;
    private final File nativeLibDir;

    public DebianRootfs(ProrootRuntime runtime, Context context) {
        this.context = context.getApplicationContext();
        this.rootfsDir = runtime.rootfsDir();
        this.nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    /**
     * 原生 xz（若存在 jniLibs/libxz.so）。
     *
     * **真机结论：装不上。** 用 zig 交叉编译的静态 musl xz（264 KB）在 app 进程里
     * 一启动就被 seccomp 杀掉（rc=159 = SIGSYS，6 ms，输出 0 字节），单线程版同样。
     * 讽刺的是这正是不需要 shim 的场景：它必须在 rootfs 解出来之前运行，
     * 而 shim 只能注入动态链接的 guest 程序。静态二进制的 syscall 号在寄存器里，
     * 码型扫描也抓不到（整份二进制只有 1 个 movz x8 站点，是 rt_sigreturn）。
     *
     * 所以这段代码目前是"有就用、没有就走 Java"的备用通道：将来若把 xz 放进
     * rootfs（动态链接 glibc、可被 shim 覆盖），或者改用 zstd-jni 这类 in-process
     * 的 JNI 库，可以复用。
     */
    private boolean decompressWithNativeXz(File src, File dst) {
        File xz = new File(nativeLibDir, "libxz.so");
        if (!xz.isFile()) {
            DebugLog.i("bootstrap: native xz not bundled, using Java decoder");
            return false;
        }
        long t0 = System.currentTimeMillis();
        try {
            Process p = new ProcessBuilder(xz.getAbsolutePath(), "-dc", src.getAbsolutePath())
                    .redirectOutput(dst)
                    // Android 的 ProcessBuilder 没有 Redirect.DISCARD（API 35 也没有），用 /dev/null
                    .redirectError(new File("/dev/null"))
                    .start();
            int rc = p.waitFor();
            long n = dst.length();
            DebugLog.i("bootstrap: native xz rc=" + rc + " -> " + n + " bytes in "
                    + (System.currentTimeMillis() - t0) + "ms");
            return rc == 0 && n > (1 << 20);
        } catch (Exception e) {
            DebugLog.w("bootstrap: native xz failed: " + e);
            return false;
        }
    }

    /** 纯 Java 解码（后备）：不依赖任何二进制，但慢（15 MB/s 量级）。 */
    private void decompressWithJavaXz(File src, File dst) throws IOException {
        long t1 = System.currentTimeMillis();
        long total = 0;
        try (XZInputStream in = new XZInputStream(
                     new BufferedInputStream(new FileInputStream(src), 1 << 16));
             OutputStream out = new BufferedOutputStream(
                     new FileOutputStream(dst), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        }
        DebugLog.i("bootstrap: java xz " + src.getName() + " -> " + total + " bytes in "
                + (System.currentTimeMillis() - t1) + "ms");
    }

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

    /** APK 里带了几段就返回几段，按序号排好（AssetManager.list 的顺序不保证）。 */
    private String[] partAssets() throws IOException {
        String prefix = BUNDLED_ASSET + ".";
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String name : context.getAssets().list("")) {
            if (name.startsWith(prefix) && name.endsWith(".bin")) names.add(name);
        }
        if (names.isEmpty()) {
            throw new IOException("no bundled rootfs parts (" + prefix + "N.bin)");
        }
        java.util.Collections.sort(names, (a, b) -> Integer.compare(partIndex(a), partIndex(b)));
        return names.toArray(new String[0]);
    }

    private static int partIndex(String assetName) {
        String s = assetName.substring(assetName.lastIndexOf('.', assetName.length() - 5) + 1);
        try {
            return Integer.parseInt(s.substring(0, s.indexOf('.')));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 把 APK 里的一段资产落到磁盘：AssetManager 的流直接喂解包器不可靠。 */
    private void copyAsset(String name, File dst) throws IOException {
        long t = System.currentTimeMillis();
        long total = 0;
        try (InputStream in = context.getAssets().open(name);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        }
        if (total < (1 << 20)) {
            throw new IOException("bundled asset too small: " + name + " " + total + " bytes");
        }
        DebugLog.i("bootstrap: copied " + name + " " + total + " bytes in "
                + (System.currentTimeMillis() - t) + "ms");
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
            if (rc == 0) return true;
            // rc 不等于 0 也可能只是**部分条目**失败：app 进程建硬链接会被 SELinux 拒绝
            // （实测 "can't link usr/bin/unzip -> usr/bin/zipinfo: Permission denied"），
            // 其余几十万个文件都解好了。所以只有"全部错误都是链接失败"才继续，
            // 其它情况一律走 Java 后备。
            // 不能再用「bin/bash 在不在」来判断：rootfs 拆段后，bin/bash 只落在其中一段里。
            boolean linksOnly = onlyLinkErrors(sb.toString());
            DebugLog.w("toybox tar rc=" + rc + " on " + tarball.getName()
                    + (linksOnly ? " (link errors only, continuing)" : "") + ": " + sb);
            return linksOnly;
        } catch (Exception e) {
            DebugLog.w("toybox tar unavailable: " + e);
            return false;
        }
    }

    /** 输出里每一行都是已知的硬链接失败？ */
    private static boolean onlyLinkErrors(String out) {
        boolean sawOne = false;
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.contains("can't link")) return false;
            sawOne = true;
        }
        return sawOne;
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

    /**
     * 把 pimg（镜像管理）和 glibc 补丁脚本塞进 rootfs。
     *
     * 放在解包末尾、写 marker 之前：失败只记日志，不影响运行时可用性
     * （没有 pimg 只是不能拉镜像，shell/python 照常）。
     */
    private void installTools() {
        try {
            File bin = new File(rootfsDir, "usr/local/bin");
            File lib = new File(rootfsDir, "usr/local/lib/pimg");
            if (!bin.isDirectory() && !bin.mkdirs()) return;
            if (!lib.isDirectory() && !lib.mkdirs()) return;
            File pimg = new File(bin, "pimg");
            writeAsset("pimg.py", pimg);
            //noinspection ResultOfMethodCallIgnored
            pimg.setExecutable(true, false);
            writeAsset("pimg-patch.py", new File(lib, "patch-glibc.py"));
            DebugLog.i("bootstrap: pimg + patcher installed");
        } catch (Exception e) {
            DebugLog.w("bootstrap: installTools failed: " + e);
        }
    }

    /** 写一个小资产（copyAsset 有"小于 1 MB 视为失败"的检查，专给 rootfs 分段用的）。 */
    private void writeAsset(String name, File dst) throws IOException {
        try (InputStream in = context.getAssets().open(name);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 14)) {
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    public boolean fullyInstalled() {
        return installed() && new File(rootfsDir, MARKER).isFile();
    }

    /**
     * 把当前网络的 DNS 写进 rootfs 的 /etc/resolv.conf。
     *
     * 每次启动都重写（不只在解包时）：换 Wi-Fi、开热点、切数据网络之后 DNS 会变。
     * 优先用 ConnectivityManager 报的服务器 —— 有些网络（这台设备的 Wi-Fi 就是）
     * 只放行路由器自己的解析器，写死公共 DNS 会一个都不通；公共 DNS 只作兜底。
     * 链路本地地址（fe80::…）不能写：resolv.conf 没有 scope id 语法，glibc 用不了。
     *
     * 注意 guest 的 glibc 是**在 libc 内部**读这个文件的（不走 PLT，钩子拦不到），
     * 所以真正让它生效的是两件事：构建期把路径改写成 /dev/fd/3（见 pocketroot 的
     * patch-glibc-syscalls.py），运行期 shim 把 fd 3 常驻指向这个文件
     * （shim/zng_ctor.c: zng_install_resolv）。改写用截断重写，inode 不变，
     * 已经打开的 fd 3 直接看到新内容，不需要重启会话。
     */
    public void refreshResolvConf() {
        List<String> servers = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String s = a.getHostAddress();
                    if (s != null && !servers.contains(s)) servers.add(s);
                }
            }
        } catch (Exception e) {
            DebugLog.w("bootstrap: cannot read system DNS: " + e);
        }
        if (servers.isEmpty()) {
            for (String s : FALLBACK_DNS) servers.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : servers) sb.append("nameserver ").append(s).append('\n');
        sb.append("options timeout:2 attempts:2\n");
        try {
            IOUtil.writeText(new File(rootfsDir, "etc/resolv.conf"), sb.toString());
            DebugLog.i("bootstrap: resolv.conf -> " + servers);
        } catch (IOException e) {
            DebugLog.w("bootstrap: cannot write resolv.conf: " + e);
        }
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
        // 段数由构建脚本决定（tools/make-debian-rootfs.sh --parts），这里从 assets
        // 里现数，改段数不用动代码。xz 解码是单线程的，段数就是并行度。
        final String[] assets = partAssets();
        progress.onProgress("Extracting bundled Debian rootfs (" + assets.length
                + " parts in parallel)…");

        // 每段各自「落盘 → 解压成 .tar」，N 线程同时跑。
        // 先把资产落到磁盘再解：AssetManager 的流是压缩条目的解码流，
        // 直接喂给解包器时行为依赖实现细节（本机实测读到 0 字节就 EOF）。
        final File dir = rootfsDir.getParentFile();
        // 上一次解包中途失败会留下 .part / .tar（合起来几百 MB），先扫掉。
        File[] leftovers = dir.listFiles();
        if (leftovers != null) {
            for (File f : leftovers) {
                if (f.getName().startsWith("debian-rootfs.")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        final int n = assets.length;
        final File[] parts = new File[n];
        final File[] tars = new File[n];
        final Throwable[] errors = new Throwable[n];
        final long t0 = System.currentTimeMillis();
        Thread[] workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            workers[i] = new Thread(() -> {
                try {
                    File part = new File(dir, "debian-rootfs." + idx + ".part");
                    parts[idx] = part;
                    copyAsset(assets[idx], part);
                    // 压缩格式按魔数判断，不写死：xz 由 Java 解到 .tar，
                    // 非 xz（构建时没开 xz）留给 toybox 的 -z。设备上没有 xz 二进制
                    // —— toybox 的 -J 只是 exec "xz"，实测报
                    // "tar: exec xz: No such file or directory"。
                    if (isXz(part)) {
                        File tar = new File(dir, "debian-rootfs." + idx + ".tar");
                        if (!decompressWithNativeXz(part, tar)) {
                            decompressWithJavaXz(part, tar);
                        }
                        tars[idx] = tar;
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    }
                } catch (Throwable t) {
                    errors[idx] = t;
                }
            }, "rootfs-part-" + idx);
            workers[i].start();
        }
        try {
            for (Thread w : workers) w.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while decoding rootfs", e);
        }
        for (Throwable t : errors) {
            if (t != null) throw new IOException("rootfs part decode failed: " + t, t);
        }
        DebugLog.i("bootstrap: " + n + " parts decoded in "
                + (System.currentTimeMillis() - t0) + "ms");

        // 解包仍按段顺序做：每段都是独立合法的 tar，条目总数和整包一样，
        // 所以耗时不变（2 s 量级）；并行解包反而会让两组 tar 同时 mkdir 同一个
        // 父目录、争抢 chmod，实测没有必要。
        try {
            for (int i = 0; i < n; i++) {
                File tar = tars[i];
                if (tar != null) {
                    // 优先用系统的 toybox tar：C 实现，几十万条目的包几秒解完，
                    // 符号链接/权限按 tar 头处理。IOUtil 的 Java 解包器作为后备。
                    if (!extractWithToybox(tar, rootfsDir)) {
                        DebugLog.w("bootstrap: toybox tar failed on part " + i
                                + ", falling back to Java extractor");
                        try (InputStream in = new BufferedInputStream(
                                new FileInputStream(tar), 1 << 16)) {
                            IOUtil.extractTar(in, rootfsDir);
                        }
                    }
                } else if (!extractWithToybox(parts[i], rootfsDir)) {
                    IOUtil.extractTarGz(parts[i], rootfsDir);
                }
            }
        } finally {
            for (int i = 0; i < n; i++) {
                //noinspection ResultOfMethodCallIgnored
                if (parts[i] != null) parts[i].delete();
                //noinspection ResultOfMethodCallIgnored
                if (tars[i] != null) tars[i].delete();
            }
        }

        DebugLog.i("bootstrap: extraction took " + (System.currentTimeMillis() - t0) + "ms");
        if (!installed()) {
            throw new IOException("rootfs extraction incomplete: " + rootfsDir);
        }
        File ws = new File(rootfsDir, "root/workspace");
        if (!ws.isDirectory() && !ws.mkdirs()) {
            throw new IOException("Cannot create " + ws);
        }
        // 让 stat %U / id -un / dpkg 之类能查到当前 Android uid，而不是 UNKNOWN
        ContainerRunner.ensureAccount(rootfsDir);
        installTools();
        IOUtil.writeText(new File(rootfsDir, MARKER), "ok\n");
        progress.onProgress("Runtime ready.");
        DebugLog.i("bootstrap: rootfs ready at " + rootfsDir);
    }
}
