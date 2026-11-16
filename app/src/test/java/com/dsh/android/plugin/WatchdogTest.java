package com.dsh.android.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 看门狗阈值：连续 5 次失败触发（成功一次由 noteSuccess 清零）；权限拒绝不计入。 */
public class WatchdogTest {

    @Test
    public void tripsAtFiveConsecutiveFailures() {
        assertFalse(HookRegistry.watchdogTripped(0));
        assertFalse(HookRegistry.watchdogTripped(4));
        assertTrue(HookRegistry.watchdogTripped(5));
        assertTrue(HookRegistry.watchdogTripped(6));
    }

    @Test
    public void permissionAndBudgetDenialsAreNotPluginFailures() {
        assertTrue(HookRegistry.isPolicyDenial("E_PERM: 未授权 tool.invoke"));
        assertTrue(HookRegistry.isPolicyDenial("E_BLOCKED: fs.shared 未授权"));
        assertTrue(HookRegistry.isPolicyDenial("E_BUDGET: 授权请求超时"));
        assertFalse(HookRegistry.isPolicyDenial("TypeError: undefined is not a function"));
        assertFalse(HookRegistry.isPolicyDenial("E_ARGS: 参数不是合法 JSON"));
        assertFalse(HookRegistry.isPolicyDenial(null));
        assertFalse(HookRegistry.isPolicyDenial(""));
    }
}
