package com.dsh.android.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个 hook 插件的清单（files/plugins/<id>/manifest.json）。
 *
 * 设计要点（相对 v0.2.0 的改进）：
 *
 *  - **能力要声明**：\`capabilities\` 里没写 \`rewrite\` 的插件，即使返回了改写后的参数，
 *    引擎也会忽略并记一条警告 —— "能拦截" 和 "能改写" 是两种信任级别，不该默认全给。
 *  - **失败策略要声明**：\`onError\` 默认 \`deny\`（fail-closed）。安全类钩子失败时
 *    宁可拦住这次调用，也不能假装通过（v0.2.0 的 issue #2 就是这么静默失效的）。
 *  - **预算是双层的**：\`budgetMs\` 墙钟 + \`budgetSteps\` 指令数。只给指令数的话，
 *    慢机器上一条正则回溯就能拖慢一轮对话；只给墙钟的话，死循环要靠中断，不可靠。
 */
public final class HookManifest {

    public static final String HOOK_BEFORE_TOOL = "beforeToolCall";
    public static final String HOOK_AFTER_TOOL = "afterToolResult";

    public final File dir;
    public final String id;
    public final String name;
    public final String version;
    public final String description;
    public final String entry;
    /** "deny"（默认，fail-closed）或 "allow"。 */
    public final String onError;
    public final int apiVersion;
    public final int priority;
    public final int budgetMs;
    public final int budgetSteps;
    public final List<String> hooks;
    public final Set<String> capabilities;

    private HookManifest(File dir, JSONObject o) {
        this.dir = dir;
        this.id = o.optString("id", dir.getName());
        this.name = o.optString("name", this.id);
        this.version = o.optString("version", "0");
        this.description = o.optString("description", "");
        this.entry = o.optString("entry", "main.js");
        this.onError = "allow".equals(o.optString("onError", "deny")) ? "allow" : "deny";
        this.apiVersion = o.optInt("apiVersion", 1);
        this.priority = o.optInt("priority", 0);
        JSONObject lim = o.optJSONObject("limits");
        this.budgetMs = lim == null ? 800 : lim.optInt("budgetMs", 800);
        this.budgetSteps = lim == null ? 2_000_000 : lim.optInt("budgetSteps", 2_000_000);
        this.hooks = strings(o.optJSONArray("hooks"));
        this.capabilities = new LinkedHashSet<>(strings(o.optJSONArray("capabilities")));
    }

    private static List<String> strings(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    public static HookManifest load(File dir) throws IOException {
        File f = new File(dir, "manifest.json");
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("manifest.json 不是合法 JSON: " + e.getMessage());
        }
        HookManifest m = new HookManifest(dir, o);
        if (m.apiVersion != 1) throw new IOException("不支持的 apiVersion=" + m.apiVersion);
        if (!m.id.matches("[a-z0-9_]{1,32}")) throw new IOException("id 只能是 [a-z0-9_]: " + m.id);
        if (m.hooks.isEmpty()) throw new IOException("manifest 没有声明 hooks");
        for (String h : m.hooks) {
            if (!HOOK_BEFORE_TOOL.equals(h) && !HOOK_AFTER_TOOL.equals(h)) {
                throw new IOException("未知 hook: " + h);
            }
        }
        if (!new File(dir, m.entry).isFile()) throw new IOException("入口脚本不存在: " + m.entry);
        return m;
    }

    public boolean hasHook(String hook) {
        return hooks.contains(hook);
    }

    /** 引擎按这个决定要不要接受插件返回的改写内容。 */
    public boolean can(String capability) {
        return capabilities.contains(capability);
    }

    /** 失败时是否拦下这次工具调用。 */
    public boolean failClosed() {
        return "deny".equals(onError);
    }
}
