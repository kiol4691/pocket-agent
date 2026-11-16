package com.dsh.android.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个主题声明（manifest 的 {@code contributes.theme}）。
 *
 * 主题是纯数据：token + 贴图 + 字符串 + 组件皮肤，不需要 main.js。本轮宿主读取
 * {@code tokens} 并应用到所有 UiFramework 面；{@code assets}/{@code strings} 先解析保留，
 * 组件只接到 {@code message.bubble} 一种皮肤（其余组件仍用默认外观）。
 */
public final class ThemeDecl {

    public static final class Skin {
        public final String component;
        public final JSONObject view;

        Skin(String component, JSONObject view) {
            this.component = component;
            this.view = view;
        }
    }

    public final Map<String, Object> tokens;
    public final Map<String, String> assets;
    public final Map<String, String> strings;
    public final List<Skin> skins;

    private ThemeDecl(Map<String, Object> tokens, Map<String, String> assets,
                      Map<String, String> strings, List<Skin> skins) {
        this.tokens = tokens;
        this.assets = assets;
        this.strings = strings;
        this.skins = skins;
    }

    static ThemeDecl fromJson(JSONObject o) throws IOException {
        if (o == null) throw new IOException("theme 必须是对象");
        List<Skin> skins = new ArrayList<>();
        JSONArray arr = o.optJSONArray("skins");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) throw new IOException("theme.skins[" + i + "] 不是对象");
                String component = s.optString("component", "").trim();
                if (component.isEmpty() || component.length() > 64) {
                    throw new IOException("theme.skins[" + i + "].component 非法: " + component);
                }
                JSONObject view = s.optJSONObject("view");
                if (view == null) throw new IOException("theme.skins[" + i + "].view 必须是对象");
                skins.add(new Skin(component, view));
            }
        }
        return new ThemeDecl(toMap(o.optJSONObject("tokens")),
                toStringMap(o.optJSONObject("assets")),
                toStringMap(o.optJSONObject("strings")), skins);
    }

    public Skin skin(String component) {
        for (Skin s : skins) {
            if (s.component.equals(component)) return s;
        }
        return null;
    }

    private static Map<String, Object> toMap(JSONObject o) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o == null) return out;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, plain(o.opt(k)));
        }
        return out;
    }

    private static Map<String, String> toStringMap(JSONObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o == null) return out;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, String.valueOf(o.opt(k)));
        }
        return out;
    }

    private static Object plain(Object v) {
        if (v instanceof JSONObject) {
            Map<String, Object> m = new LinkedHashMap<>();
            JSONObject o = (JSONObject) v;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, plain(o.opt(k)));
            }
            return m;
        }
        if (v instanceof JSONArray) {
            List<Object> list = new ArrayList<>();
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) list.add(plain(a.opt(i)));
            return list;
        }
        return v;
    }
}
