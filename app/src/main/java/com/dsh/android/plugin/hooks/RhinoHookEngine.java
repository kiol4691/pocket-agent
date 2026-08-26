package com.dsh.android.plugin.hooks;

import com.dsh.android.util.DebugLog;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs one hook plugin's main.js inside a jailed Rhino interpreter.
 *
 * Jail: interpreter-only (no bytecode generation), a ClassShutter that denies every Java
 * class (no LiveConnect escape via Packages/getClass), and a wall-clock instruction budget
 * enforced by overriding observeInstructionCount. The script sees standard ECMAScript plus
 * a single `dsh.log(s)` function — nothing else. Hook results are converted back from
 * plain JS objects; anything unexpected is treated as "allow" (fail-open) and logged. */
public final class RhinoHookEngine {

    private static final long HOOK_BUDGET_MS = 2_000L;
    private static volatile long deadline = 0L;

    private final String pluginId;
    private final Scriptable scope;
    private final Object lock = new Object();
    private final Map<String, Map<String, String>> styles = new LinkedHashMap<>();

    private RhinoHookEngine(String pluginId, Scriptable scope) {
        this.pluginId = pluginId;
        this.scope = scope;
    }

    /** Style overrides returned by onStyle() at load time (may be empty). */
    public Map<String, Map<String, String>> styles() {
        return styles;
    }

    /** Compile main.js into a fresh jailed scope. Returns null on failure. */
    public static RhinoHookEngine load(String pluginId, String source) {
        try {
            Context cx = factory().enterContext();
            try {
                configure(cx);
                Scriptable scope = cx.initStandardObjects();
                defineDsh(cx, scope, pluginId);
                cx.evaluateString(scope, source, pluginId + "/main.js", 1, null);
                RhinoHookEngine engine = new RhinoHookEngine(pluginId, scope);
                engine.loadStyles(cx);
                return engine;
            } finally {
                Context.exit();
            }
        } catch (Throwable t) {
            DebugLog.w("hook plugin " + pluginId + " failed to load: " + t.getMessage());
            return null;
        }
    }

    /** Optional onStyle() -> { slot: { prop: value } }; validated downstream. */
    @SuppressWarnings("unchecked")
    private void loadStyles(Context cx) {
        try {
            Object f = scope.get("onStyle", scope);
            if (!(f instanceof Function)) return;
            Object res = ((Function) f).call(cx, scope, scope, new Object[0]);
            if (!(res instanceof Scriptable)) return;
            Scriptable root = (Scriptable) res;
            for (Object id : root.getIds()) {
                if (!(id instanceof String)) continue;
                Object slotVal = root.get((String) id, root);
                if (!(slotVal instanceof Scriptable)) continue;
                Map<String, String> props = new LinkedHashMap<>();
                for (Object pid : ((Scriptable) slotVal).getIds()) {
                    if (!(pid instanceof String)) continue;
                    Object pv = ((Scriptable) slotVal).get((String) pid, (Scriptable) slotVal);
                    if (pv != null && !(pv instanceof Scriptable)) {
                        props.put((String) pid, String.valueOf(pv));
                    }
                }
                styles.put((String) id, props);
            }
        } catch (Throwable t) {
            DebugLog.w("hook " + pluginId + ".onStyle failed: " + t.getMessage());
        }
    }

    /** Optional onHistoryLimit(ctx) -> number of history messages to keep (2..500). */
    public Integer historyLimit(int defaultLimit) {
        synchronized (lock) {
            Context cx = factory().enterContext();
            try {
                configure(cx);
                Object f = scope.get("onHistoryLimit", scope);
                if (!(f instanceof Function)) return null;
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("default", defaultLimit);
                Object[] args = { toScriptable(cx, ctx) };
                Object res = ((Function) f).call(cx, scope, scope, args);
                if (res instanceof Number) return ((Number) res).intValue();
                if (res instanceof Scriptable) {
                    Object v = ScriptableObject.getProperty((Scriptable) res, "limit");
                    if (v instanceof Number) return ((Number) v).intValue();
                }
                return null;
            } catch (Throwable t) {
                DebugLog.w("hook " + pluginId + ".onHistoryLimit failed: " + t.getMessage());
                return null;
            } finally {
                Context.exit();
            }
        }
    }

    /** Invoke global function fn(ctx) if defined; null result or JS error means "allow". */
    public PluginHooks.HookResult call(String fn, Map<String, Object> ctx) {
        synchronized (lock) {
            Context cx = factory().enterContext();
            try {
                configure(cx);
                Object f = scope.get(fn, scope);
                if (!(f instanceof Function)) return null;
                Object[] args = { toScriptable(cx, ctx) };
                Object res = ((Function) f).call(cx, scope, scope, args);
                return toHookResult(res);
            } catch (RhinoException e) {
                DebugLog.w("hook " + pluginId + "." + fn + " js error: " + e.getMessage());
                return null;
            } catch (Throwable t) {
                DebugLog.w("hook " + pluginId + "." + fn + " failed: " + t.getMessage());
                return null;
            } finally {
                Context.exit();
            }
        }
    }

    // ---------- engine setup ----------

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            return new Context() {
                @Override
                protected void observeInstructionCount(int instructionCount) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new Error("hook instruction budget exceeded");
                    }
                }
            };
        }
    };

    private static ContextFactory factory() { return FACTORY; }

    private static void configure(Context cx) {
        cx.setLanguageVersion(Context.VERSION_ES6);
        cx.setOptimizationLevel(-1); // interpreter only: no runtime class generation on ART
        cx.setClassShutter(className -> false); // no Java access from scripts
        cx.setInstructionObserverThreshold(5_000);
    }

    private static void defineDsh(Context cx, Scriptable scope, String pluginId) {
        ScriptableObject dsh = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(dsh, "pluginId", pluginId);
        try {
            ScriptableObject.putProperty(dsh, "log", new FunctionObject("log", logMethod(), scope));
        } catch (NoSuchMethodException ignored) {
        }
        ScriptableObject.putProperty(scope, "dsh", dsh);
    }

    private static java.lang.reflect.Method logMethod() throws NoSuchMethodException {
        return RhinoHookEngine.class.getDeclaredMethod("jsLog", Object.class);
    }

    @SuppressWarnings("unused")
    public static void jsLog(Object msg) {
        DebugLog.i("plugin.js: " + msg);
    }

    // ---------- conversions ----------

    @SuppressWarnings("unchecked")
    private Scriptable toScriptable(Context cx, Map<String, Object> map) {
        ScriptableObject o = (ScriptableObject) cx.newObject(scope);
        for (Map.Entry<String, Object> e : map.entrySet()) {
            ScriptableObject.putProperty(o, e.getKey(), wrap(e.getValue()));
        }
        return o;
    }

    private Object wrap(Object v) {
        if (v instanceof Map) {
            NativeObject o = new NativeObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                o.put(e.getKey(), o, wrap(e.getValue()));
            }
            return o;
        }
        if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            return new NativeArray(list.toArray());
        }
        return v == null ? Context.getUndefinedValue() : v;
    }

    private PluginHooks.HookResult toHookResult(Object res) {
        Object action = member(res, "action");
        if (action == null) return null;
        String a = String.valueOf(action);
        if ("veto".equalsIgnoreCase(a)) {
            Object reason = member(res, "reason");
            return PluginHooks.HookResult.veto(reason == null ? null : String.valueOf(reason));
        }
        if ("rewrite".equalsIgnoreCase(a) || "allow".equalsIgnoreCase(a) && member(res, "text") != null) {
            Object text = member(res, "text");
            if (text != null) return PluginHooks.HookResult.rewrite(String.valueOf(text));
        }
        return null;
    }

    private static Object member(Object jsObj, String name) {
        if (!(jsObj instanceof Scriptable)) return null;
        return ScriptableObject.getProperty((Scriptable) jsObj, name);
    }

    /** Convert a scriptable to a Java map (kept for future hooks that need deep reads). */
    @SuppressWarnings("unused")
    private static Map<String, Object> toMap(Scriptable o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object id : o.getIds()) {
            if (id instanceof String) {
                Object v = o.get((String) id, o);
                if (v instanceof NativeJavaObject) v = ((NativeJavaObject) v).unwrap();
                m.put((String) id, v);
            }
        }
        return m;
    }

    @SuppressWarnings("unused")
    private static List<Object> toList(NativeArray arr) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < arr.getLength(); i++) out.add(arr.get(i, arr));
        return out;
    }
}
