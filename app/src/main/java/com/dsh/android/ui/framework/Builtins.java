package com.dsh.android.ui.framework;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.dsh.android.R;
import com.dsh.android.ui.MarkdownRenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 内置组件集。所有宿主副作用（点击监听、剪贴板、动画、图片解码、自定义绘制）都关在这里，
 * {@link UiRenderer} 本身只认识 {@link UiComponent} 接口。
 *
 * 阶段 1 覆盖 P1 的 7 种 + P2 的纯展示控件；交互控件（button/input/switch/checkbox/slider）
 * 在阶段 2 的 InteractiveComponents 里，窗口原语在阶段 4 的 WindowComponents 里。
 */
public final class Builtins {

    /** 一个流式节点最多挂多少个块 View；超出把最早的块合并。 */
    static final int MAX_CHUNK_VIEWS = 64;

    private Builtins() {
    }

    /** 每个 View 上次用的主题实例；换主题后返 true，组件据此重新取 token。 */
    private static final WeakHashMap<View, TokenResolver> APPLIED_THEME = new WeakHashMap<>();

    static boolean retheme(View view, UiComponent.Env env) {
        TokenResolver theme = env.theme();
        if (APPLIED_THEME.get(view) == theme) return false;
        APPLIED_THEME.put(view, theme);
        return true;
    }

    /** 每个 View 上次应用的样式签名；节点没写 prop 时不会每帧重复 setTextSize/setBackground。 */
    private static final WeakHashMap<View, String> APPLIED_STYLE = new WeakHashMap<>();

    static boolean styleChanged(View view, String signature) {
        if (signature.equals(APPLIED_STYLE.get(view))) return false;
        APPLIED_STYLE.put(view, signature);
        return true;
    }

    public static void install(UiRenderer renderer) {
        renderer.register(UiNode.COLUMN, linear(true, false));
        renderer.register(UiNode.ROW, linear(false, false));
        renderer.register(UiNode.HSCROLL, hscroll());
        renderer.register(UiNode.CARD, linear(true, true));
        renderer.register(UiNode.TEXT, text());
        renderer.register(UiNode.MARKDOWN, markdown());
        renderer.register(UiNode.CODE, code());
        renderer.register(UiNode.DIVIDER, divider());

        renderer.register(UiNode.BADGE, badge());
        renderer.register(UiNode.SPACER, spacer());
        renderer.register(UiNode.PROGRESS, progress());
        renderer.register(UiNode.BARS, bars());
        renderer.register(UiNode.IMAGE, image());
        renderer.register(UiNode.LIST, list());
        renderer.register(UiNode.NAV_LIST, navList());

        renderer.register(UiNode.ABSOLUTE, absolute());
        renderer.register(UiNode.TEXT_CHUNKS, textChunks());
    }

    // ---------------------------------------------------------------- 绝对定位

    /**
     * absolute：子节点按 x/y/w/h（dp）叠放，z 由渲染器统一转成 elevation。
     * 不裁剪越界内容（窗口/浮层的缩放把手可以画到边缘外）。
     */
    private static UiComponent absolute() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                FrameLayout f = new FrameLayout(ctx);
                f.setClipChildren(false);
                f.setClipToPadding(false);
                return f;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
            }

            @Override
            public boolean isContainer() {
                return true;
            }

            @Override
            public ViewGroup.LayoutParams layoutParams(Context ctx, UiNode parent, UiNode child,
                                                       int index, Env env) {
                float density = ctx.getResources().getDisplayMetrics().density;
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        child.integer("w", 0) > 0 ? px(child.integer("w", 0), density)
                                : ViewGroup.LayoutParams.WRAP_CONTENT,
                        child.integer("h", 0) > 0 ? px(child.integer("h", 0), density)
                                : ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = px(child.integer("x", 0), density);
                lp.topMargin = px(child.integer("y", 0), density);
                return lp;
            }

            private int px(int dp, float density) {
                return (int) (dp * density + 0.5f);
            }
        };
    }

    // ---------------------------------------------------------------- 流式分块

    /**
     * text.chunks：把一段增长中的文本按段落切成多个 TextView，只改最后一块。
     * 配合 {@link UiRenderer#appendText(String, String)}，流式帧不再整段 setText。
     */
    private static UiComponent textChunks() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout v = new LinearLayout(ctx);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setTag(new ChunkState());
                return v;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                LinearLayout v = (LinearLayout) view;
                ChunkState st = (ChunkState) v.getTag();
                String size = node.str("size", "body");
                String tone = node.str("tone", "primary");
                int spacing = Math.max(0, node.integer("lineSpacing", 0));
                boolean restyle = retheme(view, env)
                        || styleChanged(view, "chunks:" + size + "|" + tone + "|" + spacing);
                int maxChunk = Math.max(64, node.integer("maxChunk", UiText.MAX_CHUNK));
                String text = env.clip(node.str("text", ""));

                // 追加式流式：只改尾块，不重新打包前面的块（分块边界稳定，避免整块重排）。
                if (!restyle && !st.views.isEmpty() && !st.lastText.isEmpty()
                        && text.startsWith(st.lastText)) {
                    String delta = text.substring(st.lastText.length());
                    if (!delta.isEmpty()) {
                        appendChunk(v, st, delta, ctx, env, size, tone, spacing, maxChunk);
                    }
                    st.lastText = text;
                    return;
                }

                st.lastText = text;
                List<String> chunks = UiText.capChunks(
                        UiText.splitChunks(text, maxChunk), MAX_CHUNK_VIEWS);

                while (st.views.size() < chunks.size()) {
                    TextView tv = new TextView(ctx);
                    st.views.add(tv);
                    st.texts.add(null);
                    v.addView(tv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                while (st.views.size() > chunks.size()) {
                    TextView tv = st.views.remove(st.views.size() - 1);
                    st.texts.remove(st.texts.size() - 1);
                    v.removeView(tv);
                }
                for (int i = 0; i < chunks.size(); i++) {
                    TextView tv = st.views.get(i);
                    UiSelection.include(tv, v);
                    if (restyle) {
                        styleText(tv, env.theme(), size, tone);
                        tv.setLineSpacing(env.theme().dp(spacing), 1f);
                    }
                    if (!chunks.get(i).equals(st.texts.get(i))) {
                        tv.setText(chunks.get(i));
                        st.texts.set(i, chunks.get(i));
                    }
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static void appendChunk(LinearLayout container, ChunkState st, String delta,
                                    Context ctx, UiComponent.Env env,
                                    String size, String tone, int spacing, int maxChunk) {
        int last = st.views.size() - 1;
        String updated = st.texts.get(last) + delta;
        if (updated.length() <= maxChunk) {
            st.texts.set(last, updated);
            st.views.get(last).setText(updated);
            return;
        }
        String head = updated.substring(0, maxChunk);
        st.texts.set(last, head);
        st.views.get(last).setText(head);

        String rest = updated.substring(maxChunk);
        while (!rest.isEmpty()) {
            String part = rest.length() <= maxChunk ? rest : rest.substring(0, maxChunk);
            rest = rest.substring(part.length());
            TextView tv = new TextView(ctx);
            styleText(tv, env.theme(), size, tone);
            tv.setLineSpacing(env.theme().dp(spacing), 1f);
            tv.setText(part);
            UiSelection.include(tv, container);
            st.views.add(tv);
            st.texts.add(part);
            container.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // 块数上限：合并最早两块，保证 View 数有界
        while (st.views.size() > MAX_CHUNK_VIEWS) {
            st.texts.set(0, st.texts.get(0) + st.texts.get(1));
            st.views.get(0).setText(st.texts.get(0));
            TextView drop = st.views.remove(1);
            st.texts.remove(1);
            container.removeView(drop);
        }
    }

    private static final class ChunkState {
        final List<TextView> views = new java.util.ArrayList<>();
        final List<String> texts = new java.util.ArrayList<>();
        String lastText = "";
    }

    private static void styleText(TextView tv, TokenResolver theme, String size, String tone) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.sizeSp(size));
        tv.setTextColor(theme.textColor(tone));
    }

    // ---------------------------------------------------------------- 容器

    private static UiComponent linear(boolean vertical, boolean card) {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout v = new LinearLayout(ctx);
                v.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                if (card) {
                    int pad = env.theme().dp(env.theme().spaceDp(node.str("pad", "md")));
                    v.setPadding(pad, pad, pad, pad);
                    v.setBackground(cardBackground(env.theme(), node));
                }
                if (!vertical) v.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                return v;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                LinearLayout v = (LinearLayout) view;
                String align = node.str("align", "start");
                if (!align.equals(old.get("align"))) v.setGravity(gravity(!vertical, align));
                String gap = node.str("gap", "none");
                if (!gap.equals(old.get("gap"))) env.markLayoutDirty();
                if (card) {
                    String tone = node.str("tone", "surface");
                    String radius = node.str("radius", "md");
                    String padToken = node.str("pad", "md");
                    if (retheme(view, env)
                            || styleChanged(view, tone + "|" + radius + "|" + padToken)) {
                        view.setBackground(cardBackground(env.theme(), node));
                        int pad = env.theme().dp(env.theme().spaceDp(padToken));
                        view.setPadding(pad, pad, pad, pad);
                    }
                }
            }

            @Override
            public ViewGroup.LayoutParams layoutParams(Context ctx, UiNode parent, UiNode child,
                                                       int index, Env env) {
                // 虚拟列表的 spacer 用精确像素；其它子节点交给渲染器默认布局。
                Object px = child.props().get("heightPx");
                if (vertical && px instanceof Number) {
                    return new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.max(0, ((Number) px).intValue()));
                }
                return null;
            }

            @Override
            public boolean isContainer() {
                return true;
            }
        };
    }

    /**
     * hscroll：横向滚动容器。内层是 wrap_content 的横向 LinearLayout，
     * 子节点超出视口时左右滑动；适合输入框上方的 chip 条。
     */
    private static UiComponent hscroll() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                return new HScroll(ctx);
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                HScroll v = (HScroll) view;
                String align = node.str("align", "start");
                if (!align.equals(old.get("align"))) {
                    v.content.setGravity(gravity(true, align));
                }
            }

            @Override
            public ViewGroup contentView(View view) {
                return ((HScroll) view).content;
            }

            @Override
            public boolean isContainer() {
                return true;
            }
        };
    }

    /** HorizontalScrollView 只允许一个直接子节点：内容放在内层横向 LinearLayout 里。 */
    static final class HScroll extends HorizontalScrollView {
        final LinearLayout content;

        HScroll(Context ctx) {
            super(ctx);
            content = new LinearLayout(ctx);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            addView(content, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            setFillViewport(false);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
    }

    /**
     * list：默认与 column 一样是 LinearLayout；带 {@code height}（dp）时变成固定高度的
     * 可滚动列表，不再要求外层是 ScrollView（P2）。滚动位置由 {@link View#scrollTo} 承担，
     * UiRenderer 的虚拟窗口直接读 {@code getScrollY()} 计算可见行。
     */
    private static UiComponent list() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                return new ScrollList(ctx);
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                ScrollList v = (ScrollList) view;
                Object h = node.props().get("height");
                int px = h instanceof Number
                        ? env.theme().dp(Math.max(1, ((Number) h).intValue())) : 0;
                v.setScrollable(px > 0);
                String align = node.str("align", "start");
                if (!align.equals(old.get("align"))) v.setGravity(gravity(false, align));
                String gap = node.str("gap", "none");
                if (!gap.equals(old.get("gap"))) env.markLayoutDirty();
            }

            @Override
            public ViewGroup.LayoutParams layoutParams(Context ctx, UiNode parent, UiNode child,
                                                       int index, Env env) {
                // 虚拟列表的 spacer 用精确像素；其它子节点交给渲染器默认布局。
                Object px = child.props().get("heightPx");
                if (px instanceof Number) {
                    return new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.max(0, ((Number) px).intValue()));
                }
                return null;
            }

            @Override
            public boolean isContainer() {
                return true;
            }
        };
    }

    /**
     * 可自行纵向滚动的 list 容器：只有 height>0 时才拦截拖动，平时不干扰子节点点击。
     * 先让子节点处理 DOWN；拖动超过 touch slop 才由 onInterceptTouchEvent 接管。
     */
    static final class ScrollList extends LinearLayout {
        private final int slop;
        private boolean scrollable;
        private float downY;
        private boolean dragging;

        ScrollList(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        }

        void setScrollable(boolean on) {
            if (scrollable == on) return;
            scrollable = on;
            if (!on) scrollTo(0, 0);
        }

        /**
         * 固定高度时按 ScrollView 的方式测子节点：先给无上限高度量一遍，再收成
         * 自己的视口高度。否则 LinearLayout 会把超出视口的行按 AT_MOST(0) 量成 0 高，
         * 滚动时后半个窗口的行全是空的。
         */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            if (mode == MeasureSpec.EXACTLY || mode == MeasureSpec.AT_MOST) {
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int content = getMeasuredHeight();
                int want = MeasureSpec.getSize(heightMeasureSpec);
                int h = mode == MeasureSpec.EXACTLY ? want : Math.min(content, want);
                setMeasuredDimension(getMeasuredWidth(), Math.max(0, h));
                return;
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            if (!scrollable) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    dragging = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(e.getY() - downY) > slop) {
                        dragging = true;
                        downY = e.getY();
                        disallowParent(true);
                        return true;
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (!scrollable) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    int dy = Math.round(downY - e.getY());
                    if (dy != 0) {
                        int max = Math.max(0, contentHeight() - getHeight());
                        int target = Math.max(0, Math.min(max, getScrollY() + dy));
                        if (target != getScrollY()) {
                            scrollTo(0, target);
                            downY = e.getY();
                            disallowParent(true);
                        } else {
                            // 已经滚到顶/底：把触摸拦截权交还外层，避免嵌套滚动卡死
                            disallowParent(false);
                        }
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    disallowParent(false);
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private void disallowParent(boolean disallow) {
            android.view.ViewParent p = getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
        }

        /** 调试用：滚动/内容/可视高度快照，只给 page:dump 读。 */
        String debugState() {
            android.graphics.Rect r = new android.graphics.Rect();
            getGlobalVisibleRect(r);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            return "scrollY=" + getScrollY() + " height=" + getHeight()
                    + " content=" + contentHeight() + " children=" + getChildCount()
                    + " visible=" + r.toShortString() + " screenY=" + loc[1];
        }

        /** 已挂载的孩子总高（虚拟列表的上下 spacer 已经把不可见部分算进来）。 */
        private int contentHeight() {
            int total = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                total += c.getHeight();
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp instanceof MarginLayoutParams) {
                    total += ((MarginLayoutParams) lp).topMargin;
                    total += ((MarginLayoutParams) lp).bottomMargin;
                }
            }
            return total;
        }
    }

    static GradientDrawable cardBackground(TokenResolver theme, UiNode node) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.cardColor(node.str("tone", "surface")));
        g.setCornerRadius(theme.dp(theme.radiusDp(node.str("radius", "md"))));
        return g;
    }

    private static void styleCode(TextView tv, TokenResolver theme) {
        tv.setTextSize(theme.sizeSp("code"));
        tv.setTextColor(theme.textColor("secondary"));
        int pad = theme.dp(10);
        tv.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.codeBackground());
        bg.setCornerRadius(theme.dp(8));
        tv.setBackground(bg);
    }

    static int gravity(boolean row, String align) {
        switch (align == null ? "start" : align) {
            case "center":
                return row ? Gravity.CENTER : Gravity.CENTER_HORIZONTAL;
            case "end":
                return row ? (Gravity.END | Gravity.CENTER_VERTICAL) : Gravity.END;
            case "start":
            default:
                return row ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.START;
        }
    }

    // ---------------------------------------------------------------- 文本 / 代码

    private static UiComponent text() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                TextView tv = new TextView(ctx);
                tv.setTextSize(env.theme().sizeSp(node.str("size", "body")));
                tv.setTextColor(env.theme().textColor(node.str("tone", "primary")));
                UiSelection.include(tv);
                return tv;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                TextView tv = (TextView) view;
                String text = env.clip(node.str("text", ""));
                if (!text.equals(old.get("text"))) tv.setText(text);
                String size = node.str("size", "body");
                String tone = node.str("tone", "primary");
                if (retheme(view, env) || styleChanged(view, size + "|" + tone)) {
                    styleText(tv, env.theme(), size, tone);
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    /**
     * markdown：按空行切成块，每块一个 TextView，只对变化的块重新走 Markwon。
     * 流式追加时通常只有最后一块变化，避免了整段全文重解析（约 60ms/次）。
     */
    private static UiComponent markdown() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout v = new LinearLayout(ctx);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setTag(new BlockState());
                return v;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                LinearLayout v = (LinearLayout) view;
                BlockState st = (BlockState) v.getTag();
                String size = node.str("size", "body");
                int spacing = Math.max(0, node.integer("lineSpacing", 0));
                boolean restyle = retheme(view, env)
                        || styleChanged(view, "md:" + size + "|" + spacing);
                List<String> blocks = UiText.capChunks(
                        UiText.splitMarkdownBlocks(env.clip(node.str("text", ""))), MAX_CHUNK_VIEWS);

                while (st.views.size() < blocks.size()) {
                    TextView tv = new TextView(ctx);
                    tv.setLinkTextColor(env.theme().brandColor());
                    tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    st.views.add(tv);
                    st.texts.add(null);
                    v.addView(tv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                while (st.views.size() > blocks.size()) {
                    TextView tv = st.views.remove(st.views.size() - 1);
                    st.texts.remove(st.texts.size() - 1);
                    v.removeView(tv);
                }
                for (int i = 0; i < blocks.size(); i++) {
                    TextView tv = st.views.get(i);
                    UiSelection.include(tv);
                    int top = i == 0 ? 0 : env.theme().dp(8);   // 块间补回被空行吃掉的段落间距
                    if (tv.getPaddingTop() != top) tv.setPadding(0, top, 0, 0);
                    if (restyle) {
                        tv.setTextColor(env.theme().textColor("primary"));
                        tv.setTextSize(env.theme().sizeSp(size));
                        tv.setLineSpacing(env.theme().dp(spacing), 1f);
                        tv.setLinkTextColor(env.theme().brandColor());
                    }
                    if (!blocks.get(i).equals(st.texts.get(i))) {
                        MarkdownRenderer.setText(tv, blocks.get(i));
                        st.texts.set(i, blocks.get(i));
                    }
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static final class BlockState {
        final List<TextView> views = new java.util.ArrayList<>();
        final List<String> texts = new java.util.ArrayList<>();
    }

    private static UiComponent code() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                TextView tv = new TextView(ctx);
                tv.setTypeface(Typeface.MONOSPACE);
                styleCode(tv, env.theme());
                UiSelection.include(tv);
                return tv;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                TextView tv = (TextView) view;
                if (retheme(view, env)) styleCode(tv, env.theme());
                String body = env.clip(node.str("body", ""));
                if (!body.equals(old.get("body"))) tv.setText(body);
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static UiComponent divider() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                View v = new View(ctx);
                v.setBackgroundColor(env.theme().dividerColor());
                return v;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                if (retheme(view, env)) view.setBackgroundColor(env.theme().dividerColor());
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    // ---------------------------------------------------------------- P2 展示控件

    private static UiComponent badge() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                TextView tv = new TextView(ctx);
                tv.setTextSize(env.theme().sizeSp("caption"));
                int ph = env.theme().dp(8), pv = env.theme().dp(2);
                tv.setPadding(ph, pv, ph, pv);
                return tv;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                TextView tv = (TextView) view;
                String tone = node.str("tone", "info");
                if (retheme(view, env) || styleChanged(view, "badge:" + tone)) {
                    tv.setTextSize(env.theme().sizeSp("caption"));
                    int ph = env.theme().dp(8), pv = env.theme().dp(2);
                    tv.setPadding(ph, pv, ph, pv);
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(env.theme().badgeColor(tone));
                    g.setCornerRadius(env.theme().dp(10));
                    tv.setBackground(g);
                    tv.setTextColor(env.theme().textColor("primary"));
                }
                String text = env.clip(node.str("text", ""));
                if (!text.equals(old.get("text"))) tv.setText(text);
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    static int badgeColor(Context ctx, String tone) {
        switch (tone) {
            case "ok":
                return ctx.getColor(com.dsh.android.R.color.accent_green_dark);
            case "warn":
                return ctx.getColor(com.dsh.android.R.color.accent_amber_dark);
            case "danger":
                return ctx.getColor(com.dsh.android.R.color.accent_red_dark);
            case "info":
            default:
                return ctx.getColor(com.dsh.android.R.color.brand_tertiary);
        }
    }

    private static UiComponent spacer() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                View v = new View(ctx);
                v.setMinimumHeight(env.theme().dp(env.theme().spaceDp(node.str("space", "sm"))));
                return v;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                String space = node.str("space", "sm");
                if (retheme(view, env) || styleChanged(view, "space:" + space)) {
                    view.setMinimumHeight(env.theme().dp(env.theme().spaceDp(space)));
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static UiComponent progress() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                return pb;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                ProgressBar pb = (ProgressBar) view;
                boolean indeterminate = node.bool("indeterminate", false);
                if (indeterminate != Boolean.TRUE.equals(old.get("indeterminate"))) {
                    pb.setIndeterminate(indeterminate);
                }
                if (!indeterminate) {
                    int pct = (int) Math.round(clamp01(nodeNumeric(node.str("value", "0"), 0)) * 100);
                    if (pct != pb.getProgress()) pb.setProgress(pct);
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static UiComponent bars() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                return new BarsView(ctx);
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                BarsView v = (BarsView) view;
                Object values = node.props().get("values");
                String tone = node.str("tone", "brand");
                int height = node.integer("height", 64);
                Object maxProp = node.props().get("max");
                float max = maxProp == null ? autoMax(values)
                        : (float) nodeNumeric(String.valueOf(maxProp), 1);
                if (max <= 0) max = 1;
                v.set(values, max, env.theme().textColor(tone),
                        env.theme().dp(Math.max(16, Math.min(240, height))));
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static final class BarsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<?> cached;
        private float[] numbers = new float[0];
        private int color = Color.WHITE;

        BarsView(Context ctx) {
            super(ctx);
        }

        void set(Object values, float max, int color, int heightPx) {
            this.color = color;
            if (values instanceof List) {
                List<?> list = (List<?>) values;
                if (list != cached) {
                    cached = list;
                    numbers = new float[Math.min(list.size(), 60)];
                    for (int i = 0; i < numbers.length; i++) {
                        Object o = list.get(i);
                        numbers[i] = o instanceof Number ? ((Number) o).floatValue() : 0f;
                    }
                }
            } else {
                cached = null;
                numbers = new float[0];
            }
            if (getMinimumHeight() != heightPx) setMinimumHeight(heightPx);
            this.max = max;
            invalidate();
        }

        private float max = 1f;

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (numbers.length == 0) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setColor(color);
            float slot = (float) w / numbers.length;
            float barW = Math.max(1f, slot * 0.7f);
            for (int i = 0; i < numbers.length; i++) {
                float ratio = Math.max(0f, Math.min(1f, numbers[i] / max));
                float barH = ratio * (h - 2);
                float left = i * slot + (slot - barW) / 2;
                canvas.drawRect(left, h - barH, left + barW, h, paint);
            }
        }
    }

    private static UiComponent image() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                ImageView iv = new ImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setAdjustViewBounds(true);
                return iv;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                ImageView iv = (ImageView) view;
                String src = node.str("src", "");
                if (src.equals(old.get("src"))) return;
                Bitmap bmp = load(ctx, src, env);
                iv.setImageBitmap(bmp);
                if (bmp == null && !src.isEmpty()) env.warn("图片加载失败: " + src);
                int h = node.integer("height", 0);
                if (h > 0) {
                    ViewGroup.LayoutParams lp = iv.getLayoutParams();
                    if (lp == null) lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, env.theme().dp(h));
                    else lp.height = env.theme().dp(h);
                    iv.setLayoutParams(lp);
                }
            }

            private Bitmap load(Context ctx, String src, Env env) {
                if (src.startsWith("asset:")) return env.assets().load(src);
                if (src.startsWith("workspace:")) {
                    String path = src.substring(10).replace("..", "");
                    File f = new File(new File(ctx.getFilesDir(), "workspace"), path);
                    return env.assets().loadFile(f);
                }
                // https: 需要 net.http + 域名白名单，阶段 1 不加载远端图片
                return null;
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    /**
     * nav.list：主题/插件放置菜单的宿主组件。
     *
     * props：{@code group}（可选，过滤分组）、{@code variant} = sidebar | tabs | grid。
     * 数据来自 {@link NavShell}，当前页高亮；点击交给宿主打开页面。
     * 每项文本都标记为可选中：长按出现「复制 / 全选 / 取消」浮窗，短按仍然导航。
     */
    private static UiComponent navList() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout column = new LinearLayout(ctx);
                column.setOrientation(LinearLayout.VERTICAL);
                return column;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                LinearLayout column = (LinearLayout) view;
                String variant = node.str("variant", "sidebar");
                String group = node.str("group", "");
                NavModel model = NavShell.model();
                String active = NavShell.currentPage();
                List<NavModel.Item> items = group.isEmpty()
                        ? model.items() : model.group(group);
                String sig = variant + '|' + group + '|' + active + '|' + signature(items);
                if (!retheme(view, env) && sig.contentEquals(String.valueOf(column.getTag()))) return;
                column.setTag(sig);
                column.removeAllViews();
                if (items.isEmpty()) return;

                if ("tabs".equals(variant)) {
                    HorizontalScrollView hsv = new HorizontalScrollView(ctx);
                    hsv.setHorizontalScrollBarEnabled(false);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (NavModel.Item item : items) {
                        row.addView(navChip(ctx, item, active, env));
                    }
                    hsv.addView(row);
                    column.addView(hsv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return;
                }

                if ("grid".equals(variant)) {
                    LinearLayout row = null;
                    for (int i = 0; i < items.size(); i++) {
                        if (i % 2 == 0) {
                            row = new LinearLayout(ctx);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            column.addView(row, new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                        }
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        lp.rightMargin = env.theme().dp(6);
                        row.addView(navChip(ctx, items.get(i), active, env), lp);
                    }
                    return;
                }

                // sidebar：内置项（聊天）单独一行，其余按分组加标题。
                if (group.isEmpty()) {
                    for (NavModel.Item item : items) {
                        if (item.builtin && item.group.isEmpty()) {
                            column.addView(navRow(ctx, item, active, env));
                        }
                    }
                    for (NavModel.Group g : model.groups()) {
                        List<NavModel.Item> members = new ArrayList<>();
                        for (NavModel.Item item : g.items) {
                            if (!(item.builtin && item.group.isEmpty())) members.add(item);
                        }
                        if (members.isEmpty()) continue;
                        TextView header = new TextView(ctx);
                        header.setText(g.name.isEmpty()
                                ? ctx.getString(R.string.plugin_pages) : g.name);
                        header.setTextSize(env.theme().sizeSp("caption"));
                        header.setTextColor(env.theme().textColor("caption"));
                        header.setPadding(env.theme().dp(8), env.theme().dp(10),
                                env.theme().dp(8), env.theme().dp(4));
                        column.addView(header);
                        for (NavModel.Item item : members) {
                            column.addView(navRow(ctx, item, active, env));
                        }
                    }
                } else {
                    for (NavModel.Item item : items) {
                        column.addView(navRow(ctx, item, active, env));
                    }
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static TextView navRow(Context ctx, NavModel.Item item, String active,
                                   UiComponent.Env env) {
        TextView tv = navChip(ctx, item, active, env);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = env.theme().dp(2);
        tv.setLayoutParams(lp);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(env.theme().dp(10), env.theme().dp(9),
                env.theme().dp(10), env.theme().dp(9));
        return tv;
    }

    private static TextView navChip(Context ctx, NavModel.Item item, String active,
                                    UiComponent.Env env) {
        TextView tv = new TextView(ctx);
        tv.setText(item.label);
        tv.setTextSize(env.theme().sizeSp("body"));
        boolean on = item.page.equals(active);
        tv.setTextColor(on ? env.theme().brandColor() : env.theme().textColor("primary"));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(env.theme().dp(12), env.theme().dp(7),
                env.theme().dp(12), env.theme().dp(7));
        tv.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                ctx.getResources().getDisplayMetrics().density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? 0x1F4C8DFF : 0x00000000);
        bg.setCornerRadius(env.theme().dp(8));
        if (!on) bg.setStroke(env.theme().dp(1), env.theme().borderColor());
        tv.setBackground(bg);
        tv.setOnClickListener(v -> NavShell.open(item.page));
        // 长按选中文本 → 复制浮窗；短按仍然走上面的点击导航。
        UiSelection.include(tv);
        return tv;
    }

    private static String signature(List<NavModel.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (NavModel.Item item : items) {
            sb.append(item.page).append(':').append(item.label).append(':')
                    .append(item.group).append(';');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static float autoMax(Object values) {
        float m = 1f;
        if (values instanceof List) {
            for (Object o : (List<?>) values) {
                if (o instanceof Number) m = Math.max(m, ((Number) o).floatValue());
            }
        }
        return m;
    }

    static double nodeNumeric(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
