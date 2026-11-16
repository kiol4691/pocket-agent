package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public class UiTextTest {

    @Test
    public void emptyTextYieldsNoChunks() {
        assertTrue(UiText.splitChunks(null).isEmpty());
        assertTrue(UiText.splitChunks("").isEmpty());
        assertTrue(UiText.splitMarkdownBlocks(null).isEmpty());
    }

    @Test
    public void shortTextIsOneChunk() {
        List<String> c = UiText.splitChunks("hello\nworld");
        assertEquals(1, c.size());
        assertEquals("hello\nworld", c.get(0));
        // 短段落打包进同一块，避免流式时每段建一个 View
        assertEquals(1, UiText.splitChunks("p1\n\np2\n\np3").size());
    }

    @Test
    public void splitsNearParagraphBoundaryWhenChunkIsFull() {
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < 700; i++) a.append('a');
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 700; i++) b.append('b');
        String text = a + "\n\n" + b;                   // 1402 字
        List<String> c = UiText.splitChunks(text);
        assertEquals(2, c.size());
        assertEquals(a + "\n\n", c.get(0));
        assertEquals(b.toString(), c.get(1));
    }

    @Test
    public void hardSplitsWhenParagraphTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append("0123456789");   // 3000 字，无空行
        List<String> c = UiText.splitChunks(sb.toString());
        assertTrue(c.size() >= 3);
        for (String s : c) {
            assertTrue("块超过上限: " + s.length(), s.length() <= UiText.MAX_CHUNK);
        }
        assertEquals(sb.toString(), String.join("", c));
    }

    @Test
    public void markdownBlocksSplitOnBlankLines() {
        List<String> b = UiText.splitMarkdownBlocks("# 标题\n\n正文一\n\n正文二");
        assertEquals(3, b.size());
        assertEquals("# 标题", b.get(0));
        assertEquals("正文一", b.get(1));
        assertEquals("正文二", b.get(2));
    }

    @Test
    public void fenceKeepsBlankLinesInsideCode() {
        String md = "before\n\n```bash\necho a\n\necho b\n```\n\nafter";
        List<String> b = UiText.splitMarkdownBlocks(md);
        assertEquals(3, b.size());
        assertTrue(b.get(1).startsWith("```bash"));
        assertTrue(b.get(1).contains("echo b"));
        assertEquals("after", b.get(2));
    }

    @Test
    public void capChunksMergesOldest() {
        List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) chunks.add("p" + i + "\n\n");
        List<String> capped = UiText.capChunks(chunks, 4);
        assertEquals(4, capped.size());
        assertEquals("p0\n\np1\n\np2\n\np3\n\np4\n\np5\n\np6\n\n", capped.get(0));
        assertEquals("p9\n\n", capped.get(3));
        assertSame(chunks, UiText.capChunks(chunks, 10));
    }

    @Test
    public void truncateDoesNotSplitSurrogatePairs() {
        String emoji = "😀";                     // U+1F600，两个 char
        String s = "ab" + emoji + "cd";           // 长度 6
        assertEquals("ab", UiText.truncateTail(s, 3));   // 正好切在代理对前
        assertEquals("a", UiText.truncateTail(s, 1));
        assertEquals("cd", UiText.truncateHead(s, 3));   // 落在代理对中间时退回
        assertEquals("cd", UiText.truncateHead(s, 2));
        assertEquals(s, UiText.truncateTail(s, s.length()));
        assertEquals("", UiText.truncateTail(null, 5));
    }
}
