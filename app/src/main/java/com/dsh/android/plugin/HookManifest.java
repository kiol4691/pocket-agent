package com.dsh.android.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *  - **预算是双层的**：\`budgetMs\` 墙钟 + \`budgetSteps\` 指令数。换到 QuickJS 后指令计数
 *    不可用，只有墙钟预算生效；\`budgetSteps\` 保留字段是为了兼容已有 manifest。
 *  - **内存上限**：\`limits.memoryMb\`（默认 16 MB）。每个插件一个 runtime，超限由引擎
 *    直接拒绝分配，不会把 App 撑到 OOM。
 */
public final class HookManifest {

    public static final String HOOK_BEFORE_TOOL = "beforeToolCall";
    public static final String HOOK_AFTER_TOOL = "afterToolResult";
    public static final String HOOK_USER_MESSAGE = "userMessage";
    public static final String HOOK_CONTEXT_BUILD = "contextBuild";
    public static final String HOOK_ASSISTANT_MESSAGE = "assistantMessage";

    public final File dir;
    public final String id;
    public final String name;
    public final String version;
    public final String description;
    /** 可选英文说明；非中文界面优先显示。 */
    public final String descriptionEn;
    public final String entry;
    /** "deny"（默认，fail-closed）或 "allow"。 */
    public final String onError;
    public final int apiVersion;
    public final int priority;
    public final int budgetMs;
    public final int budgetSteps;
    /** 单个插件的 JS 堆上限（MB），来自 limits.memoryMb，夹在 4–128 之间。 */
    public final int memoryMb;
    /** 工具 handler 的默认墙钟预算，来自 limits.toolMs，夹在 1s–600s。 */
    public final int toolMs;
    public final List<String> hooks;
    public final Set<String> capabilities;
    /** v2 的 {@code permissions}：声明扩展点/能力（如 {@code render.match}）。 */
    public final Set<String> permissions;
    /** 渲染器声明：v2 的 {@code contributes.renderers}。 */
    public final List<RendererRule> renderers;
    /** 工具声明：v2 的 {@code contributes.tools}。 */
    public final List<ToolDecl> tools;
    /** UI 插槽声明：v2 的 {@code contributes.ui}。 */
    public final List<UiSlotDecl> slots;
    /** 自定义页面声明：v2 的 {@code contributes.pages}。 */
    public final List<PageDecl> pages;
    /** 导航声明：v2 的 {@code contributes.nav}。 */
    public final List<NavDecl> nav;
    /** 全局动作声明：v2 的 {@code contributes.actions}。 */
    public final List<ActionDecl> actions;
    /** 主题声明：v2 的 {@code contributes.theme}；没有就是 null。 */
    public final ThemeDecl theme;
    /** 插件状态声明：v2 的 {@code contributes.state}。 */
    public final List<StateDecl> states;
    /** manifest {@code config} 的默认值（bridge.config.get 用）。 */
    public final Map<String, Object> configDefaults;
    /** manifest {@code config[]}：用户可在插件页修改的字段。 */
    public final List<ConfigField> config;
    /** {@code net.allow}：bridge.http 允许访问的域名（仅 https）。 */
    public final List<String> netAllow;
    /** {@code toolAccess.allow}：bridge.tool.invoke 的工具白名单。 */
    public final Set<String> toolAllow;
    /** {@code toolAccess.confirm}：危险工具调用是否每次确认，默认 true。 */
    public final boolean toolConfirm;
    /** {@code androidPermissions}：需要通过权限网关申请的系统运行时权限。 */
    public final List<String> androidPermissions;
    /** {@code requires.actions} / {@code requires.states} / {@code requires.pages}：仅提示，不影响加载顺序。 */
    public final List<String> requiresActions;
    public final List<String> requiresStates;
    public final List<String> requiresPages;

    private HookManifest(File dir, JSONObject o) throws IOException {
        this.dir = dir;
        this.id = o.optString("id", dir.getName());
        this.name = o.optString("name", this.id);
        this.version = o.optString("version", "0");
        this.description = o.optString("description", "");
        this.descriptionEn = o.optString("description_en", "");
        this.entry = o.optString("entry", "main.js");
        this.onError = "allow".equals(o.optString("onError", "deny")) ? "allow" : "deny";
        this.apiVersion = o.optInt("apiVersion", 1);
        // 所有 priority 统一夹在 [-100,100]（与 docs/plugin.schema.json 一致）。
        this.priority = clampPriority(o.optInt("priority", 0));
        JSONObject lim = o.optJSONObject("limits");
        // v2 文档的默认预算是 300ms，v1 保持 800ms；上限 5000ms 对两边都生效。
        int defBudget = apiVersion >= 2 ? 300 : 800;
        this.budgetMs = Math.max(50, Math.min(5000,
                lim == null ? defBudget : lim.optInt("budgetMs", defBudget)));
        // 默认值与 schema 一致（200000），避免显式与缺省两种路径给出不同预算。
        this.budgetSteps = lim == null ? 200_000 : lim.optInt("budgetSteps", 200_000);
        int mb = lim == null ? 16 : lim.optInt("memoryMb", 16);
        this.memoryMb = Math.max(4, Math.min(128, mb));
        this.toolMs = ToolDecl.clampTimeout(lim == null ? 30_000 : lim.optInt("toolMs", 30_000));
        JSONObject contributes = o.optJSONObject("contributes");
        // v2 把钩子点名放进 contributes.hooks；v1 的顶层 hooks 继续兼容。
        List<String> contributed = contributes == null
                ? null : strings(contributes.optJSONArray("hooks"));
        this.hooks = contributed == null || contributed.isEmpty()
                ? strings(o.optJSONArray("hooks")) : contributed;
        this.capabilities = new LinkedHashSet<>(strings(o.optJSONArray("capabilities")));
        this.permissions = new LinkedHashSet<>(strings(o.optJSONArray("permissions")));
        this.renderers = renderers(contributes, this.id, this.apiVersion);
        this.tools = tools(contributes, this.apiVersion, this.toolMs);
        this.slots = slots(contributes, this.id, this.apiVersion);
        this.pages = pages(contributes, this.id, this.apiVersion);
        this.nav = nav(contributes, this.id, this.apiVersion);
        this.actions = actions(contributes, this.id, this.apiVersion);
        this.theme = theme(contributes, this.apiVersion);
        this.states = states(contributes, this.id, this.apiVersion);
        this.config = configFields(o.optJSONArray("config"));
        this.configDefaults = configDefaults(this.config);
        JSONObject net = o.optJSONObject("net");
        JSONArray netArr = net == null ? null : net.optJSONArray("allow");
        this.netAllow = strings(netArr);
        // net.allow 只允许主机名/子域名；通配符、协议、路径、端口、空白一律让 manifest 加载失败。
        if (netArr != null) {
            for (int i = 0; i < netArr.length(); i++) {
                String raw = netArr.optString(i, "");
                if (raw.trim().isEmpty()) throw new IOException("net.allow 条目不能为空");
                String bad = validateNetHost(raw.trim());
                if (bad != null) throw new IOException(bad);
            }
        }
        JSONObject toolAccess = o.optJSONObject("toolAccess");
        this.toolAllow = new LinkedHashSet<>(toolAccess == null
                ? new ArrayList<>() : strings(toolAccess.optJSONArray("allow")));
        this.toolConfirm = toolAccess == null || toolAccess.optBoolean("confirm", true);
        this.androidPermissions = strings(o.optJSONArray("androidPermissions"));
        JSONObject requires = o.optJSONObject("requires");
        this.requiresActions = requires == null
                ? new ArrayList<>() : strings(requires.optJSONArray("actions"));
        this.requiresStates = requires == null
                ? new ArrayList<>() : strings(requires.optJSONArray("states"));
        this.requiresPages = requires == null
                ? new ArrayList<>() : strings(requires.optJSONArray("pages"));
    }

    private static List<StateDecl> states(JSONObject contributes, String pluginId, int apiVersion)
            throws IOException {
        List<StateDecl> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("state");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.state 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IOException("state[" + i + "] 不是对象");
            StateDecl d;
            try {
                d = StateDecl.fromJson(pluginId, o);
            } catch (IOException e) {
                throw new IOException("state[" + i + "] " + e.getMessage());
            }
            if (!seen.add(d.id)) throw new IOException("state 里 id 重复: " + d.id);
            out.add(d);
        }
        return out;
    }

    private static Map<String, Object> configDefaults(List<ConfigField> fields) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (ConfigField f : fields) out.put(f.key, f.defaultValue);
        return out;
    }

    private static List<ConfigField> configFields(JSONArray arr) throws IOException {
        List<ConfigField> out = new ArrayList<>();
        if (arr == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IOException("config[" + i + "] 不是对象");
            ConfigField f;
            try {
                f = ConfigField.fromJson(o);
            } catch (IOException e) {
                throw new IOException("config[" + i + "] " + e.getMessage());
            }
            if (!seen.add(f.key)) throw new IOException("config 里 key 重复: " + f.key);
            out.add(f);
        }
        return out;
    }

    /** 按 key 找配置声明；没有就是 null。 */
    public ConfigField configField(String key) {
        if (key == null) return null;
        for (ConfigField f : config) {
            if (f.key.equals(key)) return f;
        }
        return null;
    }

    private static ThemeDecl theme(JSONObject contributes, int apiVersion) throws IOException {
        if (contributes == null) return null;
        JSONObject o = contributes.optJSONObject("theme");
        if (o == null) return null;
        if (apiVersion < 2) throw new IOException("contributes.theme 需要 apiVersion >= 2");
        return ThemeDecl.fromJson(o);
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

    /** 解析并校验 {@code contributes.renderers}：坏声明让 manifest 直接加载失败。 */
    private static List<RendererRule> renderers(JSONObject contributes, String pluginId,
                                                int apiVersion)
            throws IOException {
        List<RendererRule> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("renderers");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.renderers 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) throw new IOException("renderers[" + i + "] 不是对象");
            String id = r.optString("id", "").trim();
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                throw new IOException("renderers[" + i + "] id 必须是 [a-z0-9_-]{1,32}: " + id);
            }
            if (!seen.add(id)) throw new IOException("renderers 里 id 重复: " + id);
            String handler = r.optString("handler", "").trim();
            if (handler.isEmpty()) throw new IOException("renderer " + id + " 缺少 handler");
            JSONObject match = r.optJSONObject("match");
            if (match == null) throw new IOException("renderer " + id + " 缺少 match");

            String type;
            List<String> fences = null;
            String regex = null;
            String prefix = null;
            if (match.optJSONArray("fence") != null) {
                type = RendererRule.MATCH_FENCE;
                fences = strings(match.optJSONArray("fence"));
                if (fences.isEmpty()) throw new IOException("renderer " + id + " 的 fence 为空");
            } else if (!match.optString("regex", "").isEmpty()) {
                type = RendererRule.MATCH_REGEX;
                regex = match.optString("regex", "");
            } else if (!match.optString("prefix", "").isEmpty()) {
                type = RendererRule.MATCH_PREFIX;
                prefix = match.optString("prefix", "");
            } else {
                throw new IOException("renderer " + id + " 的 match 必须写 fence / regex / prefix 之一");
            }
            try {
                out.add(new RendererRule(pluginId, id, r.optInt("priority", 0),
                        type, fences, regex, prefix, handler));
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IOException("renderer " + id + " 的 regex 非法: " + e.getMessage());
            }
        }
        return out;
    }

    /** 解析并校验 {@code contributes.tools}：坏声明让 manifest 直接加载失败。 */
    private static List<ToolDecl> tools(JSONObject contributes, int apiVersion,
                                        int defaultTimeoutMs) throws IOException {
        List<ToolDecl> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("tools");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.tools 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) throw new IOException("tools[" + i + "] 不是对象");
            ToolDecl d;
            try {
                d = ToolDecl.fromJson(t, defaultTimeoutMs);
            } catch (IOException e) {
                throw new IOException("tools[" + i + "] " + e.getMessage());
            }
            if (!seen.add(d.name)) throw new IOException("tools 里工具名重复: " + d.name);
            out.add(d);
        }
        return out;
    }

    /** 解析并校验 {@code contributes.ui}：坏声明让 manifest 直接加载失败。 */
    private static List<UiSlotDecl> slots(JSONObject contributes, String pluginId, int apiVersion)
            throws IOException {
        List<UiSlotDecl> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("ui");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.ui 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IOException("ui[" + i + "] 不是对象");
            UiSlotDecl d;
            try {
                d = UiSlotDecl.fromJson(pluginId, o);
            } catch (IOException e) {
                throw new IOException("ui[" + i + "] " + e.getMessage());
            }
            if (!seen.add(d.slot)) throw new IOException("ui 里插槽重复声明: " + d.slot);
            out.add(d);
        }
        return out;
    }

    /** 解析并校验 {@code contributes.pages}：坏声明让 manifest 直接加载失败。 */
    private static List<PageDecl> pages(JSONObject contributes, String pluginId, int apiVersion)
            throws IOException {
        List<PageDecl> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("pages");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.pages 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IOException("pages[" + i + "] 不是对象");
            PageDecl d;
            try {
                d = PageDecl.fromJson(pluginId, o);
            } catch (IOException e) {
                throw new IOException("pages[" + i + "] " + e.getMessage());
            }
            if (!seen.add(d.id)) throw new IOException("pages 里页面 id 重复: " + d.id);
            out.add(d);
        }
        return out;
    }

    /** 解析并校验 {@code contributes.nav}：坏声明让 manifest 直接加载失败。 */
    private static List<NavDecl> nav(JSONObject contributes, String pluginId, int apiVersion)
            throws IOException {
        List<NavDecl> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("nav");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.nav 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IOException("nav[" + i + "] 不是对象");
            NavDecl d;
            try {
                d = NavDecl.fromJson(pluginId, o);
            } catch (IOException e) {
                throw new IOException("nav[" + i + "] " + e.getMessage());
            }
            if (!seen.add(d.page)) throw new IOException("nav 里页面重复声明: " + d.page);
            out.add(d);
        }
        return out;
    }

    /** 解析并校验 {@code contributes.actions}。 */
    private static List<ActionDecl> actions(JSONObject contributes, String pluginId, int apiVersion)
            throws IOException {
        List<ActionDecl> out = new ArrayList<>();
        if (contributes == null) return out;
        JSONArray arr = contributes.optJSONArray("actions");
        if (arr == null || arr.length() == 0) return out;
        if (apiVersion < 2) throw new IOException("contributes.actions 需要 apiVersion >= 2");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IOException("actions[" + i + "] 不是对象");
            ActionDecl d;
            try {
                d = ActionDecl.fromJson(pluginId, o);
            } catch (IOException e) {
                throw new IOException("actions[" + i + "] " + e.getMessage());
            }
            if (!seen.add(d.id)) throw new IOException("actions 里动作 id 重复: " + d.id);
            out.add(d);
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
        if (m.apiVersion != 1 && m.apiVersion != 2) {
            throw new IOException("不支持的 apiVersion=" + m.apiVersion);
        }
        if (!m.id.matches("[a-z0-9_]{1,32}")) throw new IOException("id 只能是 [a-z0-9_]: " + m.id);
        // id 必须等于目录名：caps/config/grants 都以 id 作命名空间，两者不一致会串号。
        if (!m.id.equals(dir.getName())) {
            throw new IOException("id 必须与插件目录名一致: id=" + m.id + ", dir=" + dir.getName());
        }
        if (m.hooks.isEmpty() && m.renderers.isEmpty() && m.tools.isEmpty()
                && m.slots.isEmpty() && m.pages.isEmpty() && m.nav.isEmpty()
                && m.actions.isEmpty() && m.theme == null && m.states.isEmpty()) {
            throw new IOException("manifest 没有声明任何扩展点");
        }
        for (String h : m.hooks) {
            if (!HOOK_BEFORE_TOOL.equals(h) && !HOOK_AFTER_TOOL.equals(h)
                    && !HOOK_USER_MESSAGE.equals(h) && !HOOK_CONTEXT_BUILD.equals(h)
                    && !HOOK_ASSISTANT_MESSAGE.equals(h)) {
                throw new IOException("未知 hook: " + h);
            }
        }
        // entry 只能是插件目录内的单个 .js 文件名；再用 canonicalPath 兜底防穿越。
        if (!m.entry.matches("[A-Za-z0-9_.-]+\\.js")) {
            throw new IOException("entry 必须是插件目录内的 .js 文件名: " + m.entry);
        }
        File baseDir = dir.getCanonicalFile();
        File script = new File(baseDir, m.entry).getCanonicalFile();
        if (!script.getPath().startsWith(baseDir.getPath() + File.separator)) {
            throw new IOException("entry 越出插件目录: " + m.entry);
        }
        if (!script.isFile()) {
            // 主题是纯数据，可以没有 main.js；其它扩展点都需要入口脚本。
            boolean needsScript = !m.hooks.isEmpty() || !m.renderers.isEmpty() || !m.tools.isEmpty()
                    || !m.slots.isEmpty() || !m.pages.isEmpty() || !m.nav.isEmpty()
                    || !m.actions.isEmpty() || !m.states.isEmpty();
            if (needsScript) throw new IOException("入口脚本不存在: " + m.entry);
        }
        return m;
    }

    /** 按界面语言选择插件说明：中文用 description，其它语言优先 description_en。 */
    public String descriptionFor(String language) {
        if (language != null && language.startsWith("zh")) return description;
        return descriptionEn == null || descriptionEn.isEmpty() ? description : descriptionEn;
    }

    /** 所有 priority 统一夹在 [-100,100]（与 docs/plugin.schema.json 一致）。 */
    public static int clampPriority(int priority) {
        return Math.max(-100, Math.min(100, priority));
    }

    /** 校验 net.allow 的一项：只允许主机名/子域名，拒绝通配符、协议、路径、端口、空白。 */
    public static String validateNetHost(String host) {
        if (host == null || host.isEmpty()) return "net.allow 条目不能为空";
        if (host.contains("*") || host.contains("/") || host.contains("\\")
                || host.contains(":") || !host.equals(host.trim())
                || !host.matches("[A-Za-z0-9.-]+")) {
            return "net.allow 只允许主机名/子域名: " + host;
        }
        // 每段不能以连字符开头/结尾，避免 "-x.com"/"x-.com" 这类脏名。
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) {
                return "net.allow 只允许主机名/子域名: " + host;
            }
        }
        return null;
    }

    public boolean hasHook(String hook) {
        return hooks.contains(hook);
    }

    /** 引擎按这个决定要不要接受插件返回的改写内容。 */
    public boolean can(String capability) {
        boolean declared = capabilities.contains(capability) || permissions.contains(capability);
        if (!declared) return false;
        // v2 capability 还受插件页逐项开关控制；v1 旧名没有对应开关，保持声明即生效。
        // prefs 未初始化（纯 JVM 单测/manifest 加载早期）按声明生效，初始化后以用户开关为准。
        return !isCapability(capability) || !PluginCaps.isReady()
                || PluginCaps.enabled(id, capability);
    }

    /** v2 的 capability 名单（与 PLUGIN_API §2 一致）；其余都是 grant 或 v1 旧名。 */
    private static final Set<String> CAPABILITIES = new LinkedHashSet<>(Arrays.asList(
            "tool.register", "hook.tool", "hook.tool.rewrite", "hook.message", "hook.context",
            "render.match", "ui.slot", "ui.page", "ui.overlay", "device.info", "secret.get",
            "state.read", "state.provide", "action.provide", "action.bind"));

    public static boolean isCapability(String name) {
        return name != null && CAPABILITIES.contains(name);
    }

    /** 能否否决工具调用：v1 的 {@code veto} 或 v2 的 {@code hook.tool}。 */
    public boolean canVeto() {
        return can("veto") || can("hook.tool");
    }

    /** 能否改写工具参数：v1 的 {@code rewrite} 或 v2 的 {@code hook.tool.rewrite}。 */
    public boolean canRewriteArgs() {
        return can("rewrite") || can("hook.tool.rewrite");
    }

    /** 能否替换工具结果：v1 的 {@code replace} 或 v2 的 {@code hook.tool.rewrite}。 */
    public boolean canReplaceResult() {
        return can("replace") || can("hook.tool.rewrite");
    }

    /** 所有需要运行时授权（grant）的能力名；capability 不走网关。 */
    public static final String[] GRANT_NAMES = {
            "net.http", "fs.workspace", "fs.shared", "exec.guest", "tool.invoke",
            "bridge.adb", "env.time", "clipboard", "notify", "overlay.system", "location"
    };

    /** 是否是外部资源授权；{@code android.permission.*} 也算。 */
    public static boolean isGrant(String name) {
        if (name == null) return false;
        if (name.startsWith("android.permission.")) return true;
        for (String g : GRANT_NAMES) {
            if (g.equals(name)) return true;
        }
        return false;
    }

    /** manifest 是否声明了这个 grant（只有声明过才允许申请/自动代理）。 */
    public boolean declaresGrant(String name) {
        if (name == null) return false;
        if (name.startsWith("android.permission.")) return androidPermissions.contains(name);
        return permissions.contains(name);
    }

    /** bridge.tool.invoke 的白名单；{@code *} 表示全部。 */
    public boolean allowsTool(String tool) {
        return tool != null && (toolAllow.contains(tool) || toolAllow.contains("*"));
    }

    /** 有没有值得接线的渲染器（能力声明 + 至少一条声明）。 */
    public boolean canRender() {
        return !renderers.isEmpty() && can("render.match");
    }

    /** 有没有可挂载的插槽（能力声明 + 至少一条声明）。 */
    public boolean canSlots() {
        return !slots.isEmpty() && can("ui.slot");
    }

    /** 有没有可打开的页面（能力声明 + 至少一条声明）。 */
    public boolean canPages() {
        return !pages.isEmpty() && can("ui.page");
    }

    /** 有没有可用的导航声明（能力声明 + 至少一条声明）。 */
    public boolean canNav() {
        return !nav.isEmpty() && can("ui.page");
    }

    /** 有没有可提供的全局动作（能力声明 + 至少一条声明）。 */
    public boolean canActions() {
        return !actions.isEmpty() && can("action.provide");
    }

    public boolean hasTheme() {
        return theme != null;
    }

    /** 有没有可发布的插件状态（需要 state.provide）。 */
    public boolean canStates() {
        return !states.isEmpty() && can("state.provide");
    }

    /** 皮肤替换消息/卡片外观属于高危能力，必须声明 render.match。 */
    public boolean canSkin() {
        return theme != null && !theme.skins.isEmpty() && can("render.match");
    }

    /** 失败时是否拦下这次工具调用。 */
    public boolean failClosed() {
        return "deny".equals(onError);
    }
}
