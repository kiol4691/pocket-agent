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

    private RhinoHookEngine(String pluginId, Scriptable scope) {
        this.pluginId = pluginId;
        this.scope = scope;
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
                return new RhinoHookEngine(pluginId, scope);
            } finally {
                Context.exit();
            }
        } catch (Throwable t) {
            DebugLog.w("hook plugin " + pluginId + " failed to load: " + t.getMessage());
            return null;
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
