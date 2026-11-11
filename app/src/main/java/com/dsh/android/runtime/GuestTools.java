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
     * 2. ldconfig：三个 PATH 命中点全部换成 no-op 脚本（见 assets/ldconfig.sh）。
     *    Debian 的 ldconfig 是 static-pie（无 PT_INTERP），**不加载 shim**，因此
     *    没有 ctor 里装的 SIGSYS handler；Android 的 zygote seccomp 一拦就直接
     *    SIGSYS（exit 159）。旧包装脚本最终仍 exec 这个静态二进制，而且 dpkg 跑
     *    postinst 时把 PATH 固定为 /usr/sbin:/usr/bin:/sbin:/bin，/usr/local/bin
     *    根本不在里面 —— 只改 PATH 或只放一个包装脚本都无效。唯一可行的是不执行
     *    它：镜像自带有效的 ld.so.cache，shim 也补了自己的库搜索路径，新装的
     *    .so 落在标准 /usr/lib/aarch64-linux-gnu 里。
     *
     * 3. etc/hosts：shim 把 libc 的 /etc/hosts 改写成 /dev/fd/4 并挂到
     *    <root>/etc/hosts；镜像里没有这个文件，fd 4 装不上，localhost 只能靠
     *    glibc 内置规则。补一份标准的（已存在则不覆盖，尊重用户修改）。
     *
     * 4. usr/bin/perl 符号链接自检（P1）。真机排查确认：没有「东西在删文件」，
     *    apt 也没被 kill（dsh.log 无 TIMEOUT、apt 日志有正常 Log ended、
     *    dpkg updates/ 空）；缺的是少量关键符号链接/目录，与种子镜像解包丢链
     *    是同一类（ContainerRunner.prepare 已有 merged-usr 的同类修复）。
     *    dpkg -V 报的大量 missing 是 slim 镜像精简 doc/man 的预期，不是问题。
     */
    public static void installCompat(Context ctx, File root) {
        try {
            patchNssPaths(root);
            repairPerlLink(root);
            ensureSystemGroups(root);
            writeIfChanged(new File(root, "etc/apt/apt.conf.d/99-pocketroot"),
                    APT_CONF.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            File hosts = new File(root, "etc/hosts");
            if (!hosts.isFile()) {
                writeIfChanged(hosts, HOSTS.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = ctx.getAssets().open("ldconfig.sh")) {
                byte[] b = new byte[1 << 12];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            byte[] noop = bo.toByteArray();
            for (String rel : new String[]{
                    "usr/local/bin/ldconfig", "usr/sbin/ldconfig", "sbin/ldconfig"}) {
                File lc = new File(root, rel);
                if (writeIfChanged(lc, noop)) {
                    //noinspection ResultOfMethodCallIgnored
                    lc.setExecutable(true, false);
                }
            }
            // 维护脚本占位：guest 里本来就没有 sysvinit/systemd，这些调用没有
            // 实际作用，缺了却会让 postinst 报 "not found"(127) -> 包停在
            // half-configured -> 之后每次 apt install 都卡住（rsync 首当其冲）。
            //   · update-rc.d / invoke-rc.d 是 #!/usr/bin/perl 脚本，slim 镜像无 perl
            //   · deb-systemd-helper / deb-systemd-invoke 在 slim 镜像里整个没装
            // 与 ldconfig 同一个处理思路（见 assets/ldconfig.sh）。
            byte[] initNoop = ("#!/bin/sh\n"
                    + "# pocketroot: no init system in this guest;\n"
                    + "# init-system-helpers scripts are perl-based / absent in slim image.\n"
                    + "exit 0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (String rel : new String[]{
                    "usr/sbin/update-rc.d", "usr/sbin/invoke-rc.d",
                    "usr/bin/deb-systemd-helper", "usr/bin/deb-systemd-invoke",
                    // debconf 的 Pre-Install-Pkgs 钩子；noninteractive 下本就是空操作，
                    // slim 镜像没装它，缺了会在每次 apt 输出里留一行 "not found"。
                    "usr/sbin/dpkg-preconfigure"}) {
                File f = new File(root, rel);
                if (writeIfChanged(f, initNoop)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.setExecutable(true, false);
                }
            }

            // B 案：apt 中断后的标准修复命令 dpkg --configure -a 会报
            // "requested operation requires superuser privilege"，而 apt 的提示
            // 不会告诉你要加 --force-not-root。PATH 里 /usr/local/bin 在 /usr/bin
            // 之前，包一层即可；apt 自己用 /usr/bin/dpkg（已由 99-pocketroot
            // 带上 --force-not-root），不受影响。
            byte[] dpkgWrap = ("#!/bin/sh\n"
                    + "# pocketroot: guest runs as an unprivileged Android uid.\n"
                    + "exec /usr/bin/dpkg --force-not-root \"$@\"\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            File dw = new File(root, "usr/local/bin/dpkg");
            if (writeIfChanged(dw, dpkgWrap)) {
                //noinspection ResultOfMethodCallIgnored
                dw.setExecutable(true, false);
            }

            // C 案：系统 adduser/addgroup 在非 root 下开头就 fatal(
            // "Only root may add a user or group")，postinst 连锁失败。纯 no-op
            // 也不够：openssh-client 后续 chgrp _ssh、dbus 的 dpkg-statoverride
            // 都要能解析到组。换成往 /etc/{passwd,group} 直接追加条目的实现。
            byte[] adduserSh = ("#!/bin/sh\n"
                    + "# pocketroot: no root; append the account directly so that\n"
                    + "# postinst's chgrp / dpkg-statoverride can resolve it.\n"
                    + "name=\n"
                    + "for a in \"$@\"; do\n"
                    + "  case \"$a\" in -*) ;; *) name=\"$a\" ;; esac\n"
                    + "done\n"
                    + "[ -n \"$name\" ] || exit 0\n"
                    + "gid=100\n"
                    + "while grep -q \":$gid:\" /etc/group 2>/dev/null; do gid=$((gid+1)); done\n"
                    + "grep -q \"^$name:\" /etc/group 2>/dev/null || "
                    + "echo \"$name:x:$gid:\" >> /etc/group\n"
                    + "grep -q \"^$name:\" /etc/passwd 2>/dev/null || "
                    + "echo \"$name:x:$gid:$gid::/nonexistent:/usr/sbin/nologin\" >> /etc/passwd\n"
                    + "exit 0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (String rel : new String[]{"usr/sbin/adduser", "usr/sbin/addgroup"}) {
                File f = new File(root, rel);
                if (writeIfChanged(f, adduserSh)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.setExecutable(true, false);
                }
            }

            // A 案：ssl-cert 的 postinst 经 debconf 调 make-ssl-cert，在 guest 里必然
            // 卡死（真机：dpkg --configure ssl-cert 永不返回；它挂着，postgresql-common
            // /-17/postgresql 就全留在 unpacked，dpkg --audit 永远不干净）。
            // 换成不经 debconf 的实现：建组 + openssl 直接生成 snakeoil 证书，
            // 语义等价、幂等、几秒完成。同类坑清单见 pocketroot 的
            // docs/debian-postinst-pitfalls.md。
            byte[] sslPostinst = ("#!/bin/sh\n"
                    + "# pocketroot: upstream postinst drives make-ssl-cert through debconf,\n"
                    + "# which never returns in this guest. Generate the snakeoil cert directly.\n"
                    + "set -e\n"
                    + "if ! getent group ssl-cert >/dev/null 2>&1; then\n"
                    + "    addgroup --quiet --system --force-badname ssl-cert 2>/dev/null || true\n"
                    + "fi\n"
                    + "if [ ! -s /etc/ssl/certs/ssl-cert-snakeoil.pem ] || "
                    + "[ ! -s /etc/ssl/private/ssl-cert-snakeoil.key ]; then\n"
                    + "    mkdir -p /etc/ssl/certs /etc/ssl/private\n"
                    + "    h=$(hostname 2>/dev/null || echo localhost)\n"
                    + "    openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 "
                    + "-subj \"/CN=$h\" "
                    + "-keyout /etc/ssl/private/ssl-cert-snakeoil.key "
                    + "-out /etc/ssl/certs/ssl-cert-snakeoil.pem >/dev/null 2>&1 || true\n"
                    + "fi\n"
                    + "chmod 710 /etc/ssl/private 2>/dev/null || true\n"
                    + "exit 0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            File sslPost = new File(root, "var/lib/dpkg/info/ssl-cert.postinst");
            if (sslPost.getParentFile() != null && sslPost.getParentFile().isDirectory()
                    && writeIfChanged(sslPost, sslPostinst)) {
                //noinspection ResultOfMethodCallIgnored
                sslPost.setExecutable(true, false);
            }

            // su 兼容层：guest 只有一个 uid，没有可切换的用户，也没有 root 去验密码。
            // postinst 里 su <user> -c "..." 这种"降权执行"在本沙箱里等价于直接执行；
            // 上游 postgresql-common 就是这么用的，不替换会在 "Password:" 上永久阻塞
            // （真机：dpkg --configure postgresql-common 挂死，postgresql-17 连带卡住）。
            byte[] suSh = ("#!/bin/sh\n"
                    + "# pocketroot: single-uid sandbox; run the command directly.\n"
                    + "cmd=\n"
                    + "sh_=/bin/sh\n"
                    + "while [ $# -gt 0 ]; do\n"
                    + "    case $1 in\n"
                    + "        -c) cmd=$2; shift 2 ;;\n"
                    + "        -s) sh_=$2; shift 2 ;;\n"
                    + "        -) shift ;;\n"
                    + "        -*) shift ;;\n"
                    + "        *) shift ;;\n"
                    + "    esac\n"
                    + "done\n"
                    + "if [ -n \"$cmd\" ]; then exec \"$sh_\" -c \"$cmd\"; fi\n"
                    + "exec \"$sh_\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (String rel : new String[]{"usr/bin/su", "bin/su"}) {
                File f = new File(root, rel);
                if (!f.exists() && !f.isDirectory()) continue;
                if (writeIfChanged(f, suSh)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.setExecutable(true, false);
                }
            }

            // MariaDB：io_uring 在 Android 上返回 ENOSYS，回落到 libaio 后 bootstrap 阶段
            // 永远不退出（真机：mariadb-install-db 卡满 300s，数据目录其实已建好；卡住的
            // 进程一直占着 aria_log_control / ibdata1，于是"锁泄漏、目录不可重入"）。
            // 显式关掉 native AIO 即恢复正常退出。这里在 /usr/local/bin 放同名包装
            // （PATH 在 /usr/sbin 之前），显式传过该参数就不覆盖。
            byte[] mySqlWrap = ("#!/bin/sh\n"
                    + "# pocketroot: native AIO hangs here (io_uring ENOSYS -> libaio); disable it.\n"
                    + "for cand in /usr/sbin/@@NAME@@ /usr/bin/@@NAME@@; do\n"
                    + "    if [ -x \"$cand\" ]; then real=$cand; break; fi\n"
                    + "done\n"
                    + "[ -n \"$real\" ] || exit 127\n"
                    + "for a in \"$@\"; do\n"
                    + "    case \"$a\" in --innodb_use_native_aio*|--innodb-use-native-aio*) "
                    + "exec \"$real\" \"$@\" ;; esac\n"
                    + "done\n"
                    + "exec \"$real\" \"$@\" --innodb_use_native_aio=0\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (String name : new String[]{
                    "mariadb-install-db", "mariadbd", "mysqld"}) {
                if (!new File(root, "usr/sbin/" + name).isFile()
                        && !new File(root, "usr/bin/" + name).isFile()) continue;
                byte[] body = new String(mySqlWrap, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("@@NAME@@", name)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                File w = new File(root, "usr/local/bin/" + name);
                if (writeIfChanged(w, body)) {
                    //noinspection ResultOfMethodCallIgnored
                    w.setExecutable(true, false);
                }
            }
        } catch (Exception e) {
            DebugLog.w("GuestTools: compat " + e);
        }
    }

    /**
     * 预置 Debian 常见系统组/用户。
     *
     * 这些本该由 dbus-system-bus-common / openssh-client 的 postinst 用
     * adduser --system 建立，但非 root 下建不了（我们的 adduser 占位脚本能追加
     * 条目，可那些包的 postinst 未必会重跑）。dbus 的 dpkg-statoverride 与
     * openssh-client 的 chgrp 都需要能解析到组，缺了就把包永远留在
     * half-configured。幂等追加，已存在则跳过。
     */
    private static void ensureSystemGroups(File root) {
        try {
            File gf = new File(root, "etc/group");
            File pf = new File(root, "etc/passwd");
            if (!gf.isFile() || !pf.isFile()) return;
            String g = new String(java.nio.file.Files.readAllBytes(gf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String p = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            int id = 100;
            StringBuilder ga = new StringBuilder(), pa = new StringBuilder();
            for (String name : new String[]{"messagebus", "_ssh", "sshd"}) {
                boolean hasGroup = hasLine(g, name), hasUser = hasLine(p, name);
                while (g.contains(":" + id + ":")) id++;
                if (!hasGroup) ga.append(name).append(":x:").append(id).append(":\n");
                if (!hasUser) pa.append(name).append(":x:").append(id).append(':')
                        .append(id).append("::/nonexistent:/usr/sbin/nologin\n");
                id++;
            }
            if (ga.length() > 0) java.nio.file.Files.write(gf.toPath(),
                    ga.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            if (pa.length() > 0) java.nio.file.Files.write(pf.toPath(),
                    pa.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            DebugLog.w("GuestTools: system groups " + e);
        }
    }

    private static boolean hasLine(String text, String name) {
        for (String line : text.split("\n")) {
            if (line.startsWith(name + ":")) return true;
        }
        return false;
    }

    /**
     * 重建 /usr/bin/perl 符号链接（P1 自检）。
     *
     * Debian 的标准结构是 perl -> perl5.40.1 -> perl5.40-aarch64-linux-gnu。
     * 种子镜像解包偶尔丢链，链一断 adduser / update-rc.d 这些
     * #!/usr/bin/perl 维护脚本就报 "not found"，连锁把 postinst 打挂。
     * 与 ContainerRunner.prepare 修 merged-usr 断链是同一类问题。
     */
    private static void repairPerlLink(File root) {
        File bin = new File(root, "usr/bin");
        File perl = new File(bin, "perl");
        if (perl.exists()) return;                 // 有效文件或有效链接
        try {
            if (java.nio.file.Files.isSymbolicLink(perl.toPath())) {
                //noinspection ResultOfMethodCallIgnored
                perl.delete();                     // 断链，删掉重建
            }
            String target = null;
            File versioned = new File(bin, "perl5.40.1");
            if (versioned.exists()) {
                target = "perl5.40.1";
            } else {
                String[] names = bin.list();
                if (names != null) {
                    for (String n : names) {
                        if (n.startsWith("perl5.") && n.contains("-linux-gnu")
                                && new File(bin, n).isFile()) {
                            target = n;
                            break;
                        }
                    }
                }
            }
            if (target == null) return;            // 镜像里没有 perl，不硬造
            java.nio.file.Files.createSymbolicLink(perl.toPath(),
                    java.nio.file.Paths.get(target));
            DebugLog.i("GuestTools: repaired /usr/bin/perl -> " + target);
        } catch (Exception e) {
            DebugLog.w("GuestTools: perl link repair " + e);
        }
    }

    /**
     * 让 guest 的 nss/locale 数据文件走 shim 已经装好的 fd（3=resolv.conf，
     * 4=hosts，5=passwd，6=group，7=nsswitch）。
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
        // v4：补上 /etc/hosts 与 /etc/resolv.conf。会话 rootfs 之前只替换了
        // passwd/group/nsswitch 三项，hosts 仍是字面串 —— glibc 内部读的是宿主
        // Android 的 /etc/hosts（自带 127.0.0.1 localhost），所以 localhost 能解析、
        // 但往 <root>/etc/hosts 追加的自定义域名永远不生效。
        File marker = new File(root, ".dsh-nss-v4");
        if (marker.isFile() && marker.lastModified() >= libc.lastModified()) return;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(libc.toPath());
            boolean changed = replacePath(b, "/etc/hosts", "/dev/fd/4")
                    | replacePath(b, "/etc/resolv.conf", "/dev/fd/3")
                    | replacePath(b, "/etc/passwd", "/dev/fd/5")
                    | replacePath(b, "/etc/group", "/dev/fd/6")
                    | replacePath(b, "/etc/nsswitch.conf", "/dev/fd/7");
            // 注：sem_open 的 "/dev/shm/" 常量无法通过等长改写指向 /dev/fd/8
            // （"/dev/fd/8/" 多一个字节），改由 shim 的 zng_sem.c 直接实现。
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

    /** 标准 hosts：给 shim 的 /dev/fd/4 一个真实文件，localhost 不再只靠 glibc 内置规则。 */
    private static final String HOSTS =
            "127.0.0.1\tlocalhost\n"
            + "::1\tlocalhost ip6-localhost ip6-loopback\n"
            + "fe00::0\tip6-localnet\n"
            + "ff00::0\tip6-mcastprefix\n"
            + "ff02::1\tip6-allnodes\n"
            + "ff02::2\tip6-allrouters\n";
}
