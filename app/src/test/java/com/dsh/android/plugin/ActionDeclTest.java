package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 动作声明的纯校验：全局 id 与超时夹取。 */
public class ActionDeclTest {

    @Test
    public void idsAllowDotsButNotPocketNamespace() {
        assertNull(ActionDecl.validateId("status_panel.ping"));
        assertNull(ActionDecl.validateId("a.b_c9"));
        assertNotNull(ActionDecl.validateId(""));
        assertNotNull(ActionDecl.validateId("Status_Panel"));
        assertNotNull(ActionDecl.validateId("pocket.chat.send"));
    }

    @Test
    public void timeoutIsClamped() {
        assertEquals(500, ActionDecl.clampTimeout(1));
        assertEquals(5_000, ActionDecl.clampTimeout(5_000));
        assertEquals(60_000, ActionDecl.clampTimeout(120_000));
    }
}
