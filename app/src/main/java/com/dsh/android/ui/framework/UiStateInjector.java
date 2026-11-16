package com.dsh.android.ui.framework;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 状态注入的纯 Java 实现：把当前状态值格式化后写进节点 props，返回一棵新树。
 * 不依赖 Android，方便 JVM 单测；{@link UiStateBus#inject(UiNode)} 委托到这里。
 */
public final class UiStateInjector {

    private UiStateInjector() {
    }

    public static UiNode inject(UiNode root, Map<String, Object> values) {
        return inject(root, values, 0);
    }

    public static UiNode inject(UiNode root, Map<String, Object> values, int depth) {
        if (root == null || depth > UiNode.MAX_DEPTH) return root;
        UiNode.Builder b = UiNode.of(root.type());
        if (root.key() != null) b.key(root.key());

        Map<String, Object> props = new LinkedHashMap<>(root.props());
        Object bind = props.get("bind");
        boolean hide = false;
        if (bind instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) bind).entrySet()) {
                String attr = String.valueOf(e.getKey());
                Object spec = e.getValue();
                String state = UiStateFormat.stateName(spec);
                Object value = values == null ? null : values.get(state);
                if (value != null) {
                    props.put(attr, UiStateFormat.apply(value, spec));
                } else if ("hide".equals(root.str("fallback", "keep"))) {
                    hide = true;
                }
            }
        }
        if (hide) props.put("hidden", Boolean.TRUE);
        else props.remove("hidden");
        for (Map.Entry<String, Object> e : props.entrySet()) b.prop(e.getKey(), e.getValue());
        for (UiNode child : root.children()) b.child(inject(child, values, depth + 1));
        return b.build();
    }
}
