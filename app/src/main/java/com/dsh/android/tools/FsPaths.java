package com.dsh.android.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * guest 绝对路径 → app 侧真实路径。
 *
 * 旧实现把「不是 /root/workspace 开头的绝对路径」直接拼到工作区后面
 * （/etc/os-release → /root/workspace/etc/os-release），read / glob / grep 因此读不到
 * 工作区外的任何文件。现在按最小挂载表解析：
 *
 *   /root/workspace → app 的 workspace 目录
 *   /images         → files/oci（pimg 的镜像目录，与会话里的 bind 一致）
 *   /sdcard         → 共享存储（未授权时自然失败）
 *   其余绝对路径     → rootfs 下的同名路径（/etc/os-release、/tmp/x.log…）
 */
public final class FsPaths {
    public static final String ROOT = "/root/workspace";
    private static final String IMAGES = "/images";
    private static final String SDCARD = "/sdcard";

    private static volatile Path rootfsDir;
    private static volatile Path imagesDir;
    private static volatile Path filesDir;

    private FsPaths() {
    }

    /** 由启动流程注入；null 表示该挂载不存在。 */
    public static void mounts(Path rootfs, Path images, Path files) {
        rootfsDir = rootfs == null ? null : rootfs.toAbsolutePath().normalize();
        imagesDir = images == null ? null : images.toAbsolutePath().normalize();
        filesDir = files == null ? null : files.toAbsolutePath().normalize();
    }

    private static Path inside(Path base, String relative, String original) throws IOException {
        String rel = relative;
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path t = rel.isEmpty() ? base : base.resolve(rel).normalize();
        if (!t.startsWith(base)) throw new IOException("Path escapes its mount: " + original);
        return t;
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
        p = Paths.get(p).normalize().toString().replace('\\', '/');

        Path ws = workspace.toAbsolutePath().normalize();
        if (p.equals(ROOT) || p.startsWith(ROOT + "/")) {
            return inside(ws, p.substring(ROOT.length()), p);
        }
        Path img = imagesDir;
        if (img != null && (p.equals(IMAGES) || p.startsWith(IMAGES + "/"))) {
            return inside(img, p.substring(IMAGES.length()), p);
        }
        if (p.equals(SDCARD) || p.startsWith(SDCARD + "/")) {
            Path host = android.os.Environment.getExternalStorageDirectory().toPath()
                    .toAbsolutePath().normalize();
            return inside(host, p.substring(SDCARD.length()), p);
        }
        // 宿主路径别名：模型有时会从 bash 输出里学到 /storage/emulated/0，也认它
        String hostSd = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (p.equals(hostSd) || p.startsWith(hostSd + "/")) {
            Path host = android.os.Environment.getExternalStorageDirectory().toPath()
                    .toAbsolutePath().normalize();
            return inside(host, p.substring(hostSd.length()), p);
        }
        // 宿主侧的 app 私有路径（日志、报错里经常出现）：只反解 guest 认识的三块，
        // 其余一律拒绝，避免把 /data/user/0/<pkg>/... 这类宿主路径回显给模型。
        Path files = filesDir;
        if (files != null && (p.equals(files.toString()) || p.startsWith(files + "/"))) {
            String rel = p.substring(files.toString().length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.equals("rootfs") || rel.startsWith("rootfs/")) {
                String sub = rel.substring("rootfs".length());
                if (rootfsDir == null) throw new IOException("Runtime is not ready yet.");
                return inside(rootfsDir, sub, p);
            }
            if (rel.equals("workspace") || rel.startsWith("workspace/")) {
                return inside(ws, rel.substring("workspace".length()), p);
            }
            if (rel.equals("oci") || rel.startsWith("oci/")) {
                if (imagesDir == null) throw new IOException("/images is not mounted.");
                return inside(imagesDir, rel.substring("oci".length()), p);
            }
            throw new IOException("Not exposed to the guest: " + p
                    + ". Use a guest path such as /etc/... or " + ROOT + "/...");
        }

        Path root = rootfsDir;
        if (root == null) {
            throw new IOException("No rootfs mounted yet; cannot resolve " + p
                    + ". Use a path under " + ROOT + " or wait for the runtime to start.");
        }
        return inside(root, p, p);
    }

    /** 模型看到的规范路径。 */
    public static String display(Path workspace, Path target) {
        Path t = target.toAbsolutePath().normalize();
        Path ws = workspace.toAbsolutePath().normalize();
        if (t.startsWith(ws)) return join(ROOT, ws.relativize(t).toString());
        Path img = imagesDir;
        if (img != null && t.startsWith(img)) return join(IMAGES, img.relativize(t).toString());
        Path sd = android.os.Environment.getExternalStorageDirectory().toPath()
                .toAbsolutePath().normalize();
        if (t.startsWith(sd)) return join(SDCARD, sd.relativize(t).toString());
        Path root = rootfsDir;
        if (root != null && t.startsWith(root)) return join("", root.relativize(t).toString());
        return t.toString();
    }

    private static String join(String prefix, String rel) {
        String r = rel.replace('\\', '/');
        if (r.isEmpty()) return prefix.isEmpty() ? "/" : prefix;
        return prefix + "/" + r;
    }
}
