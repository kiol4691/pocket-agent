package com.dsh.android.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.dsh.android.R;
import com.dsh.android.util.DebugLog;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部资源授权（grant）网关。
 *
 * 两层权限里的第二层：manifest 的 {@code permissions} 只是"声明意图"，
 * 真正生效靠这里的运行时决定（仅本次 / 始终 / 拒绝）。capability 不走这里。
 *
 * 授权绑定插件身份：对 manifest + 入口脚本算 SHA-256，内容变化即视为新插件，
 * 旧 grant 全部作废，防止"覆盖已装插件目录继承授权"。
 */
public final class PluginGrants {

    public static final String PREFS = "plugin_grants";
    private static final String KEY_DECISION = "g_";
    private static final String KEY_HASH = "h_";
    /** 用户显式"信任此插件"后，危险工具的每次确认才允许被 manifest 的 confirm:false 跳过。 */
    private static final String KEY_TRUST = "t_";

    private volatile SharedPreferences prefs;
    /** 只用于把授权弹窗的 label/scope/risk 本地化；持有 application context。 */
    private volatile Context context;
    /** "仅本次"只活在进程内。 */
    private final Map<String, String> once = new ConcurrentHashMap<>();
    private final Map<String, HashCache> hashes = new ConcurrentHashMap<>();

    private static final class HashCache {
        final long manifestMtime;
        final long entryMtime;
        final String hash;

        HashCache(long manifestMtime, long entryMtime, String hash) {
            this.manifestMtime = manifestMtime;
            this.entryMtime = entryMtime;
            this.hash = hash;
        }
    }

    public void ensureContext(Context context) {
        if (prefs != null) return;
        synchronized (this) {
            if (prefs == null) {
                this.context = context.getApplicationContext();
                prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    /** status(): granted / denied / notAsked / needsSettings / unsupported。 */
    public String status(HookManifest m, String capability, PluginHost host) {
        if (m == null || capability == null) return "unsupported";
        ensureIdentity(m);
        if (!m.declaresGrant(capability) || !HookManifest.isGrant(capability)) return "unsupported";
        if (isSpecial(capability)) {
            // 特殊权限分两层：系统总开关 + 本插件的单独确认。系统没开时只能去设置页；
            // 系统开了但插件还没确认过，仍要按插件身份走"仅本次/始终/拒绝"。
            boolean system = host != null && host.specialGranted(capability);
            String d = decision(m.id, capability);
            if ("deny".equals(d)) return "denied";
            if (!system) return "needsSettings";
            if (once.containsKey(key(m.id, capability))) return "granted";
            return "always".equals(d) ? "granted" : "notAsked";
        }
        if (capability.startsWith("android.permission.")) {
            return host == null ? "unsupported" : host.androidPermissionStatus(capability);
        }
        if (once.containsKey(key(m.id, capability))) return "granted";
        String d = decision(m.id, capability);
        if ("always".equals(d)) return "granted";
        if ("deny".equals(d)) return "denied";
        return "notAsked";
    }

    public boolean isGranted(HookManifest m, String capability, PluginHost host) {
        return "granted".equals(status(m, capability, host));
    }

    /**
     * 自动代理：首次调用时等价于 request。未声明 / 已拒绝 / 特殊权限未开时抛 E_PERM。
     * 选项 {@code auto:false} 时只做检查，不弹窗。
     */
    public boolean ensure(HookManifest m, String capability, JSONObject opts, PluginHost host)
            throws Exception {
        if (m == null || !m.declaresGrant(capability)) {
            throw new IllegalArgumentException("E_PERM: 插件未声明 " + capability);
        }
        ensureIdentity(m);
        String status = status(m, capability, host);
        if ("granted".equals(status)) return true;
        if ("needsSettings".equals(status)) {
            // 特殊权限没有运行时弹窗：跳到系统设置页，用户回来后重新 status()。
            if (host != null) host.openSpecialSettings(capability);
            throw new IllegalArgumentException("E_PERM: " + capability + " 需要在系统设置中授权");
        }
        boolean auto = opts == null || opts.optBoolean("auto", true);
        boolean runtimePermission = capability.startsWith("android.permission.");
        if (!auto || ("denied".equals(status) && !runtimePermission)) {
            throw new IllegalArgumentException("E_PERM: 未授权 " + capability);
        }
        String reason = opts == null ? "" : opts.optString("reason", "");
        long timeout = opts == null ? 60_000L : opts.optLong("timeoutMs", 60_000L);
        if (!request(m, capability, reason, timeout, host)) {
            throw new IllegalArgumentException("E_PERM: 未授权 " + capability);
        }
        return true;
    }

    public boolean request(HookManifest m, String capability, String reason,
                           long timeoutMs, PluginHost host) throws Exception {
        if (m == null || !m.declaresGrant(capability) || !HookManifest.isGrant(capability)) {
            throw new IllegalArgumentException("E_BRIDGE: unsupported " + capability);
        }
        ensureIdentity(m);
        if (isSpecial(capability)) {
            if (host == null) throw new IllegalArgumentException("E_BRIDGE: 宿主不可用");
            if (!host.specialGranted(capability)) {
                // 系统特殊权限没有运行时弹窗：跳设置页，用户回来后重新 status()。
                boolean opened = host.openSpecialSettings(capability);
                DebugLog.i("grant " + m.id + " " + capability + " -> settings(" + opened + ")");
                return false;
            }
            // 系统权限已就绪：继续走下面的插件级三选一确认。
        }
        if (capability.startsWith("android.permission.")) {
            String r = host == null ? "denied"
                    : host.requestAndroidPermission(capability, clampTimeout(timeoutMs));
            boolean ok = "granted".equals(r);
            audit(m.id, capability, ok ? "always" : r);
            return ok;
        }
        if (host == null) throw new IllegalArgumentException("E_BRIDGE: 宿主不可用");

        JSONObject d = describe(m, capability, host);
        String answer = host.promptGrant(m, capability,
                d.optString("label", capability), d.optString("scope", ""),
                reason == null ? "" : reason, clampTimeout(timeoutMs));
        if (answer == null) throw new IllegalArgumentException("E_BUDGET: 授权请求超时");
        if ("once".equals(answer)) {
            once.put(key(m.id, capability), "1");
            audit(m.id, capability, "once");
            return true;
        }
        if ("always".equals(answer)) {
            prefs().edit().putString(KEY_DECISION + key(m.id, capability), "always").apply();
            audit(m.id, capability, "always");
            return true;
        }
        prefs().edit().putString(KEY_DECISION + key(m.id, capability), "deny").apply();
        audit(m.id, capability, "deny");
        return false;
    }

    /** permission.describe 的内容；也用于弹窗文案。 */
    public JSONObject describe(HookManifest m, String capability, PluginHost host) {
        JSONObject o = new JSONObject();
        try {
            o.put("capability", capability);
            o.put("label", label(capability));
            o.put("scope", scope(m, capability));
            o.put("risk", risk(capability));
            o.put("granted", isGranted(m, capability, host));
            o.put("status", status(m, capability, host));
            o.put("declared", m != null && m.declaresGrant(capability));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 一个插件当前声明的全部 grant 及状态（插件页/调试用）。 */
    public JSONObject snapshot(HookManifest m, PluginHost host) {
        JSONObject out = new JSONObject();
        if (m == null) return out;
        List<String> declared = declaredGrants(m);
        for (String cap : declared) {
            try {
                out.put(cap, describe(m, cap, host));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<String> declaredGrants(HookManifest m) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        for (String p : m.permissions) {
            if (HookManifest.isGrant(p)) out.add(p);
        }
        for (String p : m.androidPermissions) {
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    public void clear(String pluginId) {
        if (pluginId == null) return;
        SharedPreferences p = prefs();
        Map<String, ?> all = p.getAll();
        SharedPreferences.Editor ed = p.edit();
        String prefix = KEY_DECISION + pluginId + "/";
        for (String k : all.keySet()) {
            if (k.startsWith(prefix)) ed.remove(k);
        }
        ed.remove(KEY_HASH + pluginId);
        ed.remove(KEY_TRUST + pluginId);
        ed.apply();
        once.keySet().removeIf(k -> k.startsWith(pluginId + "/"));
        hashes.remove(pluginId);
        audit(pluginId, "*", "clear");
    }

    public void clear(String pluginId, String capability) {
        if (pluginId == null || capability == null) return;
        prefs().edit().remove(KEY_DECISION + key(pluginId, capability)).apply();
        once.remove(key(pluginId, capability));
        audit(pluginId, capability, "revoke");
    }

    /** 调试通道：直接落一条决定（always / deny），等价于用户点弹窗。 */
    public void setDecisionForDebug(String pluginId, String capability, String decision) {
        if (pluginId == null || capability == null) return;
        String d = "always".equals(decision) || "deny".equals(decision) ? decision : "deny";
        prefs().edit().putString(KEY_DECISION + key(pluginId, capability), d).apply();
        once.remove(key(pluginId, capability));
        audit(pluginId, capability, "debug-" + d);
    }

    /** 用户是否显式信任该插件（危险工具免逐次确认）。默认不信任。 */
    public boolean isTrusted(String pluginId) {
        return pluginId != null && prefs().getBoolean(KEY_TRUST + pluginId, false);
    }

    /**
     * 带 manifest 的信任查询：先校验来源哈希，内容变过会自动作废旧信任。
     * 拿不到 manifest 时按不信任处理。
     */
    public boolean isTrusted(HookManifest m) {
        if (m == null) return false;
        ensureIdentity(m);
        return prefs().getBoolean(KEY_TRUST + m.id, false);
    }

    public void setTrusted(String pluginId, boolean trusted) {
        if (pluginId == null) return;
        prefs().edit().putBoolean(KEY_TRUST + pluginId, trusted).apply();
        audit(pluginId, "*", trusted ? "trusted" : "untrusted");
    }

    /** 消费一次「仅本次」授权：下一次调用会重新询问。 */
    public void consumeOnce(HookManifest m, String capability) {
        if (m == null || capability == null) return;
        once.remove(key(m.id, capability));
    }

    /**
     * 原子消费「仅本次」：返回 true 表示本次调用拿到了令牌。
     * 并发下只有一个线程能从 remove 里拿到值，避免 status+consume 双放行。
     * 先校验来源哈希，内容变化后旧的 once 令牌不能再用。
     */
    public boolean takeOnce(HookManifest m, String capability) {
        if (m == null || capability == null) return false;
        ensureIdentity(m);
        return once.remove(key(m.id, capability)) != null;
    }

    // ---------------------------------------------------------------- 内部

    private boolean isSpecial(String capability) {
        return "fs.shared".equals(capability) || "overlay.system".equals(capability);
    }

    private String decision(String pluginId, String capability) {
        return prefs().getString(KEY_DECISION + key(pluginId, capability), "");
    }

    /** 来源哈希：内容变化 => 清空该插件全部 grant。 */
    private void ensureIdentity(HookManifest m) {
        String hash = sourceHash(m);
        String stored = prefs().getString(KEY_HASH + m.id, "");
        if (!hash.equals(stored)) {
            if (!stored.isEmpty()) {
                // 复用 clear，但别把刚写的 hash 也清掉。
                clearDecisions(m.id);
                audit(m.id, "*", "hash-changed");
            }
            prefs().edit().putString(KEY_HASH + m.id, hash).apply();
        }
    }

    private void clearDecisions(String pluginId) {
        SharedPreferences p = prefs();
        SharedPreferences.Editor ed = p.edit();
        String prefix = KEY_DECISION + pluginId + "/";
        for (String k : p.getAll().keySet()) {
            if (k.startsWith(prefix)) ed.remove(k);
        }
        // 来源变化说明插件内容已被替换，用户对旧内容的"信任"也必须作废。
        ed.remove(KEY_TRUST + pluginId);
        ed.apply();
        once.keySet().removeIf(k -> k.startsWith(pluginId + "/"));
    }

    private String sourceHash(HookManifest m) {
        File manifest = new File(m.dir, "manifest.json");
        File entry = new File(m.dir, m.entry);
        long mm = manifest.lastModified();
        long em = entry.lastModified();
        HashCache c = hashes.get(m.id);
        if (c != null && c.manifestMtime == mm && c.entryMtime == em) return c.hash;
        String hash = fingerprint(m);
        hashes.put(m.id, new HashCache(mm, em, hash));
        return hash;
    }

    /** manifest + 入口脚本的稳定指纹；主题皮肤确认等 UI 侧也用它判断“内容是否变过”。 */
    public static String fingerprint(HookManifest m) {
        if (m == null || m.dir == null) return "unavailable";
        File manifest = new File(m.dir, "manifest.json");
        File entry = new File(m.dir, m.entry);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(manifest.toPath()));
            if (entry.isFile()) md.update(Files.readAllBytes(entry.toPath()));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private SharedPreferences prefs() {
        SharedPreferences p = prefs;
        if (p == null) throw new IllegalStateException("E_BRIDGE: grants 未初始化");
        return p;
    }

    private static String key(String pluginId, String capability) {
        return pluginId + "/" + capability;
    }

    private static long clampTimeout(long ms) {
        return Math.max(1_000L, Math.min(ms, 60_000L));
    }

    private void audit(String pluginId, String capability, String decision) {
        try {
            DebugLog.i("grant " + pluginId + " " + capability + " -> " + decision);
        } catch (Throwable ignored) {
        }
    }

    private String res(int id, String fallback) {
        return context == null ? fallback : context.getString(id);
    }

    private String label(String cap) {
        if (cap == null) return "";
        switch (cap) {
            case "net.http": return res(R.string.grant_label_net_http, "Network access");
            case "fs.workspace": return res(R.string.grant_label_fs_workspace, "Read/write workspace");
            case "fs.shared": return res(R.string.grant_label_fs_shared, "Read/write shared storage");
            case "exec.guest": return res(R.string.grant_label_exec_guest, "Run commands");
            case "tool.invoke": return res(R.string.grant_label_tool_invoke, "Call host tools");
            case "bridge.adb": return res(R.string.grant_label_adb, "ADB / Shizuku");
            case "env.time": return res(R.string.grant_label_env_time, "Read system time");
            case "clipboard": return res(R.string.grant_label_clipboard, "Read/write clipboard");
            case "notify": return res(R.string.grant_label_notify, "Send notifications");
            case "overlay.system": return res(R.string.grant_label_overlay, "System overlay");
            case "location": return res(R.string.grant_label_location, "Read location");
            default:
                if (cap.startsWith("android.permission.")) return cap.substring(19);
                return cap;
        }
    }

    private String scope(HookManifest m, String cap) {
        if (cap == null) return "";
        switch (cap) {
            case "net.http":
                return m == null || m.netAllow.isEmpty()
                        ? res(R.string.grant_scope_net_empty, "No domains declared (net.allow is empty)")
                        : String.join(", ", m.netAllow);
            case "fs.workspace":
                return "/root/workspace";
            case "fs.shared":
                return res(R.string.grant_scope_shared, "/sdcard (requires All files access)");
            case "exec.guest":
                return res(R.string.grant_scope_exec, "Debian guest persistent shell");
            case "tool.invoke":
                return m == null || m.toolAllow.isEmpty()
                        ? res(R.string.grant_scope_tools_empty, "No tools declared (toolAccess.allow is empty)")
                        : String.join(", ", m.toolAllow);
            case "bridge.adb":
                return res(R.string.grant_scope_adb, "Device shell (Shizuku; authorize on the plugin page)");
            case "env.time":
                return res(R.string.grant_scope_time, "Time and time zone");
            case "clipboard":
                return res(R.string.grant_scope_clipboard, "Clipboard text");
            case "notify":
                return res(R.string.grant_scope_notify, "System notifications");
            case "overlay.system":
                return res(R.string.grant_scope_overlay, "Above other apps (special permission)");
            case "location":
                return res(R.string.grant_scope_location, "GPS / network location (system permission required)");
            default:
                return cap == null ? "" : cap;
        }
    }

    private String risk(String cap) {
        if (cap == null) return res(R.string.grant_risk_low, "Low");
        switch (cap) {
            case "overlay.system": return res(R.string.grant_risk_extreme, "Extreme");
            case "net.http":
            case "fs.shared":
            case "exec.guest":
            case "tool.invoke":
            case "bridge.adb":
            case "location": return res(R.string.grant_risk_high, "High");
            case "clipboard":
            case "env.time":
            case "fs.workspace": return res(R.string.grant_risk_medium, "Medium");
            case "notify": return res(R.string.grant_risk_low, "Low");
            default: return cap.startsWith("android.permission.")
                    ? res(R.string.grant_risk_permission, "Permission-dependent")
                    : res(R.string.grant_risk_low, "Low");
        }
    }
}
