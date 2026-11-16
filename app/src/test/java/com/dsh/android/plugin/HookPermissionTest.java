package com.dsh.android.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** v1 旧能力名与 v2 权限名都要映射到同一套 veto/rewrite/replace 判定。 */
public class HookPermissionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HookManifest load(String manifest) throws Exception {
        // 新校验要求 id == 目录名，按 manifest 里的 id 建目录。
        String id = new org.json.JSONObject(manifest).optString("id", "p");
        File dir = tmp.newFolder(id);
        Files.write(new File(dir, "manifest.json").toPath(),
                manifest.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "main.js").toPath(),
                "function onBeforeToolCall(){return {action:'allow'}}".getBytes(StandardCharsets.UTF_8));
        return HookManifest.load(dir);
    }

    @Test
    public void v2HookToolEnablesVetoOnly() throws Exception {
        HookManifest m = load("{\"id\":\"p1\",\"apiVersion\":2,"
                + "\"permissions\":[\"hook.tool\"],"
                + "\"contributes\":{\"hooks\":[\"beforeToolCall\"]}}");
        assertTrue(m.canVeto());
        assertFalse(m.canRewriteArgs());
        assertFalse(m.canReplaceResult());
    }

    @Test
    public void v2RewriteEnablesRewriteAndReplace() throws Exception {
        HookManifest m = load("{\"id\":\"p2\",\"apiVersion\":2,"
                + "\"permissions\":[\"hook.tool.rewrite\"],"
                + "\"contributes\":{\"hooks\":[\"beforeToolCall\",\"afterToolResult\"]}}");
        assertFalse(m.canVeto());
        assertTrue(m.canRewriteArgs());
        assertTrue(m.canReplaceResult());
    }

    @Test
    public void v1CapabilitiesStillWork() throws Exception {
        HookManifest m = load("{\"id\":\"p3\",\"apiVersion\":1,"
                + "\"hooks\":[\"beforeToolCall\",\"afterToolResult\"],"
                + "\"capabilities\":[\"veto\",\"rewrite\",\"replace\"]}");
        assertTrue(m.canVeto());
        assertTrue(m.canRewriteArgs());
        assertTrue(m.canReplaceResult());
    }

    @Test
    public void allFiveHooksAreAccepted() throws Exception {
        HookManifest m = load("{\"id\":\"p5\",\"apiVersion\":2,"
                + "\"permissions\":[\"hook.message\",\"hook.context\"],"
                + "\"contributes\":{\"hooks\":[\"beforeToolCall\",\"afterToolResult\","
                + "\"userMessage\",\"contextBuild\",\"assistantMessage\"]}}");
        assertTrue(m.hasHook(HookManifest.HOOK_USER_MESSAGE));
        assertTrue(m.hasHook(HookManifest.HOOK_CONTEXT_BUILD));
        assertTrue(m.hasHook(HookManifest.HOOK_ASSISTANT_MESSAGE));
    }

    @Test
    public void requiresAndConfigAreParsed() throws Exception {
        HookManifest m = load("{\"id\":\"p4\",\"apiVersion\":2,"
                + "\"permissions\":[\"hook.tool\"],"
                + "\"requires\":{\"actions\":[\"a.b\"],\"states\":[\"p4.s\"],"
                + "\"pages\":[\"p4.page\"]},"
                + "\"config\":["
                + "{\"key\":\"endpoint\",\"label\":\"Endpoint\",\"type\":\"string\",\"default\":\"https://x\"},"
                + "{\"key\":\"n\",\"label\":\"N\",\"type\":\"number\",\"default\":3},"
                + "{\"key\":\"on\",\"label\":\"On\",\"type\":\"boolean\",\"default\":true}],"
                + "\"contributes\":{\"hooks\":[\"beforeToolCall\"]}}");
        assertTrue(m.requiresActions.contains("a.b"));
        assertTrue(m.requiresStates.contains("p4.s"));
        assertTrue(m.requiresPages.contains("p4.page"));
        assertTrue(m.config.size() == 3);
        assertTrue(m.configField("endpoint").defaultValue.equals("https://x"));
        assertTrue(((Number) m.configField("n").defaultValue).doubleValue() == 3.0);
        assertTrue(Boolean.TRUE.equals(m.configField("on").defaultValue));
    }
}
