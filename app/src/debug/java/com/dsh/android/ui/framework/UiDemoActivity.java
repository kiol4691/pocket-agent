package com.dsh.android.ui.framework;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.android.R;
import com.dsh.android.ui.ChatBlockView;
import com.dsh.android.ui.MarkdownRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 仅 debug 构建：Ui 框架五页对照（组件 / 交互 / 主题 / 流式 / 窗口），每页自动跑并印统计。
 *
 * 1. 组件：所有内置 type 渲染 + 未知 type 丢弃 + 首帧/复渲染的 created/reused；
 * 2. 交互：按钮→动作→状态→bind 注入，input/switch/checkbox/slider 的 change 计数；
 * 3. 主题：展示树 + applyTheme/resetTheme 的 created、标题字号、对比度警告；
 * 4. 流式：单 text 120 帧 vs text.chunks+appendText 120 帧；
 * 5. 窗口：最多 3 个、拖动/缩放/置顶/最小化/返回键/几何持久化/冻结渲染 + taskbar。
 *
 * 不调模型、不发真实 AI 会话；release 构建不含本类。
 */
public final class UiDemoActivity extends Activity {

    private static final String TAG = "UiDemo";
    private static final int STREAM_FRAMES = 120;

    private static final int TAB_COMPONENTS = 0;
    private static final int TAB_INTERACTION = 1;
    private static final int TAB_THEME = 2;
    private static final int TAB_STREAM = 3;
    private static final int TAB_WINDOWS = 4;
    private static final int TAB_CHAT = 5;
    private static final String[] TAB_NAMES = {"组件", "交互", "主题", "流式", "窗口", "聊天"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] tabs = new TextView[TAB_NAMES.length];

    private LinearLayout content;
    private LinearLayout host;
    private TextView statsView;
    private WindowLayer windowLayer;
    private UiFramework fw;
    private UiRenderer renderer;
    private int tabIndex = -1;

    private int actionCount;
    private int eventCount;
    private int stateCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MarkdownRenderer.init(getApplicationContext());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.bg_base));
        int pad = UiTheme.dp(this, 16);
        page.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("UiRenderer 对照页");
        title.setTextSize(18f);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 4));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(TAB_NAMES[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(13f);
            tab.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 8));
            tab.setOnClickListener(v -> selectTab(index));
            bar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabs[i] = tab;
        }
        page.addView(bar);

        statsView = new TextView(this);
        statsView.setTextSize(11f);
        statsView.setTextColor(getColor(R.color.text_caption));
        statsView.setTypeface(Typeface.MONOSPACE);
        statsView.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 8));
        page.addView(statsView);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        page.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("每页自动执行，不调模型、不发会话。所有数字由框架自己统计。");
        hint.setTextSize(11f);
        hint.setTextColor(getColor(R.color.text_caption));
        hint.setPadding(0, UiTheme.dp(this, 8), 0, 0);
        page.addView(hint);

        FrameLayout rootView = new FrameLayout(this);
        rootView.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        windowLayer = new WindowLayer(this);
        rootView.addView(windowLayer.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootView);

        selectTab(TAB_COMPONENTS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (windowLayer != null && windowLayer.onBackPressed()) return;
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 分页

    private void selectTab(int index) {
        if (index == tabIndex) return;
        handler.removeCallbacksAndMessages(null);
        tabIndex = index;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == index ? getColor(R.color.brand)
                    : getColor(R.color.text_caption));
            tabs[i].setTypeface(null, i == index ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (index != TAB_WINDOWS) windowLayer.clearAll();

        actionCount = 0;
        eventCount = 0;
        stateCount = 0;

        content.removeAllViews();
        host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(host);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fw = new UiFramework(host);
        renderer = fw.renderer();
        fw.actions().setHandler(action -> {
            actionCount++;
            return null;
        });
        fw.events().setHandler(event -> {
            eventCount++;
            if ("tap".equals(event.event) && event.value instanceof String) {
                windowLayer.restore((String) event.value);      // taskbar → 还原窗口
            }
            return null;
        });
        statsView.setText("运行中…");

        switch (index) {
            case TAB_COMPONENTS:
                runComponents();
                break;
            case TAB_INTERACTION:
                runInteraction();
                break;
            case TAB_THEME:
                runTheme();
                break;
            case TAB_STREAM:
                runStream();
                break;
            case TAB_WINDOWS:
                runWindows();
                break;
            case TAB_CHAT:
                runChat();
                break;
            default:
                break;
        }
    }

    private boolean active(int tab) {
        return tabIndex == tab;
    }

    // ---------------------------------------------------------------- 1. 组件

    private void runComponents() {
        UiNode tree = componentsTree();
        UiRenderer.Stats first = fw.render(tree);
        int created = first.created;

        UiRenderer.Stats second = fw.render(tree);
        int created2 = second.created;
        int reused2 = second.reused;

        // 树不变 100 帧：纯 diff + 状态注入 + 事件挂载
        double sum = 0;
        double max = 0;
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            fw.render(tree);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            sum += ms;
            max = Math.max(max, ms);
        }
        double avg = sum / 100;

        // 节点预算：320 个节点应收敛到 200
        UiNode.Builder col = UiNode.column().key("budgetRoot").prop("gap", "xs");
        for (int i = 0; i < 320; i++) {
            col.child(UiNode.text("节点 #" + i).key("n" + i).prop("size", "caption"));
        }
        UiRenderer.Stats budget = fw.render(col.build());
        int budgetNodes = budget.nodes;
        int skipped = budget.skipped;

        // absolute 几何更新：结构不变时 x 变化也必须生效
        fw.render(UiNode.of(UiNode.ABSOLUTE).key("absProbe").prop("h", 60)
                .child(UiNode.text("probe").key("ap").prop("x", 10).prop("y", 4)).build());
        fw.render(UiNode.of(UiNode.ABSOLUTE).key("absProbe").prop("h", 60)
                .child(UiNode.text("probe").key("ap").prop("x", 60).prop("y", 4)).build());
        UiRenderer.Binding probe = renderer.findBinding("ap");
        float density = getResources().getDisplayMetrics().density;
        boolean moved = probe != null
                && probe.view.getLayoutParams() instanceof FrameLayout.LayoutParams
                && ((FrameLayout.LayoutParams) probe.view.getLayoutParams()).leftMargin
                == (int) (60 * density + 0.5f);

        fw.render(tree);                          // 回到组件页内容

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("type", "bogus");
        UiNode.ParseReport report = new UiNode.ParseReport();
        boolean dropped = UiNode.fromJava(bad, report) == null && report.dropped == 1;

        statsView.setText(String.format(Locale.ROOT,
                "组件：注册 %d 种；首帧建 %d；复渲染 created=%d reused=%d；"
                        + "diff 100 帧 avg %.2f/ max %.2fms；预算 320→%d skipped=%d；"
                        + "absolute 几何更新=%s；未知 type 丢弃=%s",
                renderer.registry().size(), created, created2, reused2,
                avg, max, budgetNodes, skipped, moved ? "✓" : "✗", dropped ? "✓" : "✗"));
        Log.i(TAG, "components " + statsView.getText());
    }

    private UiNode componentsTree() {
        List<Object> bars = new ArrayList<>();
        for (int i = 0; i < 8; i++) bars.add(4 + (i * 7) % 22);
        return UiNode.column().key("croot").prop("gap", "md")
                .child(UiNode.text("组件注册表").key("ct").prop("size", "title"))
                .child(UiNode.text("普通正文 · secondary").key("cb")
                        .prop("size", "body").prop("tone", "secondary"))
                .child(UiNode.card().key("ccard").prop("tone", "surface").prop("pad", "md")
                        .child(UiNode.row().key("crow").prop("gap", "sm")
                                .child(UiNode.of(UiNode.BADGE).key("cbadge")
                                        .prop("text", "运行中").prop("tone", "ok"))
                                .child(UiNode.text("卡片内文本").key("cct"))))
                .child(UiNode.code("$ make demo\nBUILD SUCCESSFUL").key("ccode"))
                .child(UiNode.of(UiNode.PROGRESS).key("cprog").prop("value", "0.62"))
                .child(UiNode.of(UiNode.BARS).key("cbars").prop("values", bars))
                .child(UiNode.of(UiNode.SPACER).key("csp").prop("space", "sm"))
                .child(UiNode.divider().key("cdiv"))
                .child(UiNode.markdown("**Markdown** 块\n\n- 项目一\n- 项目二").key("cmd"))
                .child(UiNode.of(UiNode.NAV_LIST).key("cnav")
                        .prop("group", "插件页面").prop("variant", "sidebar"))
                .child(UiNode.of(UiNode.ABSOLUTE).key("cabs").prop("h", 80)
                        .child(UiNode.text("absolute (12,8)").key("cabs1")
                                .prop("x", 12).prop("y", 8).prop("size", "caption"))
                        .child(UiNode.text("absolute (120,44)").key("cabs2")
                                .prop("x", 120).prop("y", 44).prop("size", "caption")
                                .prop("tone", "brand")))
                .build();
    }

    // ---------------------------------------------------------------- 2. 交互

    private void runInteraction() {
        fw.actions().setHandler(action -> {
            actionCount++;
            if (fw.states().set("demo.count", String.valueOf(actionCount))) stateCount++;
            return null;
        });
        fw.states().set("demo.count", "0");
        UiNode tree = interactionTree();
        fw.render(tree);

        for (int i = 0; i < 3; i++) fw.events().fire("btn", "tap", null);
        fw.events().fire("sw", "change", true);
        fw.events().fire("ck", "change", false);
        fw.events().fire("sl", "change", 40);
        assertChangeEvent();

        handler.postDelayed(() -> {
            if (!active(TAB_INTERACTION)) return;
            fw.render(tree);
            statsView.setText(String.format(Locale.ROOT,
                    "交互：动作=%d（count=%s）；事件=%d（节流 %d）；状态推送=%d",
                    actionCount, fw.states().get("demo.count"), eventCount,
                    fw.events().throttledCount(), stateCount));
            Log.i(TAG, "interaction " + statsView.getText());
        }, 350);
    }

    private void assertChangeEvent() {
        // input 的 change 只在提交/失焦发；这里直接走 dispatcher，验证计数链路
        fw.events().fire("in", "change", "abc");
    }

    private UiNode interactionTree() {
        return UiNode.column().key("iroot").prop("gap", "sm")
                .child(UiNode.text("交互 / 状态 / 动作").key("it").prop("size", "title"))
                .child(UiNode.text("0").key("icount").prop("size", "title").prop("tone", "brand")
                        .prop("bind", Collections.singletonMap("text", "demo.count")))
                .child(UiNode.of(UiNode.BUTTON).key("btn").prop("label", "点击 +1")
                        .prop("on", Collections.singletonMap("tap", "action:demo.inc")))
                .child(UiNode.of(UiNode.SWITCH).key("sw").prop("label", "开关")
                        .prop("on", Collections.singletonMap("change", "demo.change")))
                .child(UiNode.of(UiNode.CHECKBOX).key("ck").prop("label", "复选")
                        .prop("on", Collections.singletonMap("change", "demo.change")))
                .child(UiNode.of(UiNode.SLIDER).key("sl").prop("value", 20)
                        .prop("min", 0).prop("max", 100)
                        .prop("on", Collections.singletonMap("change", "demo.change")))
                .child(UiNode.of(UiNode.INPUT).key("in").prop("placeholder", "输入框")
                        .prop("on", Collections.singletonMap("change", "demo.change")))
                .build();
    }

    // ---------------------------------------------------------------- 3. 主题

    private void runTheme() {
        Map<String, Object> low = new LinkedHashMap<>();
        low.put("color.bg", "#000000");
        low.put("color.text", "#111111");
        low.put("color.textSecondary", "#222222");
        low.put("color.caption", "#333333");
        low.put("color.brand", "#444444");
        int lowWarnings = fw.applyTheme(ThemePack.fromJava(low), true).size();
        fw.resetTheme();

        UiRenderer.Stats s0 = fw.render(themeTree());
        int created0 = s0.created;
        TextView titleView = bindingView("th");
        float beforePx = titleView == null ? 0 : titleView.getTextSize();

        Map<String, Object> tokens = new LinkedHashMap<>();
        Map<String, Object> brand = new LinkedHashMap<>();
        brand.put("light", "#1F7A4D");
        brand.put("dark", "#7CFFB2");
        tokens.put("color.brand", brand);
        tokens.put("font.title", 26);
        tokens.put("font.body", 18);
        tokens.put("space.m", 20);
        tokens.put("radius.card", 20);
        tokens.put("motion.enter", "fade");
        tokens.put("motion.duration", 200);

        List<String> warnings = fw.applyTheme(ThemePack.fromJava(tokens), true);
        int created1 = renderer.stats().created;
        float themedPx = titleView == null ? 0 : titleView.getTextSize();
        float themedSp = renderer.theme().sizeSp("title");

        fw.resetTheme();
        int created2 = renderer.stats().created;
        float backPx = titleView == null ? 0 : titleView.getTextSize();
        float backSp = renderer.theme().sizeSp("title");

        // 停在主题已应用状态，便于截图
        fw.applyTheme(ThemePack.fromJava(tokens), true);
        statsView.setText(String.format(Locale.ROOT,
                "主题：展示树建 %d；切换 created=%d 恢复 created=%d；标题 %.1f→%.1f→%.1fpx"
                        + "（resolver %.0f/%.0fsp）；低对比警告 %d 条（只提示不拦）；本包警告 %d 条",
                created0, created1, created2, beforePx, themedPx, backPx,
                themedSp, backSp, lowWarnings, warnings.size()));
        Log.i(TAG, "theme " + statsView.getText());
    }

    private TextView bindingView(String key) {
        UiRenderer.Binding b = renderer.findBinding(key);
        return b != null && b.view instanceof TextView ? (TextView) b.view : null;
    }

    private UiNode themeTree() {
        List<Object> bars = new ArrayList<>();
        for (int i = 0; i < 8; i++) bars.add(4 + (i * 7) % 22);
        return UiNode.column().key("themeRoot").prop("gap", "md")
                .child(UiNode.text("主题演示 · 标题").key("th").prop("size", "title"))
                .child(UiNode.text("brand 色正文").key("tb").prop("size", "body")
                        .prop("tone", "brand"))
                .child(UiNode.text("说明文字").key("tc").prop("size", "caption")
                        .prop("tone", "caption"))
                .child(UiNode.card().key("tcard").prop("tone", "surface").prop("pad", "md")
                        .child(UiNode.row().key("trow").prop("gap", "sm")
                                .child(UiNode.of(UiNode.BADGE).key("tbadge")
                                        .prop("text", "运行中").prop("tone", "ok"))
                                .child(UiNode.text("卡片内文本").key("tct"))))
                .child(UiNode.code("$ echo theme").key("tcode"))
                .child(UiNode.of(UiNode.BUTTON).key("tbtn").prop("label", "主按钮"))
                .child(UiNode.of(UiNode.INPUT).key("tin").prop("placeholder", "输入框")
                        .prop("value", "hello"))
                .child(UiNode.of(UiNode.SWITCH).key("tsw").prop("label", "开关")
                        .prop("checked", true))
                .child(UiNode.of(UiNode.PROGRESS).key("tprog").prop("value", "0.62"))
                .child(UiNode.of(UiNode.BARS).key("tbars").prop("values", bars))
                .child(UiNode.divider().key("tdiv"))
                .build();
    }

    // ---------------------------------------------------------------- 4. 流式

    private void runStream() {
        final StringBuilder text = new StringBuilder();
        final int frames = STREAM_FRAMES;
        final double[] acc = new double[2];          // sum / max
        final int[] done = {0};

        fw.render(streamTree("等待流式开始…"));
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!active(TAB_STREAM)) return;
                done[0]++;
                text.append(String.format(Locale.ROOT,
                        "第 %d 帧：流式追加，只该 patch 尾部节点。\n", done[0]));
                long t0 = System.nanoTime();
                UiRenderer.Stats s = fw.render(streamTree(text.toString()));
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                acc[0] += ms;
                acc[1] = Math.max(acc[1], ms);
                statsView.setText(String.format(Locale.ROOT,
                        "流式 %d/%d  avg %.2fms  max %.2fms  created=%d",
                        done[0], frames, acc[0] / done[0], acc[1], s.created));
                if (done[0] < frames) {
                    handler.postDelayed(this, 40);
                } else {
                    runChunkStage(acc[0] / frames, acc[1]);
                }
            }
        };
        handler.postDelayed(tick, 300);
    }

    private void runChunkStage(double singleAvg, double singleMax) {
        if (!active(TAB_STREAM)) return;
        fw.render(chunksTree("开始：\n\n"));
        int frames = STREAM_FRAMES;
        double sum = 0;
        double max = 0;
        for (int i = 0; i < frames; i++) {
            String delta = "第 " + (i + 1) + " 帧追加的一段文本。\n\n";
            long t0 = System.nanoTime();
            fw.appendText("chunkStream", delta);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            sum += ms;
            max = Math.max(max, ms);
        }
        UiRenderer.Stats s = fw.render(chunksTree("完成（见汇总）"));
        statsView.setText(String.format(Locale.ROOT,
                "流式：单 text %d 帧 avg %.2f/ max %.2fms；"
                        + "text.chunks %d 帧 avg %.3f/ max %.3fms；末尾 created=%d",
                STREAM_FRAMES, singleAvg, singleMax, frames, sum / frames, max, s.created));
        Log.i(TAG, "stream " + statsView.getText());
    }

    private UiNode streamTree(String body) {
        return UiNode.column().key("sroot").prop("gap", "sm")
                .child(UiNode.text("流式渲染（单 text）").key("st").prop("size", "title"))
                .child(UiNode.text(body).key("stream"))
                .build();
    }

    private UiNode chunksTree(String body) {
        return UiNode.column().key("kroot").prop("gap", "sm")
                .child(UiNode.text("流式渲染（text.chunks）").key("kt").prop("size", "title"))
                .child(UiNode.of(UiNode.TEXT_CHUNKS).key("chunkStream").prop("text", body))
                .build();
    }

    // ---------------------------------------------------------------- 5. 窗口

    private void runWindows() {
        windowLayer.clearAll();
        boolean a = windowLayer.show("w1", windowNode("w1", "部署窗口", "服务已启动"));
        boolean b = windowLayer.show("w2", windowNode("w2", "日志窗口", "tail -f app.log"));
        boolean c = windowLayer.show("w3", windowNode("w3", "监控窗口", "CPU 12%"));
        boolean fourth = windowLayer.show("w4", windowNode("w4", "第四个", "应被拒绝"));

        windowLayer.bringToFront("w2");
        windowLayer.moveTo("w2", 24, 120);
        windowLayer.resizeTo("w2", 300, 360);
        String geom1 = windowLayer.geometryText("w2");
        windowLayer.close("w2");
        windowLayer.show("w2", windowNode("w2", "日志窗口", "tail -f app.log"));
        String geom2 = windowLayer.geometryText("w2");

        windowLayer.bringToFront("w3");
        windowLayer.update("w1", windowNode("w1", "部署窗口", "服务已启动 · 新内容"));
        boolean frozen = "服务已启动".equals(windowLayer.contentText("w1", "w1.body"));
        windowLayer.bringToFront("w1");
        boolean unfrozen = "服务已启动 · 新内容".equals(windowLayer.contentText("w1", "w1.body"));

        boolean back1 = windowLayer.onBackPressed();
        boolean min1 = windowLayer.isMinimized("w1");
        boolean back2 = windowLayer.onBackPressed();
        int afterClose = windowLayer.size();
        boolean back3 = windowLayer.onBackPressed();
        boolean min3 = windowLayer.isMinimized("w3");

        windowLayer.restore("w2");
        windowLayer.bringToFront("w2");
        // 让 w3 与 w2 部分重叠、右侧露出可点的「执行」按钮，手动点它验证交互子控件置顶
        windowLayer.restore("w3");
        windowLayer.resizeTo("w3", 220, 300);
        windowLayer.moveTo("w3", 150, 300);
        windowLayer.bringToFront("w2");

        statsView.setText(String.format(Locale.ROOT,
                "窗口：3 个%s 第4个拒绝=%s；返回键 最小化=%s 关闭=%s（剩%d） 再最小化=%s；"
                        + "冻结=%s/%s；几何 %s→重开 %s",
                a && b && c ? "✓" : "✗", !fourth, back1 && min1, back2, afterClose,
                back3 && min3, frozen, unfrozen, geom1, geom2));
        Log.i(TAG, "windows " + statsView.getText());

        List<Object> items = new ArrayList<>();
        items.add(taskItem("w2", "日志"));
        items.add(taskItem("w3", "监控"));
        fw.render(UiNode.column().key("deskRoot").prop("gap", "md")
                .child(UiNode.text("窗口原语 · 任务栏").key("dt").prop("size", "title"))
                .child(UiNode.text("拖动标题栏、右下角缩放、点击置顶；返回键先最小化/关闭最上层。")
                        .key("dd").prop("size", "caption").prop("tone", "secondary"))
                .child(UiNode.of(UiNode.TASKBAR).key("taskbar").prop("items", items)
                        .prop("on", Collections.singletonMap("tap", "win.restore")))
                .build());
    }

    // ---------------------------------------------------------------- 6. 聊天内容

    private void runChat() {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        host.addView(bubble);
        String md = "## 迁移验证\n\n"
                + "这是一段 prose，含 `inline code`、**粗体** 和 [链接](https://example.com)。\n\n"
                + "| 文件 | 大小 | 时间 |\n|---|---|---|\n"
                + "| a/very/long/path/to/file.txt | 1.2K | 2026-11-14 10:00 |\n"
                + "| b.txt | 3.4M | 2026-11-14 11:00 |\n\n"
                + "> | 引用列 | 值 |\n> |---|---|\n> | q | 1 |\n\n"
                + "```bash\n$ make demo\nBUILD SUCCESSFUL\n```\n";
        long t0 = System.nanoTime();
        ChatBlockView.render(bubble, md, false);
        double staticMs = (System.nanoTime() - t0) / 1_000_000.0;

        // 流式：只增长 prose 尾部，用 text.chunks
        LinearLayout stream = new LinearLayout(this);
        stream.setOrientation(LinearLayout.VERTICAL);
        host.addView(stream);
        final StringBuilder sb = new StringBuilder();
        final int frames = 60;
        final double[] acc = new double[2];
        final int[] done = {0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!active(TAB_CHAT)) return;
                done[0]++;
                sb.append("第 ").append(done[0])
                        .append(" 帧：prose 流式追加，尾部用 text.chunks。\n\n");
                long start = System.nanoTime();
                ChatBlockView.render(stream, sb.toString(), true);
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                acc[0] += ms;
                acc[1] = Math.max(acc[1], ms);
                statsView.setText(String.format(Locale.ROOT,
                        "聊天流式 %d/%d  avg %.2fms  max %.2fms", done[0], frames,
                        acc[0] / done[0], acc[1]));
                if (done[0] < frames) {
                    handler.postDelayed(this, 40);
                } else {
                    statsView.setText(String.format(Locale.ROOT,
                            "聊天：静态块 %.2fms；流式 %d 帧 avg %.2f/ max %.2fms（快路径 %d 帧，"
                                    + "split %.2fms append %.2fms/帧）；表格/引用表/代码卡/工具卡见下方",
                            staticMs, frames, acc[0] / frames, acc[1], ChatBlockView.fastFrames,
                            ChatBlockView.splitNanos / 1e6 / frames,
                            ChatBlockView.appendNanos / 1e6 / Math.max(1, ChatBlockView.fastFrames)));
                    Log.i(TAG, "chat " + statsView.getText());
                }
            }
        };
        handler.postDelayed(tick, 300);

        // 工具卡：running → done，并展开入参/输出
        LinearLayout toolHost = new LinearLayout(this);
        toolHost.setOrientation(LinearLayout.VERTICAL);
        host.addView(toolHost);
        UiFramework tfw = new UiFramework(toolHost);
        ChatComponents.install(tfw.renderer());
        tfw.render(UiNode.of(ChatComponents.TOOL_CARD).key("chatTool")
                .prop("glyph", ">_").prop("title", "Bash")
                .prop("summary", "$ make demo").prop("args", "$ make demo")
                .build());
        handler.postDelayed(() -> {
            if (!active(TAB_CHAT)) return;
            TextView out = toolHost.findViewById(ChatComponents.TOOL_OUT_ID);
            if (out != null) out.setText("BUILD SUCCESSFUL\n[exit code: 0]");
            TextView pill = toolHost.findViewById(ChatComponents.TOOL_PILL_ID);
            if (pill != null) {
                pill.setText("完成");
                pill.setTextColor(getColor(R.color.accent_green));
            }
            // 走组件自己的点击逻辑展开：glyph 会变成 ▾，折叠回来也由它负责。
            View card = toolHost.getChildAt(0);
            if (card instanceof ViewGroup) {
                View header = ((ViewGroup) card).getChildAt(0);
                if (header != null) header.performClick();
            }
        }, 700);
    }

    private Map<String, Object> taskItem(String key, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("active", key.equals(windowLayer.activeKey()));
        return m;
    }

    private UiNode windowNode(String key, String title, String body) {
        return UiNode.of(UiNode.WINDOW).key(key).prop("title", title)
                .prop("w", 280).prop("h", 300)
                .child(UiNode.text(body).key(key + ".body").prop("size", "body"))
                .child(UiNode.of(UiNode.BUTTON).key(key + ".btn").prop("label", "执行"))
                .child(UiNode.of(UiNode.ABSOLUTE).key(key + ".abs").prop("h", 96)
                        .child(UiNode.text("absolute (16,8)").key(key + ".a1")
                                .prop("x", 16).prop("y", 8).prop("size", "caption"))
                        .child(UiNode.text("absolute (110,52)").key(key + ".a2")
                                .prop("x", 110).prop("y", 52).prop("size", "caption")
                                .prop("tone", "brand")))
                .build();
    }
}
