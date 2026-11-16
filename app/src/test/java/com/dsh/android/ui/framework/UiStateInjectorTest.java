package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class UiStateInjectorTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> values(Object... kv) {
        return map(kv);
    }

    @Test
    public void simpleBindInjectsValue() {
        UiNode n = UiNode.column().key("root")
                .child(UiNode.text("0%").key("t")
                        .prop("bind", map("text", "pocket.context.summary")))
                .build();
        UiNode out = UiStateInjector.inject(n,
                values("pocket.context.summary", "62% · 62.0k / 100k"));
        assertEquals("62% · 62.0k / 100k", out.children().get(0).str("text", ""));
        assertEquals("0%", n.children().get(0).str("text", ""));   // 原树不被改
    }

    @Test
    public void objectSpecFormatsValue() {
        UiNode n = UiNode.column().key("r")
                .child(UiNode.text("").key("t").prop("bind",
                        map("text", map("state", "r", "format", "percent"))))
                .build();
        UiNode out = UiStateInjector.inject(n, values("r", 0.43));
        assertEquals("43%", out.children().get(0).str("text", ""));
    }

    @Test
    public void missingStateWithFallbackHide() {
        UiNode n = UiNode.text("init").key("t")
                .prop("fallback", "hide")
                .prop("bind", map("text", "missing"))
                .build();
        UiNode out = UiStateInjector.inject(n, values());
        assertTrue(out.bool("hidden", false));
        assertEquals("init", out.str("text", ""));
    }

    @Test
    public void missingStateWithDefaultKeep() {
        UiNode n = UiNode.text("init").key("t")
                .prop("bind", map("text", "missing"))
                .build();
        UiNode out = UiStateInjector.inject(n, values());
        assertFalse(out.bool("hidden", false));
        assertEquals("init", out.str("text", ""));
    }

    @Test
    public void hiddenIsClearedWhenValueAppears() {
        UiNode n = UiNode.text("init").key("t")
                .prop("fallback", "hide")
                .prop("bind", map("text", "x"))
                .build();
        UiNode hidden = UiStateInjector.inject(n, values());
        assertTrue(hidden.bool("hidden", false));
        UiNode shown = UiStateInjector.inject(hidden, values("x", "now"));
        assertFalse(shown.bool("hidden", false));
        assertEquals("now", shown.str("text", ""));
    }

    @Test
    public void injectsRecursivelyAndKeepsKeys() {
        UiNode n = UiNode.column().key("root").prop("gap", "sm")
                .child(UiNode.card().key("c")
                        .child(UiNode.text("").key("a").prop("bind", map("text", "x")))
                        .child(UiNode.text("").key("b").prop("bind", map("text", "y"))))
                .build();
        UiNode out = UiStateInjector.inject(n, values("x", "A", "y", "B"));
        assertEquals("sm", out.str("gap", ""));
        assertEquals("A", out.children().get(0).children().get(0).str("text", ""));
        assertEquals("B", out.children().get(0).children().get(1).str("text", ""));
        assertEquals("c", out.children().get(0).key());
    }

    @Test
    public void multipleAttributesOnOneNode() {
        UiNode n = UiNode.of("progress").key("p").prop("value", 0)
                .prop("bind", map("value", "ratio", "label", "summary"))
                .build();
        UiNode out = UiStateInjector.inject(n, values("ratio", 0.7, "summary", "70%"));
        assertEquals("0.7", out.str("value", ""));
        assertEquals("70%", out.str("label", ""));
    }

    @Test
    public void nullRootAndNullValuesAreSafe() {
        assertNull(UiStateInjector.inject(null, values()));
        UiNode n = UiNode.text("x").build();
        assertNotNull(UiStateInjector.inject(n, null));
    }
}
