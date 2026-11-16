package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

public class WindowGeometryTest {

    private static final WindowGeometry DEF = new WindowGeometry(16, 72, 280, 320, false);

    @Test
    public void parseAndFormatRoundTrip() {
        WindowGeometry g = WindowGeometry.parse("20,30,300,400,1", DEF);
        assertEquals(20, g.x);
        assertEquals(30, g.y);
        assertEquals(300, g.w);
        assertEquals(400, g.h);
        assertTrue(g.minimized);
        assertEquals("20,30,300,400,1", g.format());
    }

    @Test
    public void invalidFallsBackToDefault() {
        assertSame(DEF, WindowGeometry.parse(null, DEF));
        assertSame(DEF, WindowGeometry.parse("1,2,3", DEF));
        assertSame(DEF, WindowGeometry.parse("a,b,c,d,e", DEF));
    }

    @Test
    public void negativeAndTinyValuesAreClamped() {
        WindowGeometry g = WindowGeometry.parse("-5,-6,10,20,0", DEF);
        assertEquals(0, g.x);
        assertEquals(0, g.y);
        assertEquals(WindowGeometry.MIN_W, g.w);
        assertEquals(WindowGeometry.MIN_H, g.h);
    }

    @Test
    public void withHelpersKeepOtherFields() {
        WindowGeometry g = DEF.withPosition(100, 120).withSize(400, 500).withMinimized(true);
        assertEquals(100, g.x);
        assertEquals(120, g.y);
        assertEquals(400, g.w);
        assertEquals(500, g.h);
        assertTrue(g.minimized);
    }
}
