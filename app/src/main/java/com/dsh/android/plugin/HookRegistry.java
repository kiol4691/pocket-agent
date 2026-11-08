package com.dsh.android.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.dsh.android.util.DebugLog;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钩子插件的注册表：扫描 files/plugins/*、维护开关、串起调用链。
 *
 * 链的语义（刻意做成"最小惊讶"）：
 *  - {@code beforeToolCall}：按 priority 升序执行；**第一个否决就终止**；改写会传给下一个钩子。
 *  - {@code afterToolResult}：第一个 replace 生效后停止。
 *  - 任何钩子抛异常：按其 manifest 的 onError 处理（默认 deny），并把错误记到 {@link #lastError}
 *    给 UI 显示 —— v0.2.0 的教训是"失败只写日志"，用户以为开着。
 */
public final class HookRegistry {

    private final File dir;
    private final SharedPreferences prefs;
    private final HookEngine engine = new HookEngine();
    private final Map<String, HookManifest> manifests = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();

    public HookRegistry(Context context, File pluginsDir) {
        this.dir = pluginsDir;
        this.prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE);
        reload();
    }

    /** 重新扫描目录（安装/删除插件后调用）。 */
    public synchronized void reload() {
        manifests.clear();
        errors.clear();
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
    }

    public synchronized List<HookManifest> list() {
        return new ArrayList<>(manifests.values());
    }

    /** 加载失败但目录还在的插件（id -> 原因），UI 用红字显示。 */
    public synchronized Map<String, String> broken() {
        return new LinkedHashMap<>(errors);
    }

    public boolean enabled(String id) {
        return manifests.containsKey(id) && prefs.getBoolean("en_" + id, false);
    }

    public String lastError(String id) {
        return errors.get(id);
    }

    /**
     * 打开开关前先跑一次自检：钩子失败就不许打开。
     * 这条是 v0.2.0 issue #2 的直接教训 —— "启用"必须意味着"能跑"。
     */
    public synchronized String enable(HookManifest m) {
        try {
            engine.invoke(m, "onBeforeToolCall", sampleContext("beforeToolCall"));
            engine.invoke(m, "onAfterToolResult", sampleContext("afterToolResult"));
            prefs.edit().putBoolean("en_" + m.id, true).apply();
            errors.remove(m.id);
            return null;
        } catch (Throwable e) {
            // Throwable 不是洁癖：Rhino 会把脚本里的 Error 原样抛出来（一次真机闪退就是这么来的）。
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            errors.put(m.id, why);
            prefs.edit().putBoolean("en_" + m.id, false).apply();
            DebugLog.w("plugin " + m.id + " self-test failed: " + why);
            return why;
        }
    }

    public void disable(String id) {
        prefs.edit().putBoolean("en_" + id, false).apply();
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
                errors.remove(m.id);
            } catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                errors.put(m.id, why);
                DebugLog.w("hook " + m.id + ".onBeforeToolCall failed: " + why);
                if (m.failClosed()) {
                    return HookEngine.Decision.veto("插件 " + m.id + " 执行失败（已按 fail-closed 拦下）：" + why);
                }
                continue;
            }
            if (d == null) continue;
            if (!d.allow) return d;
            if (d.args != null) {
                if (m.can("rewrite")) {
                    current = d.args;
                } else {
                    DebugLog.w("hook " + m.id + " 改写了参数但没声明 rewrite 能力，忽略");
                }
            }
        }
        return current.equals(argsJson) ? HookEngine.Decision.allow()
                : HookEngine.Decision.rewrite(current);
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
                if (d != null && d.result != null && m.can("replace")) current = d.result;
                errors.remove(m.id);
            } catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                errors.put(m.id, why);
                DebugLog.w("hook " + m.id + ".onAfterToolResult failed: " + why);
                // 结果改写失败不阻断工具（工具已经跑完了），但错误要留痕给 UI
            }
        }
        return current;
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
