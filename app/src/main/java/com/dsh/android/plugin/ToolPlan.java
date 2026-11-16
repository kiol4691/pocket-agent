package com.dsh.android.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件工具的重名解析（纯 Java，可单测）。
 *
 * 顺序：每个工具自己的 {@code priority} 升序，同优先级按插件 id 字典序，保证"谁先占住工具名"
 * 可复现且与插件级 priority 解耦。规则：
 *  - 与内置工具重名：一律拒绝（内置工具是宿主的稳定契约，不能被插件影子掉）；
 *  - 跨插件重名：优先级最高（数值最小）的插件获得注册权，其余记冲突原因；
 *  - 没申请 {@code tool.register} 的插件整体跳过（不报错，由 lint 提示）。
 */
public final class ToolPlan {

    public static final class Entry {
        public final String pluginId;
        public final boolean gated;
        /** 工具名 → 该工具声明的 priority（缺省 0）。 */
        public final Map<String, Integer> tools;

        public Entry(String pluginId, boolean gated, Map<String, Integer> tools) {
            this.pluginId = pluginId;
            this.gated = gated;
            this.tools = tools == null ? new LinkedHashMap<>() : tools;
        }
    }

    public static final class Result {
        /** 工具名 -> 拥有注册权的插件 id。 */
        public final Map<String, String> owners = new LinkedHashMap<>();
        /** 工具名 -> 拒绝原因（给 lastError/日志用）。 */
        public final Map<String, String> conflicts = new LinkedHashMap<>();
    }

    public static Result resolve(List<Entry> entries, Set<String> builtinNames) {
        Result r = new Result();
        if (entries == null) return r;
        // 工具名 → 当前最优候选（priority 小者胜，同 priority 按插件 id）。
        Map<String, String> bestOwner = new LinkedHashMap<>();
        Map<String, Integer> bestPriority = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e == null || e.gated) continue;
            for (Map.Entry<String, Integer> t : e.tools.entrySet()) {
                String name = t.getKey();
                int p = t.getValue() == null ? 0 : t.getValue();
                if (builtinNames != null && builtinNames.contains(name)) {
                    r.conflicts.put(name, "工具 " + name + " 与内置工具重名，已拒绝注册");
                    continue;
                }
                String owner = bestOwner.get(name);
                Integer ownerPriority = bestPriority.get(name);
                if (owner == null || p < ownerPriority
                        || (p == ownerPriority && e.pluginId.compareTo(owner) < 0)) {
                    bestOwner.put(name, e.pluginId);
                    bestPriority.put(name, p);
                }
            }
        }
        r.owners.putAll(bestOwner);
        // 落选者记一条冲突原因（同一个工具名只保留最后一条，供插件页/日志展示）。
        for (Entry e : entries) {
            if (e == null || e.gated) continue;
            for (String name : e.tools.keySet()) {
                String owner = bestOwner.get(name);
                if (owner != null && !owner.equals(e.pluginId)) {
                    r.conflicts.put(name, "工具 " + name + " 已被插件 " + owner + " 注册，已拒绝");
                }
            }
        }
        return r;
    }

    private ToolPlan() {
    }
}
