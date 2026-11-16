package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class UiMotionTest {

    private static ThemePack pack(String enter, int duration) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("motion.enter", enter);
        t.put("motion.duration", duration);
        return ThemePack.fromJava(t);
    }

    @Test
    public void allowlist() {
        assertTrue(UiMotion.isAllowed("fade"));
        assertTrue(UiMotion.isAllowed("fade-rise"));
        assertTrue(UiMotion.isAllowed("spring"));
        assertFalse(UiMotion.isAllowed("bounce"));
        assertEquals("none", UiMotion.normalize("bounce"));
        assertEquals("slide-up", UiMotion.normalize("slide-up"));
    }

    @Test
    public void durationCappedAt400ms() {
        UiMotion m = UiMotion.from(pack("fade", 999), false);
        assertEquals(400, m.durationMs());
        assertEquals("fade", m.enter());
        assertTrue(m.enabled());
    }

    @Test
    public void reduceMotionDegradesToNone() {
        UiMotion m = UiMotion.from(pack("scale-in", 200), true);
        assertEquals("none", m.enter());
        assertEquals("none", m.exit());
        assertEquals(0, m.durationMs());
        assertFalse(m.enabled());
    }

    @Test
    public void defaultsAndMissingPack() {
        assertFalse(UiMotion.defaults(false).enabled());
        assertFalse(UiMotion.from(null, false).enabled());
        assertEquals(180, UiMotion.from(null, false).durationMs());
        assertFalse(UiMotion.from(pack("none", 180), false).enabled());
    }
}
