package com.dsh.android.ui.framework;

import android.content.Context;

import com.dsh.android.R;

/**
 * 默认 token 解析器：值全部来自现有暗色主题（{@code res/values/colors.xml}）与
 * {@link UiTheme} 的常量表，保证「不装主题包时视觉零变化」。
 */
public final class DefaultTokenResolver implements TokenResolver {

    private final Context ctx;

    public DefaultTokenResolver(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public float sizeSp(String sizeToken) {
        return UiTheme.sizeSp(sizeToken);
    }

    @Override
    public int textColor(String tone) {
        return UiTheme.textColor(ctx, tone);
    }

    @Override
    public int cardColor(String tone) {
        return UiTheme.cardColor(ctx, tone);
    }

    @Override
    public int codeBackground() {
        return UiTheme.codeBackground(ctx);
    }

    @Override
    public int dividerColor() {
        return UiTheme.dividerColor(ctx);
    }

    @Override
    public int badgeColor(String tone) {
        return Builtins.badgeColor(ctx, tone);
    }

    @Override
    public int spaceDp(String token) {
        return UiTheme.spaceDp(token);
    }

    @Override
    public int radiusDp(String token) {
        return UiTheme.radiusDp(token);
    }

    @Override
    public int dp(float dp) {
        return UiTheme.dp(ctx, dp);
    }

    @Override
    public int brandColor() {
        return ctx.getColor(R.color.brand);
    }

    @Override
    public int onBrandColor() {
        return ctx.getColor(R.color.on_brand);
    }

    @Override
    public int borderColor() {
        return ctx.getColor(R.color.border);
    }
}
