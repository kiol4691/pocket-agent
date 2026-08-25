package com.dsh.android.plugin;

import android.content.Context;

import org.json.JSONObject;

/** Persists per-plugin state (enabled flag + capability grants) in shared_prefs/plugins. */
public final class PluginStore {

    private final android.content.SharedPreferences prefs;

    public PluginStore(Context context) {
        prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE);
    }

    public boolean enabled(PluginInfo p) {
        return prefs.getBoolean("en_" + p.id, false);
    }

    public void setEnabled(String id, boolean enabled) {
        prefs.edit().putBoolean("en_" + id, enabled).apply();
    }

    public boolean granted(String id, String capability) {
        return prefs.getBoolean("cap_" + id + "_" + capability, false);
    }

    public void setGranted(String id, String capability, boolean granted) {
        prefs.edit().putBoolean("cap_" + id + "_" + capability, granted).apply();
    }

    /** Export full state as JSON (for the manager UI / debugging). */
    public String dump() {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            try { o.put(e.getKey(), e.getValue()); } catch (Exception ignored) { }
        }
        return o.toString();
    }
}
