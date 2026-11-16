package com.dsh.android.ui.framework;

import android.content.Context;

import com.dsh.android.R;

/**
 * 把节点上的语义 token 解析成实际的字号/颜色/间距。
 *
 * 这一层是刻意做薄的：节点不允许写裸颜色和 px，换主题（以后由插件提供 token 表）
 * 只需要替换这里的查表，节点树和渲染器都不用动。
 */
final class UiTheme {

    private UiTheme() {
    }

    /** 字号 token，单位 sp。 */
    static float sizeSp(String token) {
        switch (token == null ? "body" : token) {
            case "title":
                return 17f;
            case "caption":
                return 12f;
            case "mono":
            case "code":
                return 13f;
            case "prose":
                return 17f;
            case "body":
            default:
                return 15f;
        }
    }

    /** 文字颜色 token。 */
    static int textColor(Context ctx, String token) {
        switch (token == null ? "primary" : token) {
            case "secondary":
                return ctx.getColor(R.color.text_secondary);
            case "caption":
                return ctx.getColor(R.color.text_caption);
            case "brand":
                return ctx.getColor(R.color.brand);
            case "ok":
                return ctx.getColor(R.color.accent_green);
            case "warn":
                return ctx.getColor(R.color.accent_amber);
            case "danger":
                return ctx.getColor(R.color.accent_red);
            case "primary":
            default:
                return ctx.getColor(R.color.text_primary);
        }
    }

    /** 卡片背景 token。 */
    static int cardColor(Context ctx, String token) {
        switch (token == null ? "surface" : token) {
            case "accent":
                return ctx.getColor(R.color.brand_tertiary);
            case "warn":
                return ctx.getColor(R.color.accent_amber_dark);
            case "danger":
                return ctx.getColor(R.color.accent_red_dark);
            case "surface":
            default:
                return ctx.getColor(R.color.layer1);
        }
    }

    static int codeBackground(Context ctx) {
        return ctx.getColor(R.color.code_bg);
    }

    static int dividerColor(Context ctx) {
        return ctx.getColor(R.color.border_light);
    }

    /** 间距 token：none / xs / sm / md / lg，单位 dp。 */
    static int spaceDp(String token) {
        switch (token == null ? "none" : token) {
            case "xs":
                return 4;
            case "sm":
                return 8;
            case "md":
                return 12;
            case "lg":
                return 16;
            case "none":
            default:
                return 0;
        }
    }

    /** 圆角 token，单位 dp。 */
    static int radiusDp(String token) {
        switch (token == null ? "md" : token) {
            case "sm":
                return 8;
            case "lg":
                return 16;
            case "md":
            default:
                return 12;
        }
    }

    static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
