package com.dsh.android.ui.framework;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 事件分发器（PLUGIN_API.md §6.2）。
 *
 * 口径：
 *  - 只给离散事件，不提供连续 move；
 *  - 点击先给最内层节点，未被消费才沿 parent 链冒泡；
 *  - {@code change} / {@code scroll} 节流 ≥100ms；
 *  - 单次回调预算 50ms，超时丢弃并记日志（不抛给界面）；
 *  - 节点被移除后监听随 View 一起回收；
 *  - 坐标一律 dp，pointer 只有 tap / longPress 有。
 */
public final class UiEventDispatcher {

    private static final String TAG = "UiEventDispatcher";
    public static final long BUDGET_MS = 50;
    public static final long THROTTLE_MS = 100;

    public interface Handler {
        Object onEvent(UiEvent event);
    }

    public static final class UiEvent {
        public final String event;
        /** 节点上声明的处理函数名；action 形式为 {@code action:<fn>}。 */
        public final String handlerName;
        public final String nodeKey;
        public final String nodeId;
        public final String nodeType;
        public final Map<String, Object> node;
        public final Map<String, Object> pointer;
        public final Object value;
        public final Map<String, Object> ctx;

        UiEvent(String event, String handlerName, UiRenderer.Binding b, Map<String, Object> node,
                Map<String, Object> pointer, Object value, Map<String, Object> ctx) {
            this.event = event;
            this.handlerName = handlerName;
            this.nodeKey = b.key;
            this.nodeId = b.id;
            this.nodeType = b.type;
            this.node = node;
            this.pointer = pointer;
            this.value = value;
            this.ctx = ctx;
        }
    }

    private final UiRenderer renderer;
    private final UiActionBus actions;
    private Handler handler;
    private final Map<String, Long> lastFire = new LinkedHashMap<>();
    private final WeakHashMap<View, Boolean> appearBound = new WeakHashMap<>();
    private float lastRawX, lastRawY;
    private int firedCount;
    private int throttledCount;

    public UiEventDispatcher(UiRenderer renderer, UiActionBus actions) {
        this.renderer = renderer;
        this.actions = actions;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public int firedCount() {
        return firedCount;
    }

    public int throttledCount() {
        return throttledCount;
    }

    /** 每帧 render 之后调用：给带 {@code on} 的节点挂监听，并处理 appear。 */
    public void attach() {
        renderer.forEachBinding(this::attachOne);
    }

    private void attachOne(UiRenderer.Binding b) {
        if (b.node == null) return;
        Object on = b.node.props().get("on");
        // action: "xxx" 等价于 on.tap: "action:xxx"（任意节点；文档 §5.1）
        if (b.node.props().get("action") != null) {
            Map<String, Object> merged = on instanceof Map
                    ? new LinkedHashMap<>(asStringMap(on)) : new LinkedHashMap<>();
            if (!merged.containsKey("tap")) {
                merged.put("tap", "action:" + b.node.props().get("action"));
            }
            on = merged;
        }
        if (!(on instanceof Map)) return;
        Map<?, ?> handlers = (Map<?, ?>) on;
        View v = b.view;

        if (handlers.containsKey("tap") || handlers.containsKey("longPress")) {
            v.setOnTouchListener((view, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                    lastRawX = ev.getRawX();
                    lastRawY = ev.getRawY();
                }
                return false;                       // 不消费，交给 OnClick/LongClick
            });
        }
        if (handlers.containsKey("tap")) {
            v.setOnClickListener(view -> fireWithBubble(b, "tap", null, true));
        }
        if (handlers.containsKey("longPress")) {
            v.setOnLongClickListener(view -> {
                fireWithBubble(b, "longPress", null, true);
                return true;
            });
        }
        if (handlers.containsKey("appear") || handlers.containsKey("disappear")) {
            if (!Boolean.TRUE.equals(appearBound.get(v))) {
                appearBound.put(v, Boolean.TRUE);
                v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        fire(b, "appear", null, false);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        fire(b, "disappear", null, false);
                    }
                });
            }
        }
    }

    /** 供交互组件调用：change / submit / focus / blur / scroll。 */
    public void fire(String bindingKey, String event, Object value) {
        UiRenderer.Binding b = renderer.findBinding(bindingKey);
        if (b == null) return;
        fireWithBubble(b, event, value, false);
    }

    /** 组件只持有 View 引用时用（绑定 key 由渲染器反查）。 */
    public void fireByView(View view, String event, Object value) {
        UiRenderer.Binding b = renderer.findBindingByView(view);
        if (b == null) return;
        fireWithBubble(b, event, value, false);
    }

    private boolean fireWithBubble(UiRenderer.Binding b, String event, Object value, boolean pointer) {
        UiRenderer.Binding cur = b;
        while (cur != null) {
            if (throttled(cur, event)) {
                throttledCount++;
                return true;                     // 节流也算消费，不继续冒泡
            }
            if (fire(cur, event, value, pointer)) return true;
            cur = cur.parent;
        }
        return false;
    }

    private boolean throttled(UiRenderer.Binding b, String event) {
        if (!"change".equals(event) && !"scroll".equals(event)) return false;
        String k = b.key + ":" + event;
        long now = System.currentTimeMillis();
        Long last = lastFire.get(k);
        if (last != null && now - last < THROTTLE_MS) return true;
        lastFire.put(k, now);
        return false;
    }

    private boolean fire(UiRenderer.Binding b, String event, Object value, boolean withPointer) {
        if (b.node == null) return false;
        Object handlerName = handlerFor(b.node, event);
        if (handlerName == null) return false;

        long t0 = System.nanoTime();
        Map<String, Object> ctx = buildCtx(b, event, value, withPointer);
        String name = String.valueOf(handlerName);
        UiEvent e = new UiEvent(event, name, b,
                asMap(ctx.get("node")), asMap(ctx.get("pointer")), value, ctx);
        firedCount++;

        Object ret;
        if (name.startsWith("action:")) {
            actions.dispatchIfAction(name, ctx);
            ret = Boolean.TRUE;                 // 动作形式一律视为已消费
        } else {
            ret = handler == null ? null : handler.onEvent(e);
        }
        handleResult(ret);

        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        if (ms > BUDGET_MS) Log.w(TAG, "事件 " + event + " 回调 " + ms + "ms 超过 50ms 预算");
        return true;                            // 有 handler 就算消费
    }

    /** 节点对某个事件的处理器名；{@code action} 是 {@code tap} 的简写（任意节点，§5.1）。 */
    private static Object handlerFor(UiNode node, String event) {
        Object on = node.props().get("on");
        if (on instanceof Map) {
            Object h = ((Map<?, ?>) on).get(event);
            if (h != null) return h;
        }
        if ("tap".equals(event)) {
            Object action = node.props().get("action");
            if (action != null) return "action:" + action;
        }
        return null;
    }

    private void handleResult(Object ret) {
        if (ret instanceof String) actions.toast((String) ret);
        else if (ret instanceof UiNode) renderer.render((UiNode) ret);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object v) {
        return (Map<String, Object>) v;
    }

    private Map<String, Object> buildCtx(UiRenderer.Binding b, String event,
                                         Object value, boolean withPointer) {
        float density = renderer.context().getResources().getDisplayMetrics().density;
        int[] loc = new int[2];
        b.view.getLocationOnScreen(loc);
        float w = b.view.getWidth() / density, h = b.view.getHeight() / density;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", b.id);
        node.put("key", b.key);
        node.put("type", b.type);
        node.put("data", b.node.props().get("data"));
        node.put("params", b.node.props().get("params"));
        node.put("x", loc[0] / density);
        node.put("y", loc[1] / density);
        node.put("w", w);
        node.put("h", h);

        Map<String, Object> pointer = null;
        if (withPointer) {
            pointer = new LinkedHashMap<>();
            pointer.put("x", lastRawX / density);
            pointer.put("y", lastRawY / density);
            pointer.put("localX", (lastRawX - loc[0]) / density);
            pointer.put("localY", (lastRawY - loc[1]) / density);
            pointer.put("w", w);
            pointer.put("h", h);
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("event", event);
        ctx.put("node", node);
        if (pointer != null) ctx.put("pointer", pointer);
        ctx.put("value", value);
        return ctx;
    }
}
