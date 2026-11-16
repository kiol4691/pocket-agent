package com.dsh.android.ui.framework;

/** 语义 token → 实际值。主题包接入后替换这个实现，节点树与渲染器都不动。 */
public interface TokenResolver {

    float sizeSp(String sizeToken);

    int textColor(String tone);

    int cardColor(String tone);

    int codeBackground();

    int dividerColor();

    int badgeColor(String tone);

    /** @return dp 数（不是 px），由调用方再乘 density。 */
    int spaceDp(String token);

    int radiusDp(String token);

    /** dp → px。 */
    int dp(float dp);

    int brandColor();

    int onBrandColor();

    int borderColor();
}
