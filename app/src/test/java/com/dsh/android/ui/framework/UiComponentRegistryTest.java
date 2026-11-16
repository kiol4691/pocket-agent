package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;

import org.junit.Test;

import java.util.Map;

/** 组件注册表是纯 Java 的，JVM 单测里不用构造 View 就能验证注册/覆盖/白名单同步。 */
public class UiComponentRegistryTest {

    private static UiComponent stub() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                return null;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> oldProps, Env env) {
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    @Test
    public void registerAndLookup() {
        UiComponentRegistry r = new UiComponentRegistry();
        UiComponent c = stub();
        r.register("my.card", c);
        assertTrue(r.has("my.card"));
        assertSame(c, r.get("my.card"));
        assertFalse(r.has("nope"));
        assertNull(r.get("nope"));
        assertEquals(1, r.size());
    }

    @Test
    public void registeringSyncsWhitelist() {
        UiComponentRegistry r = new UiComponentRegistry();
        assertFalse(UiNode.isKnownType("custom.thing"));
        r.register("custom.thing", stub());
        assertTrue(UiNode.isKnownType("custom.thing"));
        assertTrue(r.types().contains("custom.thing"));
    }

    @Test
    public void laterRegistrationOverrides() {
        UiComponentRegistry r = new UiComponentRegistry();
        UiComponent a = stub(), b = stub();
        r.register("x", a);
        r.register("x", b);
        assertEquals(1, r.size());
        assertSame(b, r.get("x"));
    }

    @Test
    public void nullAndEmptyAreIgnored() {
        UiComponentRegistry r = new UiComponentRegistry();
        r.register(null, stub());
        r.register("", stub());
        r.register("x", null);
        assertEquals(0, r.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void typesViewIsReadOnly() {
        UiComponentRegistry r = new UiComponentRegistry();
        r.register("x", stub());
        r.types().clear();
    }
}
