package com.dsh.android.ui.framework;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.android.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * T3 窗口原语（应用内，不含系统级悬浮窗）。
 *
 * 宿主把 {@link #view()} 盖在内容层上，返回键先问 {@link #onBackPressed()}：
 * <pre>
 *   WindowLayer layer = new WindowLayer(activity);
 *   root.addView(layer.view());
 *   layer.show("deploy", UiNode.of(UiNode.WINDOW).prop("title", "部署").child(...).build());
 * </pre>
 *
 * 能力：
 *  - 最多 {@link #MAX_WINDOWS} 个活窗口，超出 show() 返回 false；
 *  - 标题栏拖动、右下角缩放（最小 160×120dp）；
 *  - 点击任意位置置顶（layer 内部处理，不需要事件总线）；
 *  - 标题栏 «—» 最小化、«×» 关闭；返回键先最小化最上层，已最小化则关闭；
 *  - 几何按 key 持久化到 SharedPreferences；
 *  - 非活动窗口的更新只暂存，等置顶时才渲染（冻结渲染）。
 */
public final class WindowLayer {

    public static final int MAX_WINDOWS = 3;
    private static final String PREFS = "window_geometry";

    private final Context ctx;
    private final FrameLayout root;
    private final SharedPreferences prefs;
    private final LinkedHashMap<String, WindowFrame> frames = new LinkedHashMap<>();
    private String activeKey;
    private UiEventDispatcher.Handler eventHandler;
    private UiActionBus.Handler actionHandler;

    public WindowLayer(Context ctx) {
        this.ctx = ctx;
        this.root = new FrameLayout(ctx);
        this.root.setClipChildren(false);
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public View view() {
        return root;
    }

    public int size() {
        return frames.size();
    }

    public String activeKey() {
        return activeKey;
    }

    public List<String> keys() {
        return new ArrayList<>(frames.keySet());
    }

    /** 窗口内容的点击/输入事件统一交给宿主；和主内容区共用一套 handler 即可。 */
    public void setEventHandler(UiEventDispatcher.Handler handler) {
        this.eventHandler = handler;
    }

    public void setActionHandler(UiActionBus.Handler handler) {
        this.actionHandler = handler;
    }

    public boolean isMinimized(String key) {
        WindowFrame f = frames.get(key);
        return f != null && f.minimized;
    }

    /** 关闭全部窗口并清掉持久化几何（调试页/验收用，保证从干净状态开始）。 */
    public void clearAll() {
        for (String key : new ArrayList<>(frames.keySet())) close(key);
        prefs.edit().clear().apply();
    }

    /** 打开或更新窗口；已有 3 个活窗口时返回 false。 */
    public boolean show(String key, UiNode window) {
        if (key == null || window == null) return false;
        WindowFrame f = frames.get(key);
        if (f == null) {
            if (frames.size() >= MAX_WINDOWS) return false;
            f = new WindowFrame(key, frames.size());
            frames.put(key, f);
            root.addView(f.frame);
            f.restoreGeometry();
        }
        f.update(window);
        bringToFront(key);
        return true;
    }

    /** 只更新内容；非活动窗口暂存，置顶时再渲染。 */
    public boolean update(String key, UiNode window) {
        WindowFrame f = frames.get(key);
        if (f == null || window == null) return false;
        f.update(window);
        return true;
    }

    public boolean close(String key) {
        WindowFrame f = frames.remove(key);
        if (f == null) return false;
        f.saveGeometry();
        root.removeView(f.frame);
        if (key.equals(activeKey)) {
            activeKey = topKey();
            WindowFrame top = activeKey == null ? null : frames.get(activeKey);
            if (top != null) top.onActivated();
        }
        return true;
    }

    public boolean minimize(String key) {
        WindowFrame f = frames.get(key);
        if (f == null) return false;
        f.setMinimized(true);
        return true;
    }

    public boolean restore(String key) {
        WindowFrame f = frames.get(key);
        if (f == null) return false;
        f.setMinimized(false);
        bringToFront(key);
        return true;
    }

    public void bringToFront(String key) {
        WindowFrame f = frames.get(key);
        if (f == null) return;
        root.bringChildToFront(f.frame);
        activeKey = key;
        f.onActivated();
    }

    /** true 表示消费了返回键：先最小化最上层，已最小化则关闭。 */
    public boolean onBackPressed() {
        WindowFrame f = topFrame();
        if (f == null) return false;
        if (!f.minimized) {
            f.setMinimized(true);
            return true;
        }
        close(f.key);
        return true;
    }

    private WindowFrame topFrame() {
        String key = topKey();
        return key == null ? null : frames.get(key);
    }

    private String topKey() {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            for (Map.Entry<String, WindowFrame> e : frames.entrySet()) {
                if (e.getValue().frame == child) return e.getKey();
            }
        }
        return null;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- 单个窗口

    private final class WindowFrame {

        final String key;
        final FrameLayout frame;
        final LinearLayout chrome;
        final LinearLayout titleBar;
        final TextView title;
        final FrameLayout content;
        final View resize;
        final UiFramework ui;
        final int cascadeIndex;

        UiNode pending;
        WindowGeometry geometry;
        int geomW;
        int geomH;
        boolean minimized;
        boolean resizable = true;

        WindowFrame(String key, int cascadeIndex) {
            this.key = key;
            this.cascadeIndex = cascadeIndex;

            frame = new FrameLayout(ctx) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        // 交互子控件（button/input）会消费触摸，父容器的 OnTouchListener 收不到；
                        // 在 dispatch 阶段置顶，保证点窗口内任何位置都能激活。
                        post(() -> WindowLayer.this.bringToFront(WindowFrame.this.key));
                    }
                    return super.dispatchTouchEvent(ev);
                }
            };
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ctx.getColor(R.color.layer1));
            bg.setCornerRadius(dp(14));
            bg.setStroke(dp(1), ctx.getColor(R.color.border));
            frame.setBackground(bg);
            frame.setElevation(dp(12));
            frame.setClipChildren(false);

            chrome = new LinearLayout(ctx);
            chrome.setOrientation(LinearLayout.VERTICAL);

            titleBar = new LinearLayout(ctx);
            titleBar.setOrientation(LinearLayout.HORIZONTAL);
            titleBar.setGravity(Gravity.CENTER_VERTICAL);
            titleBar.setPadding(dp(14), 0, dp(8), 0);

            title = new TextView(ctx);
            title.setTextColor(ctx.getColor(R.color.text_primary));
            title.setTextSize(14f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            titleBar.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            titleBar.addView(chromeButton("—"), new LinearLayout.LayoutParams(dp(36), dp(36)));
            titleBar.addView(chromeButton("×"), new LinearLayout.LayoutParams(dp(36), dp(36)));

            content = new FrameLayout(ctx);
            content.setPadding(dp(14), dp(4), dp(14), dp(12));

            chrome.addView(titleBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            chrome.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            frame.addView(chrome, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            resize = new View(ctx);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(24), dp(24));
            rlp.gravity = Gravity.BOTTOM | Gravity.END;
            frame.addView(resize, rlp);

            ui = new UiFramework(content);
            ui.events().setHandler(event ->
                    eventHandler == null ? null : eventHandler.onEvent(event));
            ui.actions().setHandler(action ->
                    actionHandler == null ? null : actionHandler.invoke(action));

            titleBar.setOnTouchListener(new DragTouch());
            resize.setOnTouchListener(new ResizeTouch());
        }

        private TextView chromeButton(String label) {
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(17f);
            tv.setTextColor(ctx.getColor(R.color.text_secondary));
            if ("—".equals(label)) tv.setOnClickListener(v -> setMinimized(true));
            else tv.setOnClickListener(v -> close(key));
            return tv;
        }

        void update(UiNode window) {
            String t = window.str("title", key);
            if (!t.contentEquals(title.getText())) title.setText(t);
            resizable = window.bool("resizable", true);
            resize.setVisibility(resizable && !minimized ? View.VISIBLE : View.GONE);

            int w = window.integer("w", 0);
            int h = window.integer("h", 0);
            if (w > 0 && h > 0 && (w != geomW || h != geomH)) {
                geomW = w;
                geomH = h;
                applySize();
            }

            UiNode contentRoot = contentNode(window);
            if (key.equals(activeKey)) renderRoot(contentRoot);
            else pending = contentRoot;             // 非活动窗口冻结，置顶时再渲染
        }

        boolean isActive() {
            return key.equals(activeKey);
        }

        void onActivated() {
            if (pending != null) {
                renderRoot(pending);
                pending = null;
            }
        }

        private UiNode contentNode(UiNode window) {
            UiNode.Builder col = UiNode.column().key("win:" + key);
            for (UiNode child : window.children()) col.child(child);
            return col.build();
        }

        private void renderRoot(UiNode root) {
            UiRenderer.Stats s = ui.render(root);
            if (s.warning != null) {
                // 窗口内容超预算/文本截断属于可见异常，留给调试页读取
                lastWarning = s.warning;
            }
        }

        void restoreGeometry() {
            WindowGeometry def = new WindowGeometry(
                    16 + cascadeIndex * 18, 72 + cascadeIndex * 18, 280, 320, false);
            geometry = WindowGeometry.parse(prefs.getString("win." + key, null), def);
            geomW = geometry.w;
            geomH = geometry.h;
            minimized = geometry.minimized;
            applySize();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            lp.leftMargin = dp(geometry.x);
            lp.topMargin = dp(geometry.y);
            frame.setLayoutParams(lp);
            setMinimized(minimized);
        }

        void saveGeometry() {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            float density = ctx.getResources().getDisplayMetrics().density;
            int x = (int) (lp.leftMargin / density);
            int y = (int) (lp.topMargin / density);
            geometry = new WindowGeometry(x, y, geomW, geomH, minimized);
            prefs.edit().putString("win." + key, geometry.format()).apply();
        }

        void setMinimized(boolean m) {
            if (minimized == m && content.getVisibility() == (m ? View.GONE : View.VISIBLE)) return;
            minimized = m;
            content.setVisibility(m ? View.GONE : View.VISIBLE);
            resize.setVisibility(m || !resizable ? View.GONE : View.VISIBLE);
            applySize();
            saveGeometry();
        }

        private void applySize() {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            if (lp == null) lp = new FrameLayout.LayoutParams(dp(geomW), dp(geomH));
            lp.width = dp(geomW);
            lp.height = minimized ? dp(44) : dp(geomH);
            frame.setLayoutParams(lp);
        }

        private void moveBy(int dx, int dy) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            int maxX = Math.max(0, root.getWidth() - frame.getWidth());
            int maxY = Math.max(0, root.getHeight() - frame.getHeight());
            lp.leftMargin = Math.max(0, Math.min(maxX, lp.leftMargin + dx));
            lp.topMargin = Math.max(0, Math.min(maxY, lp.topMargin + dy));
            frame.setLayoutParams(lp);
        }

        private void resizeBy(int dw, int dh) {
            float density = ctx.getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            int w = Math.max(dp(WindowGeometry.MIN_W), frame.getWidth() + dw);
            int h = Math.max(dp(WindowGeometry.MIN_H), frame.getHeight() + dh);
            int maxW = Math.max(dp(WindowGeometry.MIN_W), root.getWidth() - lp.leftMargin);
            int maxH = Math.max(dp(WindowGeometry.MIN_H), root.getHeight() - lp.topMargin);
            geomW = Math.round(Math.min(w, maxW) / density);
            geomH = Math.round(Math.min(h, maxH) / density);
            applySize();
        }

        private final class DragTouch implements View.OnTouchListener {
            float downX;
            float downY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        bringToFront(key);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > 2 || Math.abs(dy) > 2) moved = true;
                        moveBy((int) dx, (int) dy);
                        downX = e.getRawX();
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved && minimized) setMinimized(false);   // 最小化后点标题栏还原
                        saveGeometry();
                        return true;
                    default:
                        return false;
                }
            }
        }

        private final class ResizeTouch implements View.OnTouchListener {
            float downX;
            float downY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        bringToFront(key);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        resizeBy((int) (e.getRawX() - downX), (int) (e.getRawY() - downY));
                        downX = e.getRawX();
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveGeometry();
                        return true;
                    default:
                        return false;
                }
            }
        }

        /** 最近一次内容渲染的警告（调试页读）。 */
        String lastWarning;
    }

    /** 调试/验收用：最近一次内容渲染的警告，没有返回 null。 */
    public String warning(String key) {
        WindowFrame f = frames.get(key);
        return f == null ? null : f.lastWarning;
    }

    public String geometryText(String key) {
        WindowFrame f = frames.get(key);
        return f == null || f.geometry == null ? null : f.geometry.format();
    }

    /** 调试/验收用：读窗口内容里某个 key 的文本（验证冻结更新是否生效）。 */
    public String contentText(String windowKey, String nodeKey) {
        WindowFrame f = frames.get(windowKey);
        if (f == null) return null;
        UiRenderer.Binding b = f.ui.renderer().findBinding(nodeKey);
        if (b == null || !(b.view instanceof TextView)) return null;
        return ((TextView) b.view).getText().toString();
    }

    /** 调试/验收用：把窗口移到指定 dp 坐标（不做手势模拟）。 */
    public boolean moveTo(String key, int xDp, int yDp) {
        WindowFrame f = frames.get(key);
        if (f == null) return false;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) f.frame.getLayoutParams();
        lp.leftMargin = dp(xDp);
        lp.topMargin = dp(yDp);
        f.frame.setLayoutParams(lp);
        f.saveGeometry();
        return true;
    }

    /** 调试/验收用：设置窗口尺寸（dp）。 */
    public boolean resizeTo(String key, int wDp, int hDp) {
        WindowFrame f = frames.get(key);
        if (f == null) return false;
        f.geomW = Math.max(WindowGeometry.MIN_W, wDp);
        f.geomH = Math.max(WindowGeometry.MIN_H, hDp);
        f.applySize();
        f.saveGeometry();
        return true;
    }
}
