package com.dsh.android.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Parsed plugin manifest (files/plugins/<id>/manifest.json).
 *
 * Type "tool": sandbox script tools — each entry runs a script inside the proroot Ubuntu
 * with the call arguments as JSON in $DSH_TOOL_ARGS; stdout becomes the tool result.
 * Type "hook": Rhino JS hooks — main.js defines onBeforeSend / onBeforeToolCall /
 * onAfterToolResult and may veto or rewrite what flows through the agent loop. */
public final class PluginInfo {

    public static final int API_VERSION = 1;

    public final File dir;
    public final String id;
    public final String name;
    public final String version;
    public final String type;          // "tool" | "hook"
    public final String description;
    public final List<String> capabilities = new ArrayList<>();
    public final List<ToolSpec> tools = new ArrayList<>();
    public final List<String> hooks = new ArrayList<>();
    public final String entry;         // JS entry for hook plugins
    public final String error;         // non-null when the manifest is invalid

    public PluginInfo(File dir, JSONObject m) {
        this.dir = dir;
        String e = null;
        this.id = m.optString("id", dir.getName()).trim();
        this.name = m.optString("name", id);
        this.version = m.optString("version", "0.0.0");
        this.type = m.optString("type", "");
        this.description = m.optString("description", "");
        this.entry = m.optString("entry", "main.js");
        JSONArray caps = m.optJSONArray("capabilities");
        if (caps != null) for (int i = 0; i < caps.length(); i++) capabilities.add(caps.optString(i));
        JSONArray hs = m.optJSONArray("hooks");
        if (hs != null) for (int i = 0; i < hs.length(); i++) hooks.add(hs.optString(i));
        JSONArray ts = m.optJSONArray("tools");
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null) tools.add(new ToolSpec(t));
            }
        }
        if (id.isEmpty() || id.length() > 32 || !id.matches("[a-z0-9_]+")) {
            e = "invalid id (use [a-z0-9_], max 32)";
        } else if (!"tool".equals(type) && !"hook".equals(type)) {
            e = "type must be \"tool\" or \"hook\"";
        } else if (m.optInt("apiVersion", API_VERSION) != API_VERSION) {
            e = "unsupported apiVersion";
        } else if ("tool".equals(type) && tools.isEmpty()) {
            e = "tool plugin has no tools";
        } else if ("hook".equals(type) && hooks.isEmpty()) {
            e = "hook plugin declares no hooks";
        }
        this.error = e;
    }

    public boolean isTool() { return "tool".equals(type) && error == null; }

    public boolean isHook() { return "hook".equals(type) && error == null; }

    /** Globally unique tool name registered in the ToolRegistry. */
    public String toolRegistryName(ToolSpec t) { return id + "_" + t.name; }

    public static final class ToolSpec {
        public final String name;
        public final String description;
        public final String script;     // file inside the plugin dir
        public final JSONObject params; // JSON schema for "parameters"

        ToolSpec(JSONObject t) {
            this.name = t.optString("name", "").trim();
            this.description = t.optString("description", "");
            this.script = t.optString("script", "");
            this.params = t.optJSONObject("params");
        }
    }
}
