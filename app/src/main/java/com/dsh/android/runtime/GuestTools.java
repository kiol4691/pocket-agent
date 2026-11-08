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
}
