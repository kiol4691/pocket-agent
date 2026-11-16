package com.dsh.android.ui.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式文本的纯 Java 切块规则（可 JVM 单测）。
 *
 * 目标：增长中的文本不要整段 `setText`。按段落（空行）切成块，块内再按
 * {@link #MAX_CHUNK} 硬切；流式追加通常只改最后一块，前面的块原样复用。
 *
 * {@link #splitMarkdownBlocks} 额外保护 ``` 围栏：围栏内部的空行不切块，
 * 避免把一段代码拆成两次 Markdown 解析。
 */
public final class UiText {

    /** 单块上限：超过就硬切，避免一个 TextView 无限增长。 */
    public static final int MAX_CHUNK = 1024;
    /** 打包下限：段落边界至少要离块首这么远才值得切，避免每段一个 View。 */
    public static final int MIN_CHUNK = 256;

    private UiText() {
    }

    /** 普通文本切块：把段落打包进约 {@code max} 的块，单块不超过上限。 */
    public static List<String> splitChunks(String text) {
        return splitChunks(text, MAX_CHUNK);
    }

    public static List<String> splitChunks(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int cap = Math.max(64, max);
        int start = 0;
        int n = text.length();
        while (start < n) {
            if (n - start <= cap) {
                out.add(text.substring(start));
                break;
            }
            int limit = start + cap;
            int para = text.lastIndexOf("\n\n", limit);
            int end = para >= start + Math.min(MIN_CHUNK, cap / 2) ? para + 2 : limit;
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /** Markdown 分块：空行分段，``` 围栏内的空行不切。 */
    public static List<String> splitMarkdownBlocks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean fence = false;
        for (String line : text.split("\n", -1)) {
            String trim = line.trim();
            if (trim.startsWith("```")) fence = !fence;
            if (!fence && trim.isEmpty()) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * 块数上限：流式长会话的段数会一直涨，超过 {@code max} 时把最早的若干块合并成一块，
     * 保证 View 数有界（合并块可能超过 {@link #MAX_CHUNK}，但总量本来就被 MAX_TEXT 限制）。
     */
    public static List<String> capChunks(List<String> chunks, int max) {
        if (chunks == null || chunks.size() <= max) return chunks;
        int merge = chunks.size() - max + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < merge; i++) sb.append(chunks.get(i));
        List<String> out = new ArrayList<>(max);
        out.add(sb.toString());
        out.addAll(chunks.subList(merge, chunks.size()));
        return out;
    }

    /** 从尾部截断（保留开头），不切断代理对。 */
    public static String truncateTail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = Math.max(0, max);
        if (end > 0 && end < s.length()
                && Character.isHighSurrogate(s.charAt(end - 1))
                && Character.isLowSurrogate(s.charAt(end))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 从头部截断（保留结尾），不切断代理对。 */
    public static String truncateHead(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int start = s.length() - max;
        if (start > 0 && Character.isLowSurrogate(s.charAt(start))
                && Character.isHighSurrogate(s.charAt(start - 1))) {
            start++;
        }
        return s.substring(start);
    }
}
