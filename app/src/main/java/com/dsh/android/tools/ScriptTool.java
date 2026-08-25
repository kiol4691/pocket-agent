package com.dsh.android.tools;

import com.dsh.android.plugin.PluginInfo;
import com.dsh.android.plugin.PluginManager;
import com.dsh.android.plugin.broker.PluginBroker;
import com.dsh.android.runtime.ProrootRuntime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** A Type-A plugin tool: runs the plugin's script inside the proroot Ubuntu sandbox with
 * the call arguments as JSON in $DSH_TOOL_ARGS (plus DSH_PLUGIN_ID and the broker port);
 * the script's stdout becomes the tool result. The `dsh` shell function (BASH_ENV) exposes
 * user-granted host capabilities such as notify/toast. */
public final class ScriptTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private final ProrootRuntime runtime;
    private final PluginInfo plugin;
    private final PluginInfo.ToolSpec spec;

    public ScriptTool(ProrootRuntime runtime, PluginInfo plugin, PluginInfo.ToolSpec spec) {
        this.runtime = runtime;
        this.plugin = plugin;
        this.spec = spec;
    }

    @Override
    public String name() {
        return plugin.toolRegistryName(spec);
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        if (spec.params != null) {
            params = new JSONObject(spec.params.toString());
        } else {
            params.put("type", "object");
            params.put("properties", new JSONObject());
        }
        params.put("required", params.optJSONArray("required") != null
                ? params.optJSONArray("required") : new JSONArray());
        return Tool.function(name(), spec.description == null || spec.description.isEmpty()
                ? "Plugin tool " + spec.name : spec.description, params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        PluginManager pm = PluginManager.get();
        if (pm != null) pm.ensureRootfsAssets();
        File script = new File(plugin.dir, spec.script);
        if (!script.isFile()) {
            // self-heal: an earlier extraction may have been partial (OEM AssetManager
            // quirks) — re-run the per-file extractor once and re-check.
            if (pm != null) {
                com.dsh.android.plugin.PluginScanner.extractSamples(pm.context());
                pm.ensureRootfsAssets();
            }
        }
        if (!script.isFile()) return "Error: plugin script missing: " + spec.script
                + " (extraction failed; reinstall the plugin folder)";
        // The guest cannot see host paths — the plugins dir is bind-mounted at /pluginshost.
        String guestScript = "/pluginshost/" + plugin.id + "/" + spec.script;
        String cmd = "bash '" + guestScript + "'";
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("DSH_PLUGIN_ID", plugin.id);
        env.put("DSH_TOOL_ARGS", argsJson == null ? "{}" : argsJson);
        int port = PluginBroker.port();
        env.put("DSH_BROKER_PORT", String.valueOf(port > 0 ? port : 7901));
        return runtime.runBashEnv(cmd, DEFAULT_TIMEOUT_MS, env);
    }
}
