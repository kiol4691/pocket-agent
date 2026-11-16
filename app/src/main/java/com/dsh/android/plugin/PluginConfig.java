package com.dsh.android.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * 插件配置的持久化（manifest {@code config[]} 的用户值）。
 *
 * 与 grant 分开存：配置是普通设置，不参与来源哈希；插件升级后用户配置保留。
 * 值一律用字符串存，读取时按 {@link ConfigField#coerce(String)} 还原类型。
 */
public final class PluginConfig {

    public static final String PREFS = "plugin_config";
    private static volatile SharedPreferences prefs;

    public static void ensureContext(Context context) {
        if (prefs != null || context == null) return;
        synchronized (PluginConfig.class) {
            if (prefs == null) {
                prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    /** 读用户值；没改过就返回 manifest 默认值，再没有就用调用方给的 fallback。 */
    public static Object get(HookManifest m, String key, Object fallback) {
        if (m == null || key == null) return fallback;
        ConfigField f = m.configField(key);
        Object def = f != null ? f.defaultValue
                : (m.configDefaults.containsKey(key) ? m.configDefaults.get(key) : fallback);
        SharedPreferences p = prefs;
        if (p == null) return def;
        String raw = p.getString(m.id + "/" + key, null);
        if (raw == null) return def;
        return f == null ? raw : f.coerce(raw);
    }

    /** 写用户值；select 传 value、boolean 传 "true"/"false"、number 传数字字符串。 */
    public static void set(HookManifest m, String key, String raw) {
        SharedPreferences p = prefs;
        if (p == null || m == null || key == null || key.isEmpty()) return;
        p.edit().putString(m.id + "/" + key, raw == null ? "" : raw).apply();
    }

    /** 恢复该插件的全部配置（插件页"重置配置"用）。 */
    public static void clear(String pluginId) {
        SharedPreferences p = prefs;
        if (p == null || pluginId == null) return;
        Map<String, ?> all = p.getAll();
        SharedPreferences.Editor ed = p.edit();
        String prefix = pluginId + "/";
        for (String k : all.keySet()) {
            if (k.startsWith(prefix)) ed.remove(k);
        }
        ed.apply();
    }
}
