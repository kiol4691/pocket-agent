package com.dsh.android.plugin;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * bridge 的纯 Java 辅助逻辑：域名白名单与时间格式白名单。
 *
 * manifest 解析与 exec 结果对象依赖 Android 的 org.json，放到真机调试命令验证
 * （plugin:lint / grant:list）。
 */
public class PluginBridgeTest {

    @Test
    public void hostAllowMatchesExactAndWildcard() {
        assertTrue(PluginBridge.hostAllowed("api.example.com",
                Collections.singletonList("api.example.com")));
        assertTrue(PluginBridge.hostAllowed("api.example.com",
                Collections.singletonList("API.Example.com")));
        assertTrue(PluginBridge.hostAllowed("a.example.com",
                Collections.singletonList("*.example.com")));
        assertTrue(PluginBridge.hostAllowed("example.com",
                Collections.singletonList("*.example.com")));
        assertFalse(PluginBridge.hostAllowed("badexample.com",
                Collections.singletonList("*.example.com")));
        assertFalse(PluginBridge.hostAllowed("other.com",
                Collections.singletonList("api.example.com")));
    }

    @Test
    public void timePatternOnlyAllowsDocumentedSubset() {
        PluginBridge.validatePattern("yyyy-MM-dd HH:mm:ss.SSS");
        PluginBridge.validatePattern("MM/dd HH:mm");
        try {
            PluginBridge.validatePattern("yyyy-MM-dd EEEE");
            throw new AssertionError("EEEE 不应通过");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("E_ARGS"));
        }
    }
}
