package com.dsh.android.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.dsh.android.agent.ChatMessage;
import com.dsh.android.agent.ContextCompactor;
import com.dsh.android.agent.ToolCall;
import com.dsh.android.ui.framework.HostState;
import com.dsh.android.util.DebugLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 钩子插件的注册表：扫描 files/plugins/*、维护开关、串起调用链。
 *
 * 链的语义（刻意做成"最小惊讶"）：
 *  - 插件级 {@code priority} **只决定钩子链顺序**（升序）；工具/渲染器/动作/状态/页面/插槽
 *    各自有独立的 {@code priority}，不会被插件级 priority 连带影响。
 *  - {@code beforeToolCall}：按 priority 升序执行；**第一个否决就终止**；改写会传给下一个钩子。
 *  - {@code afterToolResult}：第一个 replace 生效后停止。
 *  - 任何钩子抛异常：按其 manifest 的 onError 处理（默认 deny），并把错误记到 {@link #lastError}
 *    给 UI 显示 —— v0.2.0 的教训是"失败只写日志"，用户以为开着。
 */
public final class HookRegistry {

    private final File dir;
    private final Context context;
    private final SharedPreferences prefs;
    private final HookEngine engine = new HookEngine();
    private final Map<String, HookManifest> manifests = new LinkedHashMap<>();
    private final Map<String, String> errors = new java.util.concurrent.ConcurrentHashMap<>();
    /** 状态全名索引：bridge 回调在 QuickJS 线程执行，不能同步等待 registry 锁。 */
    private volatile Map<String, StateDecl> stateIndex = java.util.Collections.emptyMap();
    /** 其余 bridge 入口用的只读快照：reload 末尾整体替换，读侧永不加锁。 */
    private volatile List<HookManifest> manifestView = java.util.Collections.emptyList();
    private volatile Map<String, HookManifest> manifestIndex = java.util.Collections.emptyMap();
    private volatile List<ActionDecl> actionView = java.util.Collections.emptyList();
    private volatile Map<String, ActionDecl> actionIndex = java.util.Collections.emptyMap();
    private volatile List<PageDecl> pageView = java.util.Collections.emptyList();
    private volatile List<NavDecl> navView = java.util.Collections.emptyList();
    private volatile List<PluginTool> toolView = java.util.Collections.emptyList();
    private volatile Map<String, PluginTool> toolIndex = java.util.Collections.emptyMap();
    /** grant 网关与宿主服务（Activity/运行时/工具/UI），由 MainActivity 注入。 */
    private final PluginGrants grants = new PluginGrants();
    private volatile PluginHost host;
    /** 当前 bridge 实现；setHost 时整体替换（调试通道可直接调用）。 */
    private volatile PluginBridge bridge;

    /** 渲染器单次调用的墙钟上限：UI 路径在 plugin-render 线程跑，不能按钩子预算等 800ms。 */
    private static final int RENDER_BUDGET_MS = 500;
    /** UI 插槽/页面/事件回调的墙钟上限；实际执行在 PluginUiHost 的后台线程。 */
    private static final int UI_BUDGET_MS = 500;
    /** 生命周期预算（文档 §9.1）：onEnable 失败等于启用失败，onDisable 只记日志。 */
    private static final int ENABLE_BUDGET_MS = 500;
    private static final int DISABLE_BUDGET_MS = 200;
    /** 看门狗：同一插件连续失败这么多次就自动禁用（成功一次即清零）。 */
    static final int WATCHDOG_LIMIT = 5;
    /** 调试日志只保留最近这么多条，避免长期运行吃内存。 */
    private static final int LOG_MAX = 200;

    /** 连续失败计数（插件 id → 次数）；成功调用清零。 */
    private final Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();

    /** 插件工具列表缓存：manifest 集合、启用状态或内置工具名变化时失效。 */
    private List<PluginTool> pluginToolCache;
    /** 上次计算 pluginToolCache 时用的内置工具名集合；集合变化必须重算（重名解析依赖它）。 */
    private Set<String> pluginToolCacheBuiltins;
    private volatile Supplier<JSONObject> sessionProvider = JSONObject::new;
    private final ArrayDeque<String> logs = new ArrayDeque<>();

    /**
     * 渲染结果缓存：流式内容结构变化时，同一块会被反复求值；JS 不能每帧跑一次。
     * 值允许为 null（"没有插件接管"也是结论），所以用 containsKey 判断。
     */
    private final Map<String, String> renderCache =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 64;
                }
            };

    /** 保护 renderCache / renderPendingKeys / renderGeneration / renderNotifyScheduled。 */
    private final Object renderLock = new Object();
    /** 正在后台求值的块 key：同一块在流式重排里只跑一次 JS。 */
    private final Set<String> renderPendingKeys = new LinkedHashSet<>();
    /** 插件重载/禁用会 ++，旧任务的结果不再回写。 */
    private int renderGeneration;
    private boolean renderNotifyScheduled;
    /** UI 路径的 renderer 后台线程；同步调试入口不受影响。 */
    private final java.util.concurrent.ExecutorService renderWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "plugin-render");
                t.setDaemon(true);
                return t;
            });
    /** 后台求值完成后在主线程回调：让聊天气泡重渲染一次（这次直接命中缓存）。 */
    private volatile Runnable renderReady;

    public HookRegistry(Context context, File pluginsDir) {
        this(context, pluginsDir, null);
    }

    public HookRegistry(Context context, File pluginsDir, PluginHost host) {
        this.context = context.getApplicationContext();
        this.dir = pluginsDir;
        this.prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE);
        this.grants.ensureContext(this.context);
        PluginConfig.ensureContext(this.context);
        PluginCaps.ensureContext(this.context);
        this.host = host;
        this.bridge = new PluginBridge(this.context, this, host);
        this.engine.setBridge(this.bridge);
        reload();
    }

    /** 重新扫描目录（安装/删除插件后调用）。 */
    public synchronized void reload() {
        // 先给还活着的启用插件一次收尾机会（文档 §9.1：重载前调用 onDisable）。
        stopEnabledPlugins();
        // 插件集合要变了：先把上一批 runtime 收掉，否则卸载的插件会留着 JS 线程和堆。
        engine.closeAll();
        manifests.clear();
        errors.clear();
        failures.clear();
        clearRenderCache();
        pluginToolCache = null;
        synchronized (logs) {
            logs.clear();
        }
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File d : subs) {
                if (!d.isDirectory()) continue;
                try {
                    HookManifest m = HookManifest.load(d);
                    manifests.put(m.id, m);
                } catch (Exception e) {
                    errors.put(d.getName(), e.getMessage());
                    DebugLog.w("plugin " + d.getName() + " load failed: " + e.getMessage());
                }
            }
        }
        List<HookManifest> sorted = new ArrayList<>(manifests.values());
        sorted.sort(Comparator.comparingInt((HookManifest m) -> m.priority).thenComparing(m -> m.id));
        manifests.clear();
        for (HookManifest m : sorted) manifests.put(m.id, m);
        // 状态同名冲突：按状态自己的 priority 升序、插件 id 字典序取第一个（与其它贡献一致）。
        List<StateDecl> stateCandidates = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (!m.canStates()) continue;
            stateCandidates.addAll(m.states);
        }
        stateCandidates.sort(Comparator.comparingInt((StateDecl d) -> d.priority)
                .thenComparing(d -> d.pluginId)
                .thenComparing(d -> d.id));
        Map<String, StateDecl> idx = new LinkedHashMap<>();
        for (StateDecl d : stateCandidates) {
            if (!idx.containsKey(d.fullName())) idx.put(d.fullName(), d);
        }
        stateIndex = idx;
        // 先更新 manifest 索引：下面的 onEnable 循环里 enabled() 依赖它。
        updateManifestView();
        // 重载后常驻作用域要重建：对仍启用的插件重跑 onEnable（失败则自动关掉）。
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id)) continue;
            try {
                engine.callLifecycle(m, "onEnable", ENABLE_BUDGET_MS);
                engine.markEnabled(m.id);
                log(m.id, "reloaded");
            } catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                errors.put(m.id, why);
                prefs.edit().putBoolean("en_" + m.id, false).apply();
                engine.close(m.id);
                DebugLog.w("plugin " + m.id + " onEnable after reload failed: " + why);
                log(m.id, "reload failed: " + why);
            }
        }
        // onEnable 可能把插件自动关掉；动作/页面/工具快照必须在它之后构建。
        rebuildSnapshots();
    }

    private void updateManifestView() {
        List<HookManifest> all = new ArrayList<>(manifests.values());
        manifestView = Collections.unmodifiableList(all);
        Map<String, HookManifest> midx = new LinkedHashMap<>();
        for (HookManifest m : all) midx.put(m.id, m);
        manifestIndex = Collections.unmodifiableMap(midx);
    }

    /**
     * 构建 bridge 读侧快照。调用方必须持有 registry 锁（reload 末尾）。
     * 这些集合之后不再修改，因此 bridge 回调可以无锁读。
     */
    private void rebuildSnapshots() {
        updateManifestView();
        List<HookManifest> all = manifestView;

        // 各贡献类型用自己的 priority（小者先），同优先级按插件 id / 声明 id，保证可复现；
        // 插件级 priority 只决定钩子链顺序，不再连带影响下面这些集合。
        List<ActionDecl> actionCandidates = new ArrayList<>();
        List<PageDecl> pageCandidates = new ArrayList<>();
        List<NavDecl> navCandidates = new ArrayList<>();
        for (HookManifest m : all) {
            if (!enabled(m.id)) continue;
            if (m.canActions()) actionCandidates.addAll(m.actions);
            if (m.canPages()) pageCandidates.addAll(m.pages);
            if (m.canNav()) navCandidates.addAll(m.nav);
        }
        actionCandidates.sort(Comparator.comparingInt((ActionDecl d) -> d.priority)
                .thenComparing(d -> d.pluginId)
                .thenComparing(d -> d.id));
        List<ActionDecl> acts = new ArrayList<>();
        Map<String, ActionDecl> aidx = new LinkedHashMap<>();
        for (ActionDecl d : actionCandidates) {
            if (aidx.containsKey(d.id)) continue;       // 同名动作：priority 最小者生效
            aidx.put(d.id, d);
            acts.add(d);
        }
        pageCandidates.sort(Comparator.comparingInt((PageDecl d) -> d.priority)
                .thenComparing(PageDecl::fullId));
        navCandidates.sort(Comparator.comparingInt((NavDecl d) -> d.priority)
                .thenComparing(d -> d.pluginId)
                .thenComparing(d -> d.page));

        List<ToolPlan.Entry> toolEntries = new ArrayList<>();
        for (HookManifest m : all) {
            if (!enabled(m.id) || m.tools.isEmpty()) continue;
            Map<String, Integer> names = new LinkedHashMap<>();
            for (ToolDecl d : m.tools) names.put(d.name, d.priority);
            toolEntries.add(new ToolPlan.Entry(m.id, !m.can("tool.register"), names));
        }
        // 内置工具名由调用方（buildTools）单独传入，这里只解决插件之间的重名。
        ToolPlan.Result plan = ToolPlan.resolve(toolEntries, Collections.emptySet());
        List<PluginTool> tools = new ArrayList<>();
        Map<String, PluginTool> tidx = new LinkedHashMap<>();
        for (HookManifest m : all) {
            if (!enabled(m.id) || !m.can("tool.register")) continue;
            for (ToolDecl d : m.tools) {
                if (!m.id.equals(plan.owners.get(d.name))) continue;
                PluginTool t = new PluginTool(this, m, d);
                tools.add(t);
                tidx.put(d.name, t);
            }
        }
        tools.sort(Comparator.comparingInt(PluginTool::priority)
                .thenComparing(PluginTool::pluginId)
                .thenComparing(PluginTool::name));

        actionView = Collections.unmodifiableList(acts);
        actionIndex = Collections.unmodifiableMap(aidx);
        pageView = Collections.unmodifiableList(pageCandidates);
        navView = Collections.unmodifiableList(navCandidates);
        toolView = Collections.unmodifiableList(tools);
        toolIndex = Collections.unmodifiableMap(tidx);
    }

    /** 由 MainActivity 注入宿主服务；重复调用覆盖旧值。 */
    public void setHost(PluginHost host) {
        this.host = host;
        this.bridge = new PluginBridge(host == null ? null : host.context(), this, host);
        this.engine.setBridge(this.bridge);
    }

    /** 当前线程是否正在执行该插件的 JS（bridge.action.invoke 防重入）。 */
    public boolean isPluginExecuting(String id) {
        return engine.isExecuting(id);
    }

    /** 调试通道：直接调一次 bridge（不经过 JS），用于真机验证 grant/执行链。 */
    public Object callBridgeForDebug(HookManifest m, String ns, String method, JSONArray args)
            throws Exception {
        PluginBridge b = bridge;
        if (b == null) throw new IllegalStateException("E_BRIDGE: bridge 未初始化");
        return b.call(m, ns, method, args);
    }

    public PluginHost host() {
        return host;
    }

    public PluginGrants grants() {
        return grants;
    }

    /** bridge 用的无锁快照。 */
    public List<HookManifest> manifestsView() {
        return manifestView;
    }

    public HookManifest manifestView(String id) {
        return id == null ? null : manifestIndex.get(id);
    }

    public List<ActionDecl> actionView() {
        return actionView;
    }

    public ActionDecl actionDeclView(String id) {
        return id == null ? null : actionIndex.get(id);
    }

    public List<PageDecl> pagesView() {
        return pageView;
    }

    public List<NavDecl> navView() {
        return navView;
    }

    public List<PluginTool> pluginToolsView() {
        return toolView;
    }

    public PluginTool pluginToolView(String name) {
        return name == null ? null : toolIndex.get(name);
    }

    public synchronized List<HookManifest> list() {
        return new ArrayList<>(manifests.values());
    }

    /** 加载失败但目录还在的插件（id -> 原因），UI 用红字显示；已加载插件的运行时错误不算。 */
    public synchronized Map<String, String> broken() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : errors.entrySet()) {
            if (!manifests.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public boolean enabled(String id) {
        return manifestIndex.containsKey(id) && prefs.getBoolean("en_" + id, false);
    }

    public String lastError(String id) {
        return errors.get(id);
    }

    /** 当前连续失败次数（看门狗计数，成功一次清零）。 */
    public int failureCount(String id) {
        Integer n = failures.get(id);
        return n == null ? 0 : n;
    }

    /**
     * 打开开关前先跑一次自检：钩子失败就不许打开。
     * 这条是 v0.2.0 issue #2 的直接教训 —— "启用"必须意味着"能跑"。
     */
    public synchronized String enable(HookManifest m) {
        try {
            // 先跑 onEnable：它建立的常驻作用域正是后面自检要用的环境。
            engine.callLifecycle(m, "onEnable", ENABLE_BUDGET_MS);
            engine.invoke(m, "onBeforeToolCall", sampleContext("beforeToolCall"));
            engine.invoke(m, "onAfterToolResult", sampleContext("afterToolResult"));
            if (m.hasHook(HookManifest.HOOK_USER_MESSAGE)) {
                engine.invoke(m, "onUserMessage", sampleContext("userMessage"));
            }
            if (m.hasHook(HookManifest.HOOK_CONTEXT_BUILD)) {
                engine.invoke(m, "onContextBuild", sampleContext("contextBuild"));
            }
            if (m.hasHook(HookManifest.HOOK_ASSISTANT_MESSAGE)) {
                engine.invoke(m, "onAssistantMessage", sampleContext("assistantMessage"));
            }
            prefs.edit().putBoolean("en_" + m.id, true).apply();
            engine.markEnabled(m.id);
            noteSuccess(m);
            pluginToolCache = null;
            log(m.id, "enabled");
            return null;
        } catch (Throwable e) {
            // Throwable 不是洁癖：Rhino 会把脚本里的 Error 原样抛出来（一次真机闪退就是这么来的）。
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            errors.put(m.id, why);
            prefs.edit().putBoolean("en_" + m.id, false).apply();
            engine.markDisabled(m.id);
            engine.close(m.id);
            pluginToolCache = null;
            log(m.id, "enable failed: " + why);
            DebugLog.w("plugin " + m.id + " self-test failed: " + why);
            return why;
        } finally {
            clearRenderCache();
            // 启用/自检失败都会改变 enabled 状态：立即重建 action/page/nav/tool/state 快照，
            // 让 bridge/UI 的无锁读侧马上跟随（调用方已持有 registry 锁，这里不再加锁）。
            rebuildSnapshots();
        }
    }

    public synchronized void disable(String id) {
        HookManifest m = manifests.get(id);
        if (m != null) {
            try {
                engine.callLifecycle(m, "onDisable", DISABLE_BUDGET_MS);
            } catch (Throwable e) {
                DebugLog.w("plugin " + id + " onDisable failed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            log(id, "disabled");
            for (StateDecl d : m.states) HostState.bus().remove(d.fullName());
        }
        engine.markDisabled(id);
        prefs.edit().putBoolean("en_" + id, false).apply();
        clearRenderCache();
        pluginToolCache = null;
        PluginHost h = host;
        if (h != null) h.onPluginDisabled(id);
        // 关掉就不该继续占着一个 JS runtime（线程 + 堆上限）。
        engine.close(id);
        // 调用方持有 registry 锁：立即刷新快照，action/page/nav/tool 立刻不可见。
        rebuildSnapshots();
    }

    /** 重载前给启用插件一次收尾机会；失败只记日志，不阻止重载。 */
    private void stopEnabledPlugins() {
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id)) continue;
            try {
                engine.callLifecycle(m, "onDisable", DISABLE_BUDGET_MS);
            } catch (Throwable e) {
                DebugLog.w("plugin " + m.id + " onDisable failed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            engine.markDisabled(m.id);
            for (StateDecl d : m.states) HostState.bus().remove(d.fullName());
            PluginHost h = host;
            if (h != null) h.onPluginDisabled(m.id);
        }
    }

    private static JSONObject sampleContext(String hook) {
        JSONObject o = new JSONObject();
        try {
            o.put("hook", hook);
            o.put("name", "bash");
            JSONObject args = new JSONObject();
            args.put("command", "ls -l /tmp");
            o.put("args", args);
            o.put("result", "total 0");
            o.put("iteration", 1);
            o.put("text", "hello");
            o.put("refs", new JSONArray());
            o.put("messages", new JSONArray());
            o.put("model", "debug");
            o.put("tokens", 0);
            o.put("session", new JSONObject());
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * 工具执行前的钩子链。返回的 Decision.args 非空表示参数被改写。
     */
    public HookEngine.Decision beforeToolCall(String toolName, String argsJson) {
        String current = argsJson;
        for (HookManifest m : chain(HookManifest.HOOK_BEFORE_TOOL)) {
            JSONObject ctx = new JSONObject();
            try {
                ctx.put("hook", HookManifest.HOOK_BEFORE_TOOL);
                ctx.put("name", toolName);
                ctx.put("args", new JSONObject(current == null || current.isEmpty() ? "{}" : current));
            } catch (Exception e) {
                continue;
            }
            HookEngine.Decision d;
            try {
                d = engine.invoke(m, "onBeforeToolCall", ctx);
                noteSuccess(m);
            } catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                noteFailure(m, "hook onBeforeToolCall failed: " + why, why);
                if (m.failClosed()) {
                    return HookEngine.Decision.veto("插件 " + m.id + " 执行失败（已按 fail-closed 拦下）：" + why);
                }
                continue;
            }
            if (d == null) continue;
            if (!d.allow) return d;
            if (d.args != null) {
                if (m.canRewriteArgs()) {
                    current = d.args;
                } else {
                    DebugLog.w("hook " + m.id + " 改写了参数但没声明 rewrite/hook.tool.rewrite 能力，忽略");
                }
            }
        }
        return argsUnchanged(current, argsJson) ? HookEngine.Decision.allow()
                : HookEngine.Decision.rewrite(current);
    }

    /** 纯函数，方便单测：参数是否与原始一致；argsJson 允许为 null（Objects.equals 空安全）。 */
    static boolean argsUnchanged(String current, String original) {
        return Objects.equals(current, original);
    }

    /** 工具执行后的钩子链：第一个 replace 生效。 */
    public String afterToolResult(String toolName, String argsJson, String result) {
        String current = result;
        for (HookManifest m : chain(HookManifest.HOOK_AFTER_TOOL)) {
            JSONObject ctx = new JSONObject();
            try {
                ctx.put("hook", HookManifest.HOOK_AFTER_TOOL);
                ctx.put("name", toolName);
                ctx.put("args", new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson));
                ctx.put("result", current == null ? "" : current);
            } catch (Exception e) {
                continue;
            }
            try {
                HookEngine.Decision d = engine.invoke(m, "onAfterToolResult", ctx);
                if (d != null && d.result != null) {
                    if (m.canReplaceResult()) {
                        current = d.result;
                    } else {
                        DebugLog.w("hook " + m.id + " 替换了结果但没声明 replace/hook.tool.rewrite 能力，忽略");
                    }
                }
                noteSuccess(m);
            } catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                // 结果改写失败不阻断工具（工具已经跑完了），但错误要留痕给 UI/看门狗
                noteFailure(m, "hook onAfterToolResult failed: " + why, why);
            }
        }
        return current;
    }

    // ---------------------------------------------------------------- 消息 / 上下文钩子

    /** 用户消息/助手消息钩子的结论；text=null 表示不改。 */
    public static final class MessageDecision {
        public final boolean allow;
        public final String reason;
        public final String text;

        MessageDecision(boolean allow, String reason, String text) {
            this.allow = allow;
            this.reason = reason;
            this.text = text;
        }
    }

    /** 上下文钩子的结论；messages=null 表示不改。 */
    public static final class ContextDecision {
        public final boolean allow;
        public final String reason;
        public final List<ChatMessage> messages;

        ContextDecision(boolean allow, String reason, List<ChatMessage> messages) {
            this.allow = allow;
            this.reason = reason;
            this.messages = messages;
        }
    }

    /** {@code onUserMessage}：用户消息入库/发给模型前。允许 veto / rewrite(text)。 */
    public MessageDecision userMessage(ChatMessage msg) {
        if (msg == null) return new MessageDecision(true, "", null);
        String original = msg.content == null ? "" : msg.content;
        String text = original;
        for (HookManifest m : chain(HookManifest.HOOK_USER_MESSAGE)) {
            JSONObject ctx = hookContext(HookManifest.HOOK_USER_MESSAGE);
            try {
                ctx.put("text", text);
                JSONArray refs = new JSONArray();
                if (msg.refs != null) for (String r : msg.refs) refs.put(r);
                ctx.put("refs", refs);
            } catch (Exception ignored) {
            }
            try {
                HookEngine.Decision d = engine.invoke(m, "onUserMessage", ctx);
                noteSuccess(m);
                if (d == null) continue;
                if (!d.allow) return new MessageDecision(false, d.reason, null);
                if (d.text != null) text = d.text;
            } catch (Throwable e) {
                String why = recordHookError(m, "onUserMessage", e);
                if (m.failClosed()) {
                    return new MessageDecision(false, "插件 " + m.id + " 执行失败: " + why, null);
                }
            }
        }
        return new MessageDecision(true, "", text.equals(original) ? null : text);
    }

    /** {@code onAssistantMessage}：最终回答写库/渲染前。允许 replace(text)。 */
    public MessageDecision assistantMessage(String content) {
        String original = content == null ? "" : content;
        String text = original;
        for (HookManifest m : chain(HookManifest.HOOK_ASSISTANT_MESSAGE)) {
            JSONObject ctx = hookContext(HookManifest.HOOK_ASSISTANT_MESSAGE);
            try {
                ctx.put("text", text);
            } catch (Exception ignored) {
            }
            try {
                HookEngine.Decision d = engine.invoke(m, "onAssistantMessage", ctx);
                noteSuccess(m);
                if (d != null && d.text != null) text = d.text;
            } catch (Throwable e) {
                String why = recordHookError(m, "onAssistantMessage", e);
                if (m.failClosed()) {
                    return new MessageDecision(false, "插件 " + m.id + " 执行失败: " + why, null);
                }
            }
        }
        return new MessageDecision(true, "", text.equals(original) ? null : text);
    }

    /**
     * {@code onContextBuild}：每次请求发出前。只允许裁剪消息或给已有 system 追加文本，
     * 不能造 tool 消息、不能破坏 tool_calls/tool 配对、总长度不超过原来的 120%。
     */
    public ContextDecision contextBuild(List<ChatMessage> messages) {
        List<ChatMessage> current = messages;
        if (current == null) return new ContextDecision(true, "", null);
        for (HookManifest m : chain(HookManifest.HOOK_CONTEXT_BUILD)) {
            JSONObject ctx = hookContext(HookManifest.HOOK_CONTEXT_BUILD);
            try {
                JSONArray arr = new JSONArray();
                for (ChatMessage c : current) arr.put(c.toLogJson());
                ctx.put("messages", arr);
                ctx.put("tokens", ContextCompactor.estimateTokens(current));
                Object model = HostState.snapshot().get("pocket.model.name");
                if (model != null) ctx.put("model", model);
            } catch (Exception ignored) {
            }
            try {
                HookEngine.Decision d = engine.invoke(m, "onContextBuild", ctx);
                noteSuccess(m);
                if (d == null) continue;
                if (!d.allow) return new ContextDecision(false, d.reason, null);
                if (d.messages == null) continue;
                List<ChatMessage> proposed = parseMessages(d.messages);
                String bad = validateContextBuild(current, proposed);
                if (bad != null) {
                    log(m.id, "onContextBuild rejected: " + bad);
                    DebugLog.w("hook " + m.id + ".onContextBuild rejected: " + bad);
                    continue;
                }
                current = proposed;
            } catch (Throwable e) {
                String why = recordHookError(m, "onContextBuild", e);
                if (m.failClosed()) {
                    return new ContextDecision(false, "插件 " + m.id + " 执行失败: " + why, null);
                }
            }
        }
        return new ContextDecision(true, "", current == messages ? null : current);
    }

    /** 解析钩子返回的 messages JSON；坏 JSON 返回 null。 */
    private static List<ChatMessage> parseMessages(String json) {
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            List<ChatMessage> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) return null;
                out.add(ChatMessage.fromJson(o));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 上下文改写的硬限制（纯 Java，便于单测）：返回 null 表示接受，否则是拒绝原因。
     */
    static String validateContextBuild(List<ChatMessage> original, List<ChatMessage> proposed) {
        if (original == null || proposed == null) return "messages 不是 JSON 数组";
        double originalChars = ContextCompactor.chars(original);
        if (ContextCompactor.chars(proposed) > originalChars * 1.2 + 1) {
            return "总长度超过原来的 20%";
        }
        if (!original.isEmpty() && "system".equals(original.get(0).role)) {
            if (proposed.isEmpty() || !"system".equals(proposed.get(0).role)) {
                return "不能删除 system 消息";
            }
        }
        // 结果必须是原序列的子序列：只能裁剪，不能新增/重排。
        int oi = 0;
        for (ChatMessage p : proposed) {
            int found = -1;
            for (int j = oi; j < original.size(); j++) {
                ChatMessage o = original.get(j);
                if (!o.role.equals(p.role)) continue;
                if ("system".equals(p.role) || sameMessage(o, p)) {
                    found = j;
                    break;
                }
            }
            if (found < 0) return "只能裁剪消息或给 system 追加文本";
            ChatMessage o = original.get(found);
            if ("system".equals(p.role)) {
                String oc = o.content == null ? "" : o.content;
                String pc = p.content == null ? "" : p.content;
                if (!pc.startsWith(oc)) return "system 消息只能追加文本";
            }
            oi = found + 1;
        }
        // tool_calls 与 tool 消息必须成对。
        Set<String> declared = new LinkedHashSet<>();
        Set<String> answered = new LinkedHashSet<>();
        for (ChatMessage p : proposed) {
            if ("assistant".equals(p.role) && p.toolCalls != null) {
                for (ToolCall tc : p.toolCalls) declared.add(tc.id);
            }
            if ("tool".equals(p.role) && p.toolCallId != null) {
                if (!declared.contains(p.toolCallId)) return "tool 消息没有对应的 tool_calls";
                answered.add(p.toolCallId);
            }
        }
        for (String id : declared) {
            if (!answered.contains(id)) return "tool_calls 缺少对应的 tool 结果";
        }
        return null;
    }

    private static boolean sameMessage(ChatMessage a, ChatMessage b) {
        if (!eq(a.content, b.content) || !eq(a.toolCallId, b.toolCallId)) return false;
        List<ToolCall> x = a.toolCalls;
        List<ToolCall> y = b.toolCalls;
        if (x == null || y == null) return x == y;
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            ToolCall p = x.get(i);
            ToolCall q = y.get(i);
            if (!eq(p.id, q.id) || !eq(p.name, q.name) || !eq(p.arguments, q.arguments)) return false;
        }
        return true;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 钩子 ctx 的公共部分：hook + 当前会话（没有会话时给空对象）。 */
    private static JSONObject hookContext(String hook) {
        JSONObject o = new JSONObject();
        try {
            o.put("hook", hook);
            JSONObject session = new JSONObject();
            Map<String, Object> snap = HostState.snapshot();
            Object id = snap.get("pocket.session.id");
            Object title = snap.get("pocket.session.title");
            if (id != null) session.put("id", id);
            if (title != null) session.put("title", title);
            o.put("session", session);
        } catch (Exception ignored) {
        }
        return o;
    }

    private String recordHookError(HookManifest m, String fn, Throwable e) {
        String why = e.getMessage() == null ? e.toString() : e.getMessage();
        noteFailure(m, "hook " + fn + " failed: " + why, why);
        return why;
    }

    /** 成功一次就清掉连续失败计数与最近错误（错误提示随恢复消失）。 */
    private void noteSuccess(HookManifest m) {
        if (m == null) return;
        failures.remove(m.id);
        errors.remove(m.id);
    }

    /**
     * 记一次运行时失败并喂给看门狗。连续 {@link #WATCHDOG_LIMIT} 次失败自动禁用插件，
     * 成功一次即清零（{@link #noteSuccess}）。生命周期/启用自检失败不经过这里，
     * 它们本来就按“启用失败”处理。
     *
     * <p>权限/预算类拒绝（{@code E_PERM} / {@code E_BLOCKED} / {@code E_BUDGET}）不算插件缺陷：
     * 用户在授权框点“拒绝”不该把插件自动禁用，下一次调用重新申请即可。
     */
    private void noteFailure(HookManifest m, String logLine, String errorText) {
        if (m == null) return;
        log(m.id, logLine);
        if (isPolicyDenial(errorText)) {
            DebugLog.i("plugin " + m.id + ": " + logLine + "（权限/预算拒绝，不计入看门狗）");
            return;
        }
        errors.put(m.id, errorText);
        DebugLog.w("plugin " + m.id + ": " + logLine);
        int n = failures.merge(m.id, 1, Integer::sum);
        if (watchdogTripped(n)) autoDisable(m, n, errorText);
    }

    /** 纯函数，方便单测：权限/预算类拒绝不应计入看门狗的连续失败。 */
    static boolean isPolicyDenial(String why) {
        if (why == null || why.isEmpty()) return false;
        return why.contains("E_PERM") || why.contains("E_BLOCKED") || why.contains("E_BUDGET");
    }

    /** 纯函数，方便单测：连续失败达到阈值即触发看门狗。 */
    static boolean watchdogTripped(int consecutiveFailures) {
        return consecutiveFailures >= WATCHDOG_LIMIT;
    }

    /**
     * 看门狗动作：不跑 onDisable（JS 已经不可信），直接关开关、收 runtime、清 UI/缓存，
     * 把原因留在 lastError 里给插件页红字显示；用户可手动重新启用。
     */
    private void autoDisable(HookManifest m, int count, String lastError) {
        synchronized (this) {
            if (!prefs.getBoolean("en_" + m.id, false)) {
                failures.remove(m.id);
                return;
            }
            prefs.edit().putBoolean("en_" + m.id, false).apply();
            engine.markDisabled(m.id);
            engine.close(m.id);
            for (StateDecl d : m.states) HostState.bus().remove(d.fullName());
            pluginToolCache = null;
            clearRenderCache();
            // 自动禁用同样改变 enabled 状态，快照要立刻跟上（actionDecls 直接读 actionView）。
            rebuildSnapshots();
            PluginHost h = host;
            if (h != null) h.onPluginDisabled(m.id);
            failures.remove(m.id);
            String note = "已自动禁用（连续 " + count + " 次失败）: " + lastError;
            errors.put(m.id, note);
            log(m.id, "auto-disabled after " + count + " consecutive failures");
            DebugLog.w("plugin " + m.id + " " + note);
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 当前可注册的插件工具。
     *
     * <p>只考虑 enabled 且声明了 {@code tool.register} 的插件；重名解析交给
     * {@link ToolPlan}。manifest 集合/启用状态变化会让缓存失效；内置工具名集合
     * 也作为缓存判据（随 buildTools 变化时自动重算）。
     *
     * @param builtinNames 已注册的内置工具名，插件不能覆盖
     */
    public synchronized List<PluginTool> pluginTools(Set<String> builtinNames) {
        // 内置工具名也参与重名解析：记录上次用的集合，集合一变就必须重算，
        // 不能只依赖 manifest/启用状态失效（否则 buildTools 变化后缓存仍是旧结论）。
        Set<String> builtins = builtinNames == null
                ? Collections.<String>emptySet() : new LinkedHashSet<>(builtinNames);
        if (pluginToolCache != null && builtins.equals(pluginToolCacheBuiltins)) {
            return pluginToolCache;
        }
        List<ToolPlan.Entry> entries = new ArrayList<>();
        List<HookManifest> candidates = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id) || m.tools.isEmpty()) continue;
            Map<String, Integer> names = new LinkedHashMap<>();
            for (ToolDecl d : m.tools) names.put(d.name, d.priority);
            entries.add(new ToolPlan.Entry(m.id, !m.can("tool.register"), names));
            candidates.add(m);
        }
        ToolPlan.Result plan = ToolPlan.resolve(entries, builtins);
        List<PluginTool> out = new ArrayList<>();
        for (HookManifest m : candidates) {
            if (!m.can("tool.register")) continue;
            for (ToolDecl d : m.tools) {
                if (m.id.equals(plan.owners.get(d.name))) {
                    out.add(new PluginTool(this, m, d));
                } else {
                    String why = plan.conflicts.get(d.name);
                    if (why == null) why = "工具 " + d.name + " 未注册（重名）";
                    errors.put(m.id, why);
                    log(m.id, why);
                }
            }
        }
        pluginToolCache = out;
        pluginToolCacheBuiltins = builtins;
        return out;
    }

    /** 工具调用的完整链路：参数校验/默认值 → JS handler → 文本结果。 */
    String invokeTool(HookManifest m, ToolDecl decl, String argsJson) {
        JSONObject args;
        try {
            args = normalizeArgs(decl, argsJson);
        } catch (IllegalArgumentException e) {
            log(m.id, "tool " + decl.name + " rejected: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
        JSONObject ctx = new JSONObject();
        try {
            ctx.put("name", decl.name);
            ctx.put("args", args);
            ctx.put("session", session());
            // AgentLoop 目前不透传迭代序号；字段保留，插件可以安全读取。
            ctx.put("iteration", 0);
        } catch (Exception ignored) {
        }
        try {
            String out = engine.invokeText(m, decl.handler, ctx, decl.timeoutMs);
            String text = out == null ? "(工具 " + decl.name + " 没有返回内容)" : out;
            noteSuccess(m);
            log(m.id, "tool " + decl.name + " ok (" + text.length() + " chars)");
            return text;
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            noteFailure(m, "tool " + decl.name + " failed: " + why,
                    "工具 " + decl.name + " 失败: " + why);
            return "Error: 插件 " + m.id + " 的工具 " + decl.name + " 执行失败：" + why;
        }
    }

    /** 调试通道 plugin:test：直接调一个导出函数，不经过 ToolRegistry。 */
    public String testFunction(String id, String fnName, String ctxJson) {
        HookManifest m;
        String loadError;
        synchronized (this) {
            m = manifests.get(id);
            loadError = errors.get(id);
        }
        if (m == null) {
            return loadError == null ? "no such plugin: " + id : "插件加载失败: " + loadError;
        }
        JSONObject ctx;
        try {
            String t = ctxJson == null ? "" : ctxJson.trim();
            ctx = t.isEmpty() ? new JSONObject() : new JSONObject(t);
        } catch (Exception e) {
            return "ctxJson 不是合法 JSON: " + e.getMessage();
        }
        int budget = ("onEnable".equals(fnName) || "onDisable".equals(fnName))
                ? ENABLE_BUDGET_MS : m.toolMs;
        try {
            String out = engine.invokeText(m, fnName, ctx, budget);
            log(id, "test " + fnName + " -> " + (out == null ? "(空)" : out.length() + " chars"));
            return out == null ? "(函数未实现或返回 undefined)" : out;
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            log(id, "test " + fnName + " failed: " + why);
            return "FAILED: " + why;
        }
    }

    /** 会话信息由 UI 层注入；拿不到时退化成空对象，不影响工具调用。 */
    public void setSessionProvider(Supplier<JSONObject> provider) {
        this.sessionProvider = provider == null ? JSONObject::new : provider;
    }

    private JSONObject session() {
        try {
            JSONObject s = sessionProvider.get();
            return s == null ? new JSONObject() : s;
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /**
     * 按 inputSchema 做最小校验：填默认值、检查 required 与常见类型。
     * 复杂 schema（anyOf / 嵌套）不做拒绝 —— 这层的目标是拦住"明显写错"，不是完整校验器。
     */
    private static JSONObject normalizeArgs(ToolDecl decl, String argsJson) {
        String t = argsJson == null ? "" : argsJson.trim();
        JSONObject in;
        try {
            in = t.isEmpty() ? new JSONObject() : new JSONObject(t);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数不是合法 JSON 对象: " + e.getMessage());
        }
        JSONObject out;
        try {
            out = new JSONObject(in.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("参数不是合法 JSON 对象");
        }
        JSONObject props = decl.inputSchema.optJSONObject("properties");
        if (props != null) {
            java.util.Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject p = props.optJSONObject(key);
                if (p == null) continue;
                if (!out.has(key)) {
                    Object def = p.opt("default");
                    if (def != null && def != JSONObject.NULL) {
                        try {
                            out.put(key, def);
                        } catch (Exception ignored) {
                        }
                    }
                }
                checkType(out, key, p);
            }
        }
        JSONArray req = decl.inputSchema.optJSONArray("required");
        if (req != null) {
            for (int i = 0; i < req.length(); i++) {
                String key = req.optString(i, "");
                if (key.isEmpty()) continue;
                if (!out.has(key) || out.isNull(key)) {
                    throw new IllegalArgumentException("缺少必填参数: " + key);
                }
            }
        }
        return out;
    }

    private static void checkType(JSONObject args, String key, JSONObject prop) {
        if (!args.has(key) || args.isNull(key)) return;
        String type = prop.optString("type", "");
        if (type.isEmpty()) return;
        Object v = args.opt(key);
        boolean ok;
        switch (type) {
            case "string":
                ok = v instanceof String;
                break;
            case "boolean":
                ok = v instanceof Boolean;
                break;
            case "integer":
                ok = v instanceof Integer || v instanceof Long;
                break;
            case "number":
                ok = v instanceof Number;
                break;
            case "array":
                ok = v instanceof JSONArray;
                break;
            case "object":
                ok = v instanceof JSONObject;
                break;
            default:
                return;
        }
        if (!ok) throw new IllegalArgumentException("参数 " + key + " 应为 " + type);
    }

    // ---------------------------------------------------------------- UI 插槽 / 页面

    /** 宿主 UI 侧读 manifest（事件回调要按插件 id 找）。 */
    public synchronized HookManifest manifest(String id) {
        return manifests.get(id);
    }

    /** 某个插槽当前可用的声明（enabled + ui.slot），顺序就是求值顺序。 */
    public synchronized List<UiSlotDecl> uiSlots(String slot) {
        List<UiSlotDecl> out = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id) || !m.canSlots()) continue;
            for (UiSlotDecl d : m.slots) {
                if (d.slot.equals(slot)) out.add(d);
            }
        }
        // 插槽自己的 priority（小者先），同优先级按插件 id，避免受插件级 priority 影响。
        out.sort(Comparator.comparingInt((UiSlotDecl d) -> d.priority)
                .thenComparing(d -> d.pluginId));
        return out;
    }

    /** 当前可进入的插件页面（enabled + ui.page）。 */
    public synchronized List<PageDecl> pages() {
        List<PageDecl> out = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id) || !m.canPages()) continue;
            out.addAll(m.pages);
        }
        out.sort(Comparator.comparingInt((PageDecl d) -> d.priority)
                .thenComparing(PageDecl::fullId));
        return out;
    }

    /** 当前生效的导航声明（enabled + ui.page），顺序即覆盖优先级。 */
    public synchronized List<NavDecl> navDecls() {
        List<NavDecl> out = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id) || !m.canNav()) continue;
            out.addAll(m.nav);
        }
        out.sort(Comparator.comparingInt((NavDecl d) -> d.priority)
                .thenComparing(d -> d.pluginId)
                .thenComparing(d -> d.page));
        return out;
    }

    /**
     * 一次 UI handler 的结果：哪个插件接管 + 返回的视图 JSON。
     */
    public static final class UiRender {
        public final HookManifest manifest;
        public final String json;

        UiRender(HookManifest manifest, String json) {
            this.manifest = manifest;
            this.json = json;
        }
    }

    /** 插槽链：第一个返回非空视图节点的插件生效（字符串返回值按"无视图"处理）。 */
    public UiRender renderSlot(String slot, JSONObject ctx) {
        for (UiSlotDecl d : uiSlots(slot)) {
            HookManifest m = manifest(d.pluginId);
            if (m == null) continue;
            String json = invokeUiFn(m, d.handler, ctx);
            if (looksLikeView(json)) return new UiRender(m, json);
        }
        return null;
    }

    public UiRender renderPage(PageDecl page, JSONObject ctx) {
        if (!enabled(page.pluginId)) return null;
        HookManifest m = manifest(page.pluginId);
        if (m == null) return null;
        String json = invokeUiFn(m, page.handler, ctx);
        return looksLikeView(json) ? new UiRender(m, json) : null;
    }

    private static boolean looksLikeView(String json) {
        return json != null && json.trim().startsWith("{");
    }

    /** UI 渲染/事件/动作共用的入口：预算 500ms，异常记 lastError 与日志。 */
    public String invokeUiFn(HookManifest m, String fnName, JSONObject ctx) {
        try {
            // 用 invokeText：事件/动作可能返回字符串（toast），invokeJson 只认对象。
            String out = engine.invokeText(m, fnName, ctx, Math.min(UI_BUDGET_MS, m.budgetMs));
            noteSuccess(m);
            return out;
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            noteFailure(m, "ui " + fnName + " failed: " + why, "ui " + fnName + " 失败: " + why);
            return null;
        }
    }

    /** 已加载的主题插件（主题不依赖启用开关，设置页直接选择）。 */
    public synchronized List<HookManifest> themes() {
        List<HookManifest> out = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (m.hasTheme()) out.add(m);
        }
        return out;
    }

    public synchronized HookManifest themePlugin(String id) {
        HookManifest m = manifests.get(id);
        return m != null && m.hasTheme() ? m : null;
    }

    /**
     * 当前生效的插件状态声明（enabled）。
     * 直接走 {@link #stateIndex}：它已按声明 priority→pluginId→id 排好并去重，
     * 赢家与 {@link #stateDecl(String)} 完全一致；这里只补一层 enabled 过滤。
     */
    public synchronized List<StateDecl> stateDecls() {
        List<StateDecl> out = new ArrayList<>();
        for (StateDecl d : stateIndex.values()) {
            if (enabled(d.pluginId)) out.add(d);
        }
        return out;
    }

    /**
     * 按全名找声明；不看启用状态（onEnable 期间 prefs 还没写入）。
     * **不加锁**：bridge 回调在 QuickJS 线程上执行，如果同步等 registry 锁会与 reload 互锁。
     */
    public StateDecl stateDecl(String fullName) {
        if (fullName == null) return null;
        return stateIndex.get(fullName);
    }

    /**
     * {@code requires} 里缺失的依赖（仅提示与 lint，不改变加载顺序）。
     * 返回人类可读的条目，如 {@code action svc.restart}。
     */
    public List<String> missingRequires(HookManifest m) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        for (String a : m.requiresActions) {
            if (actionDeclView(a) == null) out.add("action " + a);
        }
        for (String s : m.requiresStates) {
            if (stateDecl(s) == null) out.add("state " + s);
        }
        for (String p : m.requiresPages) {
            boolean found = false;
            for (PageDecl d : pagesView()) {
                if (d.fullId().equals(p)) {
                    found = true;
                    break;
                }
            }
            if (!found) out.add("page " + p);
        }
        return out;
    }

    // ---------------------------------------------------------------- 动作总线

    /**
     * 当前可用的全局动作（enabled）。
     * 直接走 {@link #actionView}：它已按声明 priority→pluginId→id 排好并去重，
     * 与 {@link #actionDeclView(String)} 的赢家一致；这里不再重复"首个"逻辑。
     */
    public synchronized List<ActionDecl> actionDecls() {
        return new ArrayList<>(actionView);
    }

    public ActionDecl action(String id) {
        if (id == null) return null;
        for (ActionDecl d : actionDecls()) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    /** 调提供方的 {@code onAction(ctx)}；失败返回 "Error: ..." 文本，由 UI 层 toast。 */
    public String invokeAction(ActionDecl d, JSONObject params, String source) {
        // 用无锁快照：bridge.action.invoke 会在 QuickJS 回调里走这条路径。
        HookManifest m = manifestView(d.pluginId);
        if (m == null || !enabled(d.pluginId)) return "Error: 动作提供方未启用: " + d.id;
        JSONObject ctx = new JSONObject();
        try {
            ctx.put("action", d.id);
            ctx.put("params", normalizeParams(d.params, params));
            ctx.put("source", source == null ? "" : source);
        } catch (Exception ignored) {
        }
        try {
            String out = engine.invokeText(m, ActionDecl.PROVIDER_FN, ctx, d.timeoutMs);
            noteSuccess(m);
            log(m.id, "action " + d.id + " ok");
            return out;
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            noteFailure(m, "action " + d.id + " failed: " + why, "动作 " + d.id + " 失败: " + why);
            return "Error: 动作 " + d.id + " 执行失败: " + why;
        }
    }

    private static JSONObject normalizeParams(JSONObject schema, JSONObject in) {
        if (in == null) in = new JSONObject();
        JSONObject out;
        try {
            out = new JSONObject(in.toString());
        } catch (Exception e) {
            out = new JSONObject();
        }
        if (schema == null) return out;
        JSONObject props = schema.optJSONObject("properties");
        if (props != null) {
            java.util.Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject p = props.optJSONObject(key);
                if (p == null) continue;
                if (!out.has(key)) {
                    Object def = p.opt("default");
                    if (def != null && def != JSONObject.NULL) {
                        try {
                            out.put(key, def);
                        } catch (Exception ignored) {
                        }
                    }
                }
                checkType(out, key, p);
            }
        }
        JSONArray req = schema.optJSONArray("required");
        if (req != null) {
            for (int i = 0; i < req.length(); i++) {
                String key = req.optString(i, "");
                if (!key.isEmpty() && (!out.has(key) || out.isNull(key))) {
                    throw new IllegalArgumentException("缺少必填参数: " + key);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 渲染器

    /** 当前生效的渲染器链（按渲染器自己的 priority 排序，与插件级 priority 无关）。 */
    public synchronized List<RendererRule> renderers() {
        List<RendererRule> out = new ArrayList<>();
        for (HookManifest m : manifests.values()) {
            if (!enabled(m.id) || !m.canRender()) continue;
            out.addAll(m.renderers);
        }
        out.sort(RendererRule.ORDER);
        return out;
    }

    /**
     * 渲染链（同步）：第一个命中的插件返回视图节点 JSON 后停止。
     *
     * <ul>
     *   <li>没有插件接管 → null（调用方回退默认渲染）；</li>
     *   <li>正则匹配超预算 → 跳过该条并记 {@link #lastError}；</li>
     *   <li>handler 异常/超时 → 记错并继续下一条，整块最终回退默认渲染。</li>
     * </ul>
     *
     * 结果按 (type, lang, body) 缓存，避免流式重排时对同一块反复跑 JS。
     * UI 线程请用 {@link #renderBlockForUi}：这里会在调用线程上跑 JS。
     */
    public String renderBlock(String type, String lang, String body) {
        String key = renderKey(type, lang, body);
        final int gen;
        synchronized (renderLock) {
            if (renderCache.containsKey(key)) return renderCache.get(key);
            // 记下求值前的代号：求值期间若发生 reload/禁用（clearRenderCache 会 ++），
            // 旧结果不能写进新一代的缓存。
            gen = renderGeneration;
        }
        String json = evaluateBlock(type, lang, body);
        synchronized (renderLock) {
            if (gen == renderGeneration) renderCache.put(key, json);
        }
        return json;
    }

    /**
     * UI 主线程入口：只读缓存；未命中立即返回 null（本帧走默认渲染），
     * 同时把求值提交到 plugin-render 线程。完成后回调 {@link #setRenderReadyListener}
     * 触发一次重渲染，第二次直接命中缓存 —— 主线程不再执行插件 JS。
     *
     * <p>非主线程调用等价于同步 {@link #renderBlock}。
     */
    public String renderBlockForUi(String type, String lang, String body) {
        String key = renderKey(type, lang, body);
        boolean onMain;
        try {
            onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable e) {
            onMain = false;
        }
        if (!onMain) return renderBlock(type, lang, body);
        final int gen;
        synchronized (renderLock) {
            if (renderCache.containsKey(key)) return renderCache.get(key);
            if (!renderPendingKeys.add(key)) return null;
            gen = renderGeneration;
        }
        try {
            renderWorker.execute(() -> finishAsyncRender(key, type, lang, body, gen));
        } catch (Throwable e) {
            synchronized (renderLock) {
                renderPendingKeys.remove(key);
            }
        }
        return null;
    }

    /** 注册“后台 renderer 有新结果”的回调；回调在主线程执行。 */
    public void setRenderReadyListener(Runnable listener) {
        renderReady = listener;
    }

    /** 后台线程上的实际求值；结果只在本代插件集合未变时回写缓存并通知 UI。 */
    private void finishAsyncRender(String key, String type, String lang, String body, int gen) {
        String json = null;
        try {
            json = evaluateBlock(type, lang, body);
        } catch (Throwable ignored) {
        }
        boolean notify = false;
        synchronized (renderLock) {
            renderPendingKeys.remove(key);
            if (gen == renderGeneration) {
                renderCache.put(key, json);
                if (!renderNotifyScheduled) {
                    renderNotifyScheduled = true;
                    notify = true;
                }
            }
        }
        if (!notify) return;
        android.os.Handler handler = mainHandler();
        if (handler == null) {
            synchronized (renderLock) {
                renderNotifyScheduled = false;
            }
            return;
        }
        handler.post(() -> {
            synchronized (renderLock) {
                renderNotifyScheduled = false;
            }
            Runnable cb = renderReady;
            if (cb != null) cb.run();
        });
    }

    private android.os.Handler mainHandler() {
        try {
            android.os.Looper looper = android.os.Looper.getMainLooper();
            return looper == null ? null : new android.os.Handler(looper);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String renderKey(String type, String lang, String body) {
        return type + '\u0000' + (lang == null ? "" : lang) + '\u0000'
                + (body == null ? "" : body);
    }

    /** 渲染链本体（不含缓存）：可能在 UI 线程之外的任何线程执行。 */
    private String evaluateBlock(String type, String lang, String body) {
        List<RendererRule> chain = renderers();
        if (chain.isEmpty()) return null;
        String json = null;
        for (RendererRule rule : chain) {
            if (!rule.matches(type, lang, body)) {
                if (rule.lastTimedOut) {
                    String why = "renderer " + rule.id + " 的正则匹配超过 100ms，已跳过";
                    errors.put(rule.pluginId, why);
                    DebugLog.w("plugin " + rule.pluginId + " " + why);
                }
                continue;
            }
            HookManifest m;
            synchronized (this) {
                m = manifests.get(rule.pluginId);
            }
            if (m == null) continue;

            JSONObject ctx = new JSONObject();
            try {
                ctx.put("type", type);
                ctx.put("lang", lang == null ? "" : lang);
                ctx.put("body", body == null ? "" : body);
                ctx.put("data", dataOf(body));
                json = engine.invokeJson(m, rule.handler, ctx, RENDER_BUDGET_MS);
                noteSuccess(m);
            } catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                noteFailure(m, "renderer " + rule.id + " failed: " + why,
                        "renderer " + rule.id + " 失败: " + why);
                json = null;      // 异常/超时：回退默认渲染
            }
            if (json != null) break;
        }
        return json;
    }

    /** {@code body} 是合法 JSON 对象时预解析好放进 ctx.data，否则为 null（文档 §4）。 */
    private static Object dataOf(String body) {
        if (body == null) return JSONObject.NULL;
        String t = body.trim();
        if (!t.startsWith("{")) return JSONObject.NULL;
        try {
            return new JSONObject(t);
        } catch (Exception e) {
            return JSONObject.NULL;
        }
    }

    private void clearRenderCache() {
        synchronized (renderLock) {
            renderCache.clear();
            renderPendingKeys.clear();
            renderGeneration++;
            renderNotifyScheduled = false;
        }
    }

    // ---------------------------------------------------------------- 调试

    /** plugin:lint：静态检查 + 声明函数的可执行检查，不改变启用状态。 */
    public String lint(String id, Set<String> builtinNames) {
        HookManifest m;
        List<HookManifest> all;
        String loadError;
        synchronized (this) {
            m = manifests.get(id);
            all = new ArrayList<>(manifests.values());
            loadError = errors.get(id);
        }
        if (m == null) {
            return loadError == null ? "no such plugin: " + id : "插件加载失败: " + loadError;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("plugin ").append(id).append("  api=v").append(m.apiVersion)
                .append("  priority=").append(m.priority).append('\n');
        int errs = 0;
        int warns = 0;

        if (!m.tools.isEmpty() && !m.can("tool.register")) {
            sb.append("WARN  声明了 tools 但未申请 tool.register，运行时不会注册\n");
            warns++;
        }
        if (!m.renderers.isEmpty() && !m.can("render.match")) {
            sb.append("WARN  声明了 renderers 但未申请 render.match，运行时不会接线\n");
            warns++;
        }
        if (!m.slots.isEmpty() && !m.can("ui.slot")) {
            sb.append("WARN  声明了 ui 插槽但未申请 ui.slot，运行时不会挂载\n");
            warns++;
        }
        if (!m.pages.isEmpty() && !m.can("ui.page")) {
            sb.append("WARN  声明了 pages 但未申请 ui.page，运行时不会出现在导航里\n");
            warns++;
        }
        if (!m.nav.isEmpty() && !m.can("ui.page")) {
            sb.append("WARN  声明了 nav 但未申请 ui.page，运行时会被忽略\n");
            warns++;
        }
        if (!m.actions.isEmpty() && !m.can("action.provide")) {
            sb.append("WARN  声明了 actions 但未申请 action.provide，运行时不会注册\n");
            warns++;
        }
        if (m.theme != null && !m.theme.skins.isEmpty() && !m.can("render.match")) {
            sb.append("WARN  theme.skins 未申请 render.match，皮肤不会生效\n");
            warns++;
        }
        if (!m.states.isEmpty() && !m.can("state.provide")) {
            sb.append("WARN  声明了 state 但未申请 state.provide，bridge.state.set 会报 E_PERM\n");
            warns++;
        }
        if (m.hasHook(HookManifest.HOOK_BEFORE_TOOL)
                && !m.canVeto() && !m.canRewriteArgs()) {
            sb.append("WARN  beforeToolCall 没有 veto/rewrite（hook.tool / hook.tool.rewrite）能力，只能放行\n");
            warns++;
        }
        if (m.hasHook(HookManifest.HOOK_AFTER_TOOL) && !m.canReplaceResult()) {
            sb.append("WARN  afterToolResult 没有 replace（hook.tool.rewrite）能力，返回值不会被改写\n");
            warns++;
        }
        if (m.hasHook(HookManifest.HOOK_USER_MESSAGE) && !m.can("hook.message")) {
            sb.append("WARN  userMessage 未申请 hook.message，运行时只能放行\n");
            warns++;
        }
        if (m.hasHook(HookManifest.HOOK_CONTEXT_BUILD) && !m.can("hook.context")) {
            sb.append("WARN  contextBuild 未申请 hook.context，运行时只能放行\n");
            warns++;
        }
        if (m.hasHook(HookManifest.HOOK_ASSISTANT_MESSAGE) && !m.can("hook.message")) {
            sb.append("WARN  assistantMessage 未申请 hook.message，输出不会被改写\n");
            warns++;
        }
        for (String miss : missingRequires(m)) {
            sb.append("WARN  缺少依赖: ").append(miss).append("（未安装或未启用）\n");
            warns++;
        }
        if (m.declaresGrant("net.http") && m.netAllow.isEmpty()) {
            sb.append("WARN  声明了 net.http 但 net.allow 为空，bridge.http 会全部拒绝\n");
            warns++;
        }
        if (m.declaresGrant("tool.invoke") && m.toolAllow.isEmpty()) {
            sb.append("WARN  声明了 tool.invoke 但 toolAccess.allow 为空，所有工具调用都会被拒\n");
            warns++;
        }
        if (!m.toolAllow.isEmpty() && !m.declaresGrant("tool.invoke")) {
            sb.append("WARN  toolAccess.allow 非空但未声明 tool.invoke，bridge.tool.invoke 会 E_PERM\n");
            warns++;
        }
        if (m.declaresGrant("location")
                && !m.declaresGrant("android.permission.ACCESS_FINE_LOCATION")
                && !m.declaresGrant("android.permission.ACCESS_COARSE_LOCATION")) {
            sb.append("WARN  声明了 location 但 androidPermissions 没有定位权限，bridge.location.get 会 E_PERM\n");
            warns++;
        }
        if (!m.declaresGrant("location")
                && (m.declaresGrant("android.permission.ACCESS_FINE_LOCATION")
                || m.declaresGrant("android.permission.ACCESS_COARSE_LOCATION"))) {
            sb.append("WARN  声明了定位系统权限但未声明 location grant，bridge.location.get 会 E_PERM\n");
            warns++;
        }

        if (!m.tools.isEmpty()) {
            // 静态重名检查覆盖所有已加载插件（不看启用状态），和运行时规则同一套。
            List<ToolPlan.Entry> entries = new ArrayList<>();
            for (HookManifest other : all) {
                if (other.tools.isEmpty()) continue;
                Map<String, Integer> names = new LinkedHashMap<>();
                for (ToolDecl d : other.tools) names.put(d.name, d.priority);
                entries.add(new ToolPlan.Entry(other.id, !other.can("tool.register"), names));
            }
            ToolPlan.Result plan = ToolPlan.resolve(entries, builtinNames);
            for (ToolDecl d : m.tools) {
                if (m.id.equals(plan.owners.get(d.name))) {
                    sb.append("OK    工具 ").append(d.name).append(" -> ").append(d.handler)
                            .append("  timeout=").append(d.timeoutMs).append("ms")
                            .append("  priority=").append(d.priority).append('\n');
                } else {
                    String why = plan.conflicts.get(d.name);
                    sb.append("ERROR ")
                            .append(why == null ? "工具 " + d.name + " 未注册" : why).append('\n');
                    errs++;
                }
            }
        }

        List<String> declared = new ArrayList<>();
        for (String h : m.hooks) {
            declared.add("on" + Character.toUpperCase(h.charAt(0)) + h.substring(1));
        }
        for (ToolDecl d : m.tools) declared.add(d.handler);
        for (RendererRule r : m.renderers) declared.add(r.handler);
        for (UiSlotDecl d : m.slots) declared.add(d.handler);
        for (PageDecl p : m.pages) declared.add(p.handler);
        if (!m.actions.isEmpty()) declared.add(ActionDecl.PROVIDER_FN);

        for (UiSlotDecl d : m.slots) {
            sb.append("OK    插槽 ").append(d.slot).append(" -> ").append(d.handler).append('\n');
        }
        for (PageDecl p : m.pages) {
            sb.append("OK    页面 ").append(p.fullId()).append(" -> ").append(p.handler)
                    .append("  refresh=").append(p.refreshMs).append("ms\n");
        }
        for (ActionDecl a : m.actions) {
            boolean dup = false;
            for (HookManifest other : all) {
                if (other == m) continue;
                for (ActionDecl o : other.actions) {
                    if (o.id.equals(a.id)) dup = true;
                }
            }
            if (dup) {
                sb.append("WARN  动作 id 与其它插件冲突: ").append(a.id).append('\n');
                warns++;
            } else {
                sb.append("OK    动作 ").append(a.id).append("  ").append(a.label)
                        .append("  timeout=").append(a.timeoutMs).append("ms\n");
            }
        }
        for (StateDecl s : m.states) {
            boolean dup = false;
            for (HookManifest other : all) {
                if (other == m) continue;
                for (StateDecl o : other.states) {
                    if (o.fullName().equals(s.fullName())) dup = true;
                }
            }
            if (dup) {
                sb.append("WARN  状态全名与其它插件冲突: ").append(s.fullName()).append('\n');
                warns++;
            } else {
                sb.append("OK    状态 ").append(s.fullName()).append("  ").append(s.type)
                        .append("  ").append(s.label).append('\n');
            }
        }

        if (!m.nav.isEmpty()) {
            Set<String> knownPages = new LinkedHashSet<>();
            knownPages.add(NavDecl.CHAT_PAGE);
            for (HookManifest other : all) {
                for (PageDecl p : other.pages) knownPages.add(p.fullId());
            }
            for (NavDecl n : m.nav) {
                if (!knownPages.contains(n.page)) {
                    sb.append("WARN  nav 指向未注册页面: ").append(n.page).append('\n');
                    warns++;
                } else {
                    sb.append("OK    导航 ").append(n.page);
                    if (n.group != null) sb.append("  group=").append(n.group);
                    if (n.order != null) sb.append("  order=").append(n.order);
                    if (Boolean.TRUE.equals(n.hidden)) sb.append("  hidden");
                    sb.append('\n');
                }
            }
        }
        for (String cap : grants.declaredGrants(m)) {
            sb.append("OK    授权 ").append(cap)
                    .append("  ").append(grants.status(m, cap, host)).append('\n');
        }
        declared.add("onEnable");
        declared.add("onDisable");

        boolean wasEnabled = enabled(id);
        try {
            for (String fn : declared) {
                if (engine.hasFunction(m, fn)) {
                    sb.append("OK    函数 ").append(fn).append('\n');
                } else if ("onEnable".equals(fn) || "onDisable".equals(fn)) {
                    sb.append("--    可选函数 ").append(fn).append(" 未实现\n");
                } else {
                    sb.append("ERROR 声明的函数未实现: ").append(fn).append('\n');
                    errs++;
                }
            }
        } catch (Throwable e) {
            sb.append("ERROR 入口脚本执行失败: ")
                    .append(e.getMessage() == null ? e.toString() : e.getMessage()).append('\n');
            errs++;
        } finally {
            // lint 不应把未启用插件的 runtime 一直挂着。
            if (!wasEnabled) engine.close(id);
        }

        sb.append("结果: ").append(errs).append(" error, ").append(warns).append(" warn");
        return sb.toString();
    }

    /** plugin:logs：按插件过滤最近的调试日志。 */
    public String logs(String id, int n) {
        List<String> picked = new ArrayList<>();
        synchronized (logs) {
            for (String line : logs) {
                if (id == null || id.isEmpty() || line.contains("[" + id + "]")) picked.add(line);
            }
        }
        if (picked.isEmpty()) return "(没有日志)";
        int limit = Math.max(1, Math.min(n, 100));
        int from = Math.max(0, picked.size() - limit);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < picked.size(); i++) sb.append(picked.get(i)).append('\n');
        return sb.toString();
    }

    private void log(String id, String msg) {
        String line;
        try {
            line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date()) + " [" + id + "] " + msg;
        } catch (Throwable e) {
            line = "[" + id + "] " + msg;
        }
        synchronized (logs) {
            logs.addLast(line);
            while (logs.size() > LOG_MAX) logs.removeFirst();
        }
    }

    private List<HookManifest> chain(String hook) {
        List<HookManifest> out = new ArrayList<>();
        synchronized (this) {
            for (HookManifest m : manifests.values()) {
                if (m.hasHook(hook) && enabled(m.id)) out.add(m);
            }
        }
        return out;
    }
}
