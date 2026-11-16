package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class UiNodeTest {

    @Test
    public void builder_isImmutableSnapshot() {
        UiNode.Builder b = UiNode.text("first").key("t").prop("tone", "brand");
        UiNode n = b.build();
        b.prop("text", "second").prop("size", "title");
        assertEquals("first", n.str("text", ""));
        assertEquals("brand", n.str("tone", ""));
        assertEquals("body", n.str("size", "body"));
        assertEquals("t", n.key());
    }

    @Test
    public void typedAccessors() {
        UiNode n = UiNode.of(UiNode.ROW)
                .prop("gap", "sm")
                .prop("weight", 3)
                .prop("grow", true)
                .build();
        assertEquals("sm", n.str("gap", "none"));
        assertEquals(3, n.integer("weight", 0));
        assertTrue(n.bool("grow", false));
        assertFalse(n.bool("missing", false));
        assertEquals(7, n.integer("missing", 7));
    }

    @Test
    public void childrenBuildersAreResolved() {
        UiNode n = UiNode.column().key("root")
                .child(UiNode.text("a").key("a"))
                .child(UiNode.divider().key("d"))
                .children(Arrays.asList(UiNode.text("b").key("b").build()))
                .build();
        assertEquals(3, n.children().size());
        assertEquals("a", n.children().get(0).key());
        assertEquals(UiNode.DIVIDER, n.children().get(1).type());
        assertEquals("b", n.children().get(2).key());
    }

    @Test
    public void nullsAreIgnored() {
        UiNode n = UiNode.column().key("r")
                .child((UiNode) null)
                .child((UiNode.Builder) null)
                .prop("ignored", null)
                .build();
        assertTrue(n.children().isEmpty());
        assertFalse(n.props().containsKey("ignored"));
    }

    @Test
    public void textFactoriesHandleNull() {
        assertEquals("", UiNode.text(null).build().str("text", "x"));
        assertEquals("", UiNode.code(null).build().str("body", "x"));
        assertEquals("", UiNode.markdown(null).build().str("text", "x"));
    }

    @Test
    public void knownTypesAndLimits() {
        assertTrue(UiNode.isKnownType(UiNode.CARD));
        assertTrue(UiNode.isKnownType(UiNode.MARKDOWN));
        assertFalse(UiNode.isKnownType("webview"));
        assertEquals(200, UiNode.MAX_NODES);
        assertEquals(8, UiNode.MAX_DEPTH);
        assertEquals(4096, UiNode.MAX_TEXT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyTypeRejected() {
        UiNode.of("").build();
    }

    @Test
    public void deepTreeCanBeBuilt() {
        UiNode.Builder b = UiNode.text("leaf");
        for (int i = 0; i < 12; i++) b = UiNode.column().child(b);
        UiNode root = b.build();
        int depth = 0;
        UiNode cur = root;
        while (!cur.children().isEmpty()) {
            depth++;
            cur = cur.children().get(0);
        }
        assertEquals(12, depth);
    }
}
