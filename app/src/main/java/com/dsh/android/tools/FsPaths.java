package com.dsh.android.tools;

import java.io.IOException;
import java.nio.file.Path;

/** 官方 tool-fs 的 guest 绝对路径（/root/workspace/...）→ app 工作区 Path。 */
public final class FsPaths {
    public static final String ROOT = "/root/workspace";

    private FsPaths() {
    }

    public static Path resolve(Path workspace, String guestPath) throws IOException {
        if (guestPath == null || guestPath.trim().isEmpty()) {
            throw new IOException("file_path must be a non-empty string");
        }
        String p = guestPath.trim().replace('\\', '/');
        if (!p.startsWith("/")) {
            throw new IOException("The path " + p + " is not an absolute path, it should start with `/`. "
                    + "Maybe you meant " + ROOT + "/" + p + "?");
        }
        if (!p.startsWith(ROOT)) p = ROOT + p;   // 其它绝对路径也落在工作区内，避免越界
        String rel = p.substring(ROOT.length());
        // Path.resolve 遇到绝对参数会直接返回它自己，前导 / 必须先去掉，否则永远越界
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path t = workspace.resolve(rel).normalize();
        if (!t.startsWith(workspace)) throw new IOException("Path escapes workspace: " + p);
        return t;
    }

    /** 模型看到的规范路径。 */
    public static String display(Path workspace, Path target) {
        String rel = workspace.relativize(target).toString().replace('\\', '/');
        return rel.isEmpty() ? ROOT : ROOT + "/" + rel;
    }
}
