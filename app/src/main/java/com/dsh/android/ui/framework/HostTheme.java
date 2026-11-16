package com.dsh.android.ui.framework;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;

import com.dsh.android.plugin.HookManifest;
import com.dsh.android.plugin.HookRegistry;
import com.dsh.android.plugin.PluginGrants;
import com.dsh.android.plugin.ThemeDecl;
import com.dsh.android.util.DebugLog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题外壳：当前主题包 + 所有 UiFramework 的统一应用/恢复。
 *
 * 一次只应用一个主题（设置页选择）；tokens 立即影响所有由 UI 框架渲染的界面，
 * 皮肤目前只接 {@code message.bubble}。原生外壳（顶栏/抽屉/输入框）仍用资源色，
 * 这部分留给后续「主题 token → 原生 View」的映射。
 */
public final class HostTheme {

    public static final String PREFS = "settings";
    public static final String PREF_ACTIVE = "active_theme";
    /** 主题皮肤确认：值为 manifest+entry 指纹，内容变化后需要重新确认。 */
    private static final String PREF_SKIN_ACK = "skin_ack_";
    public static final String DEFAULT_NAME = "默认主题";

    private static volatile ThemePack pack;
    private static volatile String id = "default";
    private static volatile String name = DEFAULT_NAME;
    private static volatile boolean dark = true;
    private static volatile int version;
    private static volatile Map<String, UiNode> skins = Collections.emptyMap();
    /** strings / assets / 已解码 icon（图标只保留成功加载的项）。 */
    private static volatile Map<String, String> labels = Collections.emptyMap();
    private static volatile Map<String, String> assets = Collections.emptyMap();
    /**
     * 皮肤 {@code asset://<key>} 展开后的目录前缀：必须是插件**目录名**（{@code m.dir.getName()}），
     * 因为渲染器的资源根是 files/plugins，manifest id 与目录名可以不同。
     */
    private static volatile String assetDir = "default";
    private static volatile Map<String, android.graphics.Bitmap> icons = Collections.emptyMap();
    /** 原生外壳（顶栏/输入框）在主题切换后重刷标签与图标。 */
    private static volatile Runnable chromeListener;
    private static final List<WeakReference<UiFramework>> FRAMEWORKS = new ArrayList<>();

    private HostTheme() {
    }

    public static ThemePack pack() {
        return pack;
    }

    public static boolean isDark() {
        return dark;
    }

    public static String id() {
        return id;
    }

    public static String name() {
        return name;
    }

    public static int version() {
        return version;
    }

    /** 主题声明的 UI 标签；没有覆盖时返回 fallback。 */
    public static String label(String key, String fallback) {
        String text = labels.get(key);
        return text == null || text.isEmpty() ? fallback : text;
    }

    /** 主题声明的图标（{@code icon.*} token / assets）；没有覆盖时返回 null（用内置图标）。 */
    public static android.graphics.Bitmap icon(String key) {
        return icons.get(key);
    }

    /** 原生外壳监听器：主题应用/恢复后重刷顶栏图标、输入框 hint 等。 */
    public static void setChromeListener(Runnable listener) {
        chromeListener = listener;
    }

    /** 用户的皮肤是否已确认过（按主题内容指纹绑定）。 */
    public static boolean skinsAllowed(Context ctx, HookManifest m) {
        if (ctx == null || m == null || !m.canSkin()) return false;
        String ack = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_SKIN_ACK + m.id, "");
        return ack != null && !ack.isEmpty() && ack.equals(PluginGrants.fingerprint(m));
    }

    /** 应用一个主题插件；未显式确认过皮肤时只应用 token，不套 skins。 */
    public static void apply(Context ctx, HookManifest m) {
        apply(ctx, m, false);
    }

    /**
     * @param consentSkins true 表示用户刚刚看过“会替换消息/卡片外观”的警告并确认；
     *                     会写入指纹，之后启动恢复才允许继续套皮肤。
     */
    public static void apply(Context ctx, HookManifest m, boolean consentSkins) {
        if (m == null || m.theme == null) {
            reset(ctx);
            return;
        }
        dark = isDark(ctx);
        pack = ThemePack.fromJava(m.theme.tokens);
        id = m.id;
        name = m.name == null || m.name.isEmpty() ? m.id : m.name;
        boolean allowSkins = m.canSkin() && (consentSkins || skinsAllowed(ctx, m));
        if (consentSkins && m.canSkin() && ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PREF_SKIN_ACK + m.id, PluginGrants.fingerprint(m)).apply();
        }
        Map<String, UiNode> map = new LinkedHashMap<>();
        if (allowSkins) {
            for (ThemeDecl.Skin s : m.theme.skins) {
                try {
                    UiNode node = UiNode.fromJson(s.view);
                    if (node != null) map.put(s.component, node);
                } catch (Exception e) {
                    DebugLog.w("theme " + m.id + " skin " + s.component + " parse failed: " + e);
                }
            }
        }
        skins = map;
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(m.theme.strings));
        assets = Collections.unmodifiableMap(new LinkedHashMap<>(m.theme.assets));
        assetDir = m.dir == null || m.dir.getName().isEmpty() ? m.id : m.dir.getName();
        icons = loadIcons(m);
        version++;
        publishState();
        notifyFrameworks();
        notifyChrome();
    }

    public static void reset(Context ctx) {
        pack = null;
        id = "default";
        name = DEFAULT_NAME;
        dark = isDark(ctx);
        skins = Collections.emptyMap();
        labels = Collections.emptyMap();
        assets = Collections.emptyMap();
        assetDir = "default";
        icons = Collections.emptyMap();
        version++;
        publishState();
        notifyFrameworks();
        notifyChrome();
    }

    /**
     * 解析 {@code icon.*}：值可以是 {@code asset://<assets 键>} 或 {@code asset://<相对路径>}；
     * 内置图标名（如 {@code send}）表示不覆盖。失败/超限只记 warning，不影响其它 token。
     */
    private static Map<String, android.graphics.Bitmap> loadIcons(HookManifest m) {
        if (m == null || m.theme == null || m.dir == null) return Collections.emptyMap();
        Map<String, android.graphics.Bitmap> out = new LinkedHashMap<>();
        UiAssets loader = new UiAssets(m.dir);
        for (Map.Entry<String, Object> e : m.theme.tokens.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("icon.")) continue;
            String value = e.getValue() instanceof String ? ((String) e.getValue()).trim() : "";
            if (value.isEmpty() || !value.startsWith("asset:")) continue;
            String path = value.substring(value.startsWith("asset://") ? 8 : 6);
            String mapped = m.theme.assets.get(path);
            if (mapped != null && !mapped.isEmpty()) path = mapped;
            android.graphics.Bitmap bmp = loader.load("asset:" + path);
            if (bmp != null) {
                out.put(key.substring(5), bmp);
            } else {
                DebugLog.w("theme " + m.id + " icon " + key + " ignored: " + value);
            }
        }
        for (String warning : loader.warnings()) {
            DebugLog.w("theme " + m.id + " asset: " + warning);
        }
        return out;
    }

    /** 启动恢复：读 settings.active_theme，找不到就保持默认。 */
    public static void restore(Context ctx, HookRegistry registry) {
        if (ctx == null || registry == null) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String want = p.getString(PREF_ACTIVE, "");
        if (want == null || want.isEmpty()) return;
        HookManifest m = registry.themePlugin(want);
        if (m == null) {
            p.edit().remove(PREF_ACTIVE).apply();
            return;
        }
        apply(ctx, m);
    }

    public static void register(UiFramework fw) {
        if (fw == null) return;
        synchronized (FRAMEWORKS) {
            FRAMEWORKS.add(new WeakReference<>(fw));
        }
        ThemePack p = pack;
        if (p != null) fw.applyTheme(p, dark);
    }

    public static void unregister(UiFramework fw) {
        synchronized (FRAMEWORKS) {
            for (int i = FRAMEWORKS.size() - 1; i >= 0; i--) {
                UiFramework f = FRAMEWORKS.get(i).get();
                if (f == null || f == fw) FRAMEWORKS.remove(i);
            }
        }
    }

    /** 给消息气泡套皮肤；没有 skin 就原样返回。 */
    public static UiNode applyMessageSkin(UiNode content, String text) {
        Map<String, String> vars = commonVars();
        vars.put("message.text", text == null ? "" : text);
        vars.put("message.role", "assistant");
        vars.put("message.time", "");
        return applySkin("message.bubble", content, vars);
    }

    /** 工具卡皮肤（agent 自发调用与插件 show:true 共用），占位符见 §12.1。 */
    public static UiNode applyToolSkin(UiNode content, String tool, String args,
                                       String result, String exitCode) {
        Map<String, String> vars = commonVars();
        vars.put("tool.name", tool == null ? "" : tool);
        vars.put("tool.args", args == null ? "" : args);
        vars.put("tool.result", result == null ? "" : result);
        vars.put("tool.exitCode", exitCode == null ? "" : exitCode);
        return applySkin("tool.card", content, vars);
    }

    /** 代码卡皮肤；占位符 {@code {{code.lang}}} / {@code {{code.body}}}。 */
    public static UiNode applyCodeSkin(UiNode content, String lang, String body) {
        Map<String, String> vars = commonVars();
        vars.put("code.lang", lang == null ? "" : lang);
        vars.put("code.body", body == null ? "" : body);
        return applySkin("code.card", content, vars);
    }

    /** 表格卡皮肤（占位符同通用集合，主要是 theme.name / session.title）。 */
    public static UiNode applyTableSkin(UiNode content) {
        return applySkin("table", content, commonVars());
    }

    /** 通用皮肤入口：组件名 → 皮肤节点；assets 里的 {@code asset://<key>} 同步解析。 */
    public static UiNode applySkin(String component, UiNode content, Map<String, String> vars) {
        UiNode skin = skins.get(component);
        if (skin == null) return content;
        return ThemeSkin.apply(skin, content, vars, assets, assetDir);
    }

    private static Map<String, String> commonVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        Object title = HostState.bus().get("pocket.session.title");
        vars.put("session.title", title == null ? "" : String.valueOf(title));
        vars.put("theme.name", name);
        vars.put("message.text", "");
        vars.put("message.role", "");
        vars.put("message.time", "");
        vars.put("tool.name", "");
        vars.put("tool.args", "");
        vars.put("tool.result", "");
        vars.put("tool.exitCode", "");
        vars.put("code.lang", "");
        vars.put("code.body", "");
        return vars;
    }

    private static void notifyChrome() {
        Runnable listener = chromeListener;
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(listener);
    }

    private static void notifyFrameworks() {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            List<UiFramework> alive = new ArrayList<>();
            synchronized (FRAMEWORKS) {
                for (int i = FRAMEWORKS.size() - 1; i >= 0; i--) {
                    UiFramework f = FRAMEWORKS.get(i).get();
                    if (f == null) {
                        FRAMEWORKS.remove(i);
                    } else {
                        alive.add(f);
                    }
                }
            }
            ThemePack p = pack;
            for (UiFramework f : alive) {
                if (p == null) f.resetTheme();
                else f.applyTheme(p, dark);
            }
        });
    }

    private static void publishState() {
        HostState.publish("pocket.theme.id", id);
        HostState.publish("pocket.theme.name", name);
    }

    private static boolean isDark(Context ctx) {
        if (ctx == null) return true;
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
