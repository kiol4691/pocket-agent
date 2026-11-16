package com.dsh.android.ui.framework;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 节点 type → {@link UiComponent} 的注册表。刻意不依赖 Android，方便在 JVM 单测里验证
 * 注册/覆盖/白名单同步这些逻辑（不必构造 View）。
 *
 * 注册时同步调用 {@link UiNode#registerType}，让 {@code fromJson} 的白名单和这里保持一致。
 */
public final class UiComponentRegistry {

    private final Map<String, UiComponent> components = new LinkedHashMap<>();

    public void register(String type, UiComponent component) {
        if (type == null || type.isEmpty() || component == null) return;
        components.put(type, component);
        UiNode.registerType(type);
    }

    public UiComponent get(String type) {
        return components.get(type);
    }

    public boolean has(String type) {
        return components.containsKey(type);
    }

    public int size() {
        return components.size();
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(components.keySet());
    }
}
