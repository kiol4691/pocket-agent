package com.dsh.android.ui.framework;

import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 状态总线：插件/主题用 {@code bind: {"属性": "状态名"}} 订阅值，宿主用 {@link #set} 发布。
 *
 * 实现选择：**render 前注入**（{@link #inject}）而不是 render 后 patch。后者会在下一帧
 * 被节点树里的原始值覆盖回去，导致闪烁。注入是纯函数：把当前状态值格式化后写进节点 props，
 * 再交给 UiRenderer 走正常 diff（View 照旧复用，只 patch 变化的属性）。
 *
 * 规则（对应 PLUGIN_API.md §6.3）：
 *  - 同值不推；变化最多每 {@value #MIN_INTERVAL_MS} ms 合并通知一次（≤10 次/秒）；
 *  - 没有提供者时按节点的 {@code fallback}：{@code hide} → 加 {@code hidden} prop 由渲染器隐藏，
 *    {@code keep}（默认）→ 保持初始值；
 *  - 节点不可见时渲染器仍会跳过（见 UiRenderer 的 hidden 处理）。
 *
 * v2 起宿主全局只保留一个实例（{@code HostState.bus()}），每个 UiFramework 注册一个监听；
 * 因此聊天流、插件插槽/页面、设置页看到的是同一份实时值。
 */
public final class UiStateBus {

    public static final long MIN_INTERVAL_MS = 100;

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable emit = this::emitNow;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long lastEmitMs;

    /** 追加一个监听（每个 UiFramework 一个）；重复添加同一实例会被忽略。 */
    public void addOnChange(Runnable onChange) {
        if (onChange != null && !listeners.contains(onChange)) listeners.add(onChange);
    }

    public void removeOnChange(Runnable onChange) {
        if (onChange != null) listeners.remove(onChange);
    }

    /** 只保留一个监听（兼容旧 API；新代码用 add/remove）。 */
    public void setOnChange(Runnable onChange) {
        listeners.clear();
        if (onChange != null) listeners.add(onChange);
    }

    /** @return 值是否真的变了（同值不推，可用于避免无意义重渲染）。 */
    public boolean set(String name, Object value) {
        if (name == null || name.isEmpty()) return false;
        Object old = values.get(name);
        if (old == null ? value == null : old.equals(value)) return false;
        values.put(name, value);
        schedule();
        return true;
    }

    public Object get(String name) {
        return values.get(name);
    }

    /** 当前全部状态值的快照（调试通道用）。 */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(values);
    }

    /** 删除一个状态（插件禁用/卸载时清掉它发布的值）。 */
    public void remove(String name) {
        if (name != null && values.remove(name) != null) schedule();
    }

    /** 批量预置（宿主启动/切换会话时用），只发一次通知。 */
    public void seed(Map<String, Object> initial) {
        if (initial == null) return;
        boolean changed = false;
        for (Map.Entry<String, Object> e : initial.entrySet()) {
            if (set(e.getKey(), e.getValue())) changed = true;
        }
        if (changed) schedule();
    }

    private void schedule() {
        long now = System.currentTimeMillis();
        long delay = Math.max(0, MIN_INTERVAL_MS - (now - lastEmitMs));
        main.removeCallbacks(emit);
        main.postDelayed(emit, delay);
    }

    private void emitNow() {
        lastEmitMs = System.currentTimeMillis();
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // 某个界面销毁/重渲染失败不能影响其它界面
            }
        }
    }

    /** 用当前状态值注入整棵树（纯逻辑在 {@link UiStateInjector}，便于 JVM 单测）。 */
    public UiNode inject(UiNode root) {
        return UiStateInjector.inject(root, values);
    }

    /** 宿主内置状态（§13.1）的默认值；接主界面时由真实数据源覆盖。 */
    public void seedBuiltins() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pocket.context.tokens", 0);
        m.put("pocket.context.limit", 100000);
        m.put("pocket.context.ratio", 0.0);
        m.put("pocket.context.summary", "0% · 0 / 100k");
        m.put("pocket.context.compacting", Boolean.FALSE);
        m.put("pocket.agent.busy", Boolean.FALSE);
        m.put("pocket.agent.iteration", 0);
        m.put("pocket.runtime.state", "ready");
        m.put("pocket.display.width", 360);
        m.put("pocket.display.height", 640);
        m.put("pocket.display.dark", Boolean.TRUE);
        m.put("pocket.nav.page", "pocket.chat");
        m.put("pocket.model.name", "");
        m.put("pocket.session.messages", 0);
        seed(m);
    }
}
