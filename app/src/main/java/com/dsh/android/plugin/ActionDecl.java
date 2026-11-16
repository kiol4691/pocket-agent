package com.dsh.android.plugin;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 一条全局动作声明（manifest 的 {@code contributes.actions[]}）。
 *
 * 动作总线把「主题画的按钮」和「插件提供的能力」解耦：节点只写 {@code action: "action:svc.restart"}，
 * 具体执行由当前优先级最高的提供方插件的 {@code onAction(ctx)} 完成。
 */
public final class ActionDecl {

    public static final String PROVIDER_FN = "onAction";
    public static final int DEFAULT_TIMEOUT_MS = 5_000;

    public final String pluginId;
    public final String id;
    public final String label;
    /** 参数 JSON Schema；调用前做最小校验。 */
    public final JSONObject params;
    /** 可选确认文案，支持 {{参数名}} 插值。 */
    public final String confirm;
    public final int timeoutMs;
    /** 同一动作多个提供方时的优先级：小者胜（与其它贡献类型方向一致）。 */
    public final int priority;

    private ActionDecl(String pluginId, String id, String label, JSONObject params,
                       String confirm, int timeoutMs, int priority) {
        this.pluginId = pluginId;
        this.id = id;
        this.label = label;
        this.params = params;
        this.confirm = confirm;
        this.timeoutMs = timeoutMs;
        this.priority = priority;
    }

    static ActionDecl fromJson(String pluginId, JSONObject o) throws IOException {
        String id = o.optString("id", "").trim();
        String bad = validateId(id);
        if (bad != null) throw new IOException(bad);

        String label = o.optString("label", "").trim();
        if (label.isEmpty()) throw new IOException("动作 " + id + " 缺少 label");
        if (label.length() > 64) throw new IOException("动作 " + id + " 的 label 不能超过 64 字");

        int timeout = o.has("timeoutMs")
                ? clampTimeout(o.optInt("timeoutMs", DEFAULT_TIMEOUT_MS)) : DEFAULT_TIMEOUT_MS;
        return new ActionDecl(pluginId, id, label, o.optJSONObject("params"),
                o.optString("confirm", ""), timeout,
                HookManifest.clampPriority(o.optInt("priority", 0)));
    }

    /** {@code pocket.*} 保留给宿主。 */
    public static String validateId(String id) {
        if (id == null || !id.matches("[a-z0-9_.]{1,48}")) {
            return "动作 id 必须是 [a-z0-9_.]{1,48}: " + id;
        }
        if (id.startsWith("pocket.")) return "动作 id 不能占用 pocket.*: " + id;
        return null;
    }

    public static int clampTimeout(int ms) {
        return Math.max(500, Math.min(60_000, ms));
    }
}
