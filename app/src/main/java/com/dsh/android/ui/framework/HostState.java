package com.dsh.android.ui.framework;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 宿主实时状态：把 run 时的真实值发布到全局 {@link UiStateBus}，供节点 {@code bind} 绑定。
 *
 * <p>这是 v2 state 的宿主侧实现：聊天流、插件插槽/页面、设置页共用一个总线，值变化最多
 * 每 100ms 合并一次并触发各自的增量重渲染。插件侧 {@code bridge.state.set} 仍待 bridge 轮次。
 *
 * <p>纯格式化/夹取逻辑（{@link #summary(int, int)} / {@link #ratio(int, int)}）保持静态可测；
 * Handler 只在 {@link #install(Context)} 里创建，避免 JVM 单测加载类时触发 Android stub。
 */
public final class HostState {

    public static final int DEFAULT_LIMIT = 100_000;

    private static volatile UiStateBus bus;
    private static Context app;
    /** install/stop 共用一把锁：避免重复 install 起两个 ticker。 */
    private static volatile boolean installed;
    private static Handler tickHandler;
    private static Runnable tickTask;

    private HostState() {
    }

    public static UiStateBus bus() {
        UiStateBus b = bus;
        if (b == null) {
            synchronized (HostState.class) {
                if (bus == null) {
                    bus = new UiStateBus();
                    bus.seedBuiltins();
                }
                b = bus;
            }
        }
        return b;
    }

    /** 应用启动时调一次：预置内置值、真实显示参数与时间 ticker。重复调用无副作用。 */
    public static synchronized void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        app = ctx.getApplicationContext();
        UiStateBus b = bus();
        b.seedBuiltins();
        publishDisplay(app);
        publish("pocket.theme.id", "default");
        publish("pocket.theme.name", "默认主题");
        final Handler main = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!installed || tickTask != this) return;   // 已 stop：不再排下一次
                publishTime();
                main.postDelayed(this, 30_000);
            }
        };
        tickHandler = main;
        tickTask = tick;
        main.post(tick);
    }

    /** 停掉时间 ticker（幂等）；宿主真正退出时调用，避免后台长期无谓 publish。 */
    public static synchronized void stop() {
        installed = false;
        Handler h = tickHandler;
        Runnable task = tickTask;
        tickHandler = null;
        tickTask = null;
        if (h != null && task != null) h.removeCallbacks(task);
    }

    public static void publish(String name, Object value) {
        if (name == null || name.isEmpty()) return;
        bus().set(name, value);
    }

    public static void publishSession(String id, String title) {
        publish("pocket.session.id", id == null ? "" : id);
        publish("pocket.session.title", title == null ? "" : title);
    }

    /** 上下文统计：tokens/limit/ratio/summary/messages 一并发布。 */
    public static void publishContext(int tokens, int limit, int messages) {
        int lim = limit > 0 ? limit : DEFAULT_LIMIT;
        publish("pocket.context.tokens", tokens);
        publish("pocket.context.limit", lim);
        publish("pocket.context.ratio", ratio(tokens, lim));
        publish("pocket.context.summary", summary(tokens, lim));
        publish("pocket.context.messages", messages);
        publish("pocket.context.compacting", Boolean.FALSE);
    }

    /** 窗口尺寸/深浅色/方向/字体缩放/动画开关。 */
    public static void publishDisplay(Context ctx) {
        if (ctx == null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        Configuration c = ctx.getResources().getConfiguration();
        publish("pocket.display.width", Math.round(dm.widthPixels / dm.density));
        publish("pocket.display.height", Math.round(dm.heightPixels / dm.density));
        publish("pocket.display.density", dm.density);
        publish("pocket.display.orientation",
                c.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait");
        publish("pocket.display.dark",
                (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        publish("pocket.display.fontScale", c.fontScale);
        float anim = 1f;
        try {
            anim = Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
        }
        publish("pocket.display.reduceMotion", anim == 0f);
    }

    /** 刘海/手势条 insets（dp）；布局完成后调用。 */
    public static void publishInsets(View decor) {
        if (decor == null) return;
        WindowInsets insets;
        try {
            insets = decor.getRootWindowInsets();
        } catch (Throwable e) {
            return;
        }
        if (insets == null) return;
        float d = decor.getResources().getDisplayMetrics().density;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("top", Math.round(insets.getSystemWindowInsetTop() / d));
        m.put("bottom", Math.round(insets.getSystemWindowInsetBottom() / d));
        m.put("left", Math.round(insets.getSystemWindowInsetLeft() / d));
        m.put("right", Math.round(insets.getSystemWindowInsetRight() / d));
        publish("pocket.display.insets", m);
    }

    public static Map<String, Object> snapshot() {
        return bus().snapshot();
    }

    // ---------------------------------------------------------------- 纯逻辑

    public static double ratio(int tokens, int limit) {
        if (limit <= 0) return 0;
        double r = tokens / (double) limit;
        return Math.max(0, Math.min(1, r));
    }

    /** 与文档示例一致：{@code 62% · 62.0k / 100k}。 */
    public static String summary(int tokens, int limit) {
        if (limit <= 0) return "";
        return Math.round(ratio(tokens, limit) * 100) + "% · "
                + compact(tokens) + " / " + compact(limit);
    }

    static String compact(int n) {
        if (n < 1000) return String.valueOf(n);
        double k = n / 1000.0;
        return String.format(Locale.ROOT, k >= 100 ? "%.0fk" : "%.1fk", k);
    }

    private static void publishTime() {
        long now = System.currentTimeMillis();
        publish("pocket.time.now", now);
        publish("pocket.time.text", new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(now)));
        publish("pocket.time.date", new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(now)));
    }
}
