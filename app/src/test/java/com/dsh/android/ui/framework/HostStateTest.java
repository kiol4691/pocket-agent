package com.dsh.android.ui.framework;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 宿主状态的纯格式化：比例夹取与 summary 文本。 */
public class HostStateTest {

    @Test
    public void ratioIsClampedToZeroOne() {
        assertEquals(0.0, HostState.ratio(-10, 100), 0.0001);
        assertEquals(0.5, HostState.ratio(50, 100), 0.0001);
        assertEquals(1.0, HostState.ratio(150, 100), 0.0001);
        assertEquals(0.0, HostState.ratio(10, 0), 0.0001);
    }

    @Test
    public void summaryMatchesDocumentExample() {
        assertEquals("62% · 62.0k / 100k", HostState.summary(62_000, 100_000));
        assertEquals("0% · 0 / 100k", HostState.summary(0, 100_000));
    }

    @Test
    public void compactKeepsSmallNumbersAndShortensThousands() {
        assertEquals("999", HostState.compact(999));
        assertEquals("1.0k", HostState.compact(1_000));
        assertEquals("62.0k", HostState.compact(62_000));
        assertEquals("100k", HostState.compact(100_000));
    }
}
