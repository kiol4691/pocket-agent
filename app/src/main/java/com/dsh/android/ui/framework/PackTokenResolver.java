package com.dsh.android.ui.framework;

import java.util.Locale;

/**
 * 主题包 token 覆盖默认解析器。映射表见 {@code docs/PLUGIN_API.md} §12.1.1：
 * DSL 的 {@code size}/{@code tone}/{@code pad}/{@code gap}/{@code radius} 都落到主题 token。
 * 主题包没提供的项一律回退 {@link DefaultTokenResolver}，保证部分主题也能用。
 */
public final class PackTokenResolver implements TokenResolver {

    private final TokenResolver base;
    private final ThemePack pack;
    private final boolean dark;

    public PackTokenResolver(TokenResolver base, ThemePack pack, boolean dark) {
        this.base = base;
        this.pack = pack == null ? ThemePack.fromJava(null) : pack;
        this.dark = dark;
    }

    @Override
    public float sizeSp(String sizeToken) {
        String key = "font." + (sizeToken == null ? "body" : sizeToken);
        Integer v = pack.number(key);
        return v == null ? base.sizeSp(sizeToken) : clamp(v, 10, 32);
    }

    @Override
    public int textColor(String tone) {
        String key;
        switch (tone == null ? "primary" : tone) {
            case "secondary": key = "color.textSecondary"; break;
            case "caption": key = "color.caption"; break;
            case "brand": key = "color.brand"; break;
            case "ok": key = "color.ok"; break;
            case "warn": key = "color.warn"; break;
            case "danger": key = "color.danger"; break;
            case "primary":
            default: key = "color.text"; break;
        }
        Integer c = pack.color(key, dark);
        return c == null ? base.textColor(tone) : c;
    }

    @Override
    public int cardColor(String tone) {
        String key = "color.surface";
        if ("accent".equals(tone)) key = "color.brand";
        if ("warn".equals(tone)) key = "color.warn";
        if ("danger".equals(tone)) key = "color.danger";
        Integer c = pack.color(key, dark);
        return c == null ? base.cardColor(tone) : c;
    }

    @Override
    public int codeBackground() {
        Integer c = pack.color("color.codeBg", dark);
        return c == null ? base.codeBackground() : c;
    }

    @Override
    public int dividerColor() {
        Integer c = pack.color("color.border", dark);
        return c == null ? base.dividerColor() : c;
    }

    @Override
    public int badgeColor(String tone) {
        return base.badgeColor(tone);
    }

    @Override
    public int spaceDp(String token) {
        String t = token == null ? "none" : token.toLowerCase(Locale.ROOT);
        if ("none".equals(t)) return 0;
        String key;
        switch (t) {
            case "sm": key = "space.s"; break;
            case "md": key = "space.m"; break;
            case "lg": key = "space.l"; break;
            default: key = "space." + t; break;
        }
        Integer v = pack.number(key);
        if (v == null) v = pack.number("space.gutter");
        return v == null ? base.spaceDp(token) : clamp(v, 0, 48);
    }

    @Override
    public int radiusDp(String token) {
        String key = "radius.card";
        if ("sm".equals(token)) key = "radius.chip";
        if ("lg".equals(token)) key = "radius.button";
        Integer v = pack.number(key);
        return v == null ? base.radiusDp(token) : clamp(v, 0, 32);
    }

    @Override
    public int dp(float dp) {
        return base.dp(dp);
    }

    @Override
    public int brandColor() {
        Integer c = pack.color("color.brand", dark);
        return c == null ? base.brandColor() : c;
    }

    @Override
    public int onBrandColor() {
        return base.onBrandColor();
    }

    @Override
    public int borderColor() {
        Integer c = pack.color("color.border", dark);
        return c == null ? base.borderColor() : c;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
