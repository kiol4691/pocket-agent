package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** nav 声明的纯校验：页面 id 与 order 夹取。 */
public class NavDeclTest {

    @Test
    public void pageIds() {
        assertNull(NavDecl.validatePage("pocket.chat"));
        assertNull(NavDecl.validatePage("status_panel.status"));
        assertNull(NavDecl.validatePage("a-b.c_d"));
        assertNotNull(NavDecl.validatePage(""));
        assertNotNull(NavDecl.validatePage("Status"));
        assertNotNull(NavDecl.validatePage("a b"));
        assertNotNull(NavDecl.validatePage("中文"));
    }

    @Test
    public void orderIsClamped() {
        assertEquals(-1000, NavDecl.clampOrder(-9999));
        assertEquals(0, NavDecl.clampOrder(0));
        assertEquals(10, NavDecl.clampOrder(10));
        assertEquals(1000, NavDecl.clampOrder(9999));
    }
}
