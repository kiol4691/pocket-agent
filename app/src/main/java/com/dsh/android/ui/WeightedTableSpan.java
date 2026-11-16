package com.dsh.android.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 按内容加权分配列宽的表格 span，替代 Markwon 的 TableRowSpan（等分列宽）。
 *
 * 动因：默认实现把可用宽度平均分给各列，四列表格里文件名列只有 ~70dp，
 * `/etc/apt/sources.list.d/debian.sources` 会被拆成十几行单字。
 */
public class WeightedTableSpan extends ReplacementSpan {

    public static class Row {
        final List<String> cells;
        final boolean header;
        Row(List<String> cells, boolean header) { this.cells = cells; this.header = header; }
    }

    private static final int PAD_H = dp(8);
    private static final int PAD_V = dp(8);
    private static final int MIN_W = 1;
    private static final int MAX_W = 6;

    /** 下一次解析创建 span 时采用的可用宽度（px）。ReplacementSpan 的 getSize 拿不到容器宽度，
     *  由 {@link MarkdownRenderer#setText} 在 setMarkdown 之前写入，span 构造时捕获到实例字段。 */
    private static int pendingWidth;

    public static void setAvailableWidth(int px) {
        pendingWidth = px;
    }

    private static int dp(float v) {
        return (int) (v * android.content.res.Resources.getSystem().getDisplayMetrics().density + 0.5f);
    }

    private final List<Row> rows = new ArrayList<>();
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** 本 span 的可用宽度（px）：构造时从 pendingWidth 捕获，不能跨气泡共用静态值。 */
    private final int availableWidth;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int textColor = 0xFFCFD3D6;
    private final int headerTextColor = 0xFFF2F4F6;
    private final int headerBg = 0x1FFFFFFF;
    private final int lineColor = 0x14FFFFFF;

    private int[] widths;      // 每列像素宽
    private int[] rowTops;     // 每行顶部 y
    private int[] rowHeights;
    private int totalHeight;
    private int totalWidth;
    private Layout[][] layouts; // 单元格排版结果

    public WeightedTableSpan(float textSizePx) {
        paint.setTextSize(textSizePx);
        paint.setColor(textColor);
        this.availableWidth = pendingWidth;
        bgPaint.setColor(headerBg);
        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(1f);
    }

    public void addRow(List<String> cells, boolean header) { rows.add(new Row(cells, header)); }

    private void ensureMeasured(int avail) {
        if (widths != null && totalWidth == avail) return;
        final int columns = columns();
        if (columns == 0) { widths = new int[0]; totalWidth = 0; totalHeight = 0; return; }

        final int[] maxLen = new int[columns];
        for (Row r : rows) {
            for (int i = 0; i < columns && i < r.cells.size(); i++) {
                maxLen[i] = Math.max(maxLen[i], r.cells.get(i).length());
            }
        }
        final int[] weight = new int[columns];
        int sum = 0;
        for (int i = 0; i < columns; i++) {
            weight[i] = Math.max(MIN_W, Math.min(MAX_W, maxLen[i]));
            sum += weight[i];
        }
        widths = new int[columns];
        int used = 0;
        for (int i = 0; i < columns; i++) {
            widths[i] = (int) ((long) avail * weight[i] / sum);
            used += widths[i];
        }
        widths[columns - 1] += avail - used;   // 余数补给最后一列
        totalWidth = avail;

        layouts = new Layout[rows.size()][columns];
        rowTops = new int[rows.size()];
        rowHeights = new int[rows.size()];
        int y = 0;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            TextPaint tp = new TextPaint(paint);
            if (row.header) { tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setColor(headerTextColor); }
            int h = 0;
            for (int c = 0; c < columns; c++) {
                String text = c < row.cells.size() ? row.cells.get(c) : "";
                int w = Math.max(1, widths[c] - PAD_H * 2);
                Layout l = StaticLayout.Builder.obtain(text, 0, text.length(), tp, w)
                        .setIncludePad(false)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build();
                layouts[r][c] = l;
                h = Math.max(h, l.getHeight());
            }
            rowTops[r] = y;
            rowHeights[r] = h + PAD_V * 2;
            y += rowHeights[r];
        }
        totalHeight = y;
    }

    private int columns() {
        int n = 0;
        for (Row r : rows) n = Math.max(n, r.cells.size());
        return n;
    }

    @Override
    public int getSize(@NonNull Paint p, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        ensureMeasured(widthHint());
        if (fm != null) {
            fm.ascent = -totalHeight;
            fm.descent = 0;
            fm.top = fm.ascent;
            fm.bottom = 0;
        }
        return totalWidth;
    }

    private int widthHint() {
        return availableWidth > 0 ? availableWidth : defaultWidth;
    }

    private int defaultWidth = 720;

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint p) {
        ensureMeasured(totalWidth > 0 ? totalWidth : widthHint());

        float topY = y - totalHeight;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (row.header) {
                canvas.drawRect(x, topY + rowTops[r], x + totalWidth,
                        topY + rowTops[r] + rowHeights[r], bgPaint);
            }
            float cx = x;
            for (int c = 0; c < widths.length; c++) {
                Layout l = layouts[r][c];
                if (l != null) {
                    canvas.save();
                    canvas.translate(cx + PAD_H, topY + rowTops[r] + PAD_V);
                    l.draw(canvas);
                    canvas.restore();
                }
                cx += widths[c];
                if (c < widths.length - 1) {
                    canvas.drawLine(cx, topY + rowTops[r], cx,
                            topY + rowTops[r] + rowHeights[r], linePaint);
                }
            }
            canvas.drawLine(x, topY + rowTops[r] + rowHeights[r],
                    x + totalWidth, topY + rowTops[r] + rowHeights[r], linePaint);
        }
    }
}
