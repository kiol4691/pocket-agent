package com.dsh.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.dsh.android.R;
import com.dsh.android.plugin.ActionDecl;
import com.dsh.android.plugin.HookRegistry;
import com.dsh.android.ui.framework.UiNode;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 全局动作总线的宿主侧执行器。
 *
 * 节点只写 {@code action: "action:svc.restart"}；这里按 id 找到提供方，
 * 需要时弹确认框（支持 {{参数}} 插值），再把 {@code onAction(ctx)} 放到后台线程执行。
 * 结果：字符串 → toast；视图节点 → 替换当前插槽/页面内容；其它忽略。
 */
public final class PluginActionBus {

    public interface ToastSink {
        void show(String text);
    }

    public interface NodeSink {
        void render(UiNode node);
    }

    private final Activity activity;
    private final Supplier<HookRegistry> registry;
    private final ToastSink toast;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 同时在跑的动作上限：动作里再派发动作时不至于无限堆积（超时交给插件侧）。 */
    private static final int MAX_PENDING = 8;
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-action");
        t.setDaemon(true);
        return t;
    });

    public PluginActionBus(Activity activity, Supplier<HookRegistry> registry, ToastSink toast) {
        this.activity = activity;
        this.registry = registry;
        this.toast = toast;
    }

    public void dispatch(String id, JSONObject params, String source, NodeSink sink) {
        final ActionDecl decl;
        try {
            HookRegistry reg = registry.get();
            decl = reg == null ? null : reg.action(id);
        } catch (Throwable e) {
            toast.show(activity.getString(R.string.action_unavailable, id));
            return;
        }
        if (decl == null) {
            toast.show(activity.getString(R.string.action_unavailable, id));
            return;
        }
        // 异步执行有排队深度，防止动作里再派发动作时无限堆积（上层 UiActionBus 的嵌套上限管不到这里）。
        if (pending.get() >= MAX_PENDING) {
            toast.show(activity.getString(R.string.action_queue_full, id));
            return;
        }
        String confirm = decl.confirm == null ? "" : decl.confirm.trim();
        if (confirm.isEmpty()) {
            execute(decl, params, source, sink);
            return;
        }
        String msg = interpolate(confirm, params);
        main.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new AlertDialog.Builder(activity)
                    .setTitle(decl.label)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> execute(decl, params, source, sink))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void execute(ActionDecl decl, JSONObject params, String source, NodeSink sink) {
        pending.incrementAndGet();
        worker.execute(() -> {
            String out;
            try {
                HookRegistry reg = registry.get();
                // 确认框/排队期间提供方可能被停用或重载掉：这里按当前清单再取一次，避免调用旧声明。
                ActionDecl current = reg == null ? null : reg.action(decl.id);
                if (current == null) {
                    main.post(() -> toast.show(activity.getString(R.string.action_provider_gone, decl.id)));
                    return;
                }
                out = reg.invokeAction(current, params, source);
            } catch (Throwable e) {
                out = "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                pending.decrementAndGet();
            }
            if (out == null) return;
            final String result = out;
            main.post(() -> applyResult(result, sink));
        });
    }

    private void applyResult(String result, NodeSink sink) {
        String text = result.trim();
        if (text.startsWith("{") && sink != null) {
            UiNode node = null;
            try {
                node = UiNode.fromJson(new JSONObject(text));
            } catch (Exception ignored) {
            }
            if (node != null) {
                try {
                    sink.render(node);
                    return;
                } catch (Throwable e) {
                    // 渲染异常不能炸掉界面：回传成文本提示。
                    toast.show(activity.getString(R.string.action_render_failed, e.getMessage()));
                    return;
                }
            }
        }
        if (!text.isEmpty()) toast.show(text);
    }

    private static String interpolate(String text, JSONObject params) {
        if (params == null) return text;
        String out = text;
        java.util.Iterator<String> it = params.keys();
        while (it.hasNext()) {
            String k = it.next();
            out = out.replace("{{" + k + "}}", String.valueOf(params.opt(k)));
        }
        return out;
    }
}
