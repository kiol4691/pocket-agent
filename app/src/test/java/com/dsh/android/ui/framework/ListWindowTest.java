package com.dsh.android.ui.framework;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 列表虚拟化窗口：估算高度、实测高度与裁剪。 */
public class ListWindowTest {

    @Test
    public void emptyListHasEmptyWindow() {
        ListWindow.Range r = ListWindow.range(null, 0, 10, 0, 100);
        assertEquals(0, r.start);
        assertEquals(0, r.end);
    }

    @Test
    public void windowUsesEstimateWhenUnmeasured() {
        ListWindow.Range r = ListWindow.range(null, 100, 10, 0, 25);
        assertEquals(0, r.start);
        assertEquals(3, r.end);
        assertEquals(1000, ListWindow.totalHeight(null, 100, 10));
    }

    @Test
    public void windowUsesMeasuredHeights() {
        int[] heights = {100, 0, 50};
        ListWindow.Range r = ListWindow.range(heights, 3, 10, 110, 130);
        assertEquals(2, r.start);
        assertEquals(3, r.end);
        assertEquals(160, ListWindow.totalHeight(heights, 3, 10));
    }

    @Test
    public void estimateUsesMeasuredAverage() {
        int[] heights = {65, 0, 65, 0};
        assertEquals(65, ListWindow.estimateFromMeasured(heights, 128));
    }

    @Test
    public void estimateFallsBackWhenNothingMeasured() {
        assertEquals(128, ListWindow.estimateFromMeasured(new int[]{0, 0}, 128));
        assertEquals(128, ListWindow.estimateFromMeasured(null, 128));
        assertEquals(1, ListWindow.estimateFromMeasured(null, 0));
    }

    @Test
    public void windowIsClampedAtEnds() {
        ListWindow.Range start = ListWindow.range(null, 5, 10, -50, 8);
        assertEquals(0, start.start);
        assertEquals(1, start.end);

        ListWindow.Range end = ListWindow.range(null, 5, 10, 500, 600);
        assertEquals(4, end.start);
        assertEquals(5, end.end);
    }
}
