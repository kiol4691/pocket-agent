package com.dsh.android.ui.framework;

import java.util.Collections;
import java.util.Map;

/**
 * 主题皮肤的应用逻辑（纯 Java，可单测）。
 *
 * 宿主约定：皮肤的根节点必须是 {@code column} 或 {@code card}，内置内容作为最后一个子节点
 * 追加进去；其它根类型视为不支持，回退默认外观。文本类属性里的 {@code {{占位符}}} 会替换。
 */
public final class ThemeSkin {

    private ThemeSkin() {
    }

    public static UiNode apply(UiNode skin, UiNode content, Map<String, String> vars) {
        return apply(skin, content, vars, null, null);
    }

    /**
     * 同上，并把 {@code src: "asset://<key>"} 解析成主题声明的资源：
     * {@code <prefix>/<path>}（prefix 是插件目录名，path 来自 {@code theme.assets}）。
     * 已经是完整相对路径（含 {@code /}）的地址原样保留。
     */
    public static UiNode apply(UiNode skin, UiNode content, Map<String, String> vars,
                               Map<String, String> assets, String assetPrefix) {
        if (skin == null) return content;
        UiNode root = substitute(skin,
                vars == null ? Collections.emptyMap() : vars, assets, assetPrefix, 0);
        if (!isContainer(root.type())) return content;
        UiNode.Builder b = UiNode.of(root.type());
        if (root.key() != null) b.key(root.key());
        for (Map.Entry<String, Object> e : root.props().entrySet()) b.prop(e.getKey(), e.getValue());
        for (UiNode child : root.children()) b.child(child);
        if (content != null) b.child(content);
        return b.build();
    }

    static boolean isContainer(String type) {
        return UiNode.COLUMN.equals(type) || UiNode.CARD.equals(type);
    }

    private static UiNode substitute(UiNode node, Map<String, String> vars,
                                     Map<String, String> assets, String assetPrefix, int depth) {
        if (node == null || depth > UiNode.MAX_DEPTH) return node;
        UiNode.Builder b = UiNode.of(node.type());
        if (node.key() != null) b.key(node.key());
        for (Map.Entry<String, Object> e : node.props().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String && isTextProp(e.getKey())) {
                v = replace((String) v, vars);
            }
            if (v instanceof String && "src".equals(e.getKey())) {
                v = resolveAsset((String) v, assets, assetPrefix);
            }
            b.prop(e.getKey(), v);
        }
        for (UiNode child : node.children()) {
            b.child(substitute(child, vars, assets, assetPrefix, depth + 1));
        }
        return b.build();
    }

    /** {@code asset://<key>} → {@code asset://<prefix>/<path>}；其它地址原样返回。 */
    static String resolveAsset(String src, Map<String, String> assets, String prefix) {
        if (src == null || assets == null || prefix == null) return src;
        if (!src.startsWith("asset://")) return src;
        String key = src.substring(8);
        if (key.isEmpty() || key.contains("/")) return src;
        String path = assets.get(key);
        if (path == null || path.isEmpty()) return src;
        while (path.startsWith("/")) path = path.substring(1);
        return "asset://" + prefix + "/" + path;
    }

    private static boolean isTextProp(String key) {
        return "text".equals(key) || "body".equals(key) || "label".equals(key)
                || "title".equals(key) || "summary".equals(key);
    }

    /**
     * 占位符替换：单趟扫描，只展开一层。
     *
     * <p>不能逐个变量做 {@code String.replace}：正文（如 {@code {{tool.result}} 的内容}）里
     * 出现的 {@code {{其它变量}}} 会被后面的替换二次展开，属于注入。未知占位符原样保留。
     */
    static String replace(String s, Map<String, String> vars) {
        if (s == null || s.isEmpty() || vars == null || vars.isEmpty()) return s;
        int from = s.indexOf("{{");
        if (from < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (from >= 0) {
            int end = s.indexOf("}}", from + 2);
            if (end < 0) break;
            out.append(s, i, from);
            String key = s.substring(from + 2, end);
            if (vars.containsKey(key)) {
                String value = vars.get(key);
                out.append(value == null ? "" : value);
            } else {
                out.append(s, from, end + 2);
            }
            i = end + 2;
            from = s.indexOf("{{", i);
        }
        out.append(s, i, s.length());
        return out.toString();
    }
}
