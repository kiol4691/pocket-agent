package com.dsh.android.plugin;

import com.quickjs.JSArray;
import com.quickjs.JSContext;
import com.quickjs.JSFunction;
import com.quickjs.JSObject;
import com.quickjs.QuickJS;
import com.quickjs.QuickJSException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type B 钩子的执行引擎：QuickJS（本地 fork，见 {@code qjsfork/}）。
 *
 * 为什么从 Rhino 换过来：Rhino 每次调用都要 {@code initSafeStandardObjects}，
 * 真机上几十到几百毫秒；而且拿不到内存上限，插件一次 {@code while(true) a.push(...)}
 * 就能把进程撑到 OOM。QuickJS 冷启动 11 ms、之后每次 1 ms 级，并且 fork 里补了
 * {@code setMemoryLimit} / {@code setMaxStackSize} / {@code setTimeoutMs}。
 *
 * 与旧实现对应的几条约定：
 *
 *  1. **资源闸门**：每个插件一个 runtime（内存上限按 runtime 生效，默认 16 MB，可在
 *     manifest 的 {@code limits.memoryMb} 调整），超时用宿主的 interrupt handler 中断，
 *     死循环和大分配都变成 JS 异常，进程存活。
 *  2. **常驻作用域**：每个插件在 runtime 上复用一个 context，入口脚本只执行一次；
 *     {@code onEnable} 里定义的全局变量在后续钩子/工具/渲染调用里都可见。插件之间仍然
 *     互不可见（各有一个 runtime）。
 *  3. **崩溃即重置**：一旦出现 out of memory / interrupted，runtime 状态没有保证，
 *     直接丢弃重建；重建后如果插件仍处于启用状态，会自动重放一次 {@code onEnable}。
 *  4. **返回值结构化**：只认 {@code {action, reason, args, result}}，不是对象就当放行，
 *     不做任何"猜意图"的转换。
 *  5. **没有 Java 访问**：QuickJS 默认只给标准库，绑定不注册任何宿主对象；插件拿不到
 *     Java 类、文件、网络。
 *
 * 注意：旧实现的 {@code budgetSteps}（指令数预算）在 QuickJS 上无法按指令计数，
 * 现在只按墙钟预算执行；{@code limits.budgetSteps} 仍会被读取但不生效。
 */
public final class HookEngine {

    /** 一次钩子调用的结论。 */
    public static final class Decision {
        public final boolean allow;
        public final String args;      // 改写后的参数 JSON；null = 不改
        public final String result;    // 替换后的工具结果；null = 不改
        public final String text;      // 替换后的用户/助手消息文本；null = 不改
        public final String messages;  // 替换后的上下文消息 JSON 数组；null = 不改
        public final String reason;

        private Decision(boolean allow, String args, String result, String text,
                         String messages, String reason) {
            this.allow = allow;
            this.args = args;
            this.result = result;
            this.text = text;
            this.messages = messages;
            this.reason = reason;
        }

        static Decision allow() {
            return new Decision(true, null, null, null, null, "");
        }

        static Decision veto(String reason) {
            return new Decision(false, null, null, null, null, reason);
        }

        static Decision rewrite(String args) {
            return new Decision(true, args, null, null, null, "");
        }

        static Decision replace(String result) {
            return new Decision(true, null, result, null, null, "");
        }

        static Decision replaceText(String text) {
            return new Decision(true, null, null, text, null, "");
        }

        static Decision replaceMessages(String messages) {
            return new Decision(true, null, null, null, messages, "");
        }
    }

    /** 每个插件的 runtime；内存/栈上限挂在 runtime 上，所以按插件隔离。 */
    private static final class Rt {
        final QuickJS js;
        /** 常驻 context：首次调用时创建并执行入口脚本，之后复用。 */
        JSContext ctx;
        /** onEnable 是否已经跑过（含"脚本里没实现"的情况）；runtime 重建后归零。 */
        boolean onEnableDone;

        Rt(QuickJS js) {
            this.js = js;
        }
    }

    private static final class Cached {
        final long mtime;
        final String source;

        Cached(long mtime, String source) {
            this.mtime = mtime;
            this.source = source;
        }
    }

    /** C 栈上限：够递归几十层，又不至于让恶意深递归打爆线程栈。 */
    private static final long STACK_BYTES = 512 * 1024;

    private final Map<String, Rt> runtimes = new ConcurrentHashMap<>();
    private final Map<String, Cached> sources = new ConcurrentHashMap<>();
    /** 已启用的插件：runtime 重建后据此自动重放 onEnable。 */
    private final java.util.Set<String> enabledIds = ConcurrentHashMap.newKeySet();
    /** bridge.* 的宿主实现；为 null 时不注入（单测/无宿主场景）。 */
    private volatile PluginBridge bridge;
    /**
     * 当前 JS 调用的预算帧栈：bridge 回调期间暂停中断，并把阻塞耗时从预算里扣除
     * （文档 §9.2.1：budgetMs 只计 JS 自己执行的墙钟时间）。
     */
    private static final class BudgetFrame {
        final int budgetMs;
        long startMs;
        long blockedMs;
        /** 本次 JS 调用里 bridge.* 被调用的次数：防止"循环调 bridge"绕过墙钟预算。 */
        int bridgeCalls;

        BudgetFrame(int budgetMs) {
            this.budgetMs = budgetMs;
            this.startMs = System.currentTimeMillis();
        }
    }

    /**
     * 单次 JS 调用允许的 bridge 次数上限。bridge 回调期间引擎中断是暂停的（§9.2.1），
     * 一个 {@code while (true) bridge.log(...)} 会把"JS 自身耗时"压成 0，墙钟预算永不触发；
     * 这里用次数兜底，超限按 E_BUDGET 处理（会被 isBroken 重置 runtime）。
     */
    private static final int MAX_BRIDGE_CALLS = 2000;

    private final ThreadLocal<java.util.ArrayDeque<BudgetFrame>> budgetStack =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);
    /** 当前线程正在执行的插件（防 bridge.action.invoke 同步重入同一个 JS runtime）。 */
    private final ThreadLocal<java.util.ArrayDeque<String>> executingStack =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    public boolean isExecuting(String pluginId) {
        return pluginId != null && executingStack.get().contains(pluginId);
    }

    private void pushExecuting(String pluginId) {
        executingStack.get().push(pluginId);
    }

    private void popExecuting() {
        java.util.ArrayDeque<String> stack = executingStack.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) executingStack.remove();
    }

    private void pushBudget(int budgetMs) {
        budgetStack.get().push(new BudgetFrame(budgetMs));
    }

    private void popBudget() {
        java.util.ArrayDeque<BudgetFrame> stack = budgetStack.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) budgetStack.remove();
    }

    private BudgetFrame currentFrame() {
        return budgetStack.get().peek();
    }

    /** context() 首次执行入口脚本/重放 onEnable 后，本次调用的预算从这一刻重新计。 */
    private void resetBudgetFrame() {
        BudgetFrame f = currentFrame();
        if (f != null) {
            f.startMs = System.currentTimeMillis();
            f.blockedMs = 0;
        }
    }

    public void setBridge(PluginBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * 跑一个钩子。返回 null 表示插件没实现这个钩子；抛异常表示钩子失败（由调用方按
     * manifest 的 onError 决定 fail-closed 还是跳过）。
     */
    public Decision invoke(HookManifest m, String hookName, JSONObject ctxJson) throws Exception {
        // 钩子持有工具执行锁，不允许阻塞式 bridge 弹窗/长任务（§9.2.1）。
        PluginBridge.enterNonBlocking();
        try {
            return call(m, hookName, ctxJson, (ctx, mm, fn, ret) -> parse(ctx, mm, fn, ret));
        } finally {
            PluginBridge.exitNonBlocking();
        }
    }

    /**
     * 调用任意导出函数并把返回值序列化成 JSON 文本（renderer 用）。
     * 没实现、返回 undefined/null、或返回值不是对象时返回 null —— 调用方据此回退默认渲染。
     */
    public String invokeJson(HookManifest m, String fnName, JSONObject ctxJson) throws Exception {
        return call(m, fnName, ctxJson, (ctx, mm, fn, ret) -> jsonText(ctx, ret), m.budgetMs);
    }

    /** 同上，但用调用方给的墙钟预算（渲染在主线程跑，必须比钩子更短）。 */
    public String invokeJson(HookManifest m, String fnName, JSONObject ctxJson, int budgetMs)
            throws Exception {
        return call(m, fnName, ctxJson, (ctx, mm, fn, ret) -> jsonText(ctx, ret),
                Math.max(1, Math.min(budgetMs, m.budgetMs)));
    }

    /**
     * 调用工具 handler：字符串原样返回，对象/数组转 JSON 文本，数字/布尔转字符串，
     * undefined/null 返回 null（由调用方决定怎么提示"没有结果"）。
     * 工具预算可以远超 {@code budgetMs}（钩子的 300ms 约束不适用于模型主动发起的工具）。
     */
    public String invokeText(HookManifest m, String fnName, JSONObject ctxJson, int budgetMs)
            throws Exception {
        return call(m, fnName, ctxJson, (ctx, mm, fn, ret) -> text(ctx, ret),
                Math.max(1, Math.min(budgetMs, ToolDecl.MAX_TIMEOUT_MS)));
    }

    /** 脚本里是否实现了某个函数；lint 与生命周期调用共用。 */
    public boolean hasFunction(HookManifest m, String fnName) throws Exception {        for (int attempt = 0; ; attempt++) {
            Rt rt = runtime(m);
            synchronized (rt.js) {
                // runtime 可能在取到之后被 reset 释放：重取一次，绝不在已释放的 js 上执行。
                if (rt.js.isReleased()) {
                    if (attempt >= 2) throw new IllegalStateException("E_RUNTIME: runtime 已释放");
                    continue;
                }
                boolean broken = false;
                try {
                    rt.js.setTimeoutMs(2_000);
                    pushBudget(2_000);
                    pushExecuting(m.id);
                    JSContext ctx = context(rt, m);
                    return ctx.get(fnName) instanceof JSFunction;
                } catch (Throwable e) {
                    broken = isBroken(e);
                    throw e;
                } finally {
                    popExecuting();
                    popBudget();
                    try {
                        if (!rt.js.isReleased()) rt.js.setTimeoutMs(0);
                    } catch (Throwable ignored) {
                    }
                    if (broken) reset(m.id);
                }
            }
        }
    }

    /**
     * 跑一次生命周期函数（onEnable / onDisable）。没实现时是 no-op —— 两者都是可选的。
     * 预算由调用方给（启用 500ms、禁用 200ms）。
     */
    public void callLifecycle(HookManifest m, String fnName, int budgetMs) throws Exception {
        for (int attempt = 0; ; attempt++) {
            Rt rt = runtime(m);
            synchronized (rt.js) {
                // 同 call()：runtime 已释放就重取，避免在已释放的 js 上 setTimeoutMs。
                if (rt.js.isReleased()) {
                    if (attempt >= 2) throw new IllegalStateException("E_RUNTIME: runtime 已释放");
                    continue;
                }
                boolean broken = false;
                try {
                    rt.js.setTimeoutMs(budgetMs);
                    pushBudget(budgetMs);
                    pushExecuting(m.id);
                    JSContext ctx = context(rt, m);
                    // context() 里的自动重放可能改过截止时间，这里重新设定本次预算。
                    rt.js.setTimeoutMs(budgetMs);
                    resetBudgetFrame();
                    Object fn = ctx.get(fnName);
                    if (!(fn instanceof JSFunction)) {
                        // 没有实现也算"该生命周期已处理"，避免每次调用都重复探测。
                        if ("onEnable".equals(fnName)) rt.onEnableDone = true;
                        return;
                    }
                    JSArray args = new JSArray(ctx);
                    args.push(new JSObject(ctx, new JSONObject()));
                    ((JSFunction) fn).call(null, args);
                    if ("onEnable".equals(fnName)) rt.onEnableDone = true;
                    // 成功执行完必须退出：这个 for 只为"runtime 已释放"重取一次，
                    // 少了 return 会把生命周期函数无限重放（真机上表现为 onEnable 死循环 + ANR）。
                    return;
                } catch (Throwable e) {
                    broken = isBroken(e);
                    throw e;
                } finally {
                    popExecuting();
                    popBudget();
                    try {
                        if (!rt.js.isReleased()) rt.js.setTimeoutMs(0);
                    } catch (Throwable ignored) {
                    }
                    if (broken) reset(m.id);
                }
            }
        }
    }

    /** 启用成功后由 registry 标记；runtime 重建时据此补跑 onEnable。 */
    public void markEnabled(String id) {
        enabledIds.add(id);
    }

    /** 禁用/重载前由 registry 标记，之后重建 runtime 不再补跑 onEnable。 */
    public void markDisabled(String id) {
        enabledIds.remove(id);
    }

    private interface Converter<T> {
        T convert(JSContext ctx, HookManifest m, String fnName, Object ret);
    }

    private <T> T call(HookManifest m, String fnName, JSONObject ctxJson, Converter<T> conv)
            throws Exception {
        return call(m, fnName, ctxJson, conv, m.budgetMs);
    }

    private <T> T call(HookManifest m, String fnName, JSONObject ctxJson, Converter<T> conv,
                       int budgetMs) throws Exception {
        for (int attempt = 0; ; attempt++) {
            Rt rt = runtime(m);
            // QuickJS 的 runtime 不是线程安全的：钩子跑在 agent 线程，UI 入口跑在 plugin-ui 线程池，
            // renderer 跑在 plugin-render 线程，同一插件的所有入口都在 runtime 上串行（见 PLUGIN_API §9.1.1）。
            synchronized (rt.js) {
                // runtime 可能在取到之后被 reset 释放：重取一次，绝不在已释放的 js 上执行。
                if (rt.js.isReleased()) {
                    if (attempt >= 2) throw new IllegalStateException("E_RUNTIME: runtime 已释放");
                    continue;
                }
                boolean broken = false;
                try {
                    rt.js.setTimeoutMs(budgetMs);
                    pushBudget(budgetMs);
                    pushExecuting(m.id);
                    JSContext ctx = context(rt, m);
                    // context() 首次创建时会执行入口脚本；若发生自动重放，截止时间会被改过。
                    rt.js.setTimeoutMs(budgetMs);
                    resetBudgetFrame();
                    Object fn = ctx.get(fnName);
                    if (!(fn instanceof JSFunction)) return null;

                    JSArray args = new JSArray(ctx);
                    args.push(new JSObject(ctx, ctxJson));
                    Object ret = ((JSFunction) fn).call(null, args);
                    return conv.convert(ctx, m, fnName, ret);
                } catch (Throwable e) {
                    broken = isBroken(e);
                    throw e;
                } finally {
                    popExecuting();
                    popBudget();
                    try {
                        if (!rt.js.isReleased()) rt.js.setTimeoutMs(0);
                    } catch (Throwable ignored) {
                    }
                    if (broken) reset(m.id);
                }
            }
        }
    }

    /**
     * 取常驻 context（每插件一个）。首次创建时执行入口脚本；如果 runtime 之前因 OOM/超时
     * 被重建且插件仍处于启用状态，这里补跑一次 onEnable —— 常驻作用域不能因为一次崩溃
     * 就永久消失，否则之后所有调用读到的都是 undefined 的全局变量。
     */
    private JSContext context(Rt rt, HookManifest m) throws Exception {
        if (rt.ctx != null) return rt.ctx;
        JSContext ctx = rt.js.createContext();
        boolean ok = false;
        try {
            injectBridge(ctx, m);
            ctx.executeVoidScript(source(m), m.entry);
            rt.ctx = ctx;
            ok = true;
        } finally {
            if (!ok) {
                try {
                    ctx.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (enabledIds.contains(m.id) && !rt.onEnableDone) {
            rt.js.setTimeoutMs(500);
            try {
                Object fn = ctx.get("onEnable");
                if (fn instanceof JSFunction) {
                    JSArray args = new JSArray(ctx);
                    args.push(new JSObject(ctx, new JSONObject()));
                    ((JSFunction) fn).call(null, args);
                }
                rt.onEnableDone = true;
            } catch (RuntimeException | Error e) {
                // 常驻作用域没建立成功：丢掉这个半初始化的 context，下次调用从头再来。
                rt.ctx = null;
                try {
                    ctx.close();
                } catch (Throwable ignored) {
                }
                throw e;
            } finally {
                try {
                    if (!rt.js.isReleased()) rt.js.setTimeoutMs(0);
                } catch (Throwable ignored) {
                }
            }
        }
        return ctx;
    }

    /** 释放所有 runtime（插件重载/安装后调用）。 */
    public void closeAll() {
        for (String id : new java.util.ArrayList<>(runtimes.keySet())) {
            reset(id);
        }
        enabledIds.clear();
    }

    /**
     * OOM / 中断之后 runtime 状态不可信（QuickJS 文档明确说不保证），丢弃重建。
     * 只在这两类错误上做，普通脚本异常不动 runtime —— 重建一次 11 ms，不该为
     * 一次 `undefined.foo` 付这个代价。
     */
    private static boolean isBroken(Throwable e) {
        if (e instanceof OutOfMemoryError) return true;
        String s = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase();
        return s.contains("out of memory") || s.contains("interrupted")
                || s.contains("stack overflow");
    }

    private void reset(String id) {
        Rt r = runtimes.get(id);
        if (r == null) return;
        // 先取 runtime 锁再 remove：移除与销毁在同一把锁内完成，避免"已 remove 但另一个
        // 线程仍持有旧 rt"时在已释放的 js 上继续执行。compare-and-remove 防止并发
        // reset 时误删刚重建的新 runtime。
        synchronized (r.js) {
            runtimes.remove(id, r);
            if (r.js.isReleased()) return;
            if (r.ctx != null) {
                try {
                    r.ctx.close();
                } catch (Throwable ignored) {
                }
                r.ctx = null;
            }
            try {
                r.js.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private Rt runtime(HookManifest m) {
        Rt r = runtimes.get(m.id);
        if (r != null && !r.js.isReleased()) return r;
        synchronized (this) {
            r = runtimes.get(m.id);
            if (r != null && !r.js.isReleased()) return r;

            QuickJS js = QuickJS.createRuntimeWithEventQueue();
            js.setMemoryLimit(m.memoryMb * 1024L * 1024L);
            js.setMaxStackSize(STACK_BYTES);
            r = new Rt(js);
            runtimes.put(m.id, r);
            return r;
        }
    }

    /** 源码缓存：文件没变就不重复读盘（编译仍然每次做，成本约 0.3 ms）。 */
    private String source(HookManifest m) throws Exception {
        File f = new File(m.dir, m.entry);
        String key = f.getAbsolutePath();
        long mtime = f.lastModified();
        Cached c = sources.get(key);
        if (c != null && c.mtime == mtime) return c.source;

        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        sources.put(key, new Cached(mtime, src));
        return src;
    }

    /**
     * 注入 bridge.*：按命名空间建嵌套对象（§7）。JavaCallback 在 JS 线程同步执行，
     * 阻塞式调用期间暂停 runtime 中断，避免长任务被 budgetMs 误杀。
     */
    private void injectBridge(JSContext ctx, HookManifest m) {
        PluginBridge b = bridge;
        if (b == null) return;
        JSObject root = new JSObject(ctx);

        root.set("log", bridgeFn(ctx, b, m, "log", "log"));
        root.set("exec", bridgeFn(ctx, b, m, "", "exec"));
        root.set("notify", bridgeFn(ctx, b, m, "", "notify"));
        root.set("capabilities", bridgeFn(ctx, b, m, "", "capabilities"));

        root.set("config", nsObj(ctx, b, m, "config", "get"));
        root.set("storage", nsObj(ctx, b, m, "storage", "get", "set"));
        root.set("state", nsObj(ctx, b, m, "state", "set", "get", "all"));
        root.set("device", nsObj(ctx, b, m, "device",
                "model", "sdk", "density", "language", "freeStorage"));
        root.set("time", nsObj(ctx, b, m, "time", "now", "text", "date", "zone", "format"));
        root.set("fs", nsObj(ctx, b, m, "fs", "read", "write", "list", "remove"));
        root.set("http", nsObj(ctx, b, m, "http", "request"));
        root.set("ui", nsObj(ctx, b, m, "ui", "toast", "confirm", "patch", "bounds"));
        root.set("nav", nsObj(ctx, b, m, "nav", "push", "back", "current"));
        root.set("action", nsObj(ctx, b, m, "action", "available", "invoke"));
        root.set("adb", nsObj(ctx, b, m, "adb", "available", "provider", "shell", "exec"));
        root.set("secret", nsObj(ctx, b, m, "secret", "get"));
        root.set("location", nsObj(ctx, b, m, "location", "available", "status", "get"));
        root.set("clipboard", nsObj(ctx, b, m, "clipboard", "read", "write"));
        root.set("tool", nsObj(ctx, b, m, "tool", "list", "invoke"));
        root.set("permission", nsObj(ctx, b, m, "permission", "status", "describe", "request"));
        root.set("context", nsObj(ctx, b, m, "context", "usage"));
        root.set("session", nsObj(ctx, b, m, "session", "info"));
        root.set("runtime", nsObj(ctx, b, m, "runtime", "status"));
        root.set("display", nsObj(ctx, b, m, "display", "metrics"));
        root.set("theme", nsObj(ctx, b, m, "theme", "get"));
        root.set("plugins", nsObj(ctx, b, m, "plugins", "list"));

        JSObject overlay = nsObj(ctx, b, m, "overlay",
                "show", "update", "hide", "hideAll");
        overlay.set("system", nsObj(ctx, b, m, "overlay.system",
                "status", "request", "show", "hide"));
        root.set("overlay", overlay);

        ctx.set("bridge", root);
    }

    private JSObject nsObj(JSContext ctx, PluginBridge b, HookManifest m,
                           String ns, String... methods) {
        JSObject o = new JSObject(ctx);
        for (String method : methods) {
            o.set(method, bridgeFn(ctx, b, m, ns, method));
        }
        return o;
    }

    private JSFunction bridgeFn(JSContext ctx, PluginBridge b, HookManifest m,
                                String ns, String method) {
        return ctx.registerJavaMethod((receiver, args) -> {
            QuickJS js = ctx.getQuickJS();
            BudgetFrame frame = currentFrame();
            // 次数兜底：bridge 调用期间中断被暂停，纯 bridge 死循环不会触发墙钟预算。
            if (frame != null && ++frame.bridgeCalls > MAX_BRIDGE_CALLS) {
                throw new QuickJSException("E_BUDGET",
                        "bridge 调用次数超过 " + MAX_BRIDGE_CALLS + "，疑似死循环");
            }
            long pausedAt = 0;
            boolean paused = false;
            if (frame != null) {
                try {
                    js.setTimeoutMs(0);
                    paused = true;
                    pausedAt = System.currentTimeMillis();
                } catch (Throwable ignored) {
                }
            }
            try {
                return toJs(ctx, b.call(m, ns, method, args.toJSONArray()));
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new QuickJSException("BridgeError", why);
            } finally {
                if (paused) {
                    long now = System.currentTimeMillis();
                    frame.blockedMs += Math.max(0, now - pausedAt);
                    long elapsed = now - frame.startMs - frame.blockedMs;
                    long remaining = Math.max(1, frame.budgetMs - elapsed);
                    try {
                        js.setTimeoutMs(remaining);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, method);
    }

    private static Object toJs(JSContext ctx, Object v) {
        if (v instanceof JSONObject) return new JSObject(ctx, (JSONObject) v);
        if (v instanceof JSONArray) return new JSArray(ctx, (JSONArray) v);
        if (v == null || v == JSONObject.NULL) return null;
        return v;
    }

    /** 工具返回值转文本：字符串原样，其余可序列化值转 JSON/字面量。 */
    private static String text(JSContext ctx, Object ret) {
        if (ret == null) return null;
        if (ret instanceof String) return (String) ret;
        if (ret instanceof Boolean || ret instanceof Number) return String.valueOf(ret);
        if (ret instanceof JSObject) {
            JSObject o = (JSObject) ret;
            if (isUndefined(o)) return null;
            return jsonText(ctx, o);
        }
        return String.valueOf(ret);
    }

    /** 视图节点只可能是对象：其他返回值一律当作"不接管"。 */
    private static String jsonText(JSContext ctx, Object ret) {
        if (!(ret instanceof JSObject)) return null;
        JSObject o = (JSObject) ret;
        if (isUndefined(o)) return null;
        Object json = get(ctx, "JSON");
        if (!(json instanceof JSObject)) return null;
        JSArray args = new JSArray(ctx);
        args.push(o);
        try {
            Object s = ((JSObject) json).executeFunction("stringify", args);
            if (!(s instanceof String)) return null;
            String text = (String) s;
            return "null".equals(text) ? null : text;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Decision parse(JSContext ctx, HookManifest m, String fnName, Object ret) {
        if (!(ret instanceof JSObject)) return Decision.allow();
        JSObject o = (JSObject) ret;
        if (isUndefined(o)) return Decision.allow();
        String action = str(o, "action");
        String reason = str(o, "reason");
        boolean userMessage = "onUserMessage".equals(fnName);
        boolean contextBuild = "onContextBuild".equals(fnName);
        boolean assistantMessage = "onAssistantMessage".equals(fnName);

        if ("veto".equals(action) || "deny".equals(action)) {
            // 用户消息用 hook.message；工具钩子沿用 v1/v2 的 veto/hook.tool；
            // contextBuild / assistantMessage 按契约不允许 veto。
            boolean allowed = userMessage ? m.can("hook.message")
                    : (!contextBuild && !assistantMessage && m.canVeto());
            if (!allowed) {
                return Decision.allow();          // 没声明能力：忽略，由 registry 记警告
            }
            return Decision.veto(reason.isEmpty() ? "被 " + m.id + " 拦截" : reason);
        }
        if ("rewrite".equals(action)) {
            if (userMessage) {
                if (!m.can("hook.message")) return Decision.allow();
                Object text = get(o, "text");
                return text instanceof String ? Decision.replaceText((String) text) : Decision.allow();
            }
            if (!m.canRewriteArgs()) return Decision.allow();
            String json = jsonOf(ctx, o, "args");
            return json == null ? Decision.allow() : Decision.rewrite(json);
        }
        if ("replace".equals(action)) {
            if (contextBuild) {
                if (!m.can("hook.context")) return Decision.allow();
                String json = jsonOf(ctx, o, "messages");
                return json == null ? Decision.allow() : Decision.replaceMessages(json);
            }
            if (assistantMessage) {
                if (!m.can("hook.message")) return Decision.allow();
                Object text = get(o, "text");
                return text instanceof String ? Decision.replaceText((String) text) : Decision.allow();
            }
            if (!m.canReplaceResult()) return Decision.allow();
            Object r = get(o, "result");
            if (r instanceof String) return Decision.replace((String) r);
            if (r instanceof Number || r instanceof Boolean) {
                return Decision.replace(String.valueOf(r));
            }
            return Decision.allow();          // 对象/undefined：不是可替换的文本
        }
        return Decision.allow();
    }

    private static boolean isUndefined(JSObject o) {
        try {
            return o.isUndefined();
        } catch (Throwable e) {
            return true;
        }
    }

    private static Object get(JSObject o, String key) {
        try {
            return o.get(key);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String str(JSObject o, String key) {
        Object v = get(o, key);
        if (v == null) return "";
        if (v instanceof JSObject && isUndefined((JSObject) v)) return "";
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    /**
     * 取一个字段的 JSON 文本。对象/数组交给 QuickJS 自己的 JSON.stringify ——
     * 手写遍历会漏掉 undefined、循环引用和嵌套转义这些边界。
     */
    private static String jsonOf(JSContext ctx, JSObject o, String key) {
        Object v = get(o, key);
        if (v == null) return null;
        if (v instanceof JSObject) {
            JSObject jo = (JSObject) v;
            if (isUndefined(jo)) return null;
            Object json = get(ctx, "JSON");
            if (!(json instanceof JSObject)) return null;
            JSArray args = new JSArray(ctx);
            args.push(jo);
            try {
                Object s = ((JSObject) json).executeFunction("stringify", args);
                return s instanceof String ? (String) s : null;
            } catch (Throwable e) {
                return null;
            }
        }
        if (v instanceof String) return JSONObject.quote((String) v);
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return null;
    }

    /** 允许上层在插件被删除时立刻回收。 */
    public void close(String id) {
        reset(id);
    }
}
