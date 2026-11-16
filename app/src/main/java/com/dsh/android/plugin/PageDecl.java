package com.dsh.android.plugin;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 一条自定义页面声明（manifest 的 {@code contributes.pages[]}）。
 *
 * 页面全名是 {@code <pluginId>.<id>}，用于导航与 {@code page:test}；插件之间不会撞名。
 */
public final class PageDecl {

    public static final int MIN_REFRESH_MS = 1_000;
    public static final int MAX_REFRESH_MS = 600_000;

    public final String pluginId;
    public final String id;
    public final String title;
    public final String icon;
    public final String handler;
    /** 0 = 不自动刷新。 */
    public final int refreshMs;
    public final boolean scroll;
    /** 预留参数 schema；本轮只透传 params，不做完整校验。 */
    public final JSONObject params;
    /** 页面列表排序：小者靠前（与其它贡献类型方向一致）。 */
    public final int priority;

    private PageDecl(String pluginId, String id, String title, String icon, String handler,
                     int refreshMs, boolean scroll, JSONObject params, int priority) {
        this.pluginId = pluginId;
        this.id = id;
        this.title = title;
        this.icon = icon;
        this.handler = handler;
        this.refreshMs = refreshMs;
        this.scroll = scroll;
        this.params = params;
        this.priority = priority;
    }

    public String fullId() {
        return pluginId + "." + id;
    }

    static PageDecl fromJson(String pluginId, JSONObject o) throws IOException {
        String id = o.optString("id", "").trim();
        String bad = validateId(id);
        if (bad != null) throw new IOException(bad);

        String title = o.optString("title", "").trim();
        bad = validateTitle(title);
        if (bad != null) throw new IOException("页面 " + id + " 的 title 非法: " + title);

        String handler = o.optString("handler", "").trim();
        bad = UiSlotDecl.validateHandler(handler);
        if (bad != null) throw new IOException("页面 " + id + " 的 handler 非法: " + handler);

        return new PageDecl(pluginId, id, title, o.optString("icon", ""), handler,
                clampRefresh(o.optInt("refreshMs", 0)),
                o.optBoolean("scroll", true),
                o.optJSONObject("params"),
                HookManifest.clampPriority(o.optInt("priority", 0)));
    }

    public static String validateId(String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,32}")) {
            return "页面 id 必须是 [a-z0-9_-]{1,32}: " + id;
        }
        return null;
    }

    public static String validateTitle(String title) {
        if (title == null || title.isEmpty()) return "页面 title 不能为空";
        if (title.length() > 64) return "页面 title 不能超过 64 字";
        return null;
    }

    /** 0 保留"不刷新"；其余夹在 1s–10min（文档 §5.2）。 */
    public static int clampRefresh(int ms) {
        if (ms <= 0) return 0;
        return Math.max(MIN_REFRESH_MS, Math.min(MAX_REFRESH_MS, ms));
    }
}
