package com.dsh.android.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * 引用文件：把 SAF 选中的任意文件复制进 workspace/refs，模型按 guest 路径用工具读取。
 *
 * 不在客户端解析内容 —— 图片走 read_image，文本走 read，PDF/压缩包等走 bash，
 * 由模型自己决定怎么处理。
 */
public final class RefStore {
    public static final String GUEST_DIR = "/root/workspace/refs";
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private RefStore() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "workspace/refs");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static File save(Context c, Uri uri) throws Exception {
        String name = sanitize(displayName(c, uri));
        File f = new File(dir(c), UUID.randomUUID().toString().substring(0, 8) + "-" + name);
        long total = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new Exception("无法读取所选文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    f.delete();
                    throw new Exception("文件超过 50 MB");
                }
                out.write(buf, 0, n);
            }
        }
        return f;
    }

    /** 模型看到的 guest 绝对路径。 */
    public static String guestPath(File f) {
        return GUEST_DIR + "/" + f.getName();
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = cur.getString(i);
                    if (n != null && !n.trim().isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean bad = ch == '/' || ch == '\\' || ch == ':' || ch == '*'
                    || ch == '?' || ch == '"' || ch == '<' || ch == '>'
                    || ch == '|' || Character.isWhitespace(ch);
            sb.append(bad ? '_' : ch);
        }
        String s = sb.toString();
        if (s.isEmpty()) s = "file";
        return s.length() > 80 ? s.substring(s.length() - 80) : s;
    }
}
