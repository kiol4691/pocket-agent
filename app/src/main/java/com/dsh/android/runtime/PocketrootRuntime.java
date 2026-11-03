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
    /** glibc rootfs 的动态链接器（rootfs 内路径）。 */
    private static final String LD_SO = "lib/ld-linux-aarch64.so.1";

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

    /** argv: ld.so <shell> [args…] —— shim 通过 LD_PRELOAD 注入，不进 argv。 */
    @Override
    protected List<String> launchCommand(String shell) {
        List<String> args = new ArrayList<>();
        args.add(new File(rootfsDir, LD_SO).getAbsolutePath());
        args.add(shell);
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
        env.put("ZNG_LOG", new File(tmpDir, "zng.log").getAbsolutePath());
        // PATH 必须显式给：默认会继承 Android 的 PATH（/system/bin），guest 里那些路径不存在。
        // python3 在 /usr/local/bin（Debian 的 python 镜像装在 /usr/local）。
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
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
