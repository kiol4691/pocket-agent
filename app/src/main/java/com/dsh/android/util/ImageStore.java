package com.dsh.android.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * 图片附件：选图后缩放压缩存到 app 私有目录，发消息时再编码成 data URI。
 *
 * 为什么要落盘而不是把 base64 直接留在消息里：会话日志是 JSONL，一张图
 * base64 后几百 KB，几条消息就能把日志顶到几 MB；落盘后日志只记路径。
 */
public final class ImageStore {
    private static final int MAX_EDGE = 1024;
    private static final int QUALITY = 80;

    private ImageStore() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "images");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 从 SAF Uri 解码、等比缩放、压成 JPEG，返回私有目录里的文件。 */
    public static File save(Context c, Uri uri) throws Exception {
        Bitmap bmp = decode(c, uri);
        if (bmp == null) throw new Exception("无法解码图片");
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
        }
        File f = new File(dir(c), UUID.randomUUID().toString() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                throw new Exception("图片压缩失败");
            }
        }
        return f;
    }

    private static Bitmap decode(Context c, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > MAX_EDGE * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    /** 按文件后缀声明媒体类型；未知按 JPEG 处理。 */
    public static String mediaType(String path) {
        if (path == null) return "image/jpeg";
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** 图片像素尺寸（只读 bounds，不解码整张图）。 */
    public static int[] dimensions(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        return new int[]{Math.max(o.outWidth, 1), Math.max(o.outHeight, 1)};
    }

    /**
     * 保存 read_image 读到的字节：不超上限就原样落盘（保留原格式），
     * 超上限才缩放并压成 JPEG。返回私有目录里的文件。
     */
    public static File saveBytes(Context c, byte[] data, String ext, int maxEdge) throws Exception {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, b);
        boolean scale = Math.max(b.outWidth, b.outHeight) > maxEdge;
        String suffix = scale ? "jpg" : (ext.isEmpty() ? "jpg" : ext);
        File f = new File(dir(c), UUID.randomUUID().toString() + "." + suffix);
        if (!scale) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return f;
        }
        int sample = 1;
        int max = Math.max(b.outWidth, b.outHeight);
        while (max / sample > maxEdge * 2) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp == null) throw new Exception("无法解码图片");
        float k = Math.min(1f, (float) maxEdge / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (k < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * k),
                    Math.round(bmp.getHeight() * k), true);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                throw new Exception("图片压缩失败");
            }
        }
        return f;
    }

    /** OpenAI 兼容的 image_url data URI；读不到返回 null（跳过这张图而不是崩掉）。 */
    public static String dataUri(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return "data:" + mediaType(path) + ";base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 会话气泡/输入预览用的缩略图。 */
    public static Bitmap thumbnail(String path, int size) {
        if (path == null || !new File(path).isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int s = Math.max(1, Math.max(o.outWidth, o.outHeight) / Math.max(size, 1));
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Exception e) {
            return null;
        }
    }
}
