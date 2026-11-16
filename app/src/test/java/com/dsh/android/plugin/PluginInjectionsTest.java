package com.dsh.android.plugin;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** bridge.tool.invoke({inject:true}) 的注入队列：单条 8 KB、单轮 16 KB、取走即清。 */
public class PluginInjectionsTest {

    @Before
    public void setUp() {
        PluginInjections.clear();
        PluginInjections.setActiveSession("");
    }

    @Test
    public void noteCarriesPluginAndToolThenClears() {
        PluginInjections.inject("weather", "web_search", "{\"ok\":true}");
        assertEquals(1, PluginInjections.pendingCount());

        List<String> notes = PluginInjections.takeForTurn(PluginInjections.MAX_TURN);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("[plugin weather · web_search]"));
        assertTrue(notes.get(0).contains("{\"ok\":true}"));
        assertEquals(0, PluginInjections.pendingCount());
    }

    @Test
    public void singleNoteIsClampedTo8k() {
        PluginInjections.inject("p", "bash", "x".repeat(PluginInjections.MAX_ONE * 2));
        assertTrue(PluginInjections.pendingChars() <= PluginInjections.MAX_ONE);

        List<String> notes = PluginInjections.takeForTurn(PluginInjections.MAX_TURN);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).length() <= PluginInjections.MAX_ONE);
        assertTrue(notes.get(0).endsWith("已截断)"));
    }

    @Test
    public void turnBudgetStopsAt16k() {
        PluginInjections.inject("p", "bash", "a".repeat(PluginInjections.MAX_ONE));
        PluginInjections.inject("p", "read", "b".repeat(PluginInjections.MAX_ONE));
        PluginInjections.inject("p", "grep", "c".repeat(PluginInjections.MAX_ONE));

        List<String> notes = PluginInjections.takeForTurn(PluginInjections.MAX_TURN);
        int total = 0;
        for (String note : notes) total += note.length();
        assertTrue(total <= PluginInjections.MAX_TURN);
        assertTrue(notes.size() >= 2);
        // 预算不足的部分留在下一轮，不能被整桶丢弃。
        assertEquals(1, PluginInjections.pendingCount());
        List<String> next = PluginInjections.takeForTurn(PluginInjections.MAX_TURN);
        assertEquals(1, next.size());
        assertTrue(next.get(0).contains("c".repeat(16)));
        assertEquals(0, PluginInjections.pendingCount());
    }

    @Test
    public void leftoverBudgetIsTruncated() {
        PluginInjections.inject("p", "bash", "z".repeat(PluginInjections.MAX_ONE));
        List<String> notes = PluginInjections.takeForTurn(100);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).length() <= 100);
    }

    @Test
    public void clearDropsPending() {
        PluginInjections.inject("p", "bash", "x");
        PluginInjections.clear();
        assertEquals(0, PluginInjections.pendingCount());
        assertEquals(0, PluginInjections.pendingChars());
        assertTrue(PluginInjections.takeForTurn(PluginInjections.MAX_TURN).isEmpty());
    }

    /** 注入按会话隔离：A 会话的备注不会被 B 会话的回合取走。 */
    @Test
    public void queueIsIsolatedBySession() {
        PluginInjections.setActiveSession("s1");
        PluginInjections.inject("p", "bash", "from-s1");
        PluginInjections.setActiveSession("s2");
        PluginInjections.inject("p", "bash", "from-s2");

        List<String> s1 = PluginInjections.takeForTurn("s1", PluginInjections.MAX_TURN);
        assertEquals(1, s1.size());
        assertTrue(s1.get(0).contains("from-s1"));
        assertEquals(1, PluginInjections.pendingCount("s2"));

        List<String> s2 = PluginInjections.takeForTurn("s2", PluginInjections.MAX_TURN);
        assertEquals(1, s2.size());
        assertTrue(s2.get(0).contains("from-s2"));
        assertEquals(0, PluginInjections.pendingCount());
    }

    /** 无参 takeForTurn 只取当前会话。 */
    @Test
    public void defaultTakeUsesActiveSession() {
        PluginInjections.setActiveSession("s1");
        PluginInjections.inject("p", "bash", "x");
        PluginInjections.setActiveSession("s2");
        assertTrue(PluginInjections.takeForTurn(PluginInjections.MAX_TURN).isEmpty());
        assertEquals(1, PluginInjections.pendingCount("s1"));
    }

    /** 没人取的旧会话队列有上限，避免长期运行吃内存。 */
    @Test
    public void oldSessionBucketsAreBounded() {
        for (int i = 0; i < 10; i++) {
            PluginInjections.setActiveSession("s" + i);
            PluginInjections.inject("p", "bash", "n" + i);
        }
        assertEquals(8, PluginInjections.pendingCount());
    }

    /** 单会话按条数封顶：超出丢最旧的，保留最新的。 */
    @Test
    public void perSessionNotesAreBounded() {
        for (int i = 0; i < 100; i++) PluginInjections.inject("p", "bash", "n" + i);
        assertEquals(PluginInjections.MAX_NOTES_PER_SESSION,
                PluginInjections.pendingCount());
        List<String> notes = PluginInjections.takeForTurn(PluginInjections.MAX_TURN);
        assertEquals(PluginInjections.MAX_NOTES_PER_SESSION, notes.size());
        assertTrue(notes.get(notes.size() - 1).contains("n99"));
    }

    /** 单会话按字符数封顶：64 KB 以内，超出丢最旧的。 */
    @Test
    public void perSessionCharsAreBounded() {
        String big = "x".repeat(PluginInjections.MAX_ONE);
        for (int i = 0; i < 20; i++) PluginInjections.inject("p", "bash", big);
        assertTrue(PluginInjections.pendingChars() <= PluginInjections.MAX_CHARS_PER_SESSION);
        assertTrue(PluginInjections.pendingCount() < 20);
    }

    /** 预算为 0 时一条都不取，但队列必须原样保留到下一轮。 */
    @Test
    public void zeroBudgetKeepsNotes() {
        PluginInjections.inject("p", "bash", "keep-me");
        assertTrue(PluginInjections.takeForTurn(0).isEmpty());
        assertEquals(1, PluginInjections.pendingCount());
        List<String> next = PluginInjections.takeForTurn(PluginInjections.MAX_TURN);
        assertEquals(1, next.size());
        assertTrue(next.get(0).contains("keep-me"));
    }

    /** beforeToolCall 的参数比较必须空安全（原实现 argsJson==null 时 NPE）。 */
    @Test
    public void nullArgsComparisonIsSafe() {
        assertTrue(HookRegistry.argsUnchanged(null, null));
        assertTrue(HookRegistry.argsUnchanged("{}", "{}"));
        assertFalse(HookRegistry.argsUnchanged("{\"a\":1}", null));
        assertFalse(HookRegistry.argsUnchanged(null, "{}"));
    }
}
