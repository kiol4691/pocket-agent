package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class PackTokenResolverTest {

    /** 默认解析器的替身：所有 token 都返回固定值，方便看回退。 */
    private static final class Base implements TokenResolver {
        @Override public float sizeSp(String token) { return 15f; }
        @Override public int textColor(String tone) { return 0xFF111111; }
        @Override public int cardColor(String tone) { return 0xFF222222; }
        @Override public int codeBackground() { return 0xFF333333; }
        @Override public int dividerColor() { return 0xFF444444; }
        @Override public int badgeColor(String tone) { return 0xFF555555; }
        @Override public int spaceDp(String token) { return 8; }
        @Override public int radiusDp(String token) { return 12; }
        @Override public int dp(float dp) { return (int) dp; }
        @Override public int brandColor() { return 0xFF666666; }
        @Override public int onBrandColor() { return 0xFF777777; }
        @Override public int borderColor() { return 0xFF888888; }
    }

    private static ThemePack pack() {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Object> brand = new LinkedHashMap<>();
        brand.put("light", "#2F6FEB");
        brand.put("dark", "#7C9CFF");
        t.put("color.brand", brand);
        t.put("color.codeBg", "#101010");
        t.put("color.border", "#202020");
        t.put("font.body", 20);
        t.put("space.m", 20);
        t.put("radius.card", 20);
        t.put("radius.chip", 99);
        t.put("motion.enter", "fade");
        return ThemePack.fromJava(t);
    }

    @Test
    public void overridesAndFallsBack() {
        PackTokenResolver r = new PackTokenResolver(new Base(), pack(), true);
        assertEquals(20f, r.sizeSp("body"), 0.001f);
        assertEquals(15f, r.sizeSp("title"), 0.001f);        // 没定义 → 默认
        assertEquals(0xFF7C9CFF, r.textColor("brand"));
        assertEquals(0xFF111111, r.textColor("primary"));     // 没定义 → 默认
        assertEquals(20, r.spaceDp("md"));
        assertEquals(8, r.spaceDp("lg"));
        assertEquals(20, r.radiusDp("md"));
        assertEquals(32, r.radiusDp("sm"));                   // radius.chip 99 → 夹到上限
        assertEquals(0xFF101010, r.codeBackground());
        assertEquals(0xFF202020, r.borderColor());
        assertEquals(0xFF7C9CFF, r.brandColor());             // 与 tone:brand 同源
        assertEquals(24, r.dp(24));
    }

    @Test
    public void clampsOutOfRangeValues() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("font.body", 100);
        t.put("space.m", -5);
        t.put("radius.card", 100);
        PackTokenResolver r = new PackTokenResolver(new Base(), ThemePack.fromJava(t), false);
        assertEquals(32f, r.sizeSp("body"), 0.001f);
        assertEquals(0, r.spaceDp("md"));
        assertEquals(32, r.radiusDp("md"));
    }

    @Test
    public void invalidColorFallsBack() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("color.brand", "not-a-color");
        PackTokenResolver r = new PackTokenResolver(new Base(), ThemePack.fromJava(t), false);
        assertEquals(0xFF111111, r.textColor("brand"));
        assertEquals(0xFF666666, r.brandColor());
    }

    @Test
    public void emptyPackEqualsDefault() {
        PackTokenResolver r = new PackTokenResolver(new Base(), null, false);
        assertEquals(15f, r.sizeSp("body"), 0.001f);
        assertEquals(8, r.spaceDp("md"));
        assertEquals(12, r.radiusDp("md"));
    }
}
