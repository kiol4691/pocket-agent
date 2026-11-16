package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class ThemePackTest {

    private static Map<String, Object> tokens() {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Object> brand = new LinkedHashMap<>();
        brand.put("light", "#2F6FEB");
        brand.put("dark", "#7C9CFF");
        t.put("color.brand", brand);
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("light", "#111315");
        t.put("color.text", text);          // 只有 light，dark 回退 light
        t.put("color.caption", "#767C86");
        t.put("radius.card", 18);
        t.put("space.m", "20");
        t.put("font.body", 17);
        t.put("motion.enter", "fade-rise");
        return t;
    }

    @Test
    public void colorPicksLightOrDark() {
        ThemePack pack = ThemePack.fromJava(tokens());
        assertEquals(0xFF2F6FEB, (int) pack.color("color.brand", false));
        assertEquals(0xFF7C9CFF, (int) pack.color("color.brand", true));
    }

    @Test
    public void colorFallsBackToTheOtherSide() {
        ThemePack pack = ThemePack.fromJava(tokens());
        assertEquals(0xFF111315, (int) pack.color("color.text", true));
        assertEquals(0xFF111315, (int) pack.color("color.text", false));
    }

    @Test
    public void plainColorStringAndInvalidValues() {
        ThemePack pack = ThemePack.fromJava(tokens());
        assertEquals(0xFF767C86, (int) pack.color("color.caption", true));
        assertNull(pack.color("color.missing", true));
        assertNull(ThemePack.parseColor("#GGGGGG"));
        assertNull(ThemePack.parseColor("red"));
        assertNull(ThemePack.parseColor("#12345"));
        assertEquals(0x80123456, (int) ThemePack.parseColor("#80123456"));
    }

    @Test
    public void numbersAcceptIntAndString() {
        ThemePack pack = ThemePack.fromJava(tokens());
        assertEquals(18, (int) pack.number("radius.card"));
        assertEquals(20, (int) pack.number("space.m"));
        assertNull(pack.number("space.none"));
        assertNull(pack.number("font.body.bad"));
    }

    @Test
    public void stringsAndEmptyPack() {
        ThemePack pack = ThemePack.fromJava(tokens());
        assertEquals("fade-rise", pack.string("motion.enter"));
        assertNull(pack.string("motion.exit"));
        assertTrue(ThemePack.fromJava(null).isEmpty());
    }

    @Test
    public void spaceKeyMapping() {
        assertEquals("space.s", ThemePack.spaceKey("s"));
        assertEquals("space.m", ThemePack.spaceKey("m"));
        assertEquals("space.l", ThemePack.spaceKey("l"));
        assertEquals("space.gutter", ThemePack.spaceKey("gutter"));
        assertEquals("space.xs", ThemePack.spaceKey("xs"));
    }
}
