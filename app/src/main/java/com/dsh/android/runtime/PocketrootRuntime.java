package com.dsh.android.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs commands inside a Debian rootfs using pocketroot (LD_PRELOAD path-translation shim,
 * zero ptrace) instead of proroot.
 *
 * 与 proroot 的差别只有启动方式：
 *
 *   proroot    : exec 一个可执行的 launcher（libproroot.so），由它自己做路径翻译
 *   pocketroot : exec 的仍是 rootfs 里的 ld.so，但注入 LD_PRELOAD=libzng.so，
 *                路径翻译发生在 libc 的 syscall 包装层
 *
 * 所以这里只覆盖四个钩子：启动器文件、argv、环境变量、工作目录。会话/超时/进程组回收
 * 全部复用 {@link ProrootRuntime}。
 *
 * 环境变量约定（见 pocketroot 仓库 docs）：
 *   ZNG_ROOT      rootfs 根目录（guest "/" 映射到哪里）
 *   ZNG_BINDS     bind 表，每行 "host\tguest"
 *   ZNG_PRELOAD   shim 路径（子进程 exec 后仍要注入）
 *   ZNG_LOG       shim 日志写这个文件，stderr 保持干净
 *   GLIBC_TUNABLES=glibc.pthread.rseq=0   Android seccomp 拦 rseq，必须关掉
 */
public final class PocketrootRuntime extends ProrootRuntime {

    private static final String SHIM_NAME = "libzng.so";
    /**
     * 动态链接器的**副本**，随 APK 打进 nativeLibraryDir。
     *
     * 不能直接 exec rootfs 里的那份：targetSdk ≥ 29 时 Android 禁止执行 app 数据目录
     * 下的文件（W^X，实测报 Permission denied / exit 126）。nativeLibraryDir 是
     * apk_data_file，仍允许 exec —— proroot 当年把 launcher 放这里也是同一个原因。
     * 这份副本来自 Debian rootfs 的 /usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1。
     */
    private static final String LD_SO_LIB = "libldso.so";

    public PocketrootRuntime(Context context) {
        super(context);
    }

    @Override
    protected File launcherFile() {
        return new File(nativeLibDir, SHIM_NAME);
    }

    @Override
    protected String launcherName() {
        return "pocketroot shim (" + SHIM_NAME + ")";
    }

    /**
     * argv: <libldso.so> <rootfs 里的 shell 绝对路径> [args…]
     *
     * 注意第二个参数必须是**宿主路径**（rootfs 下的真实文件），不能是 guest 的 /bin/bash：
     * ld.so 是原生程序，它按真实路径打开目标 ELF，而 shim 这时还没初始化，翻译不了。
     * 一旦 bash 起来，它 exec 的命令走 PATH（guest 路径），才由 shim 负责翻译。
     */
    @Override
    protected List<String> launchCommand(String shell) {
        List<String> args = new ArrayList<>();
        args.add(new File(nativeLibDir, LD_SO_LIB).getAbsolutePath());
        args.add(new File(rootfsDir, shell.startsWith("/") ? shell.substring(1) : shell)
                .getAbsolutePath());
        return args;
    }

    @Override
    protected File launchCwd() {
        return new File(rootfsDir, "root/workspace");
    }

    @Override
    protected void applyLauncherEnv(Map<String, String> env) {
        File shim = launcherFile();
        env.put("LD_PRELOAD", shim.getAbsolutePath());
        env.put("ZNG_PRELOAD", shim.getAbsolutePath());
        env.put("ZNG_ROOT", rootfsDir.getAbsolutePath());
        env.put("ZNG_BINDS", binds());
        env.put("LD_LIBRARY_PATH", libPath());
        // Android app 进程的 seccomp 拦 rseq(293)：不关掉，glibc 启动即 SIGSYS。
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        // glibc 的 setlocale 在 libc **内部** open /usr/lib/locale/<locale>/LC_CTYPE，
        // 不走 PLT，路径不翻译就落到宿主 Android 的 /usr/lib/locale（不存在），
        // 于是 LANG=C.UTF-8 仍报 "Cannot set LC_CTYPE ... No such file or directory"
        // （tmux 直接拒绝启动）。locale 路径是动态拼接的，等长替换搞不定；
        // LOCPATH 接受宿主绝对路径，是让内部 open 命中 rootfs 的唯一办法。
        env.put("LOCPATH", rootfsDir.getAbsolutePath() + "/usr/lib/locale");
        env.put("ZNG_LOG", new File(tmpDir, "zng.log").getAbsolutePath());
        // PATH 必须显式给：默认会继承 Android 的 PATH（/system/bin），guest 里那些路径不存在。
        // python3 在 /usr/local/bin（Debian 的 python 镜像装在 /usr/local）。
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        // Android 把 TMPDIR 指到 app 的 cache（宿主路径）；guest 里不存在，
        // dpkg-deb / apt 建临时目录必失败。固定到 guest 的 /tmp。
        env.put("TMPDIR", "/tmp");
        // pimg（镜像管理）：客体路径 + 同一目录的宿主路径（run 时需要把 ZNG_ROOT 指到宿主路径）
        File images = new File(rootfsDir.getParentFile(), "oci");
        env.put("PIMG_IMAGES", "/images");
        env.put("PIMG_HOST_IMAGES", images.getAbsolutePath());
        env.put("PIMG_PATCHER", "/usr/local/lib/pimg/patch-glibc.py");
    }

    /**
     * bind 表：每行 host<TAB>guest。
     *
     * /proc /dev /sys 走宿主的（guest 里没有真实挂载）；workspace 让宿主的可写目录出现在
     * guest 的 /root/workspace；共享存储只在拿到 All-Files-Access 时绑，否则 app 进程连
     * stat 都会被 SELinux 拒绝。
     */
    private String binds() {
        StringBuilder sb = new StringBuilder();
        sb.append("/proc\t/proc\n");
        sb.append("/dev\t/dev\n");
        sb.append("/sys\t/sys\n");
        sb.append(workspaceDir.getAbsolutePath()).append("\t/root/workspace\n");
        // 镜像目录：pimg 在 guest 里看到的 /images 就是 files/oci
        // （别用 files/images —— 那是聊天图片的目录）
        File images = new File(rootfsDir.getParentFile(), "oci");
        //noinspection ResultOfMethodCallIgnored
        images.mkdirs();
        sb.append(images.getAbsolutePath()).append("\t/images\n");
        if (storageAccessGranted()) {
            File guestSdcard = new File(rootfsDir, "sdcard");
            if (guestSdcard.isDirectory() || guestSdcard.mkdirs()) {
                sb.append("/storage/emulated/0\t/sdcard\n");
            }
        }
        return sb.toString();
    }

    /** glibc 的库搜索路径：/usr/lib 里是 GNU ld script，混进去会让 ld.so 报 bad ELF magic。 */
    private String libPath() {
        String r = rootfsDir.getAbsolutePath();
        return r + "/lib:" + r + "/lib64:" + r + "/usr/lib/aarch64-linux-gnu";
    }
}
