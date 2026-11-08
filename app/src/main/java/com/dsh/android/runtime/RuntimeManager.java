package com.dsh.android.runtime;

import android.content.Context;

import java.io.IOException;

/**
 * Owns the sandbox runtime and its install lifecycle.
 *
 * 后端默认是 **pocketroot**：Debian rootfs + LD_PRELOAD 路径翻译 shim（libzng.so），
 * 开源、零 ptrace。旧的 proroot 后端（ProrootRuntime + UbuntuRootfs）仍留在仓库里，
 * 但不再默认使用 —— 它需要闭源的 5 个 libproroot*.so，且只认 Ubuntu rootfs。
 */
public final class RuntimeManager {

    public enum State {
        NOT_INSTALLED,
        READY
    }

    private final Context context;
    private final PocketrootRuntime pocketroot;
    private final DebianRootfs rootfs;

    public RuntimeManager(Context context) {
        this.context = context.getApplicationContext();
        this.pocketroot = new PocketrootRuntime(this.context);
        this.rootfs = new DebianRootfs(pocketroot, this.context);
    }

    /** 当前运行时（工具层只依赖 ProrootRuntime 这层接口）。 */
    public ProrootRuntime runtime() {
        return pocketroot;
    }

    public State state() {
        return (pocketroot.binariesInstalled() && rootfs.fullyInstalled())
                ? State.READY : State.NOT_INSTALLED;
    }

    /** Verify the shim, then extract + prepare the bundled Debian rootfs. */
    public void setup(DebianRootfs.Progress progress) throws IOException {
        com.dsh.android.util.DebugLog.i("setup: verifying shim");
        progress.onProgress("Verifying runtime shim…");
        try {
            pocketroot.verifyBinaries();
        } catch (IOException e) {
            com.dsh.android.util.DebugLog.e("setup: verifyBinaries failed", e);
            throw e;
        }
        com.dsh.android.util.DebugLog.i("setup: shim ok, bootstrapping rootfs");
        progress.onProgress("Setting up Debian rootfs…");
        rootfs.bootstrap(progress);
        // 每次启动都刷新：换网络之后 DNS 会变（rootfs 已就绪时 bootstrap 是空操作）。
        rootfs.refreshResolvConf();
    }
}