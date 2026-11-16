package com.dsh.android.ui.framework;

import java.util.Locale;
import java.util.Map;

/** 状态绑定的值格式化。纯 Java，方便 JVM 单测；不做单位猜测，只按声明的 format 处理。 */
public final class UiStateFormat {

    private UiStateFormat() {
    }

    /**
     * @param value 状态值
     * @param spec  字符串 = 状态名（不格式化，原样转字符串）；Map = 对象形式的绑定声明
     *              （{@code format} / {@code decimals} / {@code suffix}）
     */
    public static String apply(Object value, Object spec) {
        if (value == null) return "";
        if (!(spec instanceof Map)) return String.valueOf(value);
        Map<?, ?> m = (Map<?, ?>) spec;
        String fmt = str(m.get("format"), "raw");
        switch (fmt) {
            case "percent":
                return percent(asNumber(value));
            case "number":
                return number(asNumber(value), intOf(m.get("decimals"), 0), str(m.get("suffix"), ""));
            case "duration":
                return duration(asNumber(value));
            case "bytes":
                return bytes(asNumber(value));
            case "raw":
            default:
                return String.valueOf(value);
        }
    }

    /** 从绑定声明里取出状态名（对象形式 / 字符串形式都支持）。 */
    public static String stateName(Object spec) {
        if (spec instanceof String) return (String) spec;
        if (spec instanceof Map) return str(((Map<?, ?>) spec).get("state"), "");
        return "";
    }

    public static String percent(double v) {
        // 约定 0–1 为比例；明显大于 1 的当作已经是百分数
        double pct = (v >= 0 && v <= 1) ? v * 100 : v;
        return Math.round(pct) + "%";
    }

    public static String number(double v) {
        return number(v, 0, "");
    }

    public static String number(double v, int decimals, String suffix) {
        if (decimals <= 0) return Math.round(v) + suffix;
        return String.format(Locale.ROOT, "%." + Math.min(4, decimals) + "f%s", v, suffix);
    }

    public static String duration(double ms) {
        if (ms < 0) ms = 0;
        long total = (long) (ms / 1000);
        if (total < 1) return Math.round(ms) + "ms";
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    public static String bytes(double v) {
        if (v < 1024) return Math.round(v) + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double n = v;
        int i = -1;
        while (n >= 1024 && i < units.length - 1) {
            n /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", n, units[Math.max(0, i)]);
    }

    private static double asNumber(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }
}
