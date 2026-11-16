package com.dsh.android.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * capability（能力声明）的逐项用户开关。
 *
 * 与 grant 不同：capability 不走权限网关、不弹窗，只是插件页上的静态开关。
 * 默认值与 manifest 声明一致（声明即启用），用户可以逐项关掉；关掉后对应扩展点静默不生效。
 */
public final class PluginCaps {

    public static final String PREFS = "plugin_caps";
    private static volatile SharedPreferences prefs;

    public static void ensureContext(Context context) {
        if (prefs != null || context == null) return;
        synchronized (PluginCaps.class) {
            if (prefs == null) {
                prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    /** prefs 是否已初始化；未初始化时 {@link HookManifest#can} 按声明生效（纯 JVM/加载早期）。 */
    public static boolean isReady() {
        return prefs != null;
    }

    /** 未写过就按"声明即启用"处理；prefs 未初始化时 fail-closed（一律视为未启用）。 */
    public static boolean enabled(String pluginId, String capability) {
        SharedPreferences p = prefs;
        if (p == null || pluginId == null || capability == null) return false;
        return p.getBoolean(pluginId + "/" + capability, true);
    }

    public static void set(String pluginId, String capability, boolean on) {
        SharedPreferences p = prefs;
        if (p == null || pluginId == null || capability == null) return;
        p.edit().putBoolean(pluginId + "/" + capability, on).apply();
    }

    /** 恢复默认（全部已声明的 capability 重新启用）。 */
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
