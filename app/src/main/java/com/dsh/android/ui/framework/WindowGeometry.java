package com.dsh.android.ui.framework;

import java.util.Locale;

/**
 * 窗口几何（纯 Java，可 JVM 单测）：{@code x,y,w,h,minimized} 的解析与格式化。
 * 持久化用 SharedPreferences 的字符串；非法值回退默认并夹到下限。
 */
public final class WindowGeometry {

    public static final int MIN_W = 160;
    public static final int MIN_H = 120;

    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final boolean minimized;

    public WindowGeometry(int x, int y, int w, int h, boolean minimized) {
        this.x = Math.max(0, x);
        this.y = Math.max(0, y);
        this.w = Math.max(MIN_W, w);
        this.h = Math.max(MIN_H, h);
        this.minimized = minimized;
    }

    public static WindowGeometry parse(String raw, WindowGeometry fallback) {
        if (raw == null) return fallback;
        String[] parts = raw.split(",");
        if (parts.length != 5) return fallback;
        try {
            return new WindowGeometry(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    "1".equals(parts[4].trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String format() {
        return String.format(Locale.ROOT, "%d,%d,%d,%d,%d", x, y, w, h, minimized ? 1 : 0);
    }

    public WindowGeometry withMinimized(boolean minimized) {
        return new WindowGeometry(x, y, w, h, minimized);
    }

    public WindowGeometry withSize(int w, int h) {
        return new WindowGeometry(x, y, w, h, minimized);
    }

    public WindowGeometry withPosition(int x, int y) {
        return new WindowGeometry(x, y, w, h, minimized);
    }
}
