package com.dsh.android.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条用户可配置项（manifest 的 {@code config[]}，见 PLUGIN_API §1.1）。
 *
 * 纯数据 + 纯 Java 校验，插件页渲染对话框与 JVM 单测共用同一套规则。
 */
public final class ConfigField {

    public static final class Option {
        public final String label;
        public final String value;

        Option(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    public final String key;
    public final String label;
    /** string | number | boolean | select。 */
    public final String type;
    public final Object defaultValue;
    public final String hint;
    public final List<Option> options;

    private ConfigField(String key, String label, String type, Object defaultValue,
                        String hint, List<Option> options) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.defaultValue = defaultValue;
        this.hint = hint;
        this.options = options;
    }

    static ConfigField fromJson(JSONObject o) throws IOException {
        String key = o.optString("key", "").trim();
        if (!key.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IOException("config.key 必须是 [A-Za-z0-9_]{1,32}: " + key);
        }
        String label = o.optString("label", key).trim();
        if (label.isEmpty() || label.length() > 64) {
            throw new IOException("config " + key + " 的 label 不能为空且不超过 64 字");
        }
        String type = o.optString("type", "string").trim();
        if (!"string".equals(type) && !"number".equals(type)
                && !"boolean".equals(type) && !"select".equals(type)) {
            throw new IOException("config " + key + " 的 type 非法: " + type);
        }
        List<Option> options = new ArrayList<>();
        JSONArray arr = o.optJSONArray("options");
        if ("select".equals(type)) {
            if (arr == null || arr.length() == 0) {
                throw new IOException("config " + key + " 是 select，必须提供 options");
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) throw new IOException("config " + key + " options[" + i + "] 不是对象");
                String value = x.optString("value", "");
                String text = x.optString("label", value);
                if (value.isEmpty()) throw new IOException("config " + key + " options[" + i + "] 缺少 value");
                options.add(new Option(text, value));
            }
        }
        Object def = normalize(type, o.opt("default"));
        return new ConfigField(key, label, type, def,
                o.optString("hint", ""), Collections.unmodifiableList(options));
    }

    /** 按类型把 JSON 默认值收敛成 PluginConfig 会返回的 Java 类型。 */
    static Object normalize(String type, Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            switch (type) {
                case "number": return 0;
                case "boolean": return Boolean.FALSE;
                default: return "";
            }
        }
        switch (type) {
            case "number":
                if (raw instanceof Number) return ((Number) raw).doubleValue();
                try {
                    return Double.parseDouble(String.valueOf(raw));
                } catch (NumberFormatException e) {
                    return 0;
                }
            case "boolean":
                return raw instanceof Boolean ? raw : Boolean.parseBoolean(String.valueOf(raw));
            default:
                return String.valueOf(raw);
        }
    }

    /** 把用户输入按类型转换；number 解析失败时退回默认值。 */
    public Object coerce(String raw) {
        if ("number".equals(type)) {
            try {
                double d = Double.parseDouble(raw == null ? "" : raw.trim());
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) ((long) d) : (Object) d;
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        if ("boolean".equals(type)) return Boolean.parseBoolean(raw);
        return raw == null ? "" : raw;
    }
}
