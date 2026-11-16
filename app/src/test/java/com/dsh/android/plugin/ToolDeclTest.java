package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** ToolDecl 的纯校验规则：名字格式与超时夹取。 */
public class ToolDeclTest {

    @Test
    public void name_acceptsLowercaseDigitsUnderscore() {
        assertNull(ToolDecl.validateName("echo"));
        assertNull(ToolDecl.validateName("deploy_v2"));
        assertNull(ToolDecl.validateName("a"));
        assertNull(ToolDecl.validateName("tool_123456789012345678901234567"));
    }

    @Test
    public void name_rejectsBadShapes() {
        assertNotNull(ToolDecl.validateName(""));
        assertNotNull(ToolDecl.validateName(null));
        assertNotNull(ToolDecl.validateName("Echo"));
        assertNotNull(ToolDecl.validateName("has-hyphen"));
        assertNotNull(ToolDecl.validateName("has space"));
        assertNotNull(ToolDecl.validateName("中文"));
        assertNotNull(ToolDecl.validateName("tool_1234567890123456789012345678"));
    }

    @Test
    public void timeout_isClampedToOneSecondThroughTenMinutes() {
        assertEquals(ToolDecl.MIN_TIMEOUT_MS, ToolDecl.clampTimeout(0));
        assertEquals(ToolDecl.MIN_TIMEOUT_MS, ToolDecl.clampTimeout(999));
        assertEquals(1_000, ToolDecl.clampTimeout(1_000));
        assertEquals(30_000, ToolDecl.clampTimeout(30_000));
        assertEquals(ToolDecl.MAX_TIMEOUT_MS, ToolDecl.clampTimeout(600_000));
        assertEquals(ToolDecl.MAX_TIMEOUT_MS, ToolDecl.clampTimeout(600_001));
    }
}
