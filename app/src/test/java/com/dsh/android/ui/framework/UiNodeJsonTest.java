package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 视图 DSL 的 JSON → 节点树入口（走纯 Java 的 fromJava，避开 org.json 的 JVM stub）。 */
public class UiNodeJsonTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> text(String s) {
        return map("type", "text", "text", s);
    }

    @Test
    public void basicTree_keepsTypeKeyPropsChildren() {
        Map<String, Object> json = map(
                "type", "card", "key", "c", "tone", "accent", "pad", "sm",
                "children", Arrays.asList(text("a"), text("b")));
        UiNode n = UiNode.fromJava(json);
        assertEquals("card", n.type());
        assertEquals("c", n.key());
        assertEquals("accent", n.str("tone", ""));
        assertEquals(2, n.children().size());
        assertEquals("a", n.children().get(0).str("text", ""));
    }

    @Test
    public void idFallsBackAsKey() {
        UiNode n = UiNode.fromJava(map("type", "text", "id", "seg", "text", "x"));
        assertEquals("seg", n.key());
    }

    @Test
    public void unknownPropsAreKept_unknownTypeIsDropped() {
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "column", "children", Arrays.asList(
                map("type", "webview", "url", "https://x"),
                map("type", "text", "text", "ok", "data", map("k", 1)))), r);
        assertFalse(r.clean());
        assertEquals(1, r.dropped);
        assertEquals(1, n.children().size());              // 坏子树整棵丢
        assertTrue(n.children().get(0).props().get("data") instanceof Map);
    }

    @Test
    public void nestedJsonValuesBecomePlainMapsAndLists() {
        UiNode n = UiNode.fromJava(map("type", "bars", "values", Arrays.asList(1, 2, 3),
                "bind", map("value", "pocket.context.ratio", "format", "percent")));
        assertTrue(n.props().get("values") instanceof List);
        assertTrue(n.props().get("bind") instanceof Map);
        assertEquals(3, ((List<?>) n.props().get("values")).size());
    }

    @Test
    public void listItemsExpandToChildrenWithKeyField() {
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "list", "keyField", "id",
                "items", Arrays.asList(
                        map("id", "a", "type", "text", "text", "A"),
                        map("id", "b", "type", "text", "text", "B"))), r);
        assertEquals(2, n.children().size());
        assertEquals("a", n.children().get(0).key());
        assertEquals("b", n.children().get(1).key());
        assertTrue(r.clean());
    }

    @Test
    public void listItemsWithoutKeyFieldUsePosition() {
        UiNode n = UiNode.fromJava(map("type", "list",
                "items", Arrays.asList("one", "two")));
        assertEquals("#0", n.children().get(0).key());
        assertEquals("one", n.children().get(0).str("text", ""));
    }

    @Test
    public void depthLimitDropsSubtree() {
        Map<String, Object> root = map("type", "column");
        Map<String, Object> cur = root;
        for (int i = 0; i < 12; i++) {
            Map<String, Object> child = map("type", "column");
            cur.put("children", Arrays.asList(child));
            cur = child;
        }
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(root, r);
        assertNotNull(n);
        assertTrue(r.dropped > 0);
        assertTrue(anyWarningContains(r, "嵌套"));
    }

    @Test
    public void listItemsHaveTheirOwnNodeBudget() {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 300; i++) items.add(text("i" + i));
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "list", "items", items), r);
        assertEquals(300, n.children().size());
        assertEquals(1, r.nodes);          // list 本身算普通节点
        assertEquals(300, r.listNodes);    // items 不计入 200 的普通预算
        assertTrue(r.clean());
    }

    @Test
    public void listItemCountIsCapped() {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < UiNode.MAX_LIST_ITEMS + 20; i++) items.add(text("i" + i));
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "list", "items", items), r);
        assertEquals(UiNode.MAX_LIST_ITEMS, n.children().size());
        assertEquals(20, r.dropped);
        assertTrue(anyWarningContains(r, "list 超过"));
    }

    @Test
    public void listChildrenUseListBudget() {
        List<Object> kids = new ArrayList<>();
        for (int i = 0; i < 300; i++) kids.add(text("i" + i));
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "list", "children", kids), r);
        assertEquals(300, n.children().size());
        assertEquals(1, r.nodes);        // list 本身算普通节点
        assertEquals(300, r.listNodes);  // children 与 items 一样走列表预算
        assertTrue(r.clean());
    }

    @Test
    public void listChildrenAreCapped() {
        List<Object> kids = new ArrayList<>();
        for (int i = 0; i < UiNode.MAX_LIST_ITEMS + 20; i++) kids.add(text("i" + i));
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "list", "children", kids), r);
        assertEquals(UiNode.MAX_LIST_ITEMS, n.children().size());
        assertEquals(20, r.dropped);
        assertTrue(anyWarningContains(r, "list 超过"));
    }

    @Test
    public void listItemsWinWhenBothPresent() {
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "list",
                "items", Arrays.asList(text("item")),
                "children", Arrays.asList(text("child"))), r);
        assertEquals(1, n.children().size());
        assertEquals("item", n.children().get(0).str("text", ""));
    }

    @Test
    public void nodeBudgetDropsExcessChildren() {
        List<Object> kids = new ArrayList<>();
        for (int i = 0; i < 250; i++) kids.add(text("n" + i));
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(map("type", "column", "children", kids), r);
        assertEquals(UiNode.MAX_NODES, r.nodes);
        assertEquals(51, r.dropped);   // 250 个 child，1 个 root 吃掉 1 个名额
        assertEquals(199, n.children().size());
    }

    @Test
    public void longTextIsClippedWithWarning() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < UiNode.MAX_TEXT + 100; i++) sb.append('x');
        UiNode.ParseReport r = new UiNode.ParseReport();
        UiNode n = UiNode.fromJava(text(sb.toString()), r);
        assertTrue(anyWarningContains(r, "截断"));
        assertEquals(UiNode.MAX_TEXT + 1, n.str("text", "").length());
    }

    @Test
    public void allBuiltinTypesAreKnown() {
        String[] types = {"column", "row", "hscroll", "card", "text", "code", "markdown", "divider",
                "badge", "spacer", "progress", "bars", "image", "list", "nav.list",
                "button", "input", "switch", "checkbox", "slider",
                "text.chunks", "absolute", "window", "taskbar"};
        for (String t : types) assertTrue(t, UiNode.isKnownType(t));
        assertFalse(UiNode.isKnownType("webview"));
    }

    private static boolean anyWarningContains(UiNode.ParseReport r, String part) {
        for (String w : r.warnings) if (w.contains(part)) return true;
        return false;
    }
}
