package com.dsh.android.ui.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题校验（纯 Java）：对比度 + 最小触摸目标。
 * 对比度不达标只记警告、不拦启用（对应 §12.1「非法值回退默认，不影响启动」）。
 */
public final class ThemeValidator {

    /** WCAG 2.x 正文对比度阈值；大字/图标可用 3.0。 */
    public static final double MIN_TEXT = 4.5;
    public static final double MIN_LARGE = 3.0;
    /** 最小触摸目标 dp。 */
    public static final int MIN_TOUCH_DP = 40;

    private ThemeValidator() {
    }

    public static double contrastRatio(int fg, int bg) {
        double a = luminance(fg), b = luminance(bg);
        double hi = Math.max(a, b), lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    public static boolean readable(int fg, int bg, boolean largeText) {
        return contrastRatio(fg, bg) >= (largeText ? MIN_LARGE : MIN_TEXT);
    }

    public static int minTouchTargetPx(float density) {
        return Math.round(MIN_TOUCH_DP * density);
    }

    /** 返回警告列表；空列表表示通过。 */
    public static List<String> validate(ThemePack pack, boolean dark) {
        List<String> warnings = new ArrayList<>();
        if (pack == null || pack.isEmpty()) return warnings;
        Integer bg = pack.color("color.bg", dark);
        Integer surface = pack.color("color.surface", dark);
        int backdrop = bg == null ? (surface == null ? 0xFF151517 : surface) : bg;
        check(warnings, pack, "color.text", backdrop, dark, MIN_TEXT, "正文");
        check(warnings, pack, "color.textSecondary", backdrop, dark, MIN_TEXT, "次要文字");
        check(warnings, pack, "color.caption", backdrop, dark, MIN_TEXT, "说明文字");
        check(warnings, pack, "color.brand", backdrop, dark, MIN_LARGE, "品牌色");
        check(warnings, pack, "color.ok", backdrop, dark, MIN_TEXT, "成功色");
        check(warnings, pack, "color.warn", backdrop, dark, MIN_TEXT, "警告色");
        check(warnings, pack, "color.danger", backdrop, dark, MIN_TEXT, "危险色");
        return warnings;
    }

    private static void check(List<String> out, ThemePack pack, String key,
                              int backdrop, boolean dark, double min, String label) {
        Integer c = pack.color(key, dark);
        if (c == null) return;
        double ratio = contrastRatio(c, backdrop);
        if (ratio < min) {
            out.add(String.format(java.util.Locale.ROOT,
                    "%s(%s) 与背景对比度 %.2f < %.1f", label, key, ratio, min));
        }
    }

    private static double luminance(int color) {
        double r = srgb((color >> 16) & 0xFF);
        double g = srgb((color >> 8) & 0xFF);
        double b = srgb(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double srgb(int v) {
        double x = v / 255.0;
        return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }
}
