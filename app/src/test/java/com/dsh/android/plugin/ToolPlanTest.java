package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 插件工具重名解析：内置优先、工具自己的 priority→插件 id、能力门控。 */
public class ToolPlanTest {

    private static ToolPlan.Entry entry(String id, boolean gated, Object... namesAndPriorities) {
        Map<String, Integer> tools = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndPriorities.length; i += 2) {
            tools.put(String.valueOf(namesAndPriorities[i]), (Integer) namesAndPriorities[i + 1]);
        }
        return new ToolPlan.Entry(id, gated, tools);
    }

    private static Set<String> builtins(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    @Test
    public void lowerToolPriorityWins() {
        List<ToolPlan.Entry> entries = Arrays.asList(
                entry("slow", false, "echo", 10),
                entry("fast", false, "echo", 0));
        ToolPlan.Result r = ToolPlan.resolve(entries, builtins());
        assertEquals("fast", r.owners.get("echo"));
        assertTrue(r.conflicts.get("echo").contains("fast"));
    }

    @Test
    public void samePriorityFallsBackToPluginId() {
        List<ToolPlan.Entry> entries = Arrays.asList(
                entry("beta", false, "echo", 0),
                entry("alpha", false, "echo", 0));
        ToolPlan.Result r = ToolPlan.resolve(entries, builtins());
        assertEquals("alpha", r.owners.get("echo"));
    }

    /** 同一插件里不同工具各用各的 priority：互不影响。 */
    @Test
    public void perToolPriorityIsIndependent() {
        List<ToolPlan.Entry> entries = Arrays.asList(
                entry("a", false, "one", 10, "two", 0),
                entry("b", false, "one", 0, "two", 10));
        ToolPlan.Result r = ToolPlan.resolve(entries, builtins());
        assertEquals("b", r.owners.get("one"));
        assertEquals("a", r.owners.get("two"));
    }

    @Test
    public void builtinNamesAreNeverShadowed() {
        ToolPlan.Result r = ToolPlan.resolve(
                Collections.singletonList(entry("p", false, "bash", 0, "echo", 0)),
                builtins("bash"));
        assertNull(r.owners.get("bash"));
        assertEquals("p", r.owners.get("echo"));
        assertTrue(r.conflicts.get("bash").contains("内置工具"));
    }

    @Test
    public void gatedPluginsAreSkippedEntirely() {
        ToolPlan.Result r = ToolPlan.resolve(
                Collections.singletonList(entry("p", true, "echo", 0)),
                builtins());
        assertNull(r.owners.get("echo"));
        assertTrue(r.conflicts.isEmpty());
    }

    @Test
    public void distinctToolsFromManyPluginsAllSurvive() {
        ToolPlan.Result r = ToolPlan.resolve(Arrays.asList(
                entry("a", false, "one", 0),
                entry("b", false, "two", 0, "three", 0)), builtins());
        assertEquals("a", r.owners.get("one"));
        assertEquals("b", r.owners.get("two"));
        assertEquals("b", r.owners.get("three"));
        assertTrue(r.conflicts.isEmpty());
    }
}
