package com.dsh.android.ui.framework;

/**
 * {@code list} 虚拟化的窗口计算（纯 Java，可 JVM 单测）。
 *
 * 未测量过的行用 {@code estimatePx} 估算；已测量的行用真实高度。调用方把滚动带
 * （视口 + overscan）换算成像素区间，再从这里拿需要挂载的 [start, end)。
 */
public final class ListWindow {

    /** 列表行首次出现时的估算高度；实际高度测量后覆盖。 */
    public static final int DEFAULT_ESTIMATE_DP = 56;

    public static final class Range {
        public final int start;
        public final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int count() {
            return Math.max(0, end - start);
        }
    }

    private ListWindow() {
    }

    public static int itemHeight(int[] heights, int index, int estimatePx) {
        int h = heights != null && index >= 0 && index < heights.length ? heights[index] : 0;
        return h > 0 ? h : Math.max(1, estimatePx);
    }

    /** 第 index 行顶部相对列表顶部的像素偏移。 */
    public static int offsetOf(int[] heights, int count, int estimatePx, int index) {
        int n = Math.max(0, Math.min(index, count));
        int off = 0;
        for (int i = 0; i < n; i++) off += itemHeight(heights, i, estimatePx);
        return off;
    }

    public static int totalHeight(int[] heights, int count, int estimatePx) {
        return offsetOf(heights, count, estimatePx, count);
    }

    /** 已测行高的均值；没有测量过时返回 fallback（默认 56dp + gap）。 */
    public static int estimateFromMeasured(int[] heights, int fallback) {
        long sum = 0;
        int n = 0;
        if (heights != null) {
            for (int h : heights) {
                if (h > 0) {
                    sum += h;
                    n++;
                }
            }
        }
        return n == 0 ? Math.max(1, fallback)
                : (int) Math.max(1, Math.round((double) sum / n));
    }

    /** 返回与 [fromPx, toPx) 相交的行窗口；至少包含一行（count > 0 时）。 */
    public static Range range(int[] heights, int count, int estimatePx, int fromPx, int toPx) {
        if (count <= 0) return new Range(0, 0);
        int from = Math.max(0, fromPx);
        int to = Math.max(from, toPx);
        int start = -1;
        int end = 0;
        int top = 0;
        for (int i = 0; i < count; i++) {
            int bottom = top + itemHeight(heights, i, estimatePx);
            if (start < 0 && bottom > from) start = i;
            if (top < to) end = i + 1;
            top = bottom;
            if (start >= 0 && top >= to) break;
        }
        if (start < 0) start = count - 1;
        if (end <= start) end = Math.min(count, start + 1);
        return new Range(start, end);
    }
}
