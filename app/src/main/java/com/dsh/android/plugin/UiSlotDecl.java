package com.dsh.android.plugin;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一条 UI 插槽声明（manifest 的 {@code contributes.ui[]}）。
 *
 * 插槽是宿主预留的固定位置；插件只能声明"往哪个位置画"，不能自己创建容器。
 * 校验保持纯 Java，manifest 解析与 JVM 单测共用同一套规则。
 */
public final class UiSlotDecl {

    public static final String MESSAGE_HEADER = "message.header";
    public static final String MESSAGE_FOOTER = "message.footer";
    public static final String COMPOSER_ABOVE = "composer.above";
    public static final String SETTINGS_SECTION = "settings.section";
    public static final String DRAWER_FOOTER = "drawer.footer";
    public static final String HOME_HERO = "home.hero";

    /** 与 docs/plugin.schema.json 的 uiSlot.slot 枚举保持一致。 */
    public static final Set<String> SLOTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    MESSAGE_HEADER, MESSAGE_FOOTER, COMPOSER_ABOVE,
                    SETTINGS_SECTION, DRAWER_FOOTER, HOME_HERO)));

    public final String pluginId;
    public final String slot;
    public final String handler;
    /** 同一插槽多个插件时的求值顺序：小者先求值（第一个返回视图的生效）。 */
    public final int priority;

    private UiSlotDecl(String pluginId, String slot, String handler, int priority) {
        this.pluginId = pluginId;
        this.slot = slot;
        this.handler = handler;
        this.priority = priority;
    }

    static UiSlotDecl fromJson(String pluginId, JSONObject o) throws IOException {
        String slot = o.optString("slot", "").trim();
        String bad = validateSlot(slot);
        if (bad != null) throw new IOException(bad);
        String handler = o.optString("handler", "").trim();
        bad = validateHandler(handler);
        if (bad != null) throw new IOException("插槽 " + slot + " 的 handler 非法: " + handler);
        return new UiSlotDecl(pluginId, slot, handler,
                HookManifest.clampPriority(o.optInt("priority", 0)));
    }

    /** 纯校验：返回 null 表示合法。 */
    public static String validateSlot(String slot) {
        if (slot == null || !SLOTS.contains(slot)) {
            return "未知插槽: " + slot + "（可用: " + String.join(", ", SLOTS) + "）";
        }
        return null;
    }

    /** 与 schema 的 handler pattern 一致。 */
    public static String validateHandler(String handler) {
        if (handler == null || !handler.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return "handler 必须是合法函数名: " + handler;
        }
        return null;
    }
}
