package com.dsh.android.plugin;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** 渲染器匹配语义：fence / regex / prefix、大小写、纯文本限制与排序。 */
public class RendererRuleTest {

    private static RendererRule fence(String pluginId, String id, int priority,
                                      String... langs) {
        return new RendererRule(pluginId, id, priority,
                RendererRule.MATCH_FENCE, Arrays.asList(langs), null, null, "render_" + id);
    }

    private static RendererRule regex(String pluginId, String id, int priority,
                                      String pattern) {
        return new RendererRule(pluginId, id, priority,
                RendererRule.MATCH_REGEX, null, pattern, null, "render_" + id);
    }

    private static RendererRule prefix(String pluginId, String id, int priority,
                                       String prefix) {
        return new RendererRule(pluginId, id, priority,
                RendererRule.MATCH_PREFIX, null, null, prefix, "render_" + id);
    }

    @Test
    public void fence_matchesCaseInsensitively_andOnlyCodeBlocks() {
        RendererRule r = fence("deploy_card", "deploy", 0, "Deploy");
        assertTrue(r.matches("fence", "deploy", "env=prod"));
        assertTrue(r.matches("fence", "DEPLOY", "env=prod"));
        assertFalse(r.matches("fence", "json", "{}"));
        assertFalse(r.matches("text", "deploy", "env=prod"));
    }

    @Test
    public void prefix_matchesPlainTextOnly_andIsCaseSensitive() {
        RendererRule r = prefix("p", "pre", 0, "DEPLOY:");
        assertTrue(r.matches("text", "", "DEPLOY: prod\nstatus=ok"));
        assertFalse(r.matches("text", "", "deploy: prod"));
        assertFalse(r.matches("text", "", "x DEPLOY: prod"));
        assertFalse(r.matches("fence", "text", "DEPLOY: prod"));
    }

    @Test
    public void regex_findsAnywhereInPlainTextOnly() {
        RendererRule r = regex("p", "status", 0, "^\\[status\\]\\s*(.+)$");
        assertTrue(r.matches("text", "", "[status] ok"));
        assertFalse(r.matches("text", "", "status ok"));
        assertFalse(r.matches("fence", "text", "[status] ok"));
    }

    @Test
    public void emptyBody_doesNotMatch() {
        assertFalse(prefix("p", "pre", 0, "X").matches("text", "", ""));
        assertFalse(regex("p", "re", 0, "^X").matches("text", "", ""));
    }

    @Test
    public void invalidRegex_isRejected() {
        try {
            regex("p", "bad", 0, "[unclosed");
            fail("非法正则应在构造时抛出");
        } catch (PatternSyntaxException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** 链顺序只看渲染器自己的 priority，插件级 priority 不再影响（P2 解耦）。 */
    @Test
    public void order_rendererPriorityThenPluginAndRuleIds() {
        List<RendererRule> rules = new ArrayList<>(Arrays.asList(
                fence("b_plugin", "r1", 0, "x"),
                fence("a_plugin", "r2", 5, "x"),
                fence("a_plugin", "r1", 5, "x"),
                fence("a_plugin", "r0", 1, "x"),
                fence("c_plugin", "r0", 0, "x")));
        Collections.sort(rules, RendererRule.ORDER);

        assertEquals("b_plugin/r1", rules.get(0).pluginId + "/" + rules.get(0).id);
        assertEquals("c_plugin/r0", rules.get(1).pluginId + "/" + rules.get(1).id);
        assertEquals("a_plugin/r0", rules.get(2).pluginId + "/" + rules.get(2).id);
        assertEquals("a_plugin/r1", rules.get(3).pluginId + "/" + rules.get(3).id);
        assertEquals("a_plugin/r2", rules.get(4).pluginId + "/" + rules.get(4).id);
    }

    @Test
    public void matches_resetsTimeoutFlag() {
        RendererRule r = prefix("p", "pre", 0, "A");
        r.lastTimedOut = true;
        assertTrue(r.matches("text", "", "A"));
        assertFalse(r.lastTimedOut);
    }
}
