package com.dsh.android.ui.framework;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨控件自由选择 / 复制（网页式）。
 *
 * 节点上加 {@code select} 属性声明一个选择范围：
 *
 * <ul>
 *   <li>{@code "local"}：只能在这个节点内部选；</li>
 *   <li>{@code "cross"}：和同一 hub 里其它 {@code cross} 范围连成一条可选流，
 *       可以像网页一样从一段文字拖到另一段（用户消息默认 local，不参与连选）；</li>
 *   <li>{@code "none"}：显式禁止（连子节点一起排除）。</li>
 * </ul>
 *
 * 长按进入选择：先选中手指下的一个词，继续拖动会把选择延伸到同一 hub 的其它控件；
 * 松手弹出「复制 / 全选 / 取消」。选中范围用 {@link BackgroundColorSpan} 直接画在
 * TextView 上，不额外盖一层 View，滚动时高亮跟着文字走。
 *
 * **哪些文本可被选中**由组件显式声明：{@link #include(TextView)} 或
 * {@link #include(TextView, Object)}。不标记的 TextView（按钮、胶囊、卡片标题等）
 * 不参与复制；同一 owner 的相邻文本段（如 text.chunks 的块）直接拼接，不插换行。
 */
public final class UiSelection {

    public static final String MODE_NONE = "none";
    public static final String MODE_LOCAL = "local";
    public static final String MODE_CROSS = "cross";

    /** 高亮颜色：品牌蓝 33% 透明度，深浅色下都能看清。 */
    private static final int HIGHLIGHT = 0x552F6FEB;

    private static final int TAG_SCOPE = R.id.tag_select_scope;
    private static final int TAG_HUB = R.id.tag_select_hub;
    private static final int TAG_INCLUDE = R.id.tag_select_include;
    private static final int TAG_OWNER = R.id.tag_select_owner;
    private static final int TAG_EXCLUDE = R.id.tag_select_exclude;
    private static final int TAG_TEXT_TOUCH = R.id.tag_select_text_touch;
    private static final int TAG_CHAIN = R.id.tag_select_chain;

    private UiSelection() {
    }

    // ---------------------------------------------------------------- 宿主 / 组件接口

    /** 把某个容器声明为选择 hub（主界面的 transcript）。不装时自动退到 window root。 */
    public static void installHub(View root) {
        if (root != null && root.getTag(TAG_HUB) == null) {
            root.setTag(TAG_HUB, new Hub(root));
        }
    }

    /**
     * 节点渲染完就同步 {@code select}（在子节点创建之前），这样 {@link #include} 向上找
     * 选择范围时能在最近的作用域处停住，不会把转发器装到 transcript/ScrollView 上。
     */
    static void bind(UiNode node, View view) {
        if (node == null || view == null) return;
        String mode = node.str("select", "").trim();
        if (MODE_NONE.equals(mode)) {
            view.setTag(TAG_EXCLUDE, Boolean.TRUE);
            return;
        }
        if (view.getTag(TAG_EXCLUDE) != null) view.setTag(TAG_EXCLUDE, null);
        // 带 tap/action 的节点默认是控件，不是可选正文；要选文本可显式写 select。
        boolean interactive = node.props().containsKey("on") || node.props().containsKey("action");
        if (mode.isEmpty() && interactive) {
            view.setTag(TAG_EXCLUDE, Boolean.TRUE);
            detach(view);
            return;
        }
        if (MODE_LOCAL.equals(mode) || MODE_CROSS.equals(mode)) {
            attachScope(view, mode);
        } else {
            detach(view);
        }
    }

    /** 渲染结束后：清掉落在被重渲染内容上的旧选择（偏移已经对不上了）。 */
    static void sync(UiRenderer renderer) {
        ViewGroup container = renderer.container();
        // 还没挂到窗口（例如工具行先 render 再 attach）：此时不需要 hub，
        // 否则会把 hub 标签钉在行自身上，attach 后就找不到 transcript 的 hub 了。
        if (!container.isAttachedToWindow()) return;
        Hub hub = hubFor(container);
        if (hub.session != null && hub.session.touches(container)) hub.clear();
    }

    /** 当前有没有活动的选择（含已松手、工具条还挂着的状态）。 */
    public static boolean isSelecting(View anchor) {
        if (anchor == null) return false;
        Hub hub = hubFor(anchor);
        return hub.session != null;
    }

    /** 组件声明"这段文本参与复制"，并且可以从它身上长按开始选择。 */
    public static void include(TextView tv) {
        include(tv, tv);
    }

    public static void include(TextView tv, Object owner) {
        if (tv == null) return;
        tv.setTag(TAG_INCLUDE, Boolean.TRUE);
        tv.setTag(TAG_OWNER, owner == null ? tv : owner);
        // 文本控件自己也要能收到长按：很多 TextView 带 LinkMovementMethod，
        // DOWN 会被它消费，scope 根布局永远收不到。
        if (tv.getTag(TAG_TEXT_TOUCH) == null) {
            tv.setTag(TAG_TEXT_TOUCH, Boolean.TRUE);
            tv.setOnTouchListener(TEXT_FORWARDER);
        }
        tagChain(tv);
        // 组件可能在"文本还没挂到父容器"时就 include（表格单元格、工具卡都是这样），
        // 挂载后补一次，保证到 scope 之间的每一层都有 UP/CANCEL 转发器。
        if (tv.getTag(TAG_CHAIN) == null) {
            tv.setTag(TAG_CHAIN, Boolean.TRUE);
            tv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    tagChain(v);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
        }
    }

    private static void tagChain(View v) {
        if (v.getTag(TAG_SCOPE) != null) return;         // 自己就是范围（用户气泡）
        for (View p = parentOf(v); p != null; p = parentOf(p)) {
            if (p.getTag(TAG_SCOPE) != null) break;
            // 交互控件/显式排除的子树不装转发器，避免点 chip 时把事件冒到外层选择范围。
            if (p.getTag(TAG_EXCLUDE) != null) return;
            if (p.getTag(TAG_TEXT_TOUCH) == null) {
                p.setTag(TAG_TEXT_TOUCH, Boolean.TRUE);
                p.setOnTouchListener(TEXT_FORWARDER);
            }
        }
    }

    private static View parentOf(View v) {
        ViewParent parent = v.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    /** 文本控件把事件转交给它最近的选择范围；没有范围就完全透传。 */
    private static final View.OnTouchListener TEXT_FORWARDER = (v, e) -> {
        if (excluded(v)) return false;
        View scopeView = nearestScope(v);
        if (scopeView == null) return false;
        Scope s = (Scope) scopeView.getTag(TAG_SCOPE);
        if (s == null || s.gesture == null) return false;
        return s.gesture.onTouch(v, e);
    };

    private static boolean excluded(View v) {
        for (View p = v; p != null; p = parentOf(p)) {
            if (p.getTag(TAG_EXCLUDE) != null) return true;
        }
        return false;
    }

    private static View nearestScope(View v) {
        for (View p = v; p != null; ) {
            if (p.getTag(TAG_SCOPE) != null) return p;
            ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- 范围

    private static final class Scope {
        String mode;
        Gesture gesture;

        Scope(String mode) {
            this.mode = mode;
        }
    }

    /** 宿主直接给一个 View 装选择范围（用户气泡这类非节点树内容）。 */
    public static void attach(View view, String mode) {
        if (view == null) return;
        if (!MODE_LOCAL.equals(mode) && !MODE_CROSS.equals(mode)) return;
        attachScope(view, mode);
    }

    private static void attachScope(View view, String mode) {
        Scope old = (Scope) view.getTag(TAG_SCOPE);
        if (old != null) {
            old.mode = mode;
            return;
        }
        Scope s = new Scope(mode);
        s.gesture = new Gesture(view);
        view.setTag(TAG_SCOPE, s);
        view.setOnTouchListener(s.gesture);
    }

    private static void detach(View view) {
        if (view.getTag(TAG_SCOPE) == null) return;
        view.setTag(TAG_SCOPE, null);
        view.setOnTouchListener(null);
    }

    private static Hub hubFor(View v) {
        for (View p = v; p != null; ) {
            Hub h = (Hub) p.getTag(TAG_HUB);
            if (h != null) return h;
            ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        View root = v.getRootView();
        Hub h = (Hub) root.getTag(TAG_HUB);
        if (h == null) {
            h = new Hub(root);
            root.setTag(TAG_HUB, h);
        }
        return h;
    }

    // ---------------------------------------------------------------- hub / 会话

    private static final class Hub {
        final View root;
        Session session;
        ViewGroup barHost;
        View barView;

        Hub(View root) {
            this.root = root;
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    clear();
                }
            });
        }

        boolean begin(View scope, Scope s, float rawX, float rawY) {
            clear();
            List<SelectionModel.Part> parts = new ArrayList<>();
            List<TextView> tvs = new ArrayList<>();
            if (MODE_CROSS.equals(s.mode)) collectCross(root, false, parts, tvs);
            else collectScope(scope, parts, tvs);
            if (parts.isEmpty()) return false;

            Session ns = new Session(parts, tvs);
            session = ns;
            int off = ns.offsetAt(rawX, rawY);
            ns.anchor = off;
            ns.focus = off;
            // 长按先选一个词，和系统/网页的手感一致；没有词就是光标位置。
            int pi = ns.partIndexAt(off);
            if (pi >= 0) {
                SelectionModel.Part p = ns.model.part(pi);
                int local = off - ns.model.startOf(pi);
                int ws = wordStart(p.text, local);
                int we = wordEnd(p.text, local);
                // 中文没有分词器：长按先选一个字，拖动再扩；整段 CJK 连选不适合复制。
                if (we <= ws && local < p.text.length() && isCjk(p.text.charAt(local))) {
                    ws = local;
                    we = local + 1;
                }
                if (we > ws) {
                    ns.anchor = ns.model.startOf(pi) + ws;
                    ns.focus = ns.model.startOf(pi) + we;
                }
            }
            ns.apply();
            return true;
        }

        void finish(float rawX, float rawY) {
            if (session == null) return;
            showBar(rawX, rawY);
        }

        void clear() {
            Session s = session;
            session = null;
            if (s != null) s.restore();
            if (barView != null && barHost != null) barHost.removeView(barView);
            barView = null;
            barHost = null;
        }

        private void showBar(float rawX, float rawY) {
            if (session == null || barView != null) return;
            Context ctx = root.getContext();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ctx.getColor(R.color.layer1));
            bg.setCornerRadius(dp(ctx, 10));
            bg.setStroke(dp(ctx, 1), ctx.getColor(R.color.border_light));
            row.setBackground(bg);
            row.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
            row.setClickable(true);          // 吃掉落在工具条背景上的触摸，不穿透到表格

            row.addView(item(ctx, ctx.getString(R.string.copy), ctx.getColor(R.color.brand), v -> {
                copySelection();
            }));
            row.addView(item(ctx, ctx.getString(R.string.select_all),
                    ctx.getColor(R.color.text_secondary), v -> {
                        if (session == null) return;
                        session.anchor = 0;
                        session.focus = session.model.total();
                        session.apply();
                    }));
            row.addView(item(ctx, ctx.getString(R.string.cancel),
                    ctx.getColor(R.color.text_secondary), v -> clear()));

            int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            row.measure(spec, spec);
            int bw = row.getMeasuredWidth();
            int bh = row.getMeasuredHeight();
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int x = clamp((int) rawX - bw / 2, 0, Math.max(0, dm.widthPixels - bw));
            int y = clamp((int) rawY - bh - dp(ctx, 16), 0, Math.max(0, dm.heightPixels - bh));

            // 用活动内容区的 FrameLayout 承载，而不是 PopupWindow：
            // 同一个窗口里定位/触摸都确定，不会出现"看得见但点不到"。
            ViewGroup host = overlayHost(root);
            if (host == null) return;
            int[] loc = new int[2];
            host.getLocationOnScreen(loc);
            host.addView(row, new FrameLayout.LayoutParams(bw, bh));
            row.setX(x - loc[0]);
            row.setY(y - loc[1]);
            barHost = host;
            barView = row;
        }

        /** 找到 Activity 内容区的 FrameLayout（android.R.id.content），退而求其次用最近的 FrameLayout。 */
        private static ViewGroup overlayHost(View v) {
            ViewGroup firstFrame = null;
            for (View p = v; p != null; ) {
                if (p instanceof FrameLayout) {
                    if (p.getId() == android.R.id.content) return (ViewGroup) p;
                    if (firstFrame == null) firstFrame = (ViewGroup) p;
                }
                ViewParent parent = p.getParent();
                p = parent instanceof View ? (View) parent : null;
            }
            if (firstFrame != null) return firstFrame;
            View root = v.getRootView();
            return root instanceof FrameLayout ? (ViewGroup) root : null;
        }

        private TextView item(Context ctx, String label, int color, View.OnClickListener l) {
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setTextSize(14);
            tv.setTextColor(color);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
            tv.setOnClickListener(l);
            return tv;
        }

        private void copySelection() {
            Session s = session;
            if (s == null) return;
            String text = s.model.copy(s.from(), s.to());
            if (!text.isEmpty()) {
                ClipboardManager cm = (ClipboardManager)
                        root.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("pocket", text));
                // 只记长度，不写剪贴板内容
                com.dsh.android.util.DebugLog.i("selection: copied " + text.length() + " chars");
                Toast.makeText(root.getContext(), R.string.copied_clipboard, Toast.LENGTH_SHORT).show();
            }
            clear();
        }
    }

    private static final class Session {
        final List<TextView> views;
        final SelectionModel model;

        int anchor;
        int focus;

        private final Map<TextView, CharSequence> originals = new HashMap<>();
        private final Map<TextView, CharSequence> applied = new HashMap<>();
        private final Map<TextView, int[]> ranges = new HashMap<>();

        Session(List<SelectionModel.Part> parts, List<TextView> views) {
            this.views = views;
            this.model = new SelectionModel(parts);
        }

        int from() {
            return Math.min(anchor, focus);
        }

        int to() {
            return Math.max(anchor, focus);
        }

        void moveTo(int offset) {
            focus = offset;
            apply();
        }

        /** 选择区交集落在第几段；落在段间换行时算前一段。 */
        int partIndexAt(int offset) {
            for (int i = 0; i < model.size(); i++) {
                if (offset <= model.endOf(i)) return i;
            }
            return model.size() - 1;
        }

        void apply() {
            int a = from();
            int b = to();
            for (int i = 0; i < views.size(); i++) {
                TextView tv = views.get(i);
                int ps = model.startOf(i);
                int pe = model.endOf(i);
                int s = Math.max(a, ps);
                int e = Math.min(b, pe);
                int ls = e > s ? s - ps : 0;
                int le = e > s ? e - ps : 0;
                int[] old = ranges.get(tv);
                if (old != null && old[0] == ls && old[1] == le) continue;
                ranges.put(tv, new int[]{ls, le});
                paint(tv, ls, le);
            }
        }

        private void paint(TextView tv, int start, int end) {
            CharSequence cur = tv.getText();
            CharSequence was = applied.get(tv);
            CharSequence orig = originals.get(tv);
            // 文字被外部更新（流式/工具输出）：以新文字为准重建，恢复时绝不覆盖别人的更新。
            if (orig == null || (was != null && !TextUtils.equals(cur, was))) {
                orig = cur == null ? "" : cur;
                originals.put(tv, orig);
            }
            if (end <= start) {
                if (was != null) {
                    if (TextUtils.equals(cur, was)) tv.setText(orig);
                    applied.remove(tv);
                }
                return;
            }
            int len = orig.length();
            int s = Math.min(start, len);
            int e = Math.min(end, len);
            if (e <= s) return;
            SpannableStringBuilder sb = new SpannableStringBuilder(orig);
            sb.setSpan(new BackgroundColorSpan(HIGHLIGHT), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            applied.put(tv, sb);
            tv.setText(sb);
        }

        /** 恢复所有高亮；只在当前文字仍是自己画过的那份时才恢复。 */
        void restore() {
            for (Map.Entry<TextView, CharSequence> e : applied.entrySet()) {
                TextView tv = e.getKey();
                CharSequence orig = originals.get(tv);
                if (orig != null && TextUtils.equals(tv.getText(), e.getValue())) {
                    tv.setText(orig);
                }
            }
            applied.clear();
            originals.clear();
            ranges.clear();
        }

        boolean touches(View container) {
            for (TextView tv : views) {
                for (View p = tv; p != null; ) {
                    if (p == container) return true;
                    ViewParent parent = p.getParent();
                    p = parent instanceof View ? (View) parent : null;
                }
            }
            return false;
        }

        int offsetAt(float rawX, float rawY) {
            int[] loc = new int[2];
            int best = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < views.size(); i++) {
                TextView tv = views.get(i);
                tv.getLocationOnScreen(loc);
                int w = tv.getWidth();
                int h = tv.getHeight();
                if (rawX >= loc[0] && rawX <= loc[0] + w && rawY >= loc[1] && rawY <= loc[1] + h) {
                    return model.startOf(i) + localOffset(tv, rawX, rawY);
                }
                int dx = (int) Math.max(0, Math.max(loc[0] - rawX, rawX - (loc[0] + w)));
                int dy = (int) Math.max(0, Math.max(loc[1] - rawY, rawY - (loc[1] + h)));
                int d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            if (best < 0) return 0;
            TextView tv = views.get(best);
            tv.getLocationOnScreen(loc);
            if (rawY < loc[1]) return model.startOf(best);
            if (rawY > loc[1] + tv.getHeight()) return model.endOf(best);
            return model.startOf(best) + localOffset(tv, rawX, rawY);
        }
    }

    // ---------------------------------------------------------------- 手势

    private static final class Gesture implements View.OnTouchListener {
        private final View scope;
        private final int slop;
        private final int selectSlop;
        private float downX;
        private float downY;
        private float lastX;
        private float lastY;
        private boolean pending;
        private boolean selecting;
        private long lastDownTime = -1;

        private final Runnable longPress;

        Gesture(View scope) {
            this.scope = scope;
            this.slop = ViewConfiguration.get(scope.getContext()).getScaledTouchSlop();
            // 选择的长按要比"滑动"更严格：稍微一动就取消，避免滑表格时误弹工具条。
            this.selectSlop = Math.max(2, slop / 2);
            this.longPress = () -> {
                if (!pending) return;
                pending = false;
                Hub hub = hubFor(scope);
                Scope s = (Scope) scope.getTag(TAG_SCOPE);
                if (s == null) return;
                ViewParent parent = scope.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                selecting = true;
                if (!hub.begin(scope, s, downX, downY)) {
                    selecting = false;
                    return;
                }
                // 立刻显示工具条：嵌套滚动容器可能吞掉 UP（表格/代码卡），
                // 不能把「能不能复制」押在最后一个事件上。
                hub.showBar(downX, downY);
                lastX = downX;
                lastY = downY;
            };
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            // 文字控件转发的事件消费策略与范围根不同：DOWN/UP 返回 false，
            // 让 LinkMovementMethod 的链接点击照常工作；只有进入选择态才接管。
            boolean owner = v == scope;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    long downTime = e.getDownTime();
                    if (pending && downTime == lastDownTime) {
                        return owner;             // 文字先收到，根布局又收到同一次 DOWN
                    }
                    Hub hub = hubFor(scope);
                    if (hub.session != null) hub.clear();
                    lastDownTime = downTime;
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    pending = true;
                    selecting = false;
                    scope.removeCallbacks(longPress);   // 清掉上一次可能残留的计时器
                    scope.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return owner;
                }
                case MotionEvent.ACTION_MOVE: {
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    if (selecting) {
                        Hub hub = hubFor(scope);
                        if (hub.session != null) hub.session.moveTo(hub.session.offsetAt(lastX, lastY));
                        return true;
                    }
                    if (pending && (Math.abs(lastX - downX) > selectSlop
                            || Math.abs(lastY - downY) > selectSlop)) {
                        pending = false;
                        scope.removeCallbacks(longPress);
                    }
                    return owner;
                }
                case MotionEvent.ACTION_UP: {
                    scope.removeCallbacks(longPress);
                    if (selecting) {
                        selecting = false;
                        releaseParent();
                        hubFor(scope).finish(lastX, lastY);
                        return true;
                    }
                    pending = false;
                    return owner;
                }
                case MotionEvent.ACTION_CANCEL: {
                    scope.removeCallbacks(longPress);
                    pending = false;
                    if (selecting) {
                        selecting = false;
                        releaseParent();
                        hubFor(scope).finish(lastX, lastY);
                        return true;
                    }
                    return owner;
                }
                default:
                    return false;
            }
        }

        private void releaseParent() {
            ViewParent parent = scope.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    // ---------------------------------------------------------------- 采集 / 偏移

    private static void collectScope(View v, List<SelectionModel.Part> parts, List<TextView> tvs) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.getTag(TAG_EXCLUDE) != null) return;
        if (v instanceof TextView && v.getTag(TAG_INCLUDE) != null) {
            addPart((TextView) v, parts, tvs);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectScope(g.getChildAt(i), parts, tvs);
            }
        }
    }

    /** 跨控件采集：只看 {@code cross} 范围；{@code local} 范围（用户消息）整段跳过。 */
    private static void collectCross(View v, boolean inCross,
                                     List<SelectionModel.Part> parts, List<TextView> tvs) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.getTag(TAG_EXCLUDE) != null) return;
        Scope s = (Scope) v.getTag(TAG_SCOPE);
        if (s != null) {
            if (MODE_LOCAL.equals(s.mode)) return;
            inCross = true;
        }
        if (inCross && v instanceof TextView && v.getTag(TAG_INCLUDE) != null) {
            addPart((TextView) v, parts, tvs);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectCross(g.getChildAt(i), inCross, parts, tvs);
            }
        }
    }

    private static void addPart(TextView tv, List<SelectionModel.Part> parts,
                                List<TextView> tvs) {
        CharSequence text = tv.getText();
        if (text == null || text.length() == 0) return;
        Object owner = tv.getTag(TAG_OWNER);
        parts.add(new SelectionModel.Part(text.toString(), owner == null ? tv : owner));
        tvs.add(tv);
    }

    private static int localOffset(TextView tv, float rawX, float rawY) {
        CharSequence text = tv.getText();
        int len = text == null ? 0 : text.length();
        Layout layout = tv.getLayout();
        if (layout == null) return 0;
        int[] loc = new int[2];
        tv.getLocationOnScreen(loc);
        float x = rawX - loc[0] - tv.getTotalPaddingLeft() + tv.getScrollX();
        float y = rawY - loc[1] - tv.getTotalPaddingTop() + tv.getScrollY();
        if (y < 0) return 0;
        if (y > layout.getHeight()) return len;
        int line = layout.getLineForVertical((int) y);
        int offset = layout.getOffsetForHorizontal(line, x);
        return Math.max(0, Math.min(offset, len));
    }

    // ---------------------------------------------------------------- 小工具

    private static int wordStart(String text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0 && isWordChar(text.charAt(i - 1))) i--;
        return i;
    }

    private static int wordEnd(String text, int offset) {
        int i = Math.min(offset, text.length());
        if (i < text.length() && !isWordChar(text.charAt(i)) && i > 0
                && isWordChar(text.charAt(i - 1))) {
            return i;
        }
        while (i < text.length() && isWordChar(text.charAt(i))) i++;
        return i;
    }

    private static boolean isWordChar(char c) {
        if (isCjk(c)) return false;                // CJK 单字处理，避免一次选中整句
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }
}
