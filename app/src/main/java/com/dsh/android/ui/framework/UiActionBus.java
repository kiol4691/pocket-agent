package com.dsh.android.ui.framework;

import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作总线：节点上的 {@code action: "fnName"} / {@code on.tap: "action:xxx"} 回调到这里。
 *
 * 返回值按契约处理（PLUGIN_API.md §6.2 / §9）：
 *  - {@code String} → toast；
 *  - {@link UiNode} → 就地替换（这里用 renderer.render 整体重建，diff 会复用 View）；
 *  - 其它/null → 无操作。
 *
 * 约束：嵌套深度上限 3（超过记 E_CYCLE 并中止）、支持 {@code ctx.progress} 与取消。
 */
public final class UiActionBus {

    private static final String TAG = "UiActionBus";
    public static final int MAX_DEPTH = 3;

    public interface Handler {
        Object invoke(UiAction action);
    }

    public interface ToastSink {
        void show(String text);
    }

    public interface ProgressSink {
        void progress(String action, float pct, String text);
    }

    /** 动作回调的 ctx；插件侧看到的是它的字段，不是 Java 对象。 */
    public static final class UiAction {
        private final UiActionBus bus;
        public final String action;
        public final Map<String, Object> params;
        private volatile boolean cancelled;

        UiAction(UiActionBus bus, String action, Map<String, Object> params) {
            this.bus = bus;
            this.action = action;
            this.params = params == null ? Collections.emptyMap() : params;
        }

        public void progress(float pct, String text) {
            if (bus.progressSink != null) bus.progressSink.progress(action, pct, text);
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final UiRenderer renderer;
    private Handler handler;
    private ToastSink toastSink;
    private ProgressSink progressSink;
    private int depth;

    public UiActionBus(UiRenderer renderer) {
        this.renderer = renderer;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public void setToastSink(ToastSink sink) {
        this.toastSink = sink;
    }

    public void setProgressSink(ProgressSink sink) {
        this.progressSink = sink;
    }

    /** 事件/动作返回字符串时的 toast 出口。 */
    public void toast(String text) {
        if (toastSink != null) toastSink.show(text);
    }

    /** 派发一个动作。返回 handler 的原始返回值（便于宿主/测试断言）。 */
    public Object dispatch(String action, Map<String, Object> params) {
        if (depth >= MAX_DEPTH) {
            Log.w(TAG, "E_CYCLE: 动作嵌套超过 " + MAX_DEPTH + " 层，中止 " + action);
            return null;
        }
        if (handler == null) return null;
        depth++;
        try {
            UiAction ctx = new UiAction(this, action,
                    params == null ? new LinkedHashMap<>() : params);
            Object ret = handler.invoke(ctx);
            if (ctx.isCancelled()) return null;
            if (ret instanceof String) {
                if (toastSink != null) toastSink.show((String) ret);
                return null;
            }
            if (ret instanceof UiNode) {
                renderer.render((UiNode) ret);
                return ret;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "动作 " + action + " 抛异常，已按无操作处理: " + t.getMessage());
            return null;
        } finally {
            depth--;
        }
    }

    /** 供事件分发器用：把 {@code action:xxx} 形式的 handler 名转成动作调用。 */
    public Object dispatchIfAction(String handlerName, Map<String, Object> ctx) {
        if (handlerName == null || !handlerName.startsWith("action:")) return null;
        return dispatch(handlerName.substring(7), ctx);
    }
}
