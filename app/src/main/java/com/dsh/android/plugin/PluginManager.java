package com.dsh.android.plugin;

import android.content.Context;

import com.dsh.android.plugin.broker.PluginBroker;
import com.dsh.android.plugin.hooks.HookChain;
import com.dsh.android.plugin.hooks.PluginHooks;
import com.dsh.android.plugin.hooks.RhinoHookEngine;
import com.dsh.android.tools.ScriptTool;
import com.dsh.android.tools.Tool;
import com.dsh.android.tools.ToolRegistry;
import com.dsh.android.util.DebugLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Facade over the plugin system: scans plugins, persists state, starts the capability
 * broker, installs the `dsh` helper into the rootfs and exposes the hook chain. */
public final class PluginManager {

    private static final String DSH_SH =
            "# dsh - Pocket Agent plugin broker client (installed by the app)\n"
            + "dsh() {\n"
            + "  local port=\"${DSH_BROKER_PORT:-7901}\" cmd p b64 resp status data\n"
            + "  cmd=\"$1\"; shift\n"
            + "  case \"$cmd\" in notify|toast) ;; *) echo \"dsh: unknown capability '$cmd'\" >&2; return 2 ;; esac\n"
            + "  b64=$(printf '%s' \"$*\" | base64 | tr -d '\\n')\n"
            + "  if ! exec 3<>/dev/tcp/127.0.0.1/\"$port\"; then echo 'dsh: broker unreachable' >&2; return 1; fi\n"
            + "  printf '%s|%s|%s\\n' \"${DSH_PLUGIN_ID:-unknown}\" \"$cmd\" \"$b64\" >&3\n"
            + "  IFS= read -r -t 10 -u 3 resp || { echo 'dsh: broker timeout' >&2; exec 3<&- 3>&-; return 1; }\n"
            + "  exec 3<&- 3>&-\n"
            + "  status=\"${resp%%|*}\"; data=\"${resp#*|}\"\n"
            + "  if [ \"$status\" = \"OK\" ]; then printf '%s' \"$data\" | base64 -d 2>/dev/null; echo; return 0; fi\n"
            + "  printf '%s' \"$data\" | base64 -d 2>/dev/null >&2; echo >&2; return 1\n"
            + "}\n";

    private static volatile PluginManager instance;
    private static Context appContext;

    private final Context context;
    private final PluginStore store;
    private final List<PluginInfo> plugins = new ArrayList<>();
    private final List<RhinoHookEngine> hookEngines = new ArrayList<>();
    private final java.util.Map<String, String> hookErrors = new java.util.LinkedHashMap<>();

    public static PluginManager init(Context context) {
        appContext = context.getApplicationContext();
        if (instance == null) instance = new PluginManager(appContext);
        instance.reload();
        return instance;
    }

    public static PluginManager get() { return instance; }

    public static Context context() { return appContext; }

    private PluginManager(Context context) {
        this.context = context;
        this.store = new PluginStore(context);
    }

    /** Rescan plugins and (re)build hook engines. Safe to call repeatedly. */
    public synchronized void reload() {
        plugins.clear();
        hookEngines.clear();
        hookErrors.clear();
        List<PluginInfo> scanned = PluginScanner.scan(context);
        // Dedupe by manifest id: if the same id exists in more than one folder (typo'd
        // manual copies), the complete copy wins — a broken one must never shadow a
        // healthy tool with the same registered name.
        java.util.Map<String, PluginInfo> byId = new java.util.LinkedHashMap<>();
        for (PluginInfo p : scanned) {
            PluginInfo prev = byId.get(p.id);
            if (prev == null || (!complete(prev) && complete(p))) {
                byId.put(p.id, p);
            }
        }
        for (PluginInfo p : byId.values()) {
            plugins.add(p);
            if (p.isHook() && store.enabled(p)) {
                RhinoHookEngine engine = loadHookEngine(p);
                if (engine != null) {
                    hookEngines.add(engine);
                } else {
                    hookErrors.put(p.id, "hook engine failed to load — plugin is NOT active (see dsh.log)");
                }
            }
        }
        PluginBroker.ensure(context);
        ensureRootfsAssets();
    }

    /** Non-null when an enabled hook plugin failed to load its JS engine. */
    public synchronized String hookError(String pluginId) {
        return hookErrors.get(pluginId);
    }

    private static boolean complete(PluginInfo p) {
        if (p.error != null) return false;
        for (PluginInfo.ToolSpec t : p.tools) {
            if (!new File(p.dir, t.script).isFile()) return false;
        }
        return !"hook".equals(p.type) || new File(p.dir, p.entry).isFile();
    }

    public synchronized List<PluginInfo> plugins() {
        return new ArrayList<>(plugins);
    }

    public PluginStore store() { return store; }

    public boolean granted(String pluginId, String capability) {
        for (PluginInfo p : plugins) {
            if (p.id.equals(pluginId)) {
                return store.enabled(p) && p.capabilities.contains(capability)
                        && store.granted(pluginId, capability);
            }
        }
        return false;
    }

    /** Register every enabled tool plugin into a fresh per-turn ToolRegistry. */
    public synchronized void registerTools(ToolRegistry registry, com.dsh.android.runtime.ProrootRuntime runtime) {
        for (PluginInfo p : plugins) {
            if (!store.enabled(p) || !p.isTool()) continue;
            for (PluginInfo.ToolSpec t : p.tools) {
                Tool tool = new ScriptTool(runtime, p, t);
                registry.register(tool);
            }
        }
    }

    /** Hook chain over all enabled hook plugins (never null). */
    public synchronized PluginHooks hooks() {
        return new HookChain(hookEngines);
    }

    public synchronized void setEnabled(PluginInfo p, boolean enabled) {
        store.setEnabled(p.id, enabled);
        reload();
    }

    public synchronized void setGranted(PluginInfo p, String capability, boolean granted) {
        store.setGranted(p.id, capability, granted);
        reload();
    }

    private RhinoHookEngine loadHookEngine(PluginInfo p) {
        try {
            File f = new File(p.dir, p.entry);
            if (!f.isFile()) {
                DebugLog.w("hook plugin " + p.id + " entry missing: " + p.entry);
                return null;
            }
            String src = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            RhinoHookEngine e = RhinoHookEngine.load(p.id, src);
            if (e == null) DebugLog.w("hook plugin " + p.id + " disabled (load error)");
            return e;
        } catch (Exception e) {
            DebugLog.w("hook plugin " + p.id + " load failed: " + e.getMessage());
            return null;
        }
    }

    /** Write the dsh() helper into the rootfs (idempotent, cheap). */
    public void ensureRootfsAssets() {
        try {
            File rootfs = new File(context.getFilesDir(), "rootfs");
            if (!rootfs.isDirectory()) return; // runtime not set up yet
            File libDir = new File(rootfs, "usr/local/lib");
            if (!libDir.isDirectory() && !libDir.mkdirs()) return;
            File f = new File(libDir, "dsh.sh");
            byte[] cur = f.isFile() ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
            String body = new String(cur == null ? new byte[0] : cur, StandardCharsets.UTF_8);
            if (!DSH_SH.equals(body)) {
                java.nio.file.Files.write(f.toPath(), DSH_SH.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            DebugLog.w("dsh.sh install failed: " + e.getMessage());
        }
    }
}
