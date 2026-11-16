package com.dsh.android.ui.framework;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link UiNode} 树渲染成原生 View，并按 key 做增量更新。
 *
 * 三段式：
 *  1. {@link #createView} 只在"类型或 key 变了"时执行 —— 流式追加时视图会被复用；
 *  2. {@link #applyProps} 只改变化的属性（文本、颜色、字号），不改结构；
 *  3. {@link #syncChildren} 按 key 对子节点做增删/排序，只有结构真的变了才 removeAllViews。
 *
 * 组件通过 {@link #register(String, UiComponent)} 注册：框架不认识具体控件，只调
 * {@link UiComponent#create} / {@link UiComponent#apply} / {@link UiComponent#isContainer}。
 * 内置组件在构造时由 {@link Builtins#install} 装好。
 *
 * 约束（都写在这里，不靠调用方自觉）：
 *  - 单帧最多 {@link UiNode#MAX_NODES} 个节点、{@link UiNode#MAX_DEPTH} 层，超出部分丢弃并记 skipped；
 *  - 单个文本节点超过 {@link UiNode#MAX_TEXT} 字会被截断，并记一次 warning；
 *  - 只能在主线程渲染（View 不是线程安全的）。
 *
 * 分阶段：阶段 1 组件注册表；阶段 2 事件/动作/状态绑定；阶段 3 主题；阶段 4 流式与窗口原语。
 */
public final class UiRenderer {

    /** 一帧的统计。只在 render() 返回后读，下一帧会覆盖。 */
    public static final class Stats {
        public int created;
        public int reused;
        public int skipped;
        public int nodes;
        public double lastMs;
        public String warning;

        void reset() {
            created = 0;
            reused = 0;
            skipped = 0;
            nodes = 0;
            warning = null;
        }

        @Override
        public String toString() {
            return "created=" + created + " reused=" + reused + " skipped=" + skipped
                    + " nodes=" + nodes + " last=" + String.format(java.util.Locale.ROOT, "%.2f", lastMs) + "ms"
                    + (warning == null ? "" : "  ⚠ " + warning);
        }
    }

    /** 一个节点当前绑定的 View。package-private：交互层（事件/状态/动作）要读它。 */
    static final class Binding {
        final String type;
        final String key;
        final View view;
        UiNode node;
        String id;
        Binding parent;
        Map<String, Object> props = Collections.emptyMap();
        List<Binding> children = Collections.emptyList();
        boolean layoutDirty;
        String layoutKey = "";
        /** list 虚拟化状态；其它类型为 null。 */
        VirtualList virtual;

        Binding(String type, String key, View view) {
            this.type = type;
            this.key = key;
            this.view = view;
        }
    }

    /** 给没有 key 的节点复制一份并指定 key（虚拟列表行用原始下标保证窗口滚动时可追踪）。 */
    private static UiNode keyed(UiNode node, String key) {
        UiNode.Builder b = UiNode.of(node.type()).key(key);
        for (Map.Entry<String, Object> e : node.props().entrySet()) b.prop(e.getKey(), e.getValue());
        for (UiNode c : node.children()) b.child(c);
        return b.build();
    }

    /** 组件能用的宿主能力；渲染是主线程串行，current 指向正在 apply 的 binding。 */
    private final UiComponent.Env env = new UiComponent.Env() {
        @Override
        public Context context() {
            return ctx;
        }

        @Override
        public TokenResolver theme() {
            return theme;
        }

        @Override
        public UiAssets assets() {
            return assets;
        }

        @Override
        public void warn(String text) {
            stats.warning = text;
        }

        @Override
        public String clip(String text) {
            return clipText(text);
        }

        @Override
        public void markLayoutDirty() {
            if (current != null) current.layoutDirty = true;
        }
    };

    private final Context ctx;
    private final ViewGroup container;
    private final Stats stats = new Stats();
    private final UiComponentRegistry registry = new UiComponentRegistry();
    private TokenResolver theme;
    private UiMotion motion = UiMotion.defaults(false);
    private UiAssets assets;
    private Binding root;
    private Binding current;
    /** 滚动虚拟化需要重渲染时回到 UiFramework（它持有 lastRoot 与事件分发器）。 */
    private Runnable rerenderRequest;
    /** 虚拟列表行是“滚动时新挂”的，不做入场动画，否则每滑一屏都在闪。 */
    private boolean virtualizing;

    /** 少于这么多行不值得虚拟化，直接按普通容器渲染。 */
    private static final int VIRTUAL_MIN_ITEMS = 30;
    /** 首帧/未测量时先挂这么多行。 */
    private static final int VIRTUAL_INITIAL_ROWS = 20;
    /** 视口上下各多挂一段，减少快速滚动时的白屏。 */
    private static final int VIRTUAL_OVERSCAN_DP = 480;
    private static final String VIRTUAL_TOP_KEY = "_virt:top";
    private static final String VIRTUAL_BOTTOM_KEY = "_virt:bottom";

    /** 一个 list 的窗口状态；只在主线程读写。 */
    private static final class VirtualList {
        /** 项数变化时原地换数组（保留已测行高），监听器里捕获的 vs 引用也不会失效。 */
        int[] heights;
        int estimatePx;
        /** 未测量行的默认估算（56dp + gap）；首行量到真实高度后改用实测均值。 */
        int defaultEstimatePx;
        /** 列表 gap 换算成像素；行高按“行 + gap”计，滚动偏移才不会被 gap 累积误差带偏。 */
        int gapPx;
        int start = -1;
        int end = -1;
        boolean pending;
        /** 外层 ScrollView（有就用 OnScrollChanged，比 pre-draw 便宜）。 */
        ScrollView scroll;
        android.view.ViewTreeObserver.OnScrollChangedListener listener;
        /** 没有 ScrollView 时的兜底：每帧 pre-draw 检查可视区是否移动。 */
        android.view.ViewTreeObserver.OnPreDrawListener preDraw;
        /** list 自带 height 时，scrollTo 会触发它。 */
        android.view.View.OnScrollChangeListener selfListener;
        List<String> visibleKeys = Collections.emptyList();
        /** pre-draw / scan 热路径复用，避免每帧新建。 */
        final int[] location = new int[2];
        final android.graphics.Rect visibleRect = new android.graphics.Rect();

        VirtualList(int count) {
            heights = new int[count];
        }
    }

    public UiRenderer(ViewGroup container) {
        this.container = container;
        this.ctx = container.getContext();
        this.theme = new DefaultTokenResolver(ctx);
        this.assets = new UiAssets(new java.io.File(ctx.getFilesDir(), "plugins"));
        Builtins.install(this);
    }

    public Stats stats() {
        return stats;
    }

    Context context() {
        return ctx;
    }

    public TokenResolver theme() {
        return theme;
    }

    /** 动效：只作用于新建的 View；系统「移除动画」时为 none，见 {@link UiMotion}。 */
    public void setMotion(UiMotion motion) {
        this.motion = motion == null ? UiMotion.defaults(false) : motion;
    }

    public UiMotion motion() {
        return motion;
    }

    public UiAssets assets() {
        return assets;
    }

    /** 主题/插件安装后把资源目录换掉；不设则指向 {@code files/plugins}。 */
    public void setAssets(UiAssets assets) {
        this.assets = assets == null ? new UiAssets(new java.io.File(ctx.getFilesDir(), "plugins"))
                : assets;
    }

    /** UiFramework 注入：滚动窗口变化时重渲染当前树（diff 会复用已挂载的行）。 */
    public void setRerenderRequest(Runnable request) {
        this.rerenderRequest = request;
    }

    private void requestRender() {
        Runnable r = rerenderRequest;
        if (r != null) container.post(r);
    }

    /**
     * 切换主题：只重解析所有 props（清空缓存让组件重新 apply），**不重建节点树、不新建 View**。
     */
    public void setTheme(TokenResolver theme) {
        if (theme == null) return;
        this.theme = theme;
        forEachBinding(b -> {
            b.props = Collections.emptyMap();
            UiComponent comp = registry.get(b.type);
            if (comp != null && b.node != null) {
                current = b;
                applyProps(comp, b, b.node);
            }
        });
    }

    /** 注册（或覆盖）一个节点类型。同步把 type 加进 UiNode 白名单，让 fromJson 也接受它。 */
    public void register(String type, UiComponent component) {
        registry.register(type, component);
    }

    public boolean hasComponent(String type) {
        return registry.has(type);
    }

    public UiComponentRegistry registry() {
        return registry;
    }

    /** 渲染一帧。root 变化时复用已有 View，只 patch 差异。 */
    public Stats render(UiNode root) {
        requireMainThread("render");
        long t0 = System.nanoTime();
        stats.reset();
        if (root == null) {
            cleanupBinding(this.root);
            container.removeAllViews();
            this.root = null;
            UiSelection.sync(this);
            return stats;
        }
        int[] budget = new int[]{UiNode.MAX_NODES};
        Binding oldRoot = this.root;
        Binding next = sync(root, oldRoot, 0, budget, "root");
        if (next == null) {
            // 根节点因未知 type / 预算被整棵丢弃：旧绑定和容器里的旧 View 必须一起清掉，
            // 否则界面上会残留上一帧的内容。
            cleanupBinding(oldRoot);
            container.removeAllViews();
            this.root = null;
            stats.lastMs = (System.nanoTime() - t0) / 1_000_000.0;
            UiSelection.sync(this);
            return stats;
        }
        // sync 为 root 建的是一棵独立的 View（带自己的 orientation/背景）；
        // 它必须挂到调用方给的 container 上，否则整棵树渲染在游离视图里。
        if (next.view.getParent() != container) {
            container.removeAllViews();
            container.addView(next.view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (oldRoot != null && oldRoot != next) cleanupBinding(oldRoot);
        this.root = next;
        stats.lastMs = (System.nanoTime() - t0) / 1_000_000.0;
        // select 属性：装选择手势；重渲染的节点会清掉落在它上面的旧选择
        UiSelection.sync(this);
        return stats;
    }

    /** 选择层用：判断哪些选择需要随这次重渲染作废。 */
    ViewGroup container() {
        return container;
    }

    /** 清空并丢弃所有绑定。 */
    public void clear() {
        cleanupBinding(root);
        container.removeAllViews();
        root = null;
    }

    private static void requireMainThread(String what) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("UiRenderer." + what + "() 必须在主线程");
        }
    }

    /** 交互层用的绑定树访问器。 */
    public interface BindingVisitor {
        void visit(Binding binding);
    }

    Binding rootBinding() {
        return root;
    }

    void forEachBinding(BindingVisitor visitor) {
        walk(root, visitor);
    }

    private static void walk(Binding b, BindingVisitor visitor) {
        if (b == null) return;
        visitor.visit(b);
        for (Binding c : b.children) walk(c, visitor);
    }

    Binding findBindingByView(View view) {
        return findByView(root, view);
    }

    private static Binding findByView(Binding b, View view) {
        if (b == null) return null;
        if (b.view == view) return b;
        for (Binding c : b.children) {
            Binding r = findByView(c, view);
            if (r != null) return r;
        }
        return null;
    }

    Binding findBinding(String key) {
        if (key == null) return null;
        return find(root, key);
    }

    private static Binding find(Binding b, String key) {
        if (b == null) return null;
        if (key.equals(b.key) || key.equals(b.id)) return b;
        for (Binding c : b.children) {
            Binding r = find(c, key);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * 局部更新：只重新 apply 这个节点的属性，不重建父树、不重排子节点。
     * 状态绑定（≤10 次/秒）和事件后的开关/进度/选中态走这条路径。
     */
    public boolean patch(String key, Map<String, Object> patch) {
        requireMainThread("patch");
        Binding b = findBinding(key);
        if (b == null || b.node == null || patch == null || patch.isEmpty()) return false;
        UiComponent comp = registry.get(b.type);
        if (comp == null) return false;

        UiNode.Builder nb = UiNode.of(b.node.type());
        if (b.node.key() != null) nb.key(b.node.key());
        Map<String, Object> merged = new LinkedHashMap<>(b.node.props());
        merged.putAll(patch);
        for (Map.Entry<String, Object> e : merged.entrySet()) nb.prop(e.getKey(), e.getValue());
        for (UiNode c : b.node.children()) nb.child(c);

        UiNode node = nb.build();
        b.node = node;
        current = b;
        applyProps(comp, b, node);
        return true;
    }

    private Binding sync(UiNode node, Binding old, int depth, int[] budget, String fallbackKey) {
        UiComponent comp = registry.get(node.type());
        if (depth > UiNode.MAX_DEPTH || budget[0] <= 0 || comp == null) {
            stats.skipped++;
            return null;
        }
        budget[0]--;
        stats.nodes++;

        String key = node.key() == null ? fallbackKey : node.key();
        Binding b;
        if (old != null && old.type.equals(node.type()) && old.key.equals(key)) {
            b = old;
            stats.reused++;
        } else {
            current = null;
            b = new Binding(node.type(), key, comp.create(ctx, node, env));
            stats.created++;
            animateEnter(b.view);
        }
        current = b;
        b.node = node;
        Object idVal = node.props().get("id");
        b.id = idVal == null ? null : String.valueOf(idVal);
        applyProps(comp, b, node);
        // select 要在子节点创建之前生效：include() 向上找范围时才能停在正确的层级
        UiSelection.bind(node, b.view);
        syncChildren(b, node, depth, budget);
        return b;
    }

    private void syncChildren(Binding b, UiNode node, int depth, int[] budget) {
        List<UiNode> kids = node.children();
        UiComponent comp = registry.get(b.type);
        if (comp == null || !comp.isContainer()) {
            if (!kids.isEmpty()) stats.skipped += kids.size();
            return;
        }
        if (UiNode.LIST.equals(node.type()) && kids.size() >= VIRTUAL_MIN_ITEMS) {
            VirtualList vs = b.virtual;
            if (vs == null) {
                vs = new VirtualList(kids.size());
                vs.estimatePx = theme.dp(ListWindow.DEFAULT_ESTIMATE_DP);
                b.virtual = vs;
            } else if (vs.heights.length != kids.size()) {
                // 项数变化时只换高度数组并保留窗口位置：直接 new VirtualList 会回到顶部、
                // 已测行高全部清零，列表会闪一下并触发一次全量重渲染。
                vs.heights = resizeHeights(vs.heights, kids.size());
            }
            syncVirtualList(b, node, kids, depth, budget, vs);
            return;
        }
        if (b.virtual != null) {
            // 项数跌回阈值以下：解绑滚动/预绘制监听，避免每帧空转。
            detachVirtualListener(b);
            b.virtual = null;
        }
        syncKids(b, node, kids, depth, budget);
    }

    /** 普通容器的子节点对账；虚拟列表也复用这段（只是传入的是窗口内的子集）。 */
    private void syncKids(Binding b, UiNode node, List<UiNode> kids, int depth, int[] budget) {
        UiComponent comp = registry.get(b.type);
        ViewGroup vg = comp == null ? null : comp.contentView(b.view);
        if (vg == null) return;
        List<Binding> old = b.children;

        // 对账计划由纯 Java 的 UiDiff 算，View 的建/取只按计划执行。
        List<UiDiff.Item> oldItems = new ArrayList<>(old.size());
        for (Binding o : old) oldItems.add(new UiDiff.Item(o.type, o.key));
        List<UiDiff.Item> want = new ArrayList<>(kids.size());
        List<String> keys = new ArrayList<>(kids.size());
        for (int i = 0; i < kids.size(); i++) {
            UiNode kid = kids.get(i);
            String key = kid.key() == null ? "#" + i + ":" + kid.type() : kid.key();
            keys.add(key);
            want.add(new UiDiff.Item(kid.type(), key));
        }
        UiDiff.Plan plan = UiDiff.plan(oldItems, want);

        // 虚拟列表顶部 spacer 的像素高度已包含首行之前的 gap，首行不能再叠 topMargin。
        boolean virtTopSpacer = virtualizing && b.virtual != null
                && UiNode.LIST.equals(b.type) && !kids.isEmpty()
                && VIRTUAL_TOP_KEY.equals(kids.get(0).key());
        List<Binding> next = new ArrayList<>(plan.steps.size());
        boolean skippedAny = false;
        for (UiDiff.Step step : plan.steps) {
            int i = step.desiredIndex;
            Binding match = step.oldIndex >= 0 ? old.get(step.oldIndex) : null;
            Binding nb = sync(kids.get(i), match, depth + 1, budget, keys.get(i));
            if (nb == null) skippedAny = true;       // 预算/深度裁掉的节点
            else {
                nb.parent = b;
                next.add(nb);
            }
        }

        String layoutKey = layoutKey(b, next);
        boolean structural = plan.structural || skippedAny || b.layoutDirty
                || !layoutKey.equals(b.layoutKey);
        if (structural) {
            vg.removeAllViews();
            for (int i = 0; i < next.size(); i++) {
                ViewGroup.LayoutParams lp = comp.layoutParams(ctx, node, kids.get(i), i, env);
                if (lp == null) lp = layoutParams(b, next.get(i), i);
                // spacer 高度已经是 offsetOf(start)，首行再加 gap 会双计，滚动偏移与内容高度对不上。
                if (virtTopSpacer && i == 1 && lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).topMargin = 0;
                }
                vg.addView(next.get(i).view, lp);
            }
            b.layoutDirty = false;
        } else {
            // 结构没变时，自定义容器（absolute 等）的 x/y/w/h 仍可能变：只更新有差异的
            // LayoutParams，避免每帧 setLayoutParams 触发无意义的重排。
            for (int i = 0; i < next.size(); i++) {
                ViewGroup.LayoutParams lp = comp.layoutParams(ctx, node, kids.get(i), i, env);
                if (lp != null && !sameLayoutParams(next.get(i).view.getLayoutParams(), lp)) {
                    next.get(i).view.setLayoutParams(lp);
                }
            }
        }
        for (Binding o : old) {
            if (!next.contains(o)) cleanupBinding(o);
        }
        b.layoutKey = layoutKey;
        b.children = next;
    }

    // ---------------------------------------------------------------- list 虚拟化

    private void syncVirtualList(Binding b, UiNode node, List<UiNode> kids, int depth,
                                 int[] budget, VirtualList vs) {
        int count = kids.size();
        Object gapToken = node.props().get("gap");
        int gapPx = theme.dp(theme.spaceDp(gapToken == null ? "none" : String.valueOf(gapToken)));
        int defaultEstimate = theme.dp(ListWindow.DEFAULT_ESTIMATE_DP) + gapPx;
        if (vs.gapPx != gapPx) {
            vs.gapPx = gapPx;
            vs.defaultEstimatePx = defaultEstimate;
            vs.estimatePx = defaultEstimate;
            java.util.Arrays.fill(vs.heights, 0);
        } else if (vs.defaultEstimatePx == 0) {
            vs.defaultEstimatePx = defaultEstimate;
        }
        if (vs.start < 0) {
            vs.start = 0;
            vs.end = Math.min(count, VIRTUAL_INITIAL_ROWS);
        }
        // 项数变化只夹紧窗口，不回顶：否则流式追加/删除时列表会跳回开头。
        clampWindow(vs, count);

        int above = ListWindow.offsetOf(vs.heights, count, vs.estimatePx, vs.start);
        int below = ListWindow.totalHeight(vs.heights, count, vs.estimatePx)
                - ListWindow.offsetOf(vs.heights, count, vs.estimatePx, vs.end);

        List<UiNode> shown = new ArrayList<>(vs.end - vs.start + 2);
        // 高度为 0 的占位不挂 View：列表在顶部时不能凭空多出一个 gap。
        if (above > 0) {
            shown.add(UiNode.of(UiNode.SPACER).key(VIRTUAL_TOP_KEY)
                    .prop("heightPx", above).build());
        }
        List<String> visibleKeys = new ArrayList<>(vs.end - vs.start);
        for (int i = vs.start; i < vs.end; i++) {
            UiNode kid = kids.get(i);
            String key = kid.key() == null ? "#" + i + ":" + kid.type() : kid.key();
            // 行没有 key 时显式补一个「原始下标」key：同步进 shown 的 index 会因顶部 spacer 偏移，
            // 用 shown 下标会让 diff/测高找不到同一行（窗口一滚就错位）。
            shown.add(kid.key() == null ? keyed(kid, key) : kid);
            visibleKeys.add(key);
        }
        if (below > 0) {
            shown.add(UiNode.of(UiNode.SPACER).key(VIRTUAL_BOTTOM_KEY)
                    .prop("heightPx", below).build());
        }
        vs.visibleKeys = visibleKeys;
        vs.pending = false;

        boolean prevVirtual = virtualizing;
        virtualizing = true;
        try {
            syncKids(b, node, shown, depth, budget);
        } finally {
            virtualizing = prevVirtual;
        }
        // 预算裁剪只会砍掉窗口尾部：本帧就把窗口收掉并修正底部 spacer，
        // 否则 spacer 仍按原 end 计高，最后一行之后会露出一片空洞。
        int mounted = mountedRows(b, visibleKeys);
        if (mounted > 0 && mounted < visibleKeys.size()) {
            vs.end = vs.start + mounted;
            visibleKeys = new ArrayList<>(visibleKeys.subList(0, mounted));
            vs.visibleKeys = visibleKeys;
            Binding bottom = find(b, VIRTUAL_BOTTOM_KEY);
            if (bottom != null) {
                int rest = ListWindow.totalHeight(vs.heights, count, vs.estimatePx)
                        - ListWindow.offsetOf(vs.heights, count, vs.estimatePx, vs.end);
                bottom.view.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, rest)));
            }
        }
        final int start = vs.start;
        final List<String> keys = vs.visibleKeys;
        b.view.post(() -> afterVirtualLayout(b, vs, count, start, keys));
    }

    /** 项数变化时复制旧实测高度：保留已测行高，避免窗口整体按估算高度跳变。 */
    private static int[] resizeHeights(int[] old, int count) {
        int n = Math.max(0, count);
        if (old != null && old.length == n) return old;
        int[] next = new int[n];
        if (old != null && old.length > 0) {
            System.arraycopy(old, 0, next, 0, Math.min(old.length, n));
        }
        return next;
    }

    /** 项数变化后把窗口夹回 [0,count)，保留原位置而不是回顶。 */
    private static void clampWindow(VirtualList vs, int count) {
        if (count <= 0) {
            vs.start = 0;
            vs.end = 0;
            return;
        }
        vs.start = Math.max(0, Math.min(vs.start, count - 1));
        vs.end = Math.max(vs.start + 1, Math.min(vs.end, count));
    }

    /** 预算裁剪后窗口里实际挂上的行数（裁剪只会发生在尾部，所以按前缀数）。 */
    private static int mountedRows(Binding b, List<String> keys) {
        if (keys.isEmpty()) return 0;
        java.util.HashSet<String> mounted = new java.util.HashSet<>();
        for (Binding c : b.children) mounted.add(c.key);
        int n = 0;
        while (n < keys.size() && mounted.contains(keys.get(n))) n++;
        return n;
    }

    /** 布局完成后再量真实行高；viewport 可以是外层 ScrollView，也可以是 list 自己的固定高度。 */
    private void afterVirtualLayout(Binding b, VirtualList vs, int count,
                                    int start, List<String> keys) {
        if (b.virtual != vs || b.node == null) return;
        ensureScrollListener(b, vs);
        if (vs.start == start) {
            boolean changed = false;
            for (int j = 0; j < keys.size(); j++) {
                Binding cb = find(b, keys.get(j));
                if (cb == null) continue;
                int h = cb.view.getHeight() + vs.gapPx;
                int idx = start + j;
                if (idx < 0 || idx >= vs.heights.length || h <= 0) continue;
                if (Math.abs(vs.heights[idx] - h) > 1) {
                    vs.heights[idx] = h;
                    changed = true;
                }
            }
            if (changed) {
                recalibrateEstimate(b, vs, count, start);
                requestVirtualRender(vs);
            }
        }
        if (scanVirtual(b, vs, count)) requestVirtualRender(vs);
    }

    /**
     * 用已量到的行高均值替换默认估算（56dp + gap）。
     *
     * <p>估算偏差会被未测量的行放大：500 行列表里每行差 1dp，底部就差 500dp，
     * 表现为「滑到底最后几行永远够不着」。首屏量到行高后立刻校准，后续滚动的
     * 顶/底 spacer 都按真实高度算。校准导致首可见行偏移变化时同步修正 scrollY，
     * 避免列表在手指下跳一下。
     */
    private void recalibrateEstimate(Binding b, VirtualList vs, int count, int start) {
        int next = ListWindow.estimateFromMeasured(vs.heights, vs.defaultEstimatePx);
        if (Math.abs(next - vs.estimatePx) <= 1) return;
        int before = ListWindow.offsetOf(vs.heights, count, vs.estimatePx, start);
        vs.estimatePx = next;
        int after = ListWindow.offsetOf(vs.heights, count, next, start);
        if (start > 0 && after != before) {
            int target = Math.max(0, b.view.getScrollY() + (after - before));
            b.view.scrollTo(0, target);
        }
    }

    /** 调试：把第一个虚拟 list 滚到指定像素（page:scroll 用）。 */
    public boolean debugScrollList(int y) {
        Binding b = findListBinding(root);
        if (b == null) return false;
        b.view.scrollTo(0, Math.max(0, y));
        if (b.virtual != null) scanVirtual(b, b.virtual, b.virtual.heights.length);
        requestRender();
        return true;
    }

    private static Binding findListBinding(Binding b) {
        if (b == null) return null;
        if (UiNode.LIST.equals(b.type) && b.virtual != null) return b;
        for (Binding c : b.children) {
            Binding r = findListBinding(c);
            if (r != null) return r;
        }
        return null;
    }

    /** 调试：第一个虚拟 list 的窗口/滚动/内容高度快照（page:dump 用）。 */
    public String debugListState() {
        return debugListState(root);
    }

    private String debugListState(Binding b) {
        if (b == null) return null;
        if (UiNode.LIST.equals(b.type) && b.virtual != null && b.view instanceof Builtins.ScrollList) {
            VirtualList vs = b.virtual;
            int measured = 0;
            for (int h : vs.heights) if (h > 0) measured++;
            int total = ListWindow.totalHeight(vs.heights, vs.heights.length, vs.estimatePx);
            return "list " + ((Builtins.ScrollList) b.view).debugState()
                    + " start=" + vs.start + " end=" + vs.end
                    + " items=" + vs.heights.length
                    + " nodeItems=" + (b.node == null ? -1 : b.node.children().size())
                    + " measured=" + measured
                    + " estimate=" + vs.estimatePx + " gap=" + vs.gapPx
                    + " total=" + total + " above=" + ListWindow.offsetOf(vs.heights, vs.heights.length, vs.estimatePx, vs.start)
                    + " below=" + (total - ListWindow.offsetOf(vs.heights, vs.heights.length, vs.estimatePx, vs.end));
        }
        for (Binding c : b.children) {
            String r = debugListState(c);
            if (r != null) return r;
        }
        return null;
    }

    /** 界面销毁/拔离窗口时断开滚动监听；重新 attach 后由 UiFramework 触发一次重渲染恢复。 */
    public void releaseVirtualListeners() {
        cleanupBinding(root);
    }

    /**
     * 视口 + overscan 换算成行窗口；返回 range 是否变化。
     *
     * <p>不再要求外层必须是 {@link ScrollView}：可视区用 {@code getGlobalVisibleRect}
     * （天然包含所有祖先的裁剪）算，配合 list 自己的 {@code scrollY}；
     * 外层是 ScrollView 时仍然优先用它的滚动条事件。
     */
    private boolean scanVirtual(Binding b, VirtualList vs, int count) {
        if (count != vs.heights.length) return false;
        int scrollY = b.view.getScrollY();
        int firstVisible;
        int viewport;
        int[] loc = vs.location;
        b.view.getLocationInWindow(loc);
        android.graphics.Rect vis = vs.visibleRect;
        if (b.view.getGlobalVisibleRect(vis) && vis.height() > 0) {
            firstVisible = vis.top - loc[1] + scrollY;
            int own = b.view.getHeight() > 0 ? b.view.getHeight() : vis.height();
            viewport = Math.min(vis.height(), own);
        } else {
            // 还没 attach / 完全滚出屏幕：用固定高度或保守的 480dp 视口。
            firstVisible = scrollY;
            viewport = Math.max(b.view.getHeight(), theme.dp(480));
        }
        if (viewport <= 0) viewport = Math.max(1, theme.dp(480));
        int over = theme.dp(VIRTUAL_OVERSCAN_DP);
        ListWindow.Range r = ListWindow.range(vs.heights, count, vs.estimatePx,
                firstVisible - over, firstVisible + viewport + over);
        if (r.start == vs.start && r.end == vs.end) return false;
        vs.start = r.start;
        vs.end = r.end;
        return true;
    }

    private void ensureScrollListener(Binding b, VirtualList vs) {
        // list 自带 height 时靠 scrollTo 滚动：监听它自己（API 23+）。
        if (vs.selfListener == null) {
            vs.selfListener = (v, sx, sy, ox, oy) -> {
                if (b.node == null) return;
                if (scanVirtual(b, vs, vs.heights.length)) requestVirtualRender(vs);
            };
            b.view.setOnScrollChangeListener(vs.selfListener);
        }
        ScrollView sv = findScrollParent(b.view);
        if (sv != null) {
            if (vs.listener != null && vs.scroll == sv) return;
            detachScrollListener(vs);
            detachPreDraw(b, vs);
            vs.scroll = sv;
            vs.listener = () -> {
                if (b.node == null) return;
                if (scanVirtual(b, vs, vs.heights.length)) requestVirtualRender(vs);
            };
            sv.getViewTreeObserver().addOnScrollChangedListener(vs.listener);
            return;
        }
        // 没有 ScrollView：可能是固定高度的 list 自己滚，也可能是浮层在移动；
        // pre-draw 每帧检查可视区，窗口没变时 scanVirtual 返回 false，不会反复重渲染。
        if (vs.preDraw != null) return;
        vs.preDraw = () -> {
            if (b.node != null && scanVirtual(b, vs, vs.heights.length)) requestVirtualRender(vs);
            return true;
        };
        b.view.getViewTreeObserver().addOnPreDrawListener(vs.preDraw);
    }

    private static ScrollView findScrollParent(View view) {
        android.view.ViewParent p = view.getParent();
        while (p instanceof View) {
            if (p instanceof ScrollView) return (ScrollView) p;
            p = ((View) p).getParent();
        }
        return null;
    }

    private void requestVirtualRender(VirtualList vs) {
        if (vs == null || vs.pending) return;
        vs.pending = true;
        requestRender();
    }

    private void detachVirtualListener(Binding b) {
        VirtualList v = b == null ? null : b.virtual;
        if (v == null) return;
        detachScrollListener(v);
        detachPreDraw(b, v);
        if (v.selfListener != null) {
            b.view.setOnScrollChangeListener(null);
            v.selfListener = null;
        }
    }

    private void detachScrollListener(VirtualList v) {
        if (v.listener != null && v.scroll != null) {
            try {
                v.scroll.getViewTreeObserver().removeOnScrollChangedListener(v.listener);
            } catch (Throwable ignored) {
            }
        }
        v.scroll = null;
        v.listener = null;
    }

    private void detachPreDraw(Binding b, VirtualList v) {
        if (v.preDraw == null) return;
        try {
            b.view.getViewTreeObserver().removeOnPreDrawListener(v.preDraw);
        } catch (Throwable ignored) {
        }
        v.preDraw = null;
    }

    private void cleanupBinding(Binding b) {
        if (b == null) return;
        for (Binding c : b.children) cleanupBinding(c);
        detachVirtualListener(b);
    }

    /** gap + 每个孩子的 grow：这两项变了就得重排，但不需要重建视图。 */
    private static String layoutKey(Binding parent, List<Binding> kids) {
        Object gap = parent.props.get("gap");
        StringBuilder sb = new StringBuilder(gap == null ? "none" : String.valueOf(gap));
        for (Binding k : kids) sb.append('|').append(k.props.get("grow"));
        return sb.toString();
    }

    /** 结构没变时判断几何是否真的变了（width/height/margins/gravity）。 */
    private static boolean sameLayoutParams(ViewGroup.LayoutParams a, ViewGroup.LayoutParams b) {
        if (a == null || b == null) return false;
        if (a.width != b.width || a.height != b.height) return false;
        if (a instanceof ViewGroup.MarginLayoutParams && b instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams ma = (ViewGroup.MarginLayoutParams) a;
            ViewGroup.MarginLayoutParams mb = (ViewGroup.MarginLayoutParams) b;
            if (ma.leftMargin != mb.leftMargin || ma.topMargin != mb.topMargin
                    || ma.rightMargin != mb.rightMargin || ma.bottomMargin != mb.bottomMargin) {
                return false;
            }
        }
        if (a instanceof android.widget.FrameLayout.LayoutParams
                && b instanceof android.widget.FrameLayout.LayoutParams) {
            return ((android.widget.FrameLayout.LayoutParams) a).gravity
                    == ((android.widget.FrameLayout.LayoutParams) b).gravity;
        }
        return true;
    }

    private LinearLayout.LayoutParams layoutParams(Binding parent, Binding child, int index) {
        boolean row = UiNode.ROW.equals(parent.type) || UiNode.HSCROLL.equals(parent.type);
        boolean grow = Boolean.TRUE.equals(child.props.get("grow"));
        int w = row ? (grow ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT)
                : ViewGroup.LayoutParams.MATCH_PARENT;
        int h = row ? ViewGroup.LayoutParams.WRAP_CONTENT
                : (grow ? 0 : fixedHeight(child));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, grow ? 1f : 0f);
        Object gapToken = parent.props.get("gap");
        int gap = theme.dp(theme.spaceDp(gapToken == null ? "none" : String.valueOf(gapToken)));
        if (index > 0) {
            if (row) lp.leftMargin = gap;
            else lp.topMargin = gap;
        }
        return lp;
    }

    /** divider 是 1dp 发丝线，spacer 按 token 给固定高度，list 的 height 是固定像素视口。 */
    private int fixedHeight(Binding child) {
        if (UiNode.DIVIDER.equals(child.type)) return theme.dp(1);
        if (UiNode.LIST.equals(child.type)) {
            Object h = child.props.get("height");
            if (h instanceof Number) return theme.dp(Math.max(1, ((Number) h).intValue()));
        }
        if (UiNode.SPACER.equals(child.type)) {
            // 虚拟列表的占位 spacer 用精确像素；普通 spacer 仍用 space token。
            Object px = child.props.get("heightPx");
            if (px instanceof Number) return Math.max(0, ((Number) px).intValue());
            Object space = child.props.get("space");
            return theme.dp(theme.spaceDp(space == null ? "sm" : String.valueOf(space)));
        }
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    /** 新建 View 的入场动效；spec 来自主题包，reduceMotion 时 {@link UiMotion#enabled()} 为假。 */
    private void animateEnter(View v) {
        if (virtualizing || !motion.enabled()) return;
        String name = motion.enter();
        long ms = motion.durationMs();
        if ("scale-in".equals(name)) {
            v.setAlpha(0f);
            v.setScaleX(0.96f);
            v.setScaleY(0.96f);
        } else if ("fade-rise".equals(name) || "slide-up".equals(name)) {
            v.setAlpha(0f);
            v.setTranslationY(theme.dp("slide-up".equals(name) ? 16 : 8));
        } else {
            v.setAlpha(0f);
        }
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ms)
                .setInterpolator("spring".equals(name)
                        ? new OvershootInterpolator(0.8f) : new DecelerateInterpolator())
                .start();
    }

    private void applyProps(UiComponent comp, Binding b, UiNode node) {
        comp.apply(ctx, b.view, node, b.props, env);
        // fallback:hide 的状态绑定由 UiStateBus.inject 注入 hidden prop；
        // GONE 的 View 在 LinearLayout 里不占空间，也就不需要改 LayoutParams。
        boolean hidden = node.bool("hidden", false);
        if ((b.view.getVisibility() == View.GONE) != hidden) {
            b.view.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
        // z 是 absolute / window 用的层叠高度（dp），与具体组件无关，统一在这里应用
        Object z = node.props().get("z");
        if (z != null) b.view.setElevation(theme.dp((float) nodeNumeric(z, 0)));
        b.props = new HashMap<>(node.props());
    }

    /** 流式追加：找到 key 对应的节点，把 delta 拼到 text 上，只 patch 这个节点。 */
    public boolean appendText(String key, String delta) {
        requireMainThread("appendText");
        Binding b = findBinding(key);
        if (b == null || b.node == null || delta == null || delta.isEmpty()) return false;
        String text = b.node.str("text", "") + delta;
        if (text.length() > UiNode.MAX_TEXT) {
            text = UiText.truncateHead(text, UiNode.MAX_TEXT);
        }
        Map<String, Object> patch = new HashMap<>();
        patch.put("text", text);
        return patch(key, patch);
    }

    private static double nodeNumeric(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private String clipText(String s) {
        if (s == null) return "";
        if (s.length() <= UiNode.MAX_TEXT) return s;
        stats.warning = "文本超过 " + UiNode.MAX_TEXT + " 字，已截断";
        return UiText.truncateTail(s, UiNode.MAX_TEXT) + "…";
    }
}
