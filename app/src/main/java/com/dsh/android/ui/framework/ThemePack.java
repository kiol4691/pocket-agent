package com.dsh.android.ui.framework;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 主题包的 token 表（纯 Java，可 JVM 单测）。
 *
 * 只认 {@code docs/PLUGIN_API.md} §12.1 的命名：{@code color.*}（支持 {light,dark}）、
 * {@code radius.*} / {@code space.*}（dp）、{@code font.*}（sp）、{@code motion.*}。
 * 未知 token 忽略；非法值返回 null，由 {@link PackTokenResolver} 回退默认，不影响启动。
 */
public final class ThemePack {

    private final Map<String, Object> raw;

    private ThemePack(Map<String, Object> raw) {
        this.raw = raw;
    }

    public static ThemePack fromJava(Map<String, Object> tokens) {
        return new ThemePack(tokens == null
                ? Collections.emptyMap() : new LinkedHashMap<>(tokens));
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    /** @param key 完整 key，例如 {@code color.brand} */
    public Integer color(String key, boolean dark) {
        Object v = raw.get(key);
        if (v instanceof String) return parseColor((String) v);
        if (v instanceof Map) {
            Map<?, ?> pair = (Map<?, ?>) v;
            Object pick = pair.get(dark ? "dark" : "light");
            if (pick == null) pick = pair.get("dark");
            if (pick == null) pick = pair.get("light");
            if (pick instanceof String) return parseColor((String) pick);
        }
        return null;
    }

    /** @param key 完整 key，例如 {@code radius.card} / {@code space.m} / {@code font.body} */
    public Integer number(String key) {
        Object v = raw.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public String string(String key) {
        Object v = raw.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 解析 #RRGGBB / #AARRGGBB；非法返回 null（绝不抛）。 */
    public static Integer parseColor(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (!v.startsWith("#")) return null;
        String hex = v.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return null;
        try {
            long n = Long.parseLong(hex, 16);
            if (hex.length() == 6) n |= 0xFF000000L;
            return (int) n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 主题 token 名 → 视图 DSL 的 space 名（xs/s/m/l → xs/sm/md/lg）。 */
    public static String spaceKey(String token) {
        switch (token == null ? "none" : token.toLowerCase(Locale.ROOT)) {
            case "s":
                return "space.s";
            case "m":
                return "space.m";
            case "l":
                return "space.l";
            case "gutter":
                return "space.gutter";
            case "xs":
                return "space.xs";
            default:
                return "space." + token;
        }
    }
}
