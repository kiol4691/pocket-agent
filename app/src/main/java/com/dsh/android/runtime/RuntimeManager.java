package com.dsh.android.runtime;

import android.content.Context;

import java.io.IOException;

/** Owns the proroot + Ubuntu runtime and its install lifecycle. */
public final class RuntimeManager {

    public enum State {
        NOT_INSTALLED,
        READY
    }

    private final Context context;
    private final ProrootRuntime proroot;
    private final UbuntuRootfs ubuntu;

    public RuntimeManager(Context context) {
        this.context = context.getApplicationContext();
        this.proroot = new ProrootRuntime(this.context);
        this.ubuntu = new UbuntuRootfs(proroot, this.context);
    }

    public ProrootRuntime proroot() {
        return proroot;
    }

    public State state() {
        return (proroot.binariesInstalled() && ubuntu.fullyInstalled()) ? State.READY : State.NOT_INSTALLED;
    }

    /** Install proroot binaries, then download/extract/bootstrap the Ubuntu rootfs. */
    public void setup(UbuntuRootfs.Progress progress) throws IOException {
        com.dsh.android.util.DebugLog.i("setup: installing proroot binaries");
        progress.onProgress("Installing proroot binaries…");
        try {
            proroot.verifyBinaries();
        } catch (IOException e) {
            com.dsh.android.util.DebugLog.e("setup: verifyBinaries failed", e);
            throw e;
        }
        com.dsh.android.util.DebugLog.i("setup: binaries installed, bootstrapping rootfs");
        progress.onProgress("Setting up Ubuntu rootfs…");
        ubuntu.bootstrap(progress);
    }
}