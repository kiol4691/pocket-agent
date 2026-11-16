package com.dsh.android.ui.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨控件选择的纯 Java 模型：把若干文本段拼成一条"可选流"，负责偏移映射与复制文本。
 *
 * 相邻两段属于同一 owner（例如同一个 text.chunks 节点切出来的块）时直接拼接；
 * 不同 owner 之间视作换行，选中跨段时补一个 {@code '\n'}。offset 与 Android
 * {@code Layout} 一样按 UTF-16 char 计。
 */
public final class SelectionModel {

    /** 一段可选文本。owner 用**身份比较**，避免不同控件恰好同名时被误拼在一起。 */
    public static final class Part {
        public final String text;
        public final Object owner;

        public Part(String text, Object owner) {
            this.text = text == null ? "" : text;
            this.owner = owner;
        }
    }

    private final List<Part> parts;
    private final int[] starts;
    private final boolean[] newlineBefore;
    private final int total;

    public SelectionModel(List<Part> parts) {
        this.parts = new ArrayList<>(parts == null ? new ArrayList<>() : parts);
        int n = this.parts.size();
        starts = new int[n];
        newlineBefore = new boolean[n];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && this.parts.get(i - 1).owner != this.parts.get(i).owner) {
                newlineBefore[i] = true;
                pos++;
            }
            starts[i] = pos;
            pos += this.parts.get(i).text.length();
        }
        total = pos;
    }

    public int size() {
        return parts.size();
    }

    public int total() {
        return total;
    }

    public Part part(int index) {
        return parts.get(index);
    }

    /** 第 index 段正文的起始偏移（不含它前面的换行）。 */
    public int startOf(int index) {
        return starts[index];
    }

    public int endOf(int index) {
        return starts[index] + parts.get(index).text.length();
    }

    /** 复制 [from, to)：跨段时按规则补换行，不会在结尾多出换行。 */
    public String copy(int from, int to) {
        int a = clamp(from);
        int b = clamp(to);
        if (b <= a) return "";
        StringBuilder sb = new StringBuilder(b - a);
        for (int i = 0; i < parts.size(); i++) {
            int ps = starts[i];
            int pe = ps + parts.get(i).text.length();
            if (pe <= a || ps >= b) continue;
            if (newlineBefore[i] && a <= ps - 1 && b > ps - 1) sb.append('\n');
            int s = Math.max(a, ps);
            int e = Math.min(b, pe);
            if (e > s) sb.append(parts.get(i).text, s - ps, e - ps);
        }
        return sb.toString();
    }

    private int clamp(int offset) {
        return Math.max(0, Math.min(offset, total));
    }
}
