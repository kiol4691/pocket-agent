package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 页面声明的纯校验：id / title / 自动刷新间隔。 */
public class PageDeclTest {

    @Test
    public void idAndTitleRules() {
        assertNull(PageDecl.validateId("status"));
        assertNull(PageDecl.validateId("runtime-2"));
        assertNotNull(PageDecl.validateId(""));
        assertNotNull(PageDecl.validateId("Status"));
        assertNotNull(PageDecl.validateId("a.b"));

        assertNull(PageDecl.validateTitle("状态面板"));
        assertNotNull(PageDecl.validateTitle(""));
        assertNotNull(PageDecl.validateTitle(new String(new char[65]).replace('\0', 'x')));
    }

    @Test
    public void refreshIsZeroOrClampedToOneSecondThroughTenMinutes() {
        assertEquals(0, PageDecl.clampRefresh(0));
        assertEquals(0, PageDecl.clampRefresh(-5));
        assertEquals(PageDecl.MIN_REFRESH_MS, PageDecl.clampRefresh(1));
        assertEquals(5_000, PageDecl.clampRefresh(5_000));
        assertEquals(PageDecl.MAX_REFRESH_MS, PageDecl.clampRefresh(999_999));
    }

    @Test
    public void noPageDeclParsingWithoutAndroidJson() {
        // fromJson 依赖 org.json；这条只确认常量契约，解析由真机上的 UI 链路覆盖。
        assertTrue(PageDecl.MIN_REFRESH_MS < PageDecl.MAX_REFRESH_MS);
    }
}
