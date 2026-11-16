package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * manifest 加载期的安全校验：entry 穿越、net.allow 域名、id 与目录一致、
 * 贡献 id 去重、priority 夹取、budgetSteps 默认值，以及能力开关 fail-closed。
 */
public class ManifestValidationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir(String name) throws IOException {
        return tmp.newFolder(name);
    }

    private static void write(File dir, String name, String text) throws IOException {
        Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static HookManifest load(File dir, String json) throws IOException {
        write(dir, "manifest.json", json);
        return HookManifest.load(dir);
    }

    private static void assertLoadFails(File dir, String json) throws IOException {
        try {
            load(dir, json);
            fail("应当加载失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage() != null);
        }
    }

    // ---------------------------------------------------------------- 1. entry 穿越

    @Test
    public void entryWithTraversalRejected() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        assertLoadFails(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"],"
                + "\"entry\":\"../evil.js\"}");
        assertLoadFails(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"],"
                + "\"entry\":\"sub/main.js\"}");
    }

    @Test
    public void entrySymlinkOutsideRejected() throws Exception {
        File d = dir("alpha");
        File outside = tmp.newFile("outside.js");
        Path link = new File(d, "main.js").toPath();
        try {
            Files.createSymbolicLink(link, outside.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return; // 文件系统不支持符号链接时跳过（其余用例已覆盖 pattern 校验）
        }
        assertLoadFails(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"]}");
    }

    @Test
    public void normalEntryLoads() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        HookManifest m = load(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"]}");
        assertEquals("main.js", m.entry);
    }

    // ---------------------------------------------------------------- 2. net.allow

    @Test
    public void netAllowRejectsWildcardSchemePathAndPort() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        for (String bad : new String[]{"*", "*.example.com", "https://api.example.com",
                "api.example.com/v1", "api.example.com:443", "api example.com", " ", ""}) {
            assertLoadFails(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"],"
                    + "\"net\":{\"allow\":[\"" + bad + "\"]}}");
        }
    }

    @Test
    public void netAllowAcceptsHostAndSubdomain() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        HookManifest m = load(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"],"
                + "\"net\":{\"allow\":[\"api.example.com\",\"localhost\"]}}");
        assertEquals(2, m.netAllow.size());
        assertTrue(m.netAllow.contains("api.example.com"));
    }

    // ---------------------------------------------------------------- 3. id 与目录名

    @Test
    public void idMustMatchDirectoryName() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        assertLoadFails(d, "{\"id\":\"beta\",\"hooks\":[\"beforeToolCall\"]}");
    }

    // ---------------------------------------------------------------- 4. renderer 去重

    @Test
    public void duplicateRendererIdRejected() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        assertLoadFails(d, "{\"id\":\"alpha\",\"apiVersion\":2,\"contributes\":{"
                + "\"renderers\":["
                + "{\"id\":\"r\",\"handler\":\"h\",\"match\":{\"fence\":[\"x\"]}},"
                + "{\"id\":\"r\",\"handler\":\"h\",\"match\":{\"fence\":[\"y\"]}}]}}");
    }

    // ---------------------------------------------------------------- 5. priority / budgetSteps

    private static String allContributions(int priority) {
        return "{\"id\":\"alpha\",\"apiVersion\":2,\"priority\":" + priority + ","
                + "\"contributes\":{"
                + "\"hooks\":[\"beforeToolCall\"],"
                + "\"tools\":[{\"name\":\"echo\",\"handler\":\"h\","
                + "\"inputSchema\":{\"type\":\"object\"},\"priority\":" + priority + "}],"
                + "\"ui\":[{\"slot\":\"home.hero\",\"handler\":\"h\",\"priority\":" + priority + "}],"
                + "\"pages\":[{\"id\":\"main\",\"title\":\"Main\",\"handler\":\"h\","
                + "\"priority\":" + priority + "}],"
                + "\"nav\":[{\"page\":\"alpha.main\",\"priority\":" + priority + "}],"
                + "\"actions\":[{\"id\":\"a.b\",\"label\":\"A\",\"priority\":" + priority + "}],"
                + "\"state\":[{\"id\":\"s\",\"label\":\"S\",\"type\":\"string\","
                + "\"priority\":" + priority + "}],"
                + "\"renderers\":[{\"id\":\"r\",\"handler\":\"h\","
                + "\"match\":{\"fence\":[\"x\"]},\"priority\":" + priority + "}]}}";
    }

    @Test
    public void allPrioritiesClampedToRange() throws Exception {
        File high = dir("alpha");
        write(high, "main.js", "");
        HookManifest m = load(high, allContributions(999));
        assertEquals(100, m.priority);
        assertEquals(100, m.tools.get(0).priority);
        assertEquals(100, m.slots.get(0).priority);
        assertEquals(100, m.pages.get(0).priority);
        assertEquals(100, m.nav.get(0).priority);
        assertEquals(100, m.actions.get(0).priority);
        assertEquals(100, m.states.get(0).priority);
        assertEquals(100, m.renderers.get(0).priority);

        File low = dir("beta");
        write(low, "main.js", "");
        HookManifest n = load(low, allContributions(-999).replace("\"id\":\"alpha\"",
                "\"id\":\"beta\"").replace("alpha.main", "beta.main"));
        assertEquals(-100, n.priority);
        assertEquals(-100, n.tools.get(0).priority);
        assertEquals(-100, n.slots.get(0).priority);
        assertEquals(-100, n.pages.get(0).priority);
        assertEquals(-100, n.nav.get(0).priority);
        assertEquals(-100, n.actions.get(0).priority);
        assertEquals(-100, n.states.get(0).priority);
        assertEquals(-100, n.renderers.get(0).priority);
    }

    @Test
    public void budgetStepsDefaultsToSchemaValue() throws Exception {
        File d = dir("alpha");
        write(d, "main.js", "");
        HookManifest m = load(d, "{\"id\":\"alpha\",\"hooks\":[\"beforeToolCall\"]}");
        assertEquals(200_000, m.budgetSteps);
    }

    // ---------------------------------------------------------------- 7. caps fail-closed

    @Test
    public void capsDisabledWhenPrefsNotInitialized() {
        assertFalse(PluginCaps.enabled("alpha", "hook.tool"));
    }
}
