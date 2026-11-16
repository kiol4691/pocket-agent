package com.dsh.android.ui.framework;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * 框架门面：把渲染器、状态总线、动作总线、事件分发器组合起来。
 *
 * 宿主只需要这一个对象：
 * <pre>
 *   UiFramework fw = new UiFramework(container);
 *   fw.states().seedBuiltins();
 *   fw.actions().setHandler(action -&gt; {...});
 *   fw.events().setHandler(event -&gt; {...});
 *   fw.render(tree);
 * </pre>
 *
 * render 的顺序是固定的：状态注入 → diff 渲染 → 挂事件监听。
 * 状态变化由 UiStateBus 节流后回调到这里重渲染（View 照旧复用）。
 */
public final class UiFramework {

    private final Context context;
    private final UiRenderer renderer;
    private final DefaultTokenResolver defaultTheme;
    /** 全局共享状态：聊天流/插槽/页面看到同一份实时值（见 HostState）。 */
    private final UiStateBus states;
    private final UiActionBus actions;
    private final UiEventDispatcher events;
    private UiNode lastRoot;
    /** 当前树是否用了 bind；没用就不用跟着状态变化重渲染（聊天气泡大多没有）。 */
    private boolean stateBound;
    private final Runnable stateListener = () -> {
        if (lastRoot != null && stateBound) render(lastRoot);
    };

    public UiFramework(ViewGroup container) {
        this.context = container.getContext();
        this.defaultTheme = new DefaultTokenResolver(context);
        this.renderer = new UiRenderer(container);
        renderer.setTheme(defaultTheme);
        // 列表滚动到新窗口时只重跑当前树：diff 会复用可见行，不重建整页 View。
        renderer.setRerenderRequest(() -> {
            if (lastRoot != null) render(lastRoot);
        });
        this.actions = new UiActionBus(renderer);
        this.events = new UiEventDispatcher(renderer, actions);
        InteractiveComponents.install(renderer, events);
        this.states = HostState.bus();
        states.addOnChange(stateListener);
        // 界面销毁时解绑，避免全局总线持有已移除的 View 树。
        container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                // 重新 attach 后恢复列表的滚动监听（detach 时已统一断开）。
                if (lastRoot != null) container.post(() -> {
                    if (lastRoot != null) render(lastRoot);
                });
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                renderer.releaseVirtualListeners();
                states.removeOnChange(stateListener);
                HostTheme.unregister(UiFramework.this);
            }
        });
        HostTheme.register(this);
    }

    public UiRenderer renderer() {
        return renderer;
    }

    public UiStateBus states() {
        return states;
    }

    public UiActionBus actions() {
        return actions;
    }

    public UiEventDispatcher events() {
        return events;
    }

    public void setToastSink(UiActionBus.ToastSink sink) {
        actions.setToastSink(sink);
    }

    /**
     * 应用主题包：只替换 token 解析器与动效 spec，**不重建节点树、不新建 View**。
     *
     * @return 对比度校验警告；空列表表示通过。不达标只警告，不拦启用（§12.1）。
     */
    public List<String> applyTheme(ThemePack pack, boolean dark) {
        List<String> warnings = ThemeValidator.validate(pack, dark);
        renderer.setTheme(new PackTokenResolver(defaultTheme, pack, dark));
        renderer.setMotion(UiMotion.from(pack, systemReduceMotion(context)));
        if (lastRoot != null) render(lastRoot);   // 间距类 token 变了，重排但不新建 View
        return warnings;
    }

    /** 恢复默认主题（设置里永远有这个入口）。 */
    public void resetTheme() {
        renderer.setTheme(defaultTheme);
        renderer.setMotion(UiMotion.defaults(systemReduceMotion(context)));
        if (lastRoot != null) render(lastRoot);
    }

    /** 系统「移除动画」：开发者选项/无障碍里把动画时长缩放到 0。 */
    private static boolean systemReduceMotion(Context ctx) {
        return Settings.Global.getFloat(ctx.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    public UiRenderer.Stats render(UiNode root) {
        lastRoot = root;
        stateBound = hasBind(root, 0);
        UiRenderer.Stats stats = renderer.render(states.inject(root));
        events.attach();
        return stats;
    }

    private static boolean hasBind(UiNode node, int depth) {
        if (node == null || depth > UiNode.MAX_DEPTH) return false;
        if (node.props().containsKey("bind")) return true;
        for (UiNode child : node.children()) {
            if (hasBind(child, depth + 1)) return true;
        }
        return false;
    }

    /** 局部属性更新（事件后的开关/进度/选中态），不重建父树。 */
    public boolean patch(String key, java.util.Map<String, Object> props) {
        return renderer.patch(key, props);
    }

    /**
     * 节点在窗口内的像素位置：{x,y,w,h}。找不到或未挂在窗口上返回 false。
     * 只能主线程调用（View 不是线程安全的）。
     */
    public boolean bounds(String key, int[] out) {
        if (out == null || out.length < 4) return false;
        UiRenderer.Binding b = renderer.findBinding(key);
        if (b == null || b.view == null || b.view.getWindowToken() == null) return false;
        int[] loc = new int[2];
        b.view.getLocationInWindow(loc);
        out[0] = loc[0];
        out[1] = loc[1];
        out[2] = b.view.getWidth();
        out[3] = b.view.getHeight();
        return true;
    }

    /** 流式追加：只 patch 指定节点的尾部文本块。 */
    public boolean appendText(String key, String delta) {
        return renderer.appendText(key, delta);
    }

    public void clear() {
        lastRoot = null;
        renderer.clear();
    }
}
