package com.dsh.android.ui.framework;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 插件/主题贴图加载（{@code asset:} / {@code asset://}），带体积闸门。
 *
 * 口径见 {@code docs/PLUGIN_API.md} §12.1：
 *  - 单文件上限 {@link #MAX_FILE_BYTES}，同一实例累计上限 {@link #MAX_TOTAL_BYTES}；
 *  - 路径必须落在资源目录内，{@code ..} 之类的越界地址直接拒绝；
 *  - 只解 PNG/WebP/JPEG；SVG 需要矢量解析，本阶段不支持（返回 null 并记 warning）。
 *
 * 超限与失败都不抛异常，只进 {@link #warnings()}，由调用方展示。
 */
public final class UiAssets {

    public static final long MAX_FILE_BYTES = 256 * 1024;
    public static final long MAX_TOTAL_BYTES = 4 * 1024 * 1024;

    private final File baseDir;
    private final List<String> warnings = new ArrayList<>();
    private long usedBytes;

    public UiAssets(File baseDir) {
        this.baseDir = baseDir;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** {@code asset:} 或 {@code asset://} 开头的地址；其它 scheme 返回 null。 */
    static String assetPath(String src) {
        if (src == null) return null;
        if (src.startsWith("asset://")) return src.substring(8);
        if (src.startsWith("asset:")) return src.substring(6);
        return null;
    }

    /** 按插件资源地址加载；失败返回 null（原因见 {@link #warnings()}）。 */
    public Bitmap load(String src) {
        String rel = assetPath(src);
        if (rel == null || rel.isEmpty()) {
            warn("不支持的图片地址: " + src);
            return null;
        }
        if (rel.toLowerCase(Locale.ROOT).endsWith(".svg")) {
            warn("SVG 需要矢量解析，本阶段不支持: " + rel);
            return null;
        }
        File file = resolve(rel);
        return file == null ? null : loadFile(file);
    }

    /** 直接读一个文件（工作区图片等），同样受单文件与总量上限约束。 */
    public Bitmap loadFile(File file) {
        if (file == null || !file.isFile()) {
            warn("图片不存在");
            return null;
        }
        long len = file.length();
        if (len > MAX_FILE_BYTES) {
            warn(String.format(Locale.ROOT, "图片超过 %d KB: %s", MAX_FILE_BYTES / 1024, file.getName()));
            return null;
        }
        if (usedBytes + len > MAX_TOTAL_BYTES) {
            warn(String.format(Locale.ROOT, "图片总量超过 %d MB", MAX_TOTAL_BYTES / 1024 / 1024));
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = readAll(in);
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) {
                warn("图片解码失败: " + file.getName());
                return null;
            }
            usedBytes += len;
            return bmp;
        } catch (IOException e) {
            warn("图片读取失败: " + file.getName());
            return null;
        }
    }

    private File resolve(String rel) {
        if (baseDir == null) {
            warn("未配置资源目录");
            return null;
        }
        File file = new File(baseDir, rel);
        try {
            String base = baseDir.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (!path.startsWith(base + File.separator)) {
                warn("资源路径越界: " + rel);
                return null;
            }
        } catch (IOException e) {
            warn("资源路径非法: " + rel);
            return null;
        }
        return file;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_FILE_BYTES) throw new IOException("超过单文件上限");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void warn(String text) {
        warnings.add(text);
    }
}
