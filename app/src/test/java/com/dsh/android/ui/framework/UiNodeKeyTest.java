package com.dsh.android.ui.framework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** list / children 的稳定 key：同父去重，id 同时保留为 prop 供 binding 使用。 */
public class UiNodeKeyTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void duplicateItemKeysGetUniqueSuffix() {
        UiNode n = UiNode.fromJava(map("type", "list", "items", Arrays.asList(
                map("type", "text", "id", "x", "text", "A"),
                map("type", "text", "id", "x", "text", "B"))));
        assertEquals(2, n.children().size());
        assertEquals("x", n.children().get(0).key());
        assertNotEquals("x", n.children().get(1).key());
        assertTrue(n.children().get(1).key().startsWith("x#"));
    }

    @Test
    public void duplicateChildKeysGetUniqueSuffix() {
        UiNode n = UiNode.fromJava(map("type", "column", "children", Arrays.asList(
                map("type", "text", "key", "k", "text", "A"),
                map("type", "text", "key", "k", "text", "B"))));
        assertEquals("k", n.children().get(0).key());
        assertNotEquals("k", n.children().get(1).key());
    }

    @Test
    public void idIsKeptInPropsForBinding() {
        UiNode n = UiNode.fromJava(map("type", "button", "id", "go", "text", "Go"));
        assertEquals("go", n.key());
        assertEquals("go", n.str("id", ""));
    }

    @Test
    public void listItemsWithoutKeyStayPositional() {
        UiNode n = UiNode.fromJava(map("type", "list", "items", Arrays.asList("a", "b")));
        assertEquals("#0", n.children().get(0).key());
        assertEquals("#1", n.children().get(1).key());
    }
}
