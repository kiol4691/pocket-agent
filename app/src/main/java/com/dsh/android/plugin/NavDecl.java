package com.dsh.android.plugin;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 一条导航声明（manifest 的 {@code contributes.nav[]}）。
 *
 * 主题外壳用它给页面改 label / 分组 / 顺序，或把页面从菜单里隐藏；默认外壳也会复用同一份
 * 合成结果（见 {@code NavModel}）。字段缺省用 {@code null} 表示"继承页面默认值"，
 * 这样才能区分"没写"和"显式写了空分组"。
 */
public final class NavDecl {

    /** 宿主内置的聊天入口；nav 可以改它的 label/order，但不能被页面替换。 */
    public static final String CHAT_PAGE = "pocket.chat";

    public final String pluginId;
    public final String page;
    public final String label;
    public final String group;
    public final Integer order;
    public final Boolean hidden;
    /** 同一页面被多个 nav 覆盖时的优先级：小者先应用（第一个覆盖生效）。 */
    public final int priority;

    private NavDecl(String pluginId, String page, String label, String group,
                    Integer order, Boolean hidden, int priority) {
        this.pluginId = pluginId;
        this.page = page;
        this.label = label;
        this.group = group;
        this.order = order;
        this.hidden = hidden;
        this.priority = priority;
    }

    static NavDecl fromJson(String pluginId, JSONObject o) throws IOException {
        String page = o.optString("page", "").trim();
        String bad = validatePage(page);
        if (bad != null) throw new IOException(bad);

        String label = o.has("label") ? o.optString("label", "").trim() : null;
        if (label != null && label.length() > 64) {
            throw new IOException("nav " + page + " 的 label 不能超过 64 字");
        }
        String group = o.has("group") ? o.optString("group", "").trim() : null;
        if (group != null && group.length() > 32) {
            throw new IOException("nav " + page + " 的 group 不能超过 32 字");
        }
        Integer order = null;
        if (o.has("order")) order = clampOrder(o.optInt("order", 0));
        Boolean hidden = o.has("hidden") ? o.optBoolean("hidden", false) : null;

        return new NavDecl(pluginId, page,
                label == null || label.isEmpty() ? null : label,
                group == null || group.isEmpty() ? null : group,
                order, hidden, HookManifest.clampPriority(o.optInt("priority", 0)));
    }

    public static String validatePage(String page) {
        if (page == null || !page.matches("[a-z0-9_.-]{1,64}")) {
            return "nav.page 必须是 [a-z0-9_.-]{1,64}: " + page;
        }
        return null;
    }

    public static int clampOrder(int order) {
        return Math.max(-1000, Math.min(1000, order));
    }
}
