package com.dsh.android.plugin;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 一条插件状态声明（manifest 的 {@code contributes.state[]}）。 */
public final class StateDecl {

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "string", "number", "boolean", "object"));

    public final String pluginId;
    public final String id;
    public final String label;
    public final String type;
    public final boolean push;
    /** 同一状态全名被多个插件声明时的优先级：小者胜（与其它贡献类型方向一致）。 */
    public final int priority;

    private StateDecl(String pluginId, String id, String label, String type, boolean push,
                      int priority) {
        this.pluginId = pluginId;
        this.id = id;
        this.label = label;
        this.type = type;
        this.push = push;
        this.priority = priority;
    }

    public String fullName() {
        return pluginId + "." + id;
    }

    static StateDecl fromJson(String pluginId, JSONObject o) throws IOException {
        String id = o.optString("id", "").trim();
        if (!id.matches("[a-z0-9_]{1,32}")) {
            throw new IOException("状态 id 必须是 [a-z0-9_]{1,32}: " + id);
        }
        String label = o.optString("label", "").trim();
        if (label.isEmpty() || label.length() > 64) {
            throw new IOException("状态 " + id + " 的 label 非法");
        }
        String type = o.optString("type", "");
        if (!TYPES.contains(type)) {
            throw new IOException("状态 " + id + " 的 type 必须是 string/number/boolean/object");
        }
        return new StateDecl(pluginId, id, label, type, o.optBoolean("push", true),
                HookManifest.clampPriority(o.optInt("priority", 0)));
    }

    /** 校验运行时值的类型；不匹配返回 false。 */
    public boolean accepts(Object value) {
        switch (type) {
            case "string":
                return value instanceof String;
            case "number":
                return value instanceof Number;
            case "boolean":
                return value instanceof Boolean;
            case "object":
                return value instanceof JSONObject;
            default:
                return false;
        }
    }
}
