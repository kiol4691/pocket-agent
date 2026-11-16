package com.dsh.android.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.android.R;
import com.dsh.android.plugin.HookManifest;
import com.dsh.android.plugin.HookRegistry;
import com.dsh.android.plugin.PageDecl;
import com.dsh.android.ui.framework.ChatComponents;
import com.dsh.android.ui.framework.HostState;
import com.dsh.android.ui.framework.NavModel;
import com.dsh.android.ui.framework.NavShell;
import com.dsh.android.ui.framework.UiFramework;
import com.dsh.android.ui.framework.UiNode;
import com.dsh.android.ui.framework.UiSelection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 插件 UI 的宿主编排器：把 {@code contributes.ui} / {@code contributes.pages} 接到已有 UI 框架上。
 *
 * 线程模型（重要）：
 *  - 视图渲染、Dialog、事件监听全部在主线程；
 *  - 插件 JS 一律投递到 {@link #worker} 线程池执行，结果 post 回主线程再渲染。
 *    同一插件的入口仍由 HookEngine 在它的 runtime 上串行；线程池只保证
 *    一个慢插件（长工具/慢页面）不会把其它插件的插槽和页面一起堵死。
 *
 * 语义：
 *  - 插槽按 priority→id 求值，第一个返回非空视图节点的插件生效；null/异常继续下一条；
 *  - 页面失败首帧显示错误卡片 + 重试，后续刷新失败保留现有内容；
 *  - 节点 {@code action: "fn"} 与 {@code on.tap: "fn"} 都回调插件，返回字符串 → toast，
 *    返回节点 → 替换当前插槽/页面内容。
 */
public final class PluginUiHost {

    public interface ToastSink {
        void show(String text);
    }

    /** 一个挂载点：插槽或页面内容区。字段只在主线程读写。 */
    private static final class Surface {
        final ViewGroup host;
        final String slot;               // 页面为 null
        UiFramework fw;
        /** 调用方给的额外 ctx（session 每次渲染时重新取，不能从这里复用）。 */
        JSONObject extraCtx = new JSONObject();
        JSONObject baseCtx = new JSONObject();
        HookManifest owner;
        int token;
        boolean alive = true;
        /** 有求值已投递、尚未回到主线程应用；刷新定时器据此做单飞。 */
        boolean pending;
        boolean renderedOnce;
        UiCall renderCall;
        Runnable refresh;

        Surface(ViewGroup host, String slot) {
            this.host = host;
            this.slot = slot;
        }
    }

    private interface UiCall {
        HookRegistry.UiRender run(HookRegistry registry, JSONObject ctx);
    }

    private final Activity activity;
    private final Supplier<HookRegistry> registry;
    private final Supplier<JSONObject> session;
    private final ToastSink toast;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * 插件 UI 求值线程池。不能是单线程：一个插件卡在慢 bridge 调用上时，
     * 其它插件的插槽/页面仍要能渲染；同一插件的 JS 由 HookEngine 的 runtime 锁串行。
     */
    private final ExecutorService worker = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
        private final java.util.concurrent.atomic.AtomicInteger seq =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "plugin-ui-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    /** 一个打开中的页面栈帧；params 用于 bridge.nav.current。 */
    private static final class PageFrame {
        final String id;
        final JSONObject params;

        PageFrame(String id, JSONObject params) {
            this.id = id;
            this.params = params == null ? new JSONObject() : params;
        }
    }

    private final Map<ViewGroup, Surface> slots = new LinkedHashMap<>();
    private final Map<ViewGroup, Surface> messageSlots = new LinkedHashMap<>();
    /** 打开中的页面（可叠加），用于 currentPage 回退与「回到聊天」关闭全部。 */
    private final java.util.ArrayDeque<PageFrame> pageStack = new java.util.ArrayDeque<>();
    private final List<Surface> pageSurfaces = new ArrayList<>();
    private final List<Dialog> pageDialogs = new ArrayList<>();
    /** bridge.nav.current 的无锁读侧。 */
    private volatile String currentPageId = NavModel.CHAT_PAGE;
    private volatile JSONObject currentPageParams = new JSONObject();
    /** release() 之后不再渲染，worker 已关闭。 */
    private volatile boolean released;
    private PluginActionBus actionBus;

    public PluginUiHost(Activity activity, Supplier<HookRegistry> registry,
                        Supplier<JSONObject> session, ToastSink toast) {
        this.activity = activity;
        this.registry = registry;
        this.session = session;
        this.toast = toast;
    }

    // ---------------------------------------------------------------- 插槽

    /** 挂载一个常驻插槽（首页/输入框上方/抽屉/设置页）。重复挂同一容器会替换旧绑定。 */
    public void attachSlot(ViewGroup host, String slot, JSONObject extraCtx) {
        if (host == null) return;
        detachSlot(host);
        Surface s = new Surface(host, slot);
        s.extraCtx = extraCtx == null ? new JSONObject() : extraCtx;
        s.baseCtx = baseCtx(s.extraCtx);
        setup(s);
        slots.put(host, s);
        renderSlot(s);
    }

    /** 设置页 Dialog 关闭时调用，避免宿主容器被长期持有。 */
    public void detachSlot(ViewGroup host) {
        if (host == null) return;
        Surface old = slots.remove(host);
        if (old != null) old.alive = false;
    }

    /** 会话切换、插件启停后刷新所有常驻插槽。 */
    public void refreshAll() {
        for (Surface s : new ArrayList<>(slots.values())) {
            s.baseCtx = baseCtx(s.extraCtx);
            renderSlot(s);
        }
    }

    /** 插件集合变化：刷新插槽并清掉已渲染的消息头/尾（它们的内容来自旧插件版本）。 */
    public void onPluginsChanged() {
        refreshAll();
        clearMessageSlots();
    }

    public void clearMessageSlots() {
        for (ViewGroup host : new ArrayList<>(messageSlots.keySet())) {
            detachMessageSlot(host);
        }
        messageSlots.clear();
    }

    /**
     * 单条消息气泡销毁时调用：从 messageSlots 摘掉绑定并清空视图，
     * 否则 map 会强引用已移出窗口的气泡容器。
     */
    public void detachMessageSlot(ViewGroup host) {
        if (host == null) return;
        Surface old = messageSlots.remove(host);
        if (old == null) return;
        old.alive = false;
        if (old.fw != null) old.fw.clear();
        old.host.removeAllViews();
    }

    /**
     * 宿主销毁（Activity onDestroy）时调用：关闭线程池、移除所有挂起的刷新/回调，
     * 并解绑所有插槽，避免宿主对象和视图树被泄漏。
     */
    public void release() {
        released = true;
        for (Surface s : new ArrayList<>(slots.values())) {
            s.alive = false;
            if (s.fw != null) s.fw.clear();
        }
        slots.clear();
        clearMessageSlots();
        for (Surface s : new ArrayList<>(pageSurfaces)) {
            s.alive = false;
            if (s.refresh != null) main.removeCallbacks(s.refresh);
        }
        pageSurfaces.clear();
        for (Dialog d : new ArrayList<>(pageDialogs)) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
        pageDialogs.clear();
        pageStack.clear();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    /**
     * 渲染一条消息的 header/footer。同一容器可重复调用（助手回复收尾时刷新内容）。
     */
    public void renderMessageSlot(ViewGroup host, String slot, JSONObject messageCtx) {
        if (host == null) return;
        Surface s = messageSlots.get(host);
        if (s == null) {
            s = new Surface(host, slot);
            setup(s);
            messageSlots.put(host, s);
        }
        s.extraCtx = messageCtx == null ? new JSONObject() : messageCtx;
        s.baseCtx = baseCtx(s.extraCtx);
        renderSlot(s);
    }

    private void renderSlot(Surface s) {
        submit(s, (reg, ctx) -> reg.renderSlot(s.slot, ctx));
    }

    // ---------------------------------------------------------------- 页面

    /** 当前可进入的插件页面（抽屉入口用）。 */
    public List<PageDecl> pages() {
        HookRegistry reg = registry.get();
        return reg == null ? new ArrayList<>() : reg.pages();
    }

    public void openPage(PageDecl page) {
        openPage(page, new JSONObject());
    }

    public void openPage(PageDecl page, JSONObject params) {
        if (activity.isFinishing()) return;

        Dialog dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(activity.getColor(R.color.bg_base));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(10), dp(8));

        TextView title = new TextView(activity);
        title.setText(page.title);
        title.setTextSize(18);
        title.setTextColor(activity.getColor(R.color.text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(title, tlp);

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(activity.getColor(R.color.text_secondary));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(4), dp(12), dp(4));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close);
        root.addView(header);

        View divider = new View(activity);
        divider.setBackgroundColor(activity.getColor(R.color.border_light));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        if (page.scroll) {
            ScrollView sv = new ScrollView(activity);
            sv.setFillViewport(true);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        int[] bounds = pageWindowBounds();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.bg_base)));
            w.setGravity(Gravity.TOP);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.TOP;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = Math.max(dp(240), bounds[1] - bounds[0]);
            lp.y = bounds[0];
            w.setAttributes(lp);
        }

        Surface s = new Surface(content, null);
        s.baseCtx = pageCtx(page, params);
        setup(s);
        pageSurfaces.add(s);
        final PageFrame frame = new PageFrame(page.fullId(), params);
        dialog.setOnDismissListener(d -> {
            s.alive = false;
            if (s.refresh != null) main.removeCallbacks(s.refresh);
            pageSurfaces.remove(s);
            pageDialogs.remove(dialog);
            pageStack.remove(frame);
            updateCurrentPage();
        });
        pageStack.push(frame);
        updateCurrentPage();
        pageDialogs.add(dialog);
        dialog.show();
        // 不同 ROM 的 Dialog 可用区域（是否扣掉顶栏/状态栏）不一致：show 后按实际 top
        // 重新收一次高度，保证底部停在主输入框上方，而不是被系统拉到屏幕底。
        if (w != null) {
            final Window window = w;
            w.getDecorView().post(() -> {
                int[] actual = new int[2];
                window.getDecorView().getLocationOnScreen(actual);
                int h = Math.max(dp(240), bounds[1] - actual[1]);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.height = h;
                window.setAttributes(lp);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h);
            });
        }

        UiCall call = (reg, ctx) -> reg.renderPage(page, ctx);
        submit(s, call);

        if (page.refreshMs > 0) {
            s.refresh = new Runnable() {
                @Override
                public void run() {
                    if (!s.alive || released) return;
                    // 单飞：上一次求值还没回来就跳过这一拍，慢插件不会在 worker 里堆积任务
                    if (!s.pending) {
                        JSONObject ctx = copy(s.baseCtx);
                        submit(s, (reg, ignored) -> reg.renderPage(page, ctx));
                    }
                    main.postDelayed(this, page.refreshMs);
                }
            };
            main.postDelayed(s.refresh, page.refreshMs);
        }
    }

    // ---------------------------------------------------------------- 框架接线

    /**
     * 插件页面窗口的上下边界（屏幕坐标）：对齐主界面的聊天滚动区，
     * 顶部不盖顶栏、底部不盖输入框。取不到布局时退回屏幕上下各留 6%。
     */
    private int[] pageWindowBounds() {
        View scroll = activity.findViewById(R.id.scroll);
        int[] loc = new int[2];
        if (scroll != null && scroll.getHeight() > 0) {
            scroll.getLocationOnScreen(loc);
            return new int[]{loc[1], loc[1] + scroll.getHeight() - dp(8)};
        }
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        return new int[]{(int) (h * 0.06f), (int) (h * 0.94f)};
    }

    /** 关闭所有插件页面（nav.list 里的「聊天」入口）。 */
    public void closeAllPages() {
        for (Dialog d : new ArrayList<>(pageDialogs)) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateCurrentPage() {
        PageFrame top = pageStack.peek();
        String page = top == null ? NavModel.CHAT_PAGE : top.id;
        currentPageId = page;
        currentPageParams = top == null ? new JSONObject() : copy(top.params);
        NavShell.setCurrentPage(page);
        HostState.publish("pocket.nav.page", page);
    }

    public String currentPageId() {
        return currentPageId;
    }

    /** bridge.nav.current：{page, params}；聊天页返回 null。 */
    public JSONObject currentPageInfo() {
        if (NavModel.CHAT_PAGE.equals(currentPageId)) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("page", currentPageId);
            o.put("params", copy(currentPageParams));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 调试：把当前页面的虚拟 list 滚到指定像素。 */
    public String scrollPageList(int y) {
        for (Surface s : pageSurfaces) {
            if (s.fw == null) continue;
            if (s.fw.renderer().debugScrollList(y)) return "scrolled " + y;
        }
        return "(no virtual list)";
    }

    /** 调试：当前打开页面里第一个虚拟 list 的状态；没有则返回提示。 */
    public String dumpPageList() {
        for (Surface s : pageSurfaces) {
            if (s.fw == null) continue;
            String d = s.fw.renderer().debugListState();
            if (d != null) return d;
        }
        return "(no virtual list)";
    }

    /** 按全名打开页面；必须在主线程调用。 */
    public boolean pushPage(String fullId, JSONObject params) {
        if (fullId == null || fullId.isEmpty()) return false;
        for (PageDecl p : pages()) {
            if (p.fullId().equals(fullId)) {
                openPage(p, params == null ? new JSONObject() : params);
                return true;
            }
        }
        return false;
    }

    /** 返回上一页；必须在主线程调用。 */
    public boolean backPage() {
        if (pageDialogs.isEmpty()) return false;
        Dialog top = pageDialogs.get(pageDialogs.size() - 1);
        try {
            top.dismiss();
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** bridge.ui.patch：按节点 id 局部更新，必须在主线程调用。 */
    public boolean patchNode(String id, Map<String, Object> props) {
        return patchNode(null, id, props);
    }

    /**
     * 带调用方的 patch：只允许改自己拥有的 surface（插槽/页面/消息位的 owner 插件）。
     * caller 为 null 是宿主内部调用，保持全局行为。
     */
    public boolean patchNode(String callerPluginId, String id, Map<String, Object> props) {
        if (id == null || id.isEmpty() || props == null || props.isEmpty()) return false;
        for (Surface s : surfaces()) {
            if (s.fw == null) continue;
            if (callerPluginId != null && (s.owner == null || !callerPluginId.equals(s.owner.id))) {
                continue;
            }
            if (s.fw.patch(id, props)) return true;
        }
        return false;
    }

    /** bridge.ui.bounds：返回 dp 坐标；必须在主线程调用。 */
    public JSONObject boundsNode(String id) {
        return boundsNode(null, id);
    }

    /** 带调用方的 bounds：与 {@link #patchNode(String, String, Map)} 同样的 owner 限制。 */
    public JSONObject boundsNode(String callerPluginId, String id) {
        if (id == null || id.isEmpty()) return null;
        float density = activity.getResources().getDisplayMetrics().density;
        for (Surface s : surfaces()) {
            if (s.fw == null) continue;
            if (callerPluginId != null && (s.owner == null || !callerPluginId.equals(s.owner.id))) {
                continue;
            }
            int[] b = new int[4];
            if (s.fw.bounds(id, b)) {
                JSONObject o = new JSONObject();
                try {
                    o.put("x", b[0] / density);
                    o.put("y", b[1] / density);
                    o.put("w", b[2] / density);
                    o.put("h", b[3] / density);
                } catch (Exception ignored) {
                }
                return o;
            }
        }
        return null;
    }

    private List<Surface> surfaces() {
        List<Surface> all = new ArrayList<>(slots.values());
        all.addAll(messageSlots.values());
        all.addAll(pageSurfaces);
        return all;
    }

    /**
     * 应用内浮层挂载点：有打开中的插件页时挂到该页 Dialog 的 decor，否则挂 Activity decor。
     * 否则 Dialog 会盖住浮层。必须主线程调用。
     */
    public ViewGroup overlayHost() {
        if (!pageDialogs.isEmpty()) {
            Dialog top = pageDialogs.get(pageDialogs.size() - 1);
            Window w = top.getWindow();
            if (w != null) {
                View decor = w.getDecorView();
                if (decor instanceof ViewGroup) return (ViewGroup) decor;
            }
        }
        View decor = activity.getWindow().getDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    private void setup(Surface s) {
        UiSelection.installHub(s.host);
        s.fw = new UiFramework(s.host);
        // 插件插槽/页面与聊天共用同一套视图 DSL：table / codeCard / toolCard 也要能渲染。
        ChatComponents.install(s.fw.renderer());
        s.fw.setToastSink(text -> toast.show(text));
        s.fw.actions().setHandler(action -> {
            String name = action.action;
            if (name != null && name.startsWith("action:")) {
                // 全局动作：action:svc.restart → 动作总线，结果可替换当前内容。
                actionBus().dispatch(name.substring(7), actionParams(action.params), "ui",
                        node -> {
                            if (s.alive) renderWrapped(s, node);
                        });
                return Boolean.TRUE;
            }
            JSONObject ctx = copy(s.baseCtx);
            put(ctx, "action", action.action);
            put(ctx, "node", toJson(action.params.get("node")));
            put(ctx, "value", toJson(action.params.get("value")));
            // 节点 action 属于"动作"入口：允许等待（如 bridge.ui.confirm / exec）。
            dispatch(s, action.action, ctx, false);
            return Boolean.TRUE;
        });
        s.fw.events().setHandler(ev -> {
            JSONObject ctx = copy(s.baseCtx);
            put(ctx, "event", ev.event);
            put(ctx, "node", toJson(ev.node));
            put(ctx, "value", toJson(ev.value));
            if (ev.pointer != null) put(ctx, "pointer", toJson(ev.pointer));
            // 事件回调持着 50ms 预算：禁止阻塞式 bridge（§9.2.1）。
            dispatch(s, ev.handlerName, ctx, true);
            return null;      // 异步渲染，不能在 50ms 事件里等 JS
        });
    }

    private void submit(Surface s, UiCall call) {
        if (released) return;
        s.renderCall = call;
        final int token = ++s.token;
        final JSONObject ctx = copy(s.baseCtx);
        s.pending = true;
        worker.execute(() -> {
            HookRegistry.UiRender result = null;
            try {
                HookRegistry reg = registry.get();
                if (reg != null) result = call.run(reg, ctx);
            } catch (Throwable ignored) {
                // registry 已把错误记进 lastError/日志
            }
            final HookRegistry.UiRender r = result;
            main.post(() -> {
                if (released || !s.alive || s.token != token) return;
                apply(s, r);
            });
        });
    }

    private void apply(Surface s, HookRegistry.UiRender result) {
        s.pending = false;
        s.owner = result == null ? null : result.manifest;
        ViewGroup host = s.host;
        if (result == null) {
            if (s.slot == null && s.renderedOnce) return;    // 页面刷新失败：保留旧内容
            s.fw.clear();
            if (s.slot == null) showError(s, null);
            return;
        }
        UiNode node = parseNode(result.json);
        if (node == null) {
            if (s.slot == null && s.renderedOnce) return;
            if (s.slot == null) showError(s, activity.getString(R.string.plugin_page_bad_node));
            else s.fw.clear();
            return;
        }
        s.renderedOnce = true;
        renderWrapped(s, node);
    }

    /** 事件/动作结果：对象 → 替换内容；字符串 → toast；其他忽略。 */
    private void dispatch(Surface s, String fnName, JSONObject ctx, boolean nonBlocking) {
        if (fnName == null || fnName.isEmpty() || released) return;
        final HookManifest owner = s.owner;
        if (owner == null) return;
        final int token = s.token;
        final String fn = fnName.startsWith("action:")
                ? fnName.substring(7) : fnName;
        worker.execute(() -> {
            String out = null;
            if (nonBlocking) com.dsh.android.plugin.PluginBridge.enterNonBlocking();
            try {
                HookRegistry reg = registry.get();
                if (reg != null) out = reg.invokeUiFn(owner, fn, ctx);
            } catch (Throwable ignored) {
            } finally {
                if (nonBlocking) com.dsh.android.plugin.PluginBridge.exitNonBlocking();
            }
            if (out == null) return;
            final String result = out;
            main.post(() -> {
                // token 变了说明已有更新的渲染/求值，丢弃旧回调，避免覆盖新内容
                if (released || !s.alive || s.token != token) return;
                if (looksLikeNode(result)) {
                    UiNode node = parseNode(result);
                    if (node != null) {
                        s.renderedOnce = true;
                        renderWrapped(s, node);
                        return;
                    }
                    // 看起来像 JSON 但不是视图节点：按文本 toast，避免结果被吞掉。
                }
                if (!result.isEmpty()) toast.show(result);
            });
        });
    }

    private PluginActionBus actionBus() {
        if (actionBus == null) {
            actionBus = new PluginActionBus(activity, registry, text -> toast.show(text));
        }
        return actionBus;
    }

    /** 节点上的 action 参数 + 事件 value；全局动作只认这些，不把整个 event ctx 传过去。 */
    private static JSONObject actionParams(Map<String, Object> raw) {
        JSONObject out = new JSONObject();
        if (raw == null) return out;
        Object node = raw.get("node");
        if (node instanceof Map) {
            Object p = ((Map<?, ?>) node).get("params");
            if (p instanceof Map) {
                Object j = toJson(p);
                if (j instanceof JSONObject) out = (JSONObject) j;
            }
        }
        Object value = raw.get("value");
        if (value != null) put(out, "value", toJson(value));
        return out;
    }

    /**
     * 页面/浮层包一层 {@code select: cross}：长按文本才会出现「复制 / 全选 / 取消」浮窗。
     * **常驻插槽不包**：composer.above 这类位置的 chip/按钮是控件，不是正文；
     * 给插槽包 select 会让点 chip 被识别成长按选词。插槽要选文本可自己在根节点声明 select。
     * 每个面有独立 hub（见 {@link #setup}），选择不会跨到聊天流。
     */
    private static void renderWrapped(Surface s, UiNode node) {
        if (node == null) {
            s.fw.clear();
            return;
        }
        UiNode.Builder root = UiNode.column().key("plugin-surface").prop("gap", "none");
        if (s.slot == null) root.prop("select", "cross");
        root.child(node);
        s.fw.render(root.build());
    }

    private void showError(Surface s, String why) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(16), dp(4), dp(16));

        TextView msg = new TextView(activity);
        msg.setText(why == null
                ? activity.getString(R.string.plugin_page_error)
                : activity.getString(R.string.plugin_page_error_reason, why));
        msg.setTextColor(activity.getColor(R.color.accent_red));
        msg.setTextSize(13);
        box.addView(msg);

        Button retry = new Button(activity);
        retry.setText(activity.getString(R.string.plugin_page_retry));
        retry.setOnClickListener(v -> {
            if (s.renderCall != null) submit(s, s.renderCall);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(retry, lp);

        s.host.removeAllViews();
        s.host.addView(box);
    }

    // ---------------------------------------------------------------- 工具

    private JSONObject baseCtx(JSONObject extra) {
        JSONObject ctx = new JSONObject();
        try {
            JSONObject sess;
            try {
                sess = session.get();
            } catch (Throwable e) {
                sess = null;
            }
            ctx.put("session", sess == null ? new JSONObject() : sess);
            if (extra != null) {
                java.util.Iterator<String> it = extra.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    ctx.put(k, extra.get(k));
                }
            }
            if (!ctx.has("state")) ctx.put("state", new JSONObject());
        } catch (Exception ignored) {
        }
        return ctx;
    }

    private JSONObject pageCtx(PageDecl page, JSONObject params) {
        JSONObject ctx = baseCtx(null);
        JSONObject p = new JSONObject();
        try {
            p.put("id", page.fullId());
            p.put("title", page.title);
            p.put("plugin", page.pluginId);
            ctx.put("page", p);
            ctx.put("params", params == null ? new JSONObject() : params);
        } catch (Exception ignored) {
        }
        return ctx;
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o == null ? "{}" : o.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void put(JSONObject o, String key, Object v) {
        try {
            o.put(key, v == null ? JSONObject.NULL : v);
        } catch (Exception ignored) {
        }
    }

    private static boolean looksLikeNode(String json) {
        String t = json == null ? "" : json.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static UiNode parseNode(String json) {
        if (!looksLikeNode(json)) return null;
        try {
            return UiNode.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    /** Java Map/List → org.json 可序列化的对象（事件 ctx 用）。 */
    private static Object toJson(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                put(o, String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return o;
        }
        if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object item : (List<?>) v) a.put(toJson(item));
            return a;
        }
        return v;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}
