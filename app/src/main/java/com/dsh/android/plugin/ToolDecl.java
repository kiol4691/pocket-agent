package com.dsh.android.plugin;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 一条工具声明（manifest 的 {@code contributes.tools[]}）。
 *
 * 校验刻意留在这一层，且全部是纯 Java 静态方法：manifest 解析和 JVM 单测共用同一套规则，
 * 不会出现"运行时能过、单测里另一套"的分裂。
 *
 * 约定：
 *  - 名字只允许 {@code [a-z0-9_]{1,32}}，与内置工具同形，便于模型稳定调用；
 *  - {@code inputSchema} 必须是对象，且 {@code type}（若写了）只能是 object；
 *  - {@code handler} 是入口脚本里的函数名，不要求在加载期就存在（启用前的 lint 负责检查）；
 *  - {@code priority} 只影响**工具名冲突**（跨插件重名时小者胜），与插件级 priority 无关；
 *  - {@code timeoutMs} 夹在 1s–600s：低于 1s 的工具有意义不大，高于 10 分钟会挡住
 *    同一插件的其它入口（引擎按插件串行）。
 */
public final class ToolDecl {

    public static final int MIN_TIMEOUT_MS = 1_000;
    public static final int MAX_TIMEOUT_MS = 600_000;

    public final String name;
    public final String description;
    /** 原样保存给模型看的参数 schema；调用前按它做最小校验与默认值填充。 */
    public final JSONObject inputSchema;
    public final String handler;
    public final int timeoutMs;
    /** 工具重名冲突时的优先级：小者胜（与其它贡献类型的 priority 方向一致）。 */
    public final int priority;

    private ToolDecl(String name, String description, JSONObject inputSchema,
                     String handler, int timeoutMs, int priority) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.handler = handler;
        this.timeoutMs = timeoutMs;
        this.priority = priority;
    }

    /** 解析一条声明；坏声明直接让 manifest 加载失败。 */
    public static ToolDecl fromJson(JSONObject o, int defaultTimeoutMs) throws IOException {
        String name = o.optString("name", "").trim();
        String bad = validateName(name);
        if (bad != null) throw new IOException(bad);

        String handler = o.optString("handler", "").trim();
        if (handler.isEmpty()) throw new IOException("工具 " + name + " 缺少 handler");

        JSONObject schema = o.optJSONObject("inputSchema");
        if (schema == null) throw new IOException("工具 " + name + " 的 inputSchema 必须是 object");
        String type = schema.optString("type", "");
        if (!type.isEmpty() && !"object".equals(type)) {
            throw new IOException("工具 " + name + " 的 inputSchema.type 只能是 object: " + type);
        }

        int timeout = o.has("timeoutMs") ? o.optInt("timeoutMs", defaultTimeoutMs) : defaultTimeoutMs;
        return new ToolDecl(name, o.optString("description", ""), schema, handler,
                clampTimeout(timeout), HookManifest.clampPriority(o.optInt("priority", 0)));
    }

    /** 纯校验：返回 null 表示合法。 */
    public static String validateName(String name) {
        if (name == null || !name.matches("[a-z0-9_]{1,32}")) {
            return "工具名必须是 [a-z0-9_]{1,32}: " + name;
        }
        return null;
    }

    public static int clampTimeout(int ms) {
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, ms));
    }
}
