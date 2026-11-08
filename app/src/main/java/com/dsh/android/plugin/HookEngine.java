package com.dsh.android.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type B 钩子的执行引擎（Rhino，纯解释、无 Java 访问）。
 *
 * 与 v0.2.0 的区别，逐条对应它踩过的坑：
 *
 *  1. **双层预算**：墙钟 {@code budgetMs} + 指令数 {@code budgetSteps}，两者都在
 *     {@code observeInstructionCount} 里检查。只靠指令数时，正则回溯这种"指令不多但很慢"
 *     的代码会拖满整轮对话；只靠墙钟时，死循环得靠中断，Rhino 里不可靠。
 *  2. **解释执行**：{@code optimizationLevel=-1}。开了字节码优化，指令回调就不再按
 *     预期触发，预算形同虚设。
 *  3. **每次调用独立作用域**：不缓存 scope，插件之间、调用之间没有状态泄漏；
 *     只缓存**编译结果**（按 path+mtime），省掉每次几十毫秒的编译。
 *  4. **越狱**：ClassShutter 一律拒绝；{@code initSafeStandardObjects} 本身没有
 *     Packages/JavaAdapter；再加一层 "load/print 之类的宿主入口我们根本不放"。
 *  5. **返回值结构化**：只认 {@code {action, reason, args, result}}，不是对象就当放行，
 *     不做任何"猜意图"的转换。
 */
public final class HookEngine {

    /** 一次钩子调用的结论。 */
    public static final class Decision {
        public final boolean allow;
        public final String args;      // 改写后的参数 JSON；null = 不改
        public final String result;    // 替换后的工具结果；null = 不改
        public final String reason;

        private Decision(boolean allow, String args, String result, String reason) {
            this.allow = allow;
            this.args = args;
            this.result = result;
            this.reason = reason;
        }

        static Decision allow() {
            return new Decision(true, null, null, "");
        }

        static Decision veto(String reason) {
            return new Decision(false, null, null, reason);
        }

        static Decision rewrite(String args) {
            return new Decision(true, args, null, "");
        }

        static Decision replace(String result) {
            return new Decision(true, null, result, "");
        }
    }

    /**
     * 预算超限。**必须是 RuntimeException，不能是 Error**：
     * Rhino 会把它原样抛出解释器，而 java.lang.Error 不属于 Exception，
     * 上层 catch(Exception) 接不住 —— 真机上就是整进程 FATAL EXCEPTION（闪退）。
     */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String msg) {
            super(msg);
        }
    }

    /**
     * 单次调用的预算状态，挂在 Context 的 thread-local 上。
     *
     * 计时从 {@link #arm()} 才开始：初始化标准对象 + 编译脚本这段是引擎自己的固定开销
     * （真机上几十到几百毫秒），不该算进插件的预算里 —— 否则一个 300 ms 预算的钩子
     * 还没跑到自己的第一行就被判超时。
     */
    private static final class Budget {
        final int maxMs;
        final int maxSteps;
        long steps;
        long start;

        Budget(int maxMs, int maxSteps) {
            this.maxMs = maxMs;
            this.maxSteps = maxSteps;
        }

        void arm() {
            start = System.currentTimeMillis();
        }
    }

    private static final String BUDGET_KEY = "dsh.hook.budget";

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object o = cx.getThreadLocal(BUDGET_KEY);
            if (!(o instanceof Budget)) return;
            Budget b = (Budget) o;
            b.steps += instructionCount;
            if (b.steps > b.maxSteps) {
                throw new BudgetExceeded("hook 指令预算超限（" + b.steps + " 步）");
            }
            if (b.start == 0) return;
            long ms = System.currentTimeMillis() - b.start;
            if (ms > b.maxMs) throw new BudgetExceeded("hook 墙钟预算超限（" + ms + " ms）");
        }
    };

    private static final ClassShutter DENY_ALL = className -> false;

    private static final class Cached {
        final long mtime;
        final Script script;

        Cached(long mtime, Script script) {
            this.mtime = mtime;
            this.script = script;
        }
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 跑一个钩子。返回 null 表示插件没实现这个钩子；抛异常表示钩子失败（由调用方按
     * manifest 的 onError 决定 fail-closed 还是跳过）。
     */
    public Decision invoke(HookManifest m, String hookName, JSONObject ctxJson) throws Exception {
        Context cx = FACTORY.enterContext();
        try {
            cx.setOptimizationLevel(-1);          // 解释执行：预算回调才会按预期触发
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(2_000);
            cx.setClassShutter(DENY_ALL);
            Budget budget = new Budget(m.budgetMs, m.budgetSteps);
            cx.putThreadLocal(BUDGET_KEY, budget);

            // 引擎开销（建标准对象 + 编译）不计时，见 Budget 的注释。
            Scriptable scope = cx.initSafeStandardObjects(null, false);
            script(m).exec(cx, scope);

            Object fn = scope.get(hookName, scope);
            if (!(fn instanceof Function)) return null;

            Object ctx = toJs(cx, scope, ctxJson);
            budget.arm();
            Object ret = ((Function) fn).call(cx, scope, scope, new Object[]{ctx});
            return parse(cx, scope, m, ret);
        } finally {
            cx.removeThreadLocal(BUDGET_KEY);
            Context.exit();
        }
    }

    /** 编译缓存：源码或 mtime 变了才重新编译。 */
    private Script script(HookManifest m) throws Exception {
        File f = new File(m.dir, m.entry);
        String key = f.getAbsolutePath();
        long mtime = f.lastModified();
        Cached c = cache.get(key);
        if (c != null && c.mtime == mtime) return c.script;

        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Context cx = FACTORY.enterContext();
        try {
            cx.setOptimizationLevel(-1);
            Script s = cx.compileString(src, m.entry, 1, null);
            cache.put(key, new Cached(mtime, s));
            return s;
        } finally {
            Context.exit();
        }
    }

    private static Decision parse(Context cx, Scriptable scope, HookManifest m, Object ret) {
        if (!(ret instanceof Scriptable) || ret == Undefined.instance) return Decision.allow();
        Scriptable o = (Scriptable) ret;
        String action = str(o, "action");
        String reason = str(o, "reason");

        if ("veto".equals(action) || "deny".equals(action)) {
            if (!m.can("veto")) {
                return Decision.allow();          // 没声明 veto 能力：忽略，由 registry 记警告
            }
            return Decision.veto(reason.isEmpty() ? "被 " + m.id + " 拦截" : reason);
        }
        if ("rewrite".equals(action)) {
            if (!m.can("rewrite")) return Decision.allow();
            Object a = ScriptableObject.getProperty(o, "args");
            String json = jsonOf(cx, scope, a);
            return json == null ? Decision.allow() : Decision.rewrite(json);
        }
        if ("replace".equals(action)) {
            if (!m.can("replace")) return Decision.allow();
            Object r = ScriptableObject.getProperty(o, "result");
            return r == null || r == Undefined.instance
                    ? Decision.allow() : Decision.replace(Context.toString(r));
        }
        return Decision.allow();
    }

    private static String str(Scriptable o, String key) {
        Object v = ScriptableObject.getProperty(o, key);
        if (v == null || v == Undefined.instance) return "";
        return Context.toString(v);
    }

    private static String jsonOf(Context cx, Scriptable scope, Object v) {
        if (v == null || v == Undefined.instance) return null;
        Object s = NativeJSON.stringify(cx, scope, v, null, null);
        return s == null ? null : Context.toString(s);
    }

    /** org.json -> JS 值。只处理 JSON 能表达的类型，避免把 Java 对象漏进沙箱。 */
    private static Object toJs(Context cx, Scriptable scope, Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Scriptable obj = cx.newObject(scope);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object child = toJs(cx, scope, o.opt(k));
                ScriptableObject.putProperty(obj, k, child);
            }
            return obj;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            Object[] items = new Object[a.length()];
            for (int i = 0; i < a.length(); i++) items[i] = toJs(cx, scope, a.opt(i));
            return cx.newArray(scope, items);
        }
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Context.toString(v);
    }
}
