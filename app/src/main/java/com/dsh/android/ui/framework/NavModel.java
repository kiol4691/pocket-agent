package com.dsh.android.ui.framework;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导航外壳的数据模型（纯 Java，可单测）。
 *
 * <p>输入是「页面源」（聊天 + 各插件的 {@code contributes.pages}）和「nav 覆盖」
 * （{@code contributes.nav}），合成一份排好序、分好组、去掉 hidden 的菜单。
 * 默认外壳和主题里的 {@code nav.list} 节点都消费同一个模型。
 *
 * <p>覆盖规则：按传入顺序（插件 priority→id）应用，**同一页面第一个覆盖生效**；
 * 指向未注册页面的覆盖直接忽略（lint 会提示）。
 */
public final class NavModel {

    public static final String CHAT_PAGE = "pocket.chat";

    /** 页面源；{@code group} 为空串表示"默认分组"（渲染时用「插件页面」）。 */
    public static final class Source {
        public final String page;
        public final String label;
        public final String group;
        public final int order;
        public final boolean hidden;
        public final boolean builtin;

        public Source(String page, String label, String group, int order,
                      boolean hidden, boolean builtin) {
            this.page = page;
            this.label = label == null ? "" : label;
            this.group = group == null ? "" : group;
            this.order = order;
            this.hidden = hidden;
            this.builtin = builtin;
        }
    }

    /** nav 覆盖；null 字段表示继承页面默认值。 */
    public static final class Override {
        public final String page;
        public final String label;
        public final String group;
        public final Integer order;
        public final Boolean hidden;

        public Override(String page, String label, String group, Integer order, Boolean hidden) {
            this.page = page;
            this.label = label;
            this.group = group;
            this.order = order;
            this.hidden = hidden;
        }
    }

    public static final class Item {
        public final String page;
        public final String label;
        public final String group;
        public final int order;
        public final boolean builtin;

        Item(Source s) {
            this.page = s.page;
            this.label = s.label;
            this.group = s.group;
            this.order = s.order;
            this.builtin = s.builtin;
        }
    }

    public static final class Group {
        public final String name;
        public final List<Item> items;

        Group(String name, List<Item> items) {
            this.name = name;
            this.items = Collections.unmodifiableList(items);
        }
    }

    private final List<Item> items;

    private NavModel(List<Item> items) {
        this.items = Collections.unmodifiableList(items);
    }

    public static NavModel build(List<Source> sources, List<Override> overrides) {
        Map<String, Source> map = new LinkedHashMap<>();
        if (sources != null) {
            for (Source s : sources) {
                if (s != null && s.page != null) map.put(s.page, s);
            }
        }
        if (overrides != null) {
            Set<String> applied = new LinkedHashSet<>();
            for (Override o : overrides) {
                if (o == null || o.page == null || applied.contains(o.page)) continue;
                Source s = map.get(o.page);
                if (s == null) continue;
                applied.add(o.page);
                map.put(o.page, new Source(s.page,
                        o.label == null ? s.label : o.label,
                        o.group == null ? s.group : o.group,
                        o.order == null ? s.order : o.order,
                        o.hidden == null ? s.hidden : o.hidden,
                        s.builtin));
            }
        }

        List<Item> visible = new ArrayList<>();
        for (Source s : map.values()) {
            if (!s.hidden) visible.add(new Item(s));
        }
        visible.sort(Comparator.comparingInt((Item i) -> i.order).thenComparing(i -> i.page));
        return new NavModel(visible);
    }

    public static NavModel empty() {
        return new NavModel(Collections.emptyList());
    }

    public List<Item> items() {
        return items;
    }

    public List<Item> group(String group) {
        List<Item> out = new ArrayList<>();
        String g = group == null ? "" : group;
        for (Item i : items) {
            if (i.group.equals(g)) out.add(i);
        }
        return out;
    }

    /** 分组按组内最小 order 排序（items 已排序，按首次出现即可）。 */
    public List<Group> groups() {
        Map<String, List<Item>> map = new LinkedHashMap<>();
        for (Item i : items) {
            List<Item> list = map.get(i.group);
            if (list == null) {
                list = new ArrayList<>();
                map.put(i.group, list);
            }
            list.add(i);
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : map.entrySet()) {
            out.add(new Group(e.getKey(), e.getValue()));
        }
        return out;
    }

    public Item find(String page) {
        if (page == null) return null;
        for (Item i : items) {
            if (i.page.equals(page)) return i;
        }
        return null;
    }
}
