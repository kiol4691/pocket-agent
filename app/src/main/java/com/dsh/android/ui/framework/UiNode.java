package com.dsh.android.ui.framework;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 声明式界面的一个节点。
 *
 * 只有这里列出的 type 会被渲染；样式只认 token（{@code size} / {@code tone} / {@code pad}），
 * 不接受裸颜色和像素 —— 换成主题时只改 {@link UiTheme}，节点树不用动。
 *
 * 节点的 {@link #key()} 是增量更新的身份：同一父节点下 key 相同的子节点会被复用，
 * 只改属性；没有 key 时退化成"位置 + 类型"，用于静态内容。
 *
 * 构建是不可变的：{@code build()} 之后 props / children 都是快照，可以放心跨线程传，
 * 但渲染必须在主线程。
 */
public final class UiNode {

    public static final String COLUMN = "column";
    public static final String ROW = "row";
    /** 横向滚动容器：内层 wrap_content 横排，超出视口可左右滑动。 */
    public static final String HSCROLL = "hscroll";
    public static final String CARD = "card";
    public static final String TEXT = "text";
    public static final String CODE = "code";
    public static final String MARKDOWN = "markdown";
    public static final String DIVIDER = "divider";
    // P2 展示组件（阶段 1 实现）
    public static final String BADGE = "badge";
    public static final String SPACER = "spacer";
    public static final String PROGRESS = "progress";
    public static final String BARS = "bars";
    public static final String IMAGE = "image";
    public static final String LIST = "list";
    public static final String NAV_LIST = "nav.list";
    // P2 交互组件（阶段 2 实现）
    public static final String BUTTON = "button";
    public static final String INPUT = "input";
    public static final String SWITCH = "switch";
    public static final String CHECKBOX = "checkbox";
    public static final String SLIDER = "slider";
    // P3 增量 / T3 窗口原语（阶段 4 实现）
    public static final String TEXT_CHUNKS = "text.chunks";
    public static final String ABSOLUTE = "absolute";
    public static final String WINDOW = "window";
    public static final String TASKBAR = "taskbar";

    /** 单帧最多渲染多少节点：插件/主题的树不能把主线程拖死。 */
    public static final int MAX_NODES = 200;
    /**
     * {@code list} 的 items 单独计数：虚拟化后宿主只挂可见行，节点总数不再等于 View 数。
     * 这两项限制的是解析期内存，不是渲染量。
     */
    public static final int MAX_LIST_ITEMS = 1000;
    public static final int MAX_LIST_NODES = 4000;
    /** 最大嵌套层数。 */
    public static final int MAX_DEPTH = 8;
    /** 单个文本节点上限，超出截断。 */
    public static final int MAX_TEXT = 4096;

    private static final Set<String> KNOWN_TYPES = Collections.synchronizedSet(
            new HashSet<>(Arrays.asList(
                    COLUMN, ROW, HSCROLL, CARD, TEXT, CODE, MARKDOWN, DIVIDER,
                    BADGE, SPACER, PROGRESS, BARS, IMAGE, LIST, NAV_LIST,
                    BUTTON, INPUT, SWITCH, CHECKBOX, SLIDER,
                    TEXT_CHUNKS, ABSOLUTE, WINDOW, TASKBAR)));

    /** 外部组件注册时同步进白名单，让 {@code fromJson} 也能接受这个 type。 */
    public static void registerType(String type) {
        if (type != null && !type.isEmpty()) KNOWN_TYPES.add(type);
    }

    private final String type;
    private final String key;
    private final Map<String, Object> props;
    private final List<UiNode> children;

    private UiNode(Builder b) {
        this.type = b.type;
        this.key = b.key;
        this.props = Collections.unmodifiableMap(new LinkedHashMap<>(b.props));
        this.children = Collections.unmodifiableList(new ArrayList<>(b.children));
    }

    public static boolean isKnownType(String type) {
        return KNOWN_TYPES.contains(type);
    }

    /** JSON → 节点树的解析报告（裁剪/丢弃都留痕，不静默）。 */
    public static final class ParseReport {
        public final List<String> warnings = new ArrayList<>();
        public int nodes;
        /** list items 内部的节点数（不计入 {@link #nodes}）。 */
        public int listNodes;
        public int dropped;
        /** 解析 list items 期间为 true，节点预算切到 MAX_LIST_NODES。 */
        boolean inList;
        private final java.util.Set<String> warned = new java.util.HashSet<>();

        public boolean clean() {
            return warnings.isEmpty() && dropped == 0;
        }

        /** 同类警告只记一次，避免大列表被裁剪时刷屏。 */
        void warn(String text) {
            if (warned.add(text)) warnings.add(text);
        }
    }

    /**
     * 把插件/主题返回的普通 JSON 对象转成节点树。
     *
     * 这是视图 DSL 的**唯一入口**：type 白名单、节点数/深度/文本上限都在这里兜底，
     * 不让不可信数据直接进渲染器。未知 type 整棵子树丢弃；未知字段原样保留在 props 里
     * （组件自己决定用不用），不报错。
     */
    public static UiNode fromJson(JSONObject json) {
        return fromJson(json, new ParseReport());
    }

    public static UiNode fromJson(JSONObject json, ParseReport report) {
        Object v = fromJsonValue(json, report);
        return v instanceof Map ? fromJava(asStringMap(v), report) : null;
    }

    /** 纯 Java 入口：已经是 Map/List 的数据直接走这里，不需要 org.json（JVM 单测用它）。 */
    public static UiNode fromJava(Map<String, Object> map) {
        return fromJava(map, new ParseReport());
    }

    public static UiNode fromJava(Map<String, Object> map, ParseReport report) {
        return parse(map, report, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object v) {
        return (Map<String, Object>) v;
    }

    private static UiNode parse(Map<String, Object> json, ParseReport report, int depth) {
        if (json == null) return null;
        if (depth > MAX_DEPTH) {
            report.dropped++;
            report.warn("嵌套超过 " + MAX_DEPTH + " 层，子树丢弃");
            return null;
        }
        int nodeLimit = report.inList ? MAX_LIST_NODES : MAX_NODES;
        int nodeUsed = report.inList ? report.listNodes : report.nodes;
        if (nodeUsed >= nodeLimit) {
            report.dropped++;
            report.warn(report.inList
                    ? "list 节点超过 " + MAX_LIST_NODES + " 个，子树丢弃"
                    : "节点超过 " + MAX_NODES + " 个，子树丢弃");
            return null;
        }
        String type = strVal(json.get("type"), "");
        if (!isKnownType(type)) {
            report.dropped++;
            report.warn("未知 type: " + type + "，子树丢弃");
            return null;
        }
        if (report.inList) report.listNodes++;
        else report.nodes++;

        Builder b = of(type);
        String key = strVal(json.get("key"), "");
        if (key.isEmpty()) key = strVal(json.get("id"), "");
        if (!key.isEmpty()) b.key(key);

        for (Map.Entry<String, Object> e : json.entrySet()) {
            String k = e.getKey();
            // id 既可能当 key 用，也要留给渲染器设置 Binding.id（事件回传/局部 patch 用），
            // 所以不能像 type/key 那样丢弃。
            if ("type".equals(k) || "key".equals(k)
                    || "children".equals(k) || "items".equals(k)) continue;
            Object v = fromJsonValue(e.getValue(), report);
            if (v != null) b.prop(k, v);
        }

        // list 的 items 展开成 children，让它们走框架统一的按 key diff
        Object children = json.get("children");
        Object items = json.get("items");
        String keyField = strVal(json.get("keyField"), "key");
        // 同一父节点下 key 必须唯一：重复会让 diff 复用错行、find/patch 只能命中第一条。
        Set<String> usedKeys = new HashSet<>();
        if (LIST.equals(type) && (items instanceof List || children instanceof List)) {
            // list 的 items 展开成 children；children 是兼容别名。两者都走列表预算
            // （MAX_LIST_ITEMS / MAX_LIST_NODES），不能落回普通 200 节点预算，
            // 否则 500 行的大列表会被静默截成 99 行。同时存在时以 items 为准。
            parseListItems(b, (List<?>) (items instanceof List ? items : children),
                    keyField, depth, report, usedKeys);
        } else if (children instanceof List) {
            int index = 0;
            for (Object child : (List<?>) children) {
                UiNode c = parse(asStringMapOrNull(child), report, depth + 1);
                if (c != null) b.child(uniqueChild(c, index, usedKeys));
                index++;
            }
        }
        return b.build();
    }

    /** list 的 items/children：列表预算、按 keyField 补 key、超过 MAX_LIST_ITEMS 截断。 */
    private static void parseListItems(UiNode.Builder b, List<?> list, String keyField,
                                       int depth, ParseReport report, Set<String> usedKeys) {
        boolean outerInList = report.inList;
        report.inList = true;
        int kept = 0;
        try {
            for (int i = 0; i < list.size(); i++) {
                if (kept >= MAX_LIST_ITEMS) {
                    report.dropped += list.size() - i;
                    report.warn("list 超过 " + MAX_LIST_ITEMS + " 项，其余丢弃");
                    break;
                }
                Object raw = list.get(i);
                UiNode c;
                Map<String, Object> item = asStringMapOrNull(raw);
                if (item != null) {
                    c = parse(item, report, depth + 1);
                    if (c != null && c.key() == null) {
                        String k = strVal(item.get(keyField), "");
                        c = withKey(c, k.isEmpty() ? "#" + i : k);
                    }
                } else {
                    c = of(TEXT).prop("text", String.valueOf(raw)).key("#" + i).build();
                    report.listNodes++;
                }
                if (c != null) {
                    b.child(uniqueChild(c, i, usedKeys));
                    kept++;
                }
            }
        } finally {
            report.inList = outerInList;
        }
    }

    /** 同父重复 key 追加下标后缀（如 {@code x#3}），保证行身份稳定且可被 find/patch 命中。 */
    private static UiNode uniqueChild(UiNode c, int index, Set<String> used) {
        if (c.key() == null) return c;
        String key = uniqueKey(c.key(), index, used);
        return key.equals(c.key()) ? c : withKey(c, key);
    }

    private static String uniqueKey(String key, int index, Set<String> used) {
        if (used.add(key)) return key;
        String candidate = key + "#" + index;
        int n = 1;
        while (!used.add(candidate)) candidate = key + "#" + index + "-" + (n++);
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMapOrNull(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String strVal(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    /** 给已构建的节点补一个 key（UiNode 不可变，只能重建）。 */
    private static UiNode withKey(UiNode n, String key) {
        Builder b = of(n.type()).key(key);
        for (Map.Entry<String, Object> e : n.props().entrySet()) b.prop(e.getKey(), e.getValue());
        for (UiNode c : n.children()) b.child(c);
        return b.build();
    }

    /** org.json → 纯 Java 值；JSONObject/JSONArray 转成 Map/List，避免把 org.json 对象漏进 props。 */
    private static Object fromJsonValue(Object v, ParseReport report) {
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Map<String, Object> m = new LinkedHashMap<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object child = fromJsonValue(o.opt(k), report);
                if (child != null) m.put(k, child);
            }
            return m;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                Object child = fromJsonValue(a.opt(i), report);
                if (child != null) out.add(child);
            }
            return out;
        }
        if (v instanceof String) {
            String s = (String) v;
            if (s.length() > MAX_TEXT) {
                report.warn("文本超过 " + MAX_TEXT + " 字，已截断");
                return s.substring(0, MAX_TEXT) + "…";
            }
            return s;
        }
        return v;
    }

    public static Builder of(String type) {
        return new Builder(type);
    }

    public static Builder column() {
        return of(COLUMN);
    }

    public static Builder row() {
        return of(ROW);
    }

    public static Builder card() {
        return of(CARD);
    }

    public static Builder text(String text) {
        return of(TEXT).prop("text", text == null ? "" : text);
    }

    public static Builder code(String body) {
        return of(CODE).prop("body", body == null ? "" : body);
    }

    public static Builder markdown(String md) {
        return of(MARKDOWN).prop("text", md == null ? "" : md);
    }

    public static Builder divider() {
        return of(DIVIDER);
    }

    public String type() {
        return type;
    }

    /** 显式 key；没有则为 null，由渲染器按位置补一个。 */
    public String key() {
        return key;
    }

    public Map<String, Object> props() {
        return props;
    }

    public List<UiNode> children() {
        return children;
    }

    public String str(String name, String def) {
        Object v = props.get(name);
        return v == null ? def : String.valueOf(v);
    }

    public int integer(String name, int def) {
        Object v = props.get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public boolean bool(String name, boolean def) {
        Object v = props.get(name);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    public static final class Builder {
        private final String type;
        private String key;
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<UiNode> children = new ArrayList<>();

        private Builder(String type) {
            this.type = type;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder prop(String name, Object value) {
            if (name != null && value != null) props.put(name, value);
            return this;
        }

        public Builder child(UiNode node) {
            if (node != null) children.add(node);
            return this;
        }

        public Builder child(Builder builder) {
            return builder == null ? this : child(builder.build());
        }

        public Builder children(List<UiNode> nodes) {
            if (nodes != null) {
                for (UiNode n : nodes) if (n != null) children.add(n);
            }
            return this;
        }

        public UiNode build() {
            if (type == null || type.isEmpty()) {
                throw new IllegalArgumentException("node type 不能为空");
            }
            return new UiNode(this);
        }
    }
}
