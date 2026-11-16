package com.dsh.android.ui.framework;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.Map;

/**
 * 一个节点类型的渲染器。宿主副作用（点击监听、动画、id 查找、数据集展开等）全部关在组件里，
 * 框架只负责 inflate / diff / patch，不认识具体控件。
 *
 * 生命周期：
 *  - {@link #create} 只在「类型或 key 变了」时调用一次，返回的 View 后续被复用；
 *  - {@link #apply} 每帧调用，只改和 oldProps 不同的属性；
 *  - {@link #isContainer} 决定框架要不要把 children 挂进来。
 *
 * 通过 {@link UiRenderer#register(String, UiComponent)} 注册；内置组件见 {@link Builtins}。
 */
public interface UiComponent {

    View create(Context ctx, UiNode node, Env env);

    void apply(Context ctx, View view, UiNode node, Map<String, Object> oldProps, Env env);

    boolean isContainer();

    /**
     * 容器的实际挂载点：普通容器就是自身；滚动容器（hscroll）返回内层内容视图，
     * 让渲染器把 children 放进可滚动的 LinearLayout 而不是 HorizontalScrollView 本身。
     */
    default ViewGroup contentView(View view) {
        return view instanceof ViewGroup ? (ViewGroup) view : null;
    }

    /**
     * 自定义容器用：返回这个子节点的布局参数。
     * 返回 null 表示用渲染器默认（LinearLayout 的横/纵排列 + gap）。
     */
    default ViewGroup.LayoutParams layoutParams(Context ctx, UiNode parent, UiNode child,
                                                int index, Env env) {
        return null;
    }

    /** 组件能用的宿主能力，刻意做得很窄，避免组件反过来依赖渲染器实现。 */
    interface Env {
        Context context();

        /** 当前主题的 token 解析器；切换主题后组件下次 apply 会拿到新值。 */
        TokenResolver theme();

        /** 插件/主题贴图加载（asset:），带体积上限与越界检查。 */
        UiAssets assets();

        /** 记一条可展示的警告（进 {@code UiRenderer.Stats.warning}），不抛异常。 */
        void warn(String text);

        /** 按 MAX_TEXT 截断文本，超限时记一条 warning。 */
        String clip(String text);

        /** 标记「当前节点需要重排」（gap / 子节点 grow 变化时用），不重建 View。 */
        void markLayoutDirty();
    }
}
