package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ThemeValidatorTest {

    private static void put(Map<String, Object> t, String key, String color) {
        t.put(key, color);
    }

    @Test
    public void contrastRatioBlackAndWhite() {
        assertEquals(21.0, ThemeValidator.contrastRatio(0xFFFFFFFF, 0xFF000000), 0.01);
        assertEquals(1.0, ThemeValidator.contrastRatio(0xFF808080, 0xFF808080), 0.001);
    }

    @Test
    public void readableThresholds() {
        assertTrue(ThemeValidator.readable(0xFFFFFFFF, 0xFF000000, false));
        assertFalse(ThemeValidator.readable(0xFF444444, 0xFF000000, false));
        // 大字/图标 3.0 即可：白字黑底当然过，灰字黑底在 4.5 下不过、3.0 下也可能不过
        assertTrue(ThemeValidator.readable(0xFF767676, 0xFF000000, true));
    }

    @Test
    public void validPackHasNoWarnings() {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "color.bg", "#000000");
        put(t, "color.text", "#FFFFFF");
        put(t, "color.textSecondary", "#EEEEEE");
        put(t, "color.caption", "#CCCCCC");
        put(t, "color.brand", "#FFD700");
        List<String> warnings = ThemeValidator.validate(ThemePack.fromJava(t), true);
        assertTrue(warnings.toString(), warnings.isEmpty());
    }

    @Test
    public void lowContrastIsReportedNotBlocked() {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "color.bg", "#000000");
        put(t, "color.text", "#111111");
        put(t, "color.textSecondary", "#222222");
        put(t, "color.caption", "#333333");
        put(t, "color.brand", "#444444");
        List<String> warnings = ThemeValidator.validate(ThemePack.fromJava(t), true);
        assertEquals(4, warnings.size());
        assertTrue(warnings.get(0).contains("正文"));
        assertTrue(warnings.get(0).contains("对比度"));
    }

    @Test
    public void missingTokensAreIgnored() {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "color.brand", "#FFFFFF");
        assertTrue(ThemeValidator.validate(ThemePack.fromJava(t), true).isEmpty());
        assertTrue(ThemeValidator.validate(null, true).isEmpty());
    }

    @Test
    public void captionUsesNormalTextThreshold() {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "color.bg", "#000000");
        put(t, "color.caption", "#646464");     // 对比度约 3.5：过大字阈值、不过正文阈值
        List<String> warnings = ThemeValidator.validate(ThemePack.fromJava(t), true);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("说明文字"));
    }

    @Test
    public void statusColorsAreCheckedToo() {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "color.bg", "#000000");
        put(t, "color.ok", "#333333");
        put(t, "color.warn", "#333333");
        put(t, "color.danger", "#333333");
        assertEquals(3, ThemeValidator.validate(ThemePack.fromJava(t), true).size());
    }

    @Test
    public void touchTargetIsAtLeast40dp() {
        assertEquals(120, ThemeValidator.minTouchTargetPx(3f));
        assertEquals(110, ThemeValidator.minTouchTargetPx(2.75f));
    }
}
