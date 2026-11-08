package com.dsh.android;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dsh.android.agent.AgentLoop;
import com.dsh.android.agent.ChatMessage;
import com.dsh.android.agent.ToolCall;
import com.dsh.android.ui.BubbleBuilder;
import com.dsh.android.ui.MarkdownRenderer;
import com.dsh.android.llm.DeepSeekClient;
import com.dsh.android.plugin.HookManifest;
import com.dsh.android.plugin.HookRegistry;
import com.dsh.android.plugin.PluginInstaller;
import com.dsh.android.runtime.RuntimeManager;
import com.dsh.android.session.JsonlSessionLog;
import com.dsh.android.tools.AskUserTool;
import com.dsh.android.tools.BashTool;
import com.dsh.android.tools.EditTool;
import com.dsh.android.tools.ReadImageTool;
import com.dsh.android.tools.ReadTool;
import com.dsh.android.tools.WriteTool;
import com.dsh.android.tools.GlobTool;
import com.dsh.android.tools.GrepTool;
import com.dsh.android.tools.StrReplaceEditorTool;
import com.dsh.android.tools.ToolRegistry;
import com.dsh.android.tools.WebFetchTool;
import com.dsh.android.tools.WebSearchTool;
import com.dsh.android.util.DebugLog;
import com.dsh.android.util.IOUtil;
import com.dsh.android.util.ImageStore;
import com.dsh.android.util.RefStore;
import com.dsh.android.util.SecretStore;

/** Pocket Agent — lightweight on-device agent harness for Android. */
public final class MainActivity extends AppCompatActivity {

    private static final String[] MODELS = {"deepseek-chat", "deepseek-reasoner",
            "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
            "deepseek-ai/deepseek-vl2", "Qwen/Qwen2.5-VL-72B-Instruct"};
    private static final java.util.Set<String> VISION_MODELS = new java.util.HashSet<>(Arrays.asList(
            "deepseek-ai/deepseek-vl2", "Qwen/Qwen2.5-VL-72B-Instruct",
            "Qwen/Qwen2-VL-72B-Instruct", "gpt-4o", "gpt-4o-mini"));
    private static final int REQ_PICK_REF = 1001;
    private static final int MAX_REFS = 8;
    private static final String CUSTOM_MODEL_OPTION = "Custom model…";

    private LinearLayout transcript;
    private ScrollView scroll;
    private EditText input;
    private FrameLayout send;
    private ImageView sendIcon;
    private TextView topTitle;
    private View home;
    private DrawerLayout drawerLayout;
    private float downX;
    private float downY;
    private LinearLayout sessionList;
    /** 侧边栏搜索词（小写）；空串表示展示全部会话。 */
    private String sessionQuery = "";
    private EditText sessionSearch;
    private File activeSession;
    private File currentSessionFile;
    /** 启动只重放最近若干条，避免长会话把整个 onCreate 拖住。 */
    private static final int REPLAY_LIMIT = 40;
    private static final int RENDER_BATCH = 5;
    private boolean replaying;
    private int replayGeneration;
    private File replayFile;
    private boolean replayTruncated;
    private View attach;
    private LinearLayout attachPreview;
    private View attachScroll;
    private final List<File> pendingRefs = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private SecretStore secrets;
    private RuntimeManager runtime;
    private AgentLoop activeLoop;
    /** 当前挂起的问题卡片：用户点停止时用它放行阻塞中的 agent 线程。 */
    private volatile Runnable pendingQuestionCancel;
    /** Type B 钩子注册表（懒建，首次用的时候把示例插件铺到 files/plugins）。 */
    private HookRegistry hooks;
    /** 命令风险模型（assets/guard-model.json，~171 KB）。 */
    private com.dsh.android.guard.RiskModel riskModel;
    private boolean riskModelTried;

    private boolean busy;
    private boolean runtimeReady;
    private AlertDialog runtimeAlert;      // 环境异常提示，防止重复弹

    // turn runtime state
    private long turnStart;
    private TextView statusRow;
    private ValueAnimator statusAnimator;
    private ValueAnimator pillAnimator;
    private boolean userPeeking;        // 用户主动回看历史时，不再把他拉回底部
    private boolean scrollPending;      // 合并同一帧里的多次 scrollDown
    private boolean programmaticScroll; // 区分程序贴底与用户手势，避免误判回看
    private TextView assistantMeta;

    // thinking
    private LinearLayout thinkRow;
    private TextView thinkBody;
    private TextView thinkSummary;
    private TextView thinkChevron;
    private boolean[] thinkOpen = {false};
    private boolean[] thinkDone;
    private StringBuilder thinkText = new StringBuilder();
    private boolean thinkFinished;

    // tool row
    private LinearLayout lastToolRow;
    private TextView lastToolOut;
    private TextView lastToolPill;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        secrets = new SecretStore(this);
        runtime = new RuntimeManager(this);
        DebugLog.init(this);
        transcript = findViewById(R.id.transcript);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        attach = findViewById(R.id.attach);
        attachPreview = findViewById(R.id.attachPreview);
        attachScroll = findViewById(R.id.attachScroll);
        attach.setOnClickListener(v -> pickRef());
        send = findViewById(R.id.send);
        sendIcon = findViewById(R.id.sendIcon);
        topTitle = findViewById(R.id.topTitle);
        home = findViewById(R.id.home);
        drawerLayout = findViewById(R.id.drawerLayout);
        sessionList = findViewById(R.id.sessionList);
        MarkdownRenderer.init(this);

        // 只有用户主动往回滑才停止自动跟随；滑回最底部后恢复。
        // scrollDown 自己造成的滚动永远是向下（scrollY 增大），不会误判成回看。
        scroll.setOnScrollChangeListener((v, sx, sy, oldX, oldY) -> {
            if (programmaticScroll) return;
            if (sy < oldY) userPeeking = true;
            View content = ((ScrollView) v).getChildCount() > 0
                    ? ((ScrollView) v).getChildAt(0) : null;
            if (content != null && sy >= content.getBottom() - v.getHeight() - dp(8)) {
                userPeeking = false;
            }
        });

        findViewById(R.id.btnMenu).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));
        sessionSearch = findViewById(R.id.sessionSearch);
        sessionSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                if (q.equals(sessionQuery)) return;
                sessionQuery = q;
                rebuildSessionList();
            }
        });
        findViewById(R.id.btnSidebarMore).setOnClickListener(this::showSidebarMenu);
        // 抽屉宽度照官方聊天应用：约占屏宽 86%，平板封顶 440dp
        View sidebarView = findViewById(R.id.sidebar);
        int drawerW = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
        sidebarView.getLayoutParams().width = Math.max(dp(280), Math.min(drawerW, dp(440)));
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                rebuildSessionList();
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                // 收起时清掉搜索词，下次打开回到完整列表
                if (sessionSearch != null && sessionSearch.length() > 0) sessionSearch.setText("");
            }
        });

        send.setOnClickListener(v -> {
            if (busy) {
                if (activeLoop != null) activeLoop.cancel();
                cancelPendingQuestion();
                new Thread(() -> runtime.runtime().abortWork(), "abort-work").start();
            } else {
                onSend();
            }
        });
        View settingsBtn = findViewById(R.id.btnSettings);
        settingsBtn.setOnClickListener(v -> showSettings());
        settingsBtn.setOnLongClickListener(v -> { runSelfTest(); return true; });
        findViewById(R.id.btnNewSession).setOnClickListener(v -> confirmNewSession());
        findViewById(R.id.wsChip).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.ws_dialog_title)
                .setMessage(R.string.ws_dialog_msg)
                .setPositiveButton(R.string.cancel, null)
                .show());
        input.setOnEditorActionListener((v, actionId, event) -> {
            onSend();
            return true;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.isShiftPressed()) return false; // allow newline
                onSend();
                return true;
            }
            return false;
        });
        refreshRuntimeStatus();
        loadLatestSession();
        refreshTopTitle();
        if (!runtimeReady) autoSetup();
        main.postDelayed(this::promptStorageAccess, 2500);

        // 无 UI 的验证通道（见 runDebugCommand）：等 autoSetup 把 rootfs 备好再执行。
        final String dbgCmd = getIntent().getStringExtra("pocketroot_cmd");
        final boolean dbgPersist = getIntent().getBooleanExtra("pocketroot_persist", false);
        if ((dbgCmd != null && !dbgCmd.isEmpty()) || dbgPersist) {
            main.postDelayed(() -> runDebugCommand(dbgCmd == null ? "" : dbgCmd), 20_000);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (drawerLayout != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        if (dx < -30 * getResources().getDisplayMetrics().density
                                && Math.abs(dx) > Math.abs(dy)) {
                            drawerLayout.closeDrawer(GravityCompat.START);
                        }
                    } else if (downX < getResources().getDisplayMetrics().widthPixels / 2f
                            && dx > 60 * getResources().getDisplayMetrics().density
                            && dx > Math.abs(dy)) {
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        userPeeking = false;
        scroll.post(this::scrollDown);
        scroll.postDelayed(this::scrollDown, 350);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusAnimator != null) { statusAnimator.cancel(); statusAnimator = null; }
        if (pillAnimator != null) { pillAnimator.cancel(); pillAnimator = null; }
        if (runtimeAlert != null) { runtimeAlert.dismiss(); runtimeAlert = null; }
    }

    /** One-time guidance to grant All-Files-Access so the Linux runtime can browse the phone's shared
     * storage at /sdcard. Space info (df -h / inside the runtime) works without it. */
    private void promptStorageAccess() {
        if (busy || Environment.isExternalStorageManager()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.storage_title)
                .setMessage(getString(R.string.storage_msg))
                .setPositiveButton(R.string.storage_open, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton(R.string.storage_later, null)
                .show();
    }

    /** Re-check storage access when returning from the Settings grant. */
    @Override
    protected void onResume() {
        super.onResume();
        refreshRuntimeStatus();
    }

    private void autoSetup() {
        executor.execute(() -> {
            try {
                runtime.setup(msg -> DebugLog.i("setup: " + msg));
                main.post(this::refreshRuntimeStatus);
                DebugLog.i("autoSetup: runtime ready");
            } catch (Exception e) {
                final String m = IOUtil.truncate(e.getMessage(), 200);
                DebugLog.e("autoSetup failed", e);
                showRuntimeProblem(m);
            }
        });
    }

    /** 环境没能起来时才打断用户；常驻的「就绪/离线」指示灯是纯噪音，已移除。 */
    private void showRuntimeProblem(String detail) {
        main.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (runtimeAlert != null && runtimeAlert.isShowing()) return;
            runtimeAlert = new AlertDialog.Builder(this)
                    .setTitle(R.string.runtime_problem_title)
                    .setMessage(getString(R.string.runtime_problem_msg, detail))
                    .setPositiveButton(R.string.runtime_retry, (d, w) -> autoSetup())
                    .setNeutralButton(R.string.settings, (d, w) -> showSettings())
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener(d -> runtimeAlert = null)
                    .show();
        });
    }

    // ---------- runtime ----------

    private void refreshRuntimeStatus() {
        runtimeReady = runtime.state() == RuntimeManager.State.READY;
    }

    private static String shortModel(String model) {
        if (model == null) return "";
        return model.replace("deepseek-ai/", "").replace("deepseek-chat", "DS-Chat")
                .replace("deepseek-reasoner", "DS-Think").replace("DeepSeek-R1", "R1")
                .replace("DeepSeek-V3", "V3").replace("deepseek-v4-flash", "v4-flash");
    }

    // ---------- file references ----------

    private void pickRef() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK_REF);
        } catch (Exception e) {
            Toast.makeText(this, R.string.ref_pick_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_REF || resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        // 复制是 IO：放后台，大文件不该卡住输入
        executor.execute(() -> {
            List<File> got = new ArrayList<>();
            String error = null;
            for (Uri u : uris) {
                try {
                    got.add(RefStore.save(MainActivity.this, u));
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            final String err = error;
            main.post(() -> {
                for (File f : got) {
                    if (pendingRefs.size() >= MAX_REFS) {
                        Toast.makeText(this, R.string.ref_limit, Toast.LENGTH_SHORT).show();
                        break;
                    }
                    pendingRefs.add(f);
                }
                refreshRefPreview();
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void refreshRefPreview() {
        attachPreview.removeAllViews();
        if (pendingRefs.isEmpty()) {
            attachScroll.setVisibility(View.GONE);
            return;
        }
        attachScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < pendingRefs.size(); i++) {
            final int idx = i;
            TextView chip = refChip(pendingRefs.get(i).getName());
            chip.setOnClickListener(v -> {
                if (idx < pendingRefs.size()) pendingRefs.remove(idx);
                refreshRefPreview();
            });
            attachPreview.addView(chip);
        }
    }

    /** 引用 chip：显示文件名，点一下移除。 */
    private TextView refChip(String name) {
        String label = name.length() > 26 ? name.substring(0, 26) + "…" : name;
        TextView chip = new TextView(this);
        chip.setText(label + "  ✕");
        chip.setTextSize(12);
        chip.setTextColor(getColor(R.color.text_secondary));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    /** 用户气泡里的引用：右对齐的文件名 chip，正文仍是普通气泡。 */
    private void addBubbleUserWithRefs(String text, List<String> refs) {
        if (refs != null && !refs.isEmpty()) {
            hideHome();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            for (String r : refs) {
                String name = r;
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                TextView chip = refChip(name);
                chip.setOnClickListener(null);
                row.addView(chip);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END;
            lp.topMargin = dp(10);
            attachTranscript(row, lp);
        }
        if (text != null && !text.isEmpty()) addBubbleUser(text);
        else scrollDown();
    }

    // ---------- chat ----------

    private void onSend() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingRefs.isEmpty()) return;
        String apiKey = secrets.get("api_key");
        if (apiKey == null || apiKey.isEmpty()) {
            showSettings();
            return;
        }
        final List<String> refs = new ArrayList<>();
        for (File f : pendingRefs) refs.add(RefStore.guestPath(f));
        input.setText("");
        pendingRefs.clear();
        refreshRefPreview();
        addBubbleUserWithRefs(text, refs);
        setBusy(true);
        executor.execute(() -> {
            if (!runtimeReady) {
                try {
                    runtime.setup(msg -> DebugLog.i("setup: " + msg));
                    main.post(this::refreshRuntimeStatus);
                } catch (Exception e) {
                    final String m = IOUtil.truncate(e.getMessage(), 300);
                    main.post(() -> {
                        setBusy(false);
                        addBubbleAssistant(getString(R.string.runtime_setup_failed, m));
                    });
                    return;
                }
            }
            startAgent(ChatMessage.userWithRefs(text, refs));
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        sendIcon.setImageResource(b ? R.drawable.ic_stop : R.drawable.ic_send);
        send.animate().alpha(b ? 0.92f : 1f).setDuration(160).start();
    }

    private void startAgent(ChatMessage userMsg) {
        final JsonlSessionLog log;
        final List<ChatMessage> history;
        try {
            File sessionsDir = new File(getFilesDir(), "sessions");
            if (activeSession != null && activeSession.isFile()) {
                // 模型只看最近 40 条（HistoryWindow.sanitize），没必要把整份
                // 会话日志都反序列化；给 3 倍余量保证 tool_calls/tool 成对。
                history = JsonlSessionLog.loadTail(activeSession, 120);
                log = new JsonlSessionLog(activeSession, true);
            } else {
                log = new JsonlSessionLog(sessionsDir);
                history = new ArrayList<>();
                activeSession = log.file();
                currentSessionFile = log.file();
            }
        } catch (Exception e) {
            failAgent(getString(R.string.session_log_failed, e.getMessage()));
            return;
        }
        main.post(this::refreshTopTitle);
        String apiKey = secrets.get("api_key");
        android.content.SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE);
        String model = s.getString("model", "deepseek-chat");
        String baseUrl = s.getString("base_url", "https://api.deepseek.com");
        ToolRegistry tools = buildTools();
        AgentLoop loop = new AgentLoop(new DeepSeekClient(baseUrl, apiKey, model), tools,
                buildSystemPrompt(), log, runtime.runtime().workspaceDir())
                .withHooks(hookRegistry())
                .withAsker(this::askUser)
                .withRiskModel(riskModel());
        activeLoop = loop;
        final LinearLayout[] assistant = new LinearLayout[1];
        final StringBuilder[] assistantBuf = new StringBuilder[1];
        loop.run(userMsg, history, new AgentLoop.Listener() {

            @Override
            public void onTurnStart() {
                turnStart = System.currentTimeMillis();
                main.post(MainActivity.this::showStatusRow);
            }

            @Override
            public void onNotice(String note) {
                main.post(() -> {
                    dismissStatusRow();
                    addMetaOnly(note);
                    scrollDown();
                });
            }

            @Override
            public void onAssistantDelta(String delta) {
                main.post(() -> {
                    dismissStatusRow();
                    finishThinking();
                    if (assistant[0] == null) assistant[0] = addBubbleAssistant("");
                    if (assistantBuf[0] == null) assistantBuf[0] = new StringBuilder();
                    assistantBuf[0].append(delta);
                    String md = assistantBuf[0].toString();
                    final LinearLayout ab = assistant[0];
                    queueRender(() -> {
                        BubbleBuilder.sync(ab, md, MainActivity.this, bubbleStyle(), true);
                        bindBubbleLongPress(ab, () -> assistantBuf[0] == null
                                ? "" : assistantBuf[0].toString());
                        scrollDown();
                    });
                });
            }

            @Override
            public void onReasoningDelta(String delta) {
                main.post(() -> {
                    if (delta == null || delta.trim().isEmpty()) return;
                    dismissStatusRow();
                    if (thinkRow == null) newThinkRow();
                    thinkText.append(delta);
                    if (thinkOpen[0]) {
                        queueRender(() -> MarkdownRenderer.setText(thinkBody, thinkText.toString()));
                    } else {
                        thinkSummary.setText(lastLine(thinkText.toString()));
                    }
                    scrollDown();
                });
            }

            @Override
            public void onToolStart(ToolCall call) {
                main.post(() -> {
                    dismissStatusRow();
                    finishThinking();
                    resetThinkFields();
                    // 工具调用会切断当前文字气泡：先做一次非流式渲染，结束进行中的
                    // 淡入动画、让文字立即稳定，再丢引用。
                    if (assistant[0] != null && assistantBuf[0] != null) {
                        BubbleBuilder.sync(assistant[0], assistantBuf[0].toString(),
                                MainActivity.this, bubbleStyle());
                    }
                    assistant[0] = null;
                    assistantBuf[0] = null;
                    lastToolRow = newToolRow(call);
                    attachTranscript(lastToolRow);
                    if (lastToolPill != null) startPillAnimation(lastToolPill);
                    scrollDown();
                });
            }

            @Override
            public void onToolResult(ToolCall call, String output) {
                main.post(() -> {
                    stopPillAnimation();
                    if (lastToolRow != null) {
                        String out = output == null ? "(no output)" : output;
                        lastToolOut.setText(IOUtil.truncate(out, 10000));
                        lastToolOut.setTextColor(getColor(startsWithError(out) ? R.color.accent_red : R.color.tool_text));
                        applyStatusPill(lastToolPill, lastToolOut.getText().toString());
                        scrollDown();
                    }
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    dismissStatusRow();
                    stopPillAnimation();
                    settlePendingTool(getString(R.string.pill_failed), R.color.accent_red);
                    LinearLayout box = addBubbleAssistant(getString(R.string.turn_failed, message));
                    colorBubble(box, getColor(R.color.accent_red));
                    endTurn();
                    finalizeAssistantBubble(assistant, assistantBuf);
                });
            }

            @Override
            public void onFinished(String answer) {
                main.post(() -> {
                    dismissStatusRow();
                    stopPillAnimation();
                    if (answer != null && answer.equals("(cancelled)")) {
                        settlePendingTool(getString(R.string.stopped), R.color.text_caption);
                        if (assistant[0] != null) {
                            appendMeta(assistantMeta, " · " + getString(R.string.stopped));
                        } else {
                            addMetaOnly("· " + getString(R.string.stopped));
                        }
                    } else if (assistant[0] == null && answer != null && !answer.isEmpty()) {
                        addBubbleAssistant(answer);
                    }
                    finishThinking();
                    if (assistantMeta != null) {
                        long d = System.currentTimeMillis() - turnStart;
                        appendMeta(assistantMeta, " · Ran for " + duration(d));
                    }
                    endTurn();
                    finalizeAssistantBubble(assistant, assistantBuf);
                });
            }
        });
    }

    private Runnable pendingRender;
    private boolean renderQueued;

    /** Coalesces renders to one per main-loop cycle, always keeping the
     * LATEST queued job: stale snapshots from the stream must never overwrite
     * a newer render (or the finalize at turn end), or the bubble loses its
     * tail. */
    private void queueRender(Runnable job) {
        pendingRender = job;
        if (renderQueued) return;
        renderQueued = true;
        main.post(() -> {
            renderQueued = false;
            Runnable j = pendingRender;
            pendingRender = null;
            j.run();
        });
    }

    private void finalizeAssistantBubble(final LinearLayout[] assistant, final StringBuilder[] assistantBuf) {
        if (assistant[0] != null && assistantBuf[0] != null && assistantBuf[0].length() > 0) {
            BubbleBuilder.sync(assistant[0], assistantBuf[0].toString(), this, bubbleStyle());
            bindBubbleLongPress(assistant[0], () -> assistantBuf[0] == null
                    ? "" : assistantBuf[0].toString());
        }
    }

    private void finishThinking() {
        if (thinkRow == null) return;
        thinkFinished = true;
        if (thinkDone != null) thinkDone[0] = true;
        if (!thinkOpen[0]) thinkSummary.setText(firstLine(thinkText.toString()));
    }

    private void endTurn() {
        setBusy(false);
        activeLoop = null;
        assistantMeta = null;
        stopPillAnimation();
        resetThinkFields();
        lastToolRow = null;
        lastToolOut = null;
        lastToolPill = null;
        scrollDown();
    }

    private void resetThinkFields() {
        thinkRow = null;
        thinkBody = null;
        thinkSummary = null;
        thinkChevron = null;
        thinkOpen = new boolean[]{false};
        thinkDone = null;
        thinkText = null;
        thinkFinished = false;
    }

    private void refreshTopTitle() {
        if (topTitle == null) return;
        String t = getString(R.string.app_name);
        if (currentSessionFile != null && currentSessionFile.isFile()) {
            String s = sessionTitle(currentSessionFile);
            if (s != null && !s.isEmpty()) t = s;
        }
        topTitle.setText(t);
    }

    private TextView animPill;
    private boolean pillAnimRunning;

    /** 运行态用 alpha 呼吸，而不是每 450ms 改一次文字：setText 会反复触发
     *  测量/重排，三个点的硬跳也显得很机械。 */
    private void startPillAnimation(TextView pill) {
        if (pillAnimRunning && animPill == pill) return;
        stopPillAnimation();
        animPill = pill;
        pillAnimRunning = true;
        pill.setText(getString(R.string.pill_running_base) + "…");
        pillAnimator = ValueAnimator.ofFloat(0.45f, 1f);
        pillAnimator.setDuration(720);
        pillAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pillAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pillAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pillAnimator.addUpdateListener(a -> {
            if (animPill != null) animPill.setAlpha((Float) a.getAnimatedValue());
        });
        pillAnimator.start();
    }

    private void stopPillAnimation() {
        pillAnimRunning = false;
        if (pillAnimator != null) { pillAnimator.cancel(); pillAnimator = null; }
        if (animPill != null) { animPill.setAlpha(1f); animPill = null; }
    }

    /** Resolve a still-running tool pill at turn end when no onToolResult arrived
     * (cancel between iterations, or an LLM error mid-turn). */
    private void settlePendingTool(String label, int colorRes) {
        if (lastToolPill == null) return;
        String s = String.valueOf(lastToolPill.getText());
        if (s.startsWith(getString(R.string.pill_running_base))) {
            lastToolPill.setText(label);
            lastToolPill.setTextColor(getColor(colorRes));
        }
    }

    private void showStatusRow() {
        dismissStatusRow();
        statusRow = new TextView(this);
        statusRow.setText(getString(R.string.deep_diving));
        statusRow.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        attachTranscript(statusRow, lp);
        final int brand = getColor(R.color.brand);
        final int tertiary = getColor(R.color.text_tertiary);
        statusAnimator = ValueAnimator.ofInt(tertiary, brand, tertiary);
        statusAnimator.setDuration(1800);
        statusAnimator.setRepeatCount(ValueAnimator.INFINITE);
        statusAnimator.setRepeatMode(ValueAnimator.REVERSE);
        statusAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        statusAnimator.setEvaluator(new android.animation.ArgbEvaluator());
        statusAnimator.addUpdateListener(a -> statusRow.setTextColor((Integer) a.getAnimatedValue()));
        statusAnimator.start();
        scrollDown();
    }

    private void dismissStatusRow() {
        if (statusRow != null) {
            final View row = statusRow;
            statusRow = null;                 // 先摘引用，淡出期间不再被 animator 更新
            row.animate().alpha(0f).setDuration(140)
                    .withEndAction(() -> transcript.removeView(row)).start();
        }
        if (statusAnimator != null) {
            statusAnimator.cancel();
            statusAnimator = null;
        }
    }

    private void failAgent(String message) {
        main.post(() -> {
            setBusy(false);
            addBubbleAssistant(getString(R.string.turn_failed, message));
        });
    }

    // ---------- view helpers ----------

    /** 新行入场：淡入 + 轻微上移，避免消息/工具行「啪」地跳出来。
     *  会话回放一次性加几十行，动画只会糊成一团，故回放时直接跳过。 */
    private void attachTranscript(View v) {
        transcript.addView(v);
        animateIn(v);
    }

    private void attachTranscript(View v, ViewGroup.LayoutParams lp) {
        transcript.addView(v, lp);
        animateIn(v);
    }

    private void animateIn(View v) {
        if (replaying) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(8));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    private TextView addBubbleUser(String text) {
        hideHome();
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setBackgroundResource(R.drawable.bg_bubble_user);
        tv.setTextColor(getColor(R.color.bubble_user_text));
        tv.setTextSize(16);
        tv.setLineSpacing(4, 1.0f);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setMaxWidth(maxBubbleWidth());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(16);
        attachTranscript(tv, lp);
        addMetaRow(Gravity.END, formatTime(System.currentTimeMillis()));
        scrollDown();
        return tv;
    }

    private BubbleBuilder.Style bubbleStyle() {
        return new BubbleBuilder.Style(
                getColor(R.color.text_primary),
                getColor(R.color.text_secondary),
                getColor(R.color.text_caption),
                getColor(R.color.brand),
                getColor(R.color.inline_code_bg),
                getColor(R.color.code_bg),
                getColor(R.color.border_light));
    }

    private void colorBubble(LinearLayout box, int color) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v instanceof TextView) ((TextView) v).setTextColor(color);
        }
    }

    private LinearLayout addBubbleAssistant(String text) {
        hideHome();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        attachTranscript(box, lp);
        BubbleBuilder.sync(box, text, this, bubbleStyle());
        bindBubbleLongPress(box, () -> text);
        assistantMeta = addMetaRow(Gravity.START, formatTime(System.currentTimeMillis()));
        scrollDown();
        return box;
    }

    /** The prose TextViews inside an assistant bubble use a movement method and swallow touch
     * events, so a long-press must be bound on every descendant, not just the container. */
    private void bindBubbleLongPress(final View v, final java.util.function.Supplier<String> src) {
        v.setOnLongClickListener(x -> {
            openContentEditor(getString(R.string.role_ai), src.get());
            return true;
        });
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                bindBubbleLongPress(g.getChildAt(i), src);
            }
        }
    }

    /** Long-press on a bubble: open the message in an editable container with a
     * copy-all shortcut; native text selection works inside for partial copies. */
    private void openContentEditor(final String role, final String initial) {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView copyAll = tvGlyph("⧉ " + getString(R.string.copy_all), 13, R.color.brand);
        copyAll.setBackgroundResource(R.drawable.bg_chip);
        copyAll.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView title = tvGlyph(role, 11, R.color.text_caption);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        tlp.rightMargin = dp(8);
        TextView close = tvGlyph("✕", 15, R.color.text_secondary);
        close.setPadding(dp(8), dp(4), dp(2), dp(4));
        header.addView(copyAll);
        header.addView(title, tlp);
        header.addView(close);
        root.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.border_light));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(12);
        root.addView(divider, dlp);

        final EditText et = new EditText(this);
        et.setText(initial);
        et.setTextSize(14);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextColor(getColor(R.color.text_primary));
        android.graphics.drawable.GradientDrawable ep = new android.graphics.drawable.GradientDrawable();
        ep.setColor(getColor(R.color.code_bg));
        ep.setCornerRadius(dp(12));
        ep.setStroke(Math.max(1, dp(1)), getColor(R.color.border_light));
        et.setBackground(ep);
        et.setGravity(Gravity.TOP);
        et.setMinimumHeight(dp(340));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        copyAll.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.copy_all), et.getText().toString()));
            toast(getString(R.string.copied_all));
        });

        AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();
        if (dlg.getWindow() != null) {
            android.graphics.drawable.GradientDrawable wbg = new android.graphics.drawable.GradientDrawable();
            wbg.setColor(getColor(R.color.layer1));
            wbg.setCornerRadius(dp(22));
            dlg.getWindow().setBackgroundDrawable(wbg);
        }
        dlg.show();
    }

    /**
     * ask_user_question 的宿主实现：把问题画进对话流，阻塞 agent 线程直到用户点选/输入。
     * 超时与"停止"都走同一条取消路径，返回 null。
     */
    private String askUser(String question, List<String> options) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] answer = new String[1];
        final AtomicBoolean settled = new AtomicBoolean(false);

        final Runnable cancel = () -> {
            if (settled.compareAndSet(false, true)) {
                main.post(() -> addMetaOnly(getString(R.string.ask_timeout)));
                latch.countDown();
            }
        };
        pendingQuestionCancel = cancel;
        main.post(() -> showQuestionCard(question, options, ans -> {
            if (settled.compareAndSet(false, true)) {
                answer[0] = ans;
                main.post(() -> addMetaOnly(getString(R.string.ask_answered, ans)));
                latch.countDown();
            }
        }));

        try {
            if (!latch.await(10, TimeUnit.MINUTES)) {
                cancel.run();
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pendingQuestionCancel = null;
        }
        return answer[0];
    }

    private void cancelPendingQuestion() {
        Runnable r = pendingQuestionCancel;
        if (r != null) r.run();
    }

    /** 问题卡片：标题 + 问题 +（选项按钮 或 输入框）。回答后卡片留在流里作为记录。 */
    private void showQuestionCard(String question, List<String> options,
                                  java.util.function.Consumer<String> done) {
        hideHome();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_assistant);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        attachTranscript(box, lp);

        TextView label = tvGlyph("❓ " + getString(R.string.ask_label), 12, R.color.brand);
        box.addView(label);

        TextView body = tvGlyph(question, 15, R.color.text_primary);
        body.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        box.addView(body, blp);

        if (options == null || options.isEmpty()) {
            EditText et = new EditText(this);
            et.setHint(R.string.ask_hint);
            et.setTextSize(14);
            et.setTextColor(getColor(R.color.text_primary));
            et.setBackgroundResource(R.drawable.bg_input);
            et.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(10);
            box.addView(et, elp);

            TextView sendBtn = tvGlyph(getString(R.string.send), 14, R.color.brand);
            sendBtn.setBackgroundResource(R.drawable.bg_chip);
            sendBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(10);
            slp.gravity = Gravity.END;
            box.addView(sendBtn, slp);

            sendBtn.setOnClickListener(v -> {
                String t = et.getText().toString().trim();
                if (t.isEmpty()) return;
                done.accept(t);
            });
        } else {
            for (String opt : options) {
                TextView b = tvGlyph(opt, 14, R.color.text_primary);
                b.setBackgroundResource(R.drawable.bg_chip);
                b.setPadding(dp(14), dp(9), dp(14), dp(9));
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                olp.topMargin = dp(8);
                box.addView(b, olp);
                b.setOnClickListener(v -> done.accept(opt));
            }
        }
        scrollDown();
    }

    private void addMetaOnly(String text) {
        TextView tv = new TextView(this);
        tv.setText("· " + text);
        tv.setTextSize(11);
        tv.setTextColor(getColor(R.color.text_caption));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.gravity = Gravity.START;
        attachTranscript(tv, lp);
    }

    private TextView addMetaRow(int gravity, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(getColor(R.color.text_caption));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.gravity = gravity;
        attachTranscript(tv, lp);
        return tv;
    }

    private void appendMeta(TextView meta, String suffix) {
        if (meta == null) return;
        meta.append(suffix);
    }

    private int maxBubbleWidth() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return (int) Math.min(dp(460), w * 0.82f);
    }

    /** Collapsible "Think" disclosure row (official ReasoningRow): default collapsed, summary
     * follows the latest reasoning line while running, else first line. */
    private void newThinkRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(16);
        row.setLayoutParams(rp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(4));

        TextView chevron = tvGlyph("▸", 12, R.color.text_caption);
        chevron.setPadding(0, 0, dp(8), 0);
        header.addView(chevron);

        TextView title = tvGlyph(getString(R.string.think), 14, R.color.text_secondary);
        header.addView(title);

        TextView dot = tvGlyph(" · ", 12, R.color.text_caption);
        header.addView(dot);

        TextView summary = new TextView(this);
        summary.setTextSize(14);
        summary.setTextColor(getColor(R.color.text_tertiary));
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView body = new TextView(this);
        body.setTextSize(13);
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(4, 1.0f);
        body.setTextColor(getColor(R.color.text_tertiary));
        body.setPadding(dp(22), dp(2), 0, dp(2));
        body.setVisibility(View.GONE);

        final StringBuilder buf = new StringBuilder();
        final boolean[] open = {false};
        final boolean[] done = {false};
        header.setOnClickListener(v -> {
            open[0] = !open[0];
            chevron.setText(open[0] ? "▾" : "▸");
            body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            MarkdownRenderer.setText(body, buf.toString());
            if (!open[0]) {
                summary.setText(done[0] ? firstLine(buf.toString()) : lastLine(buf.toString()));
            }
        });

        row.addView(header);
        row.addView(body);
        attachTranscript(row);
        thinkRow = row;
        thinkBody = body;
        thinkSummary = summary;
        thinkChevron = chevron;
        thinkOpen = open;
        thinkDone = done;
        thinkText = buf;
        thinkFinished = false;
    }

    /** Collapsible tool row (official ToolRow): collapsed one-liner, expanded IN/OUT gutter + status pill. */
    private LinearLayout newToolRow(ToolCall call) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(16);
        row.setLayoutParams(rp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(4));

        TextView glyph = tvGlyph(toolGlyph(call.name), 13, R.color.text_caption);
        glyph.setPadding(0, 0, dp(6), 0);
        glyph.setMinWidth(dp(20));
        header.addView(glyph);

        TextView title = tvGlyph(toolTitle(call.name), 14, R.color.text_primary);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);

        TextView dot = tvGlyph(" · ", 12, R.color.text_caption);
        header.addView(dot);

        TextView summary = new TextView(this);
        summary.setTextSize(13);
        summary.setTextColor(getColor(R.color.text_tertiary));
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setText(toolSummary(call));
        header.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView headerPill = tvGlyph(getString(R.string.pill_running), 10, R.color.text_tertiary);
        headerPill.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams hpp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hpp.leftMargin = dp(6);
        headerPill.setBackgroundResource(R.drawable.bg_chip);
        header.addView(headerPill, hpp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundResource(R.drawable.bg_tool);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        body.setVisibility(View.GONE);

        TextView inCap = tvGlyph(getString(R.string.in_label), 10, R.color.text_caption);
        inCap.setTypeface(Typeface.MONOSPACE);
        body.addView(inCap);

        final TextView in = new TextView(this);
        in.setTextSize(12);
        in.setTypeface(Typeface.MONOSPACE);
        in.setTextColor(getColor(R.color.tool_text));
        in.setPadding(0, dp(2), 0, dp(8));
        in.setText(toolIn(call));
        body.addView(in);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(getColor(R.color.border_light));
        body.addView(divider);

        final TextView outCap = tvGlyph(getString(R.string.out_label), 10, R.color.text_caption);
        outCap.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(8);
        body.addView(outCap, olp);

        final TextView out = new TextView(this);
        out.setTextSize(12);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextColor(getColor(R.color.tool_text));
        out.setLineSpacing(2, 1.0f);
        out.setPadding(0, dp(2), 0, dp(4));
        out.setText(getString(R.string.pill_running));
        body.addView(out);

        final boolean[] open = {false};
        header.setOnClickListener(v -> {
            open[0] = !open[0];
            body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            glyph.setText(open[0] ? "▾" : toolGlyph(call.name));
        });

        row.addView(header);
        row.addView(body);

        lastToolOut = out;
        lastToolPill = headerPill;
        return row;
    }

    private static String toolGlyph(String name) {
        if ("bash".equals(name)) return ">_";
        if ("str_replace_editor".equals(name)) return "✎";
        return "◇";
    }

    private static String toolTitle(String name) {
        if ("bash".equals(name)) return "Bash";
        if ("str_replace_editor".equals(name)) return "Edit";
        return "Tool call";
    }

    private String toolSummary(ToolCall call) {
        try {
            JSONObject o = new JSONObject(call.arguments == null ? "{}" : call.arguments);
            if ("bash".equals(call.name)) return "$ " + o.optString("command", "");
            if ("str_replace_editor".equals(call.name)) {
                String cmd = o.optString("command", "");
                String path = o.optString("path", "");
                return cmd.isEmpty() ? path : cmd + " " + path;
            }
            return call.arguments;
        } catch (Exception e) {
            return call.arguments;
        }
    }

    private String toolIn(ToolCall call) {
        if ("bash".equals(call.name)) {
            try {
                return "$ " + new JSONObject(call.arguments).optString("command", "");
            } catch (Exception ignored) {
            }
            return "bash";
        }
        try {
            return new JSONObject(call.arguments).toString(2);
        } catch (Exception e) {
            return call.arguments;
        }
    }

    private void applyStatusPill(TextView pill, String outputText) {
        String out = outputText == null ? "" : outputText;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[exit code: (\\d+)\\]").matcher(out);
        if (m.find()) {
            int code = Integer.parseInt(m.group(1));
            pill.setText(getString(R.string.exit_code, String.valueOf(code)));
            pill.setTextColor(getColor(code == 0 ? R.color.accent_green : R.color.accent_red));
            pill.setBackgroundResource(R.drawable.bg_chip);
            return;
        }
        if (startsWithError(out) || out.contains("TIMEOUT")) {
            pill.setText(R.string.pill_failed);
            pill.setTextColor(getColor(R.color.accent_red));
            pill.setBackgroundResource(R.drawable.bg_chip);
            return;
        }
        pill.setText(R.string.pill_done);
        pill.setTextColor(getColor(R.color.accent_green));
        pill.setBackgroundResource(R.drawable.bg_chip);
    }

    private static boolean startsWithError(String out) {
        if (out == null) return false;
        String t = out.trim();
        return t.startsWith("Error") || t.startsWith("TIMEOUT");
    }

    private TextView tvGlyph(String label, int sizeSp, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(sizeSp);
        tv.setTextColor(getColor(colorRes));
        return tv;
    }

    private static String lastLine(String s) {
        if (s == null || s.isEmpty()) return "";
        int nl = s.lastIndexOf('\n');
        String line = nl < 0 ? s.trim() : s.substring(nl + 1).trim();
        return line.isEmpty() ? firstLine(s) : line;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = (nl < 0 ? s : s.substring(0, nl)).trim();
        return line.isEmpty() ? "(thinking…)" : line;
    }

    private static String formatTime(long t) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(t));
    }

    private static String duration(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) return sec + "s";
        long m = sec / 60, rs = sec % 60;
        return m + "m " + rs + "s";
    }

    /**
     * 调试入口：{@code am start ... --es pocketroot_cmd '<shell>'}
     *
     * 在 rootfs 里执行一条命令，结果写 files/cmd.log 并进 logcat。没有模型 API key 时，
     * 这是验证运行时（shim / rootfs / 工具链）唯一的自动化通道。
     */
    /** 懒加载风险模型：文件不在或解析失败就退化成"只有确定性护栏"。 */
    private com.dsh.android.guard.RiskModel riskModel() {
        if (!riskModelTried) {
            riskModelTried = true;
            try (java.io.InputStream in = getAssets().open("guard-model.json")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                riskModel = com.dsh.android.guard.RiskModel.parse(
                        new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                DebugLog.i("guard: risk model loaded, bits=" + riskModel.bits);
            } catch (Exception e) {
                DebugLog.w("guard: risk model unavailable: " + e);
            }
        }
        return riskModel;
    }

    /** 钩子注册表：首次调用时把 APK 里的示例插件铺到 files/plugins。 */
    private HookRegistry hookRegistry() {
        if (hooks == null) {
            File dir = new File(getFilesDir(), "plugins");
            PluginInstaller.seedSamples(this, dir);
            hooks = new HookRegistry(this, dir);
        }
        return hooks;
    }

    /** 工具表：agent 回合与调试通道共用一份，避免两边注册出分歧。 */
    private ToolRegistry buildTools() {
        java.nio.file.Path ws = runtime.runtime().workspaceDir().toPath();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new BashTool(runtime.runtime()));
        tools.register(new com.dsh.android.tools.ContainerTool(this));
        tools.register(new StrReplaceEditorTool(ws));
        // 官方 dsh-tool-fs 的 read / write / edit / read_image 套件（schema 与输出逐字对齐上游）
        tools.register(new ReadTool(ws));
        tools.register(new WriteTool(ws));
        tools.register(new EditTool(ws));
        tools.register(new ReadImageTool(this, ws));
        // 结构化查找/搜索：替掉 "bash find|grep 再解析输出" 这条弯路。
        tools.register(new GlobTool(ws));
        tools.register(new GrepTool(ws));
        // 联网：先 search 再 fetch，比让模型猜 URL 靠谱。
        tools.register(new WebSearchTool());
        tools.register(new WebFetchTool());
        // 需要人拍板时停下来的出口。
        tools.register(new AskUserTool(this::askUser));
        return tools;
    }

    private void runDebugCommand(final String cmd) {
        final boolean persist = getIntent().getBooleanExtra("pocketroot_persist", false);
        executor.execute(() -> {
            String out;
            try {
                if (cmd.startsWith("guard:")) {
                    // 调试通道：不用模型就能看护栏怎么判
                    String c = cmd.substring(6).trim();
                    com.dsh.android.guard.CommandGuard.Verdict v =
                            com.dsh.android.guard.CommandGuard.check(c);
                    com.dsh.android.guard.RiskModel rm = riskModel();
                    String sc = rm == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", rm.score(c));
                    out = v.action + "  [" + v.category + "]  " + v.reason + "   model=" + sc;
                } else if (cmd.startsWith("img:")) {
                    // 调试：验证多模态消息 JSON 构造，不调用 API。
                    // 用法 img:<图片绝对路径>|<文本>
                    String spec = cmd.substring(4).trim();
                    int bar = spec.indexOf('|');
                    String path = bar < 0 ? spec : spec.substring(0, bar).trim();
                    String text = bar < 0 ? "" : spec.substring(bar + 1).trim();
                    List<String> ps = new ArrayList<>();
                    if (!path.isEmpty()) ps.add(path);
                    try {
                        ChatMessage probe = ChatMessage.user(text, ps);
                        String j = probe.toJson().toString();
                        String l = probe.toLogJson().toString();
                        out = "API:" + (j.length() > 400 ? j.substring(0, 400) + "…(len=" + j.length() + ")" : j)
                                + "\nLOG:" + l;
                    } catch (Exception e) {
                        out = "Error: " + e;
                    }
                } else if (cmd.startsWith("ref:")) {
                    // 调试：验证引用文件如何进入模型请求（不调用 API）。用法 ref:<guest 路径>|<文本>
                    String spec = cmd.substring(4).trim();
                    int bar = spec.indexOf('|');
                    String path = bar < 0 ? spec : spec.substring(0, bar).trim();
                    String text = bar < 0 ? "" : spec.substring(bar + 1).trim();
                    List<String> rs = new ArrayList<>();
                    if (!path.isEmpty()) rs.add(path);
                    out = ChatMessage.userWithRefs(text, rs).toJson().toString();
                } else if (cmd.startsWith("hook:")) {
                    out = runHookForDebug(cmd.substring(5).trim());
                } else if (cmd.startsWith("tool:")) {
                    // 调试通道的一个分支：直接执行某个工具，不需要模型。
                    //    am start ... --es pocketroot_cmd 'tool:web_search {"query":"..."}'
                    out = runToolForDebug(cmd.substring(5).trim());
                } else if (persist) {
                    // 持久会话是 BashTool 真正用的路径：连跑两条，验证 cwd/环境变量是否跨调用保留。
                    String a = runtime.runtime().runBashPersistent(
                            "cd /tmp && export PKR=ok && echo first:$(pwd)", 60_000L);
                    String b = runtime.runtime().runBashPersistent(
                            "echo second:$(pwd) PKR=$PKR", 60_000L);
                    out = a + "\n--- next call ---\n" + b;
                } else if (cmd.startsWith("oci:")) {
                    // 调试：用指定镜像的 rootfs 起一个 shell（App 进程内，走 pocketroot 的 shim）
                    // 用法 oci:<镜像名>@@<shell 命令>（img: 已被多模态图片测试占用）
                    String spec = cmd.substring(4).trim();
                    int at = spec.indexOf("@@");
                    String name = at < 0 ? spec : spec.substring(0, at).trim();
                    String shcmd = at < 0 ? "cat /etc/os-release" : spec.substring(at + 2).trim();
                    out = runImageForDebug(name, shcmd);
                } else if (cmd.startsWith("proot:")) {
                    // 调试：在 App 进程内（带 zygote seccomp）用闭源 proroot 跑任意 rootfs。
                    // 用法 proot:<rootfs 绝对路径>@@<shell 命令>
                    String spec = cmd.substring(6).trim();
                    int at = spec.indexOf("@@");
                    String root = at < 0 ? spec : spec.substring(0, at).trim();
                    String shcmd = at < 0 ? "echo ok" : spec.substring(at + 2).trim();
                    out = runProrootForDebug(root, shcmd);
                } else {
                    // 调试通道也能直接执行任意命令，必须和 AgentLoop 一样过护栏，
                    // 否则 'am start --es pocketroot_cmd "rm -rf /"' 就绕过了 guardCheck
                    com.dsh.android.guard.CommandGuard.Verdict gv =
                            com.dsh.android.guard.CommandGuard.check(cmd);
                    if (gv.action != com.dsh.android.guard.CommandGuard.Action.ALLOW) {
                        out = "已拦截（安全护栏）：" + gv.reason + "\n这条命令没有执行。";
                    } else {
                        out = runtime.runtime().runBash(cmd, 300_000L);
                    }
                }
            } catch (Exception e) {
                out = "Error: " + e;
            }
            try {
                IOUtil.writeText(new File(getFilesDir(), "cmd.log"), out);
            } catch (Exception ignored) {
            }
            DebugLog.i("DEBUG CMD OUTPUT:\n" + out);
        });
    }

    /**
     * 调试通道里操作钩子：{@code hook:list} / {@code hook:enable <id>} / {@code hook:test <argsJson>}。
     * 不用模型、不用点 UI 就能验证「启用 → 自检 → 拦截」这条链。
     */
    private String runHookForDebug(String spec) {
        HookRegistry reg = hookRegistry();
        String[] parts = spec.split("\\s+", 2);
        String op = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";
        StringBuilder sb = new StringBuilder();
        if (op.equals("list")) {
            for (HookManifest m : reg.list()) {
                sb.append(m.id).append("  enabled=").append(reg.enabled(m.id))
                        .append("  caps=").append(m.capabilities)
                        .append("  onError=").append(m.onError).append('\n');
            }
            for (java.util.Map.Entry<String, String> e : reg.broken().entrySet()) {
                sb.append("BROKEN ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            return sb.length() == 0 ? "(没有插件)" : sb.toString();
        }
        if (op.equals("enable")) {
            for (HookManifest m : reg.list()) {
                if (!m.id.equals(arg)) continue;
                String why = reg.enable(m);
                return why == null ? "enabled " + arg : "FAILED " + why;
            }
            return "no such hook: " + arg;
        }
        if (op.equals("test")) {
            com.dsh.android.plugin.HookEngine.Decision d = reg.beforeToolCall("bash", arg);
            return "allow=" + d.allow + "  reason=" + d.reason + "  args=" + d.args;
        }
        return "usage: hook:list | hook:enable <id> | hook:test <argsJson>";
    }

    /**
     * 调试通道：用 files/oci/<name>/rootfs 起一个 guest shell。
     *
     * 和正常会话唯一的区别就是 ZNG_ROOT 指向镜像而不是 files/rootfs ——
     * 这正是"会话选镜像"将来要走的那条路。
     */
    private String runImageForDebug(String name, String shcmd) {
        File root = new File(getFilesDir(), "oci/" + name + "/rootfs");
        if (!root.isDirectory()) return "no such image: " + name;
        File lib = new File(getApplicationInfo().nativeLibraryDir);
        File ldso = new File(root, "lib/ld-linux-aarch64.so.1");
        if (!ldso.isFile()) return "image has no glibc loader: " + ldso;
        List<String> args = new ArrayList<>();
        // 用**镜像自己的** loader：loader 与 libc 是同一次构建出来的，混用会 SIGILL
        // （2.41 的 libldso.so 带 2.36 的 libc 实测 exit=132）。
        args.add(ldso.getAbsolutePath());
        args.add(new File(root, "bin/sh").getAbsolutePath());
        args.add("-c");
        args.add(shcmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.put("LD_PRELOAD", new File(lib, "libzng.so").getAbsolutePath());
            env.put("ZNG_PRELOAD", new File(lib, "libzng.so").getAbsolutePath());
            env.put("ZNG_ROOT", root.getAbsolutePath());
            env.put("ZNG_BINDS", "/proc\t/proc\n/dev\t/dev\n/sys\t/sys\n"
                    + new File(getFilesDir(), "workspace").getAbsolutePath() + "\t/root/workspace\n");
            env.put("LD_LIBRARY_PATH", root.getAbsolutePath() + "/lib:" + root.getAbsolutePath()
                    + "/lib64:" + root.getAbsolutePath() + "/usr/lib/aarch64-linux-gnu");
            env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            env.put("HOME", "/root");
            env.put("LANG", "C.UTF-8");
            Process p = pb.start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            java.io.InputStream is = p.getInputStream();
            while ((n = is.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
                if (buf.size() > 200_000) break;
            }
            int rc = p.waitFor();
            String s = buf.toString("UTF-8");
            return (s.isEmpty() ? "(no output)" : s) + "\n[exit " + rc + "]";
        } catch (Exception e) {
            return "Error: " + e;
        }
    }

    /**
     * 调试通道：用 proroot 启动器跑任意 rootfs（App 进程内，带 zygote seccomp）。
     *
     * 存在的意义：pocketroot 必须吃"打好补丁的 libc"，而 proroot 是 ptrace 路线，
     * 理论上能自己兜住那些被 seccomp 拦掉的 syscall。要验证这一点，必须让 proroot
     * 跑在**有 seccomp 的进程**里 —— adb shell / run-as 都是 Seccomp=0，测不出来。
     */
    private String runProrootForDebug(String rootPath, String shcmd) {
        File launcher = new File(getApplicationInfo().nativeLibraryDir, "libproroot.so");
        if (!launcher.isFile()) return "proroot launcher missing";
        List<String> args = new ArrayList<>();
        args.add(launcher.getAbsolutePath());
        args.add("-r");
        args.add(rootPath);
        args.add("-0");
        args.add("--link2symlink");
        for (String bind : new String[]{"/proc:/proc", "/dev:/dev", "/sys:/sys"}) {
            args.add("-b");
            args.add(bind);
        }
        args.add("-w");
        args.add("/");
        args.add("/bin/sh");
        args.add("-c");
        args.add(shcmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.environment().put("PROROOT_TMP_DIR", new File(getFilesDir(), "run").getAbsolutePath());
            pb.environment().put("HOME", "/root");
            pb.environment().put("LANG", "C.UTF-8");
            Process p = pb.start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            java.io.InputStream is = p.getInputStream();
            while ((n = is.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
                if (buf.size() > 200_000) break;
            }
            int rc = p.waitFor();
            String s = buf.toString("UTF-8");
            return (s.isEmpty() ? "(no output)" : s) + "\n[exit " + rc + "]";
        } catch (Exception e) {
            return "Error: " + e;
        }
    }

    /** 调试通道里跑单个工具：{@code "tool:<name> <json>"}。 */
    private String runToolForDebug(String spec) {
        int sp = spec.indexOf(' ');
        String name = sp < 0 ? spec : spec.substring(0, sp);
        String args = sp < 0 ? "{}" : spec.substring(sp + 1).trim();
        try {
            if ("bash".equals(name)) {
                String c = new org.json.JSONObject(args).optString("command", "");
                com.dsh.android.guard.CommandGuard.Verdict gv =
                        com.dsh.android.guard.CommandGuard.check(c);
                if (gv.action != com.dsh.android.guard.CommandGuard.Action.ALLOW) {
                    return "已拦截（安全护栏）：" + gv.reason + "\n这条命令没有执行。";
                }
            }
            return buildTools().execute(new ToolCall("debug", name, args));
        } catch (Exception e) {
            return "已阻止执行（参数解析失败）：" + e.getMessage();
        }
    }

    /** Hidden diagnostic: long-press the runtime status to check the rootfs + toolchain. */
    private void runSelfTest() {
        Toast.makeText(this, getString(R.string.selftest_running), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String res;
            try {
                // Debian 轻量 rootfs 自带工具链，不需要 apt；这里查的是「运行时是否真的可用」。
                res = runtime.runtime().runBash(
                        "set +e; echo ===ID===; id; uname -m; "
                                + "echo ===OS===; grep -E 'PRETTY_NAME|VARIANT_ID' /etc/os-release; "
                                + "echo ===DISK===; df -h / | tail -1; "
                                + "echo ===PY===; python3 -V; "
                                + "python3 -c 'import numpy, requests, openpyxl, docx; "
                                + "print(\"imports ok\", numpy.__version__, requests.__version__)'; "
                                + "python3 -c 'import ctypes; ctypes.CDLL(\"libgomp.so.1\"); print(\"libgomp ok\")'; "
                                + "echo ===TOOLS===; git --version; curl --version | head -1; jq --version; "
                                + "echo ===WORKSPACE===; pwd; touch ws.txt && ls ws.txt && rm ws.txt; "
                                + "echo DONE",
                        300_000L);
            } catch (Exception e) {
                res = "Error: " + e;
            }
            final String out = res;
            try {
                IOUtil.writeText(new File(getFilesDir(), "selftest.log"), out);
            } catch (Exception ignored) {
            }
            DebugLog.i("SELFTEST OUTPUT:\n" + out);
            main.post(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.selftest_title)
                    .setMessage(out)
                    .setPositiveButton(R.string.ok, null)
                    .show());
        });
    }

    // ---------- model selection ----------

    private void showModelMenu() {
        android.content.SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE);
        String saved = s.getString("model", "deepseek-chat");
        List<String> options = new ArrayList<>(Arrays.asList(MODELS));
        options.add(CUSTOM_MODEL_OPTION);
        int cur = options.indexOf(saved);
        if (cur < 0) cur = options.size() - 1; // custom
        new AlertDialog.Builder(this)
                .setTitle(R.string.model_label)
                .setSingleChoiceItems(options.toArray(new String[0]), cur, (d, which) -> {
                    d.dismiss();
                    selectModel(options.get(which));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void selectModel(String m) {
        if (CUSTOM_MODEL_OPTION.equals(m)) {
            android.content.SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE);
            String saved = s.getString("model", "");
            final EditText et = new EditText(this);
            et.setSingleLine(true);
            et.setHint(R.string.custom_model_hint);
            et.setText(saved);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.model_label)
                    .setView(et)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String v = et.getText().toString().trim();
                        if (!v.isEmpty()) saveModel(v);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        saveModel(m);
    }

    private void saveModel(String m) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("model", m).apply();
        toast(getString(R.string.model_selected, shortModel(m)));
    }

    // ---------- settings ----------

    private void showSettings() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(8), pad, 0);

        TextView title = tvGlyph(getString(R.string.settings_models), 15, R.color.text_primary);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView intro = tvGlyph(getString(R.string.settings_models_intro), 12, R.color.text_caption);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        layout.addView(intro, ilp);

        final EditText key = new EditText(this);
        key.setHint(R.string.api_key_hint);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedKey = secrets.get("api_key");
        if (savedKey != null && !savedKey.isEmpty()) key.setText(savedKey);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(14);
        layout.addView(key, klp);

        final EditText base = new EditText(this);
        base.setHint(R.string.base_url_hint);
        base.setSingleLine(true);
        android.content.SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE);
        base.setText(s.getString("base_url", ""));
        layout.addView(base);

        LinearLayout modelArea = new LinearLayout(this);
        modelArea.setOrientation(LinearLayout.VERTICAL);
        TextView modelLabel = tvGlyph(getString(R.string.model_label), 13, R.color.text_secondary);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(12);
        modelArea.addView(modelLabel, mlp);
        TextView current = tvGlyph(s.getString("model", "deepseek-chat"), 15, R.color.text_primary);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(2);
        modelArea.addView(current, clp);
        TextView change = tvGlyph(getString(R.string.model_change), 13, R.color.brand);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chp.topMargin = dp(4);
        change.setOnClickListener(v -> showModelMenu());
        modelArea.addView(change, chp);
        layout.addView(modelArea);

        layout.addView(buildHookSection());

        // 设置项越来越多（key / base_url / 模型 / 钩子），小屏上会超出对话框高度，
        // 套一层 ScrollView 才能把按钮留在可见区域。
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(scroll)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (!k.isEmpty()) secrets.put("api_key", k);
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                            .putString("base_url", base.getText().toString().trim())
                            .apply();
                    toast(getString(R.string.settings_saved));
                })
                .setNeutralButton(R.string.new_session, (d, w) -> confirmNewSession())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 设置页的「钩子插件」区块。
     *
     * 两条规矩来自 v0.2.0 的 issue #2：
     *  1. 开之前先跑自检（{@link HookRegistry#enable}），跑不通就**开不起来**，不会出现
     *     "UI 显示启用、实际一次都没生效"；
     *  2. 失败原因直接红字留在这一行上，不用去翻 dsh.log。
     */
    private LinearLayout buildHookSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(22);
        box.setLayoutParams(blp);

        TextView title = tvGlyph(getString(R.string.hooks_section), 15, R.color.text_primary);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        TextView intro = tvGlyph(getString(R.string.hooks_intro), 12, R.color.text_caption);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        box.addView(intro, ilp);

        HookRegistry reg = hookRegistry();
        for (HookManifest m : reg.list()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(12);
            box.addView(row, rlp);

            final android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(m.name + "  ·  " + m.id);
            cb.setTextSize(14);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setChecked(reg.enabled(m.id));

            String caps = m.capabilities.isEmpty() ? "-" : String.join(", ", m.capabilities);
            TextView desc = tvGlyph(m.description + "\n能力: " + caps + "  ·  失败策略: " + m.onError,
                    12, R.color.text_caption);
            final TextView status = tvGlyph(getString(reg.enabled(m.id)
                    ? R.string.hook_on : R.string.hook_off), 12, R.color.text_tertiary);
            String err = reg.lastError(m.id);
            if (err != null) {
                status.setText(getString(R.string.hook_failed, err));
                status.setTextColor(getColor(R.color.accent_red));
            }

            cb.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) {
                    reg.disable(m.id);
                    status.setText(R.string.hook_off);
                    status.setTextColor(getColor(R.color.text_tertiary));
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.hook_trust_title, m.name))
                        .setMessage(R.string.hook_trust_msg)
                        .setPositiveButton(R.string.enable, (d, w) -> {
                            String why = reg.enable(m);
                            if (why == null) {
                                status.setText(R.string.hook_on);
                                status.setTextColor(getColor(R.color.accent_green));
                            } else {
                                cb.setChecked(false);
                                status.setText(getString(R.string.hook_failed, why));
                                status.setTextColor(getColor(R.color.accent_red));
                            }
                        })
                        .setNegativeButton(R.string.cancel, (d, w) -> cb.setChecked(false))
                        .show();
            });

            row.addView(cb);
            row.addView(desc);
            row.addView(status);
        }

        for (java.util.Map.Entry<String, String> e : reg.broken().entrySet()) {
            TextView bad = tvGlyph("✕ " + e.getKey() + ": " + e.getValue(), 12, R.color.accent_red);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            box.addView(bad, lp);
        }
        return box;
    }

    private void confirmNewSession() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_session)
                .setMessage(R.string.new_session_msg)
                .setPositiveButton(R.string.new_session, (d2, w2) -> resetSession())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void resetSession() {
        dismissStatusRow();
        if (activeLoop != null) activeLoop.cancel();
        activeLoop = null;
        setBusy(false);
        activeSession = null;
        currentSessionFile = null;
        stopPillAnimation();
        resetThinkFields();
        lastToolRow = null;
        lastToolOut = null;
        lastToolPill = null;
        assistantMeta = null;
        transcript.removeAllViews();
        if (home != null) home.setVisibility(View.VISIBLE);
        input.setHint(getString(R.string.input_hint_hero));
        refreshTopTitle();
        rebuildSessionList();
        toast(getString(R.string.reset_done));
    }

    private void startNewChat() {
        if (busy) {
            toast(getString(R.string.busy_wait));
            return;
        }
        activeSession = null;
        currentSessionFile = null;
        drawerLayout.closeDrawers();
        transcript.removeAllViews();
        if (home != null) home.setVisibility(View.VISIBLE);
        input.setHint(getString(R.string.input_hint_hero));
        refreshTopTitle();
        rebuildSessionList();
    }

    private void switchSession(File file) {
        if (busy) {
            toast(getString(R.string.busy_wait));
            return;
        }
        activeSession = file;
        currentSessionFile = file;
        drawerLayout.closeDrawers();
        transcript.removeAllViews();
        try {
            replayFile = file;
            final int total = JsonlSessionLog.countLines(file);
            final List<ChatMessage> tail = JsonlSessionLog.loadTail(file, REPLAY_LIMIT);
            replayTruncated = total > tail.size();
            renderSavedSession(tail, replayTruncated ? total - tail.size() : 0);
        } catch (Exception ignored) {
            if (home != null) home.setVisibility(View.VISIBLE);
            input.setHint(getString(R.string.input_hint_hero));
        }
        refreshTopTitle();
        rebuildSessionList();
        scrollDown();
    }

    /** Full transcript replay of a saved session: user/assistant bubbles plus collapsed
     * thinking cards (persisted "reasoning") and tool cards (persisted tool_calls + tool results). */
    private void renderSavedSession(List<ChatMessage> msgs, int hiddenCount) {
        final Map<String, TextView> outs = new HashMap<>();
        final Map<String, TextView> pills = new HashMap<>();
        final int gen = ++replayGeneration;
        replaying = true;
        if (hiddenCount > 0) addShowEarlierRow(hiddenCount);
        final int[] idx = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (gen != replayGeneration) return;      // 期间切了会话，旧批次直接作废
            int end = Math.min(msgs.size(), idx[0] + RENDER_BATCH);
            while (idx[0] < end) {
                renderOne(msgs.get(idx[0]), outs, pills);
                idx[0]++;
            }
            if (idx[0] < msgs.size()) {
                main.post(step[0]);                   // 下一帧继续，onCreate 不阻塞
            } else {
                replaying = false;
                scrollDown();
            }
        };
        main.post(step[0]);
    }

    private void addShowEarlierRow(int hiddenCount) {
        TextView more = tvGlyph(getString(R.string.show_earlier, hiddenCount), 13, R.color.brand);
        more.setPadding(dp(12), dp(10), dp(12), dp(10));
        more.setBackgroundResource(R.drawable.bg_chip);
        more.setOnClickListener(v -> showFullHistory());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        mlp.topMargin = dp(12);
        attachTranscript(more, mlp);
    }

    private void renderOne(ChatMessage m, Map<String, TextView> outs, Map<String, TextView> pills) {
        if (m == null) return;
        if ("user".equals(m.role)
                && ((m.content != null && !m.content.isEmpty())
                    || (m.refs != null && !m.refs.isEmpty())
                    || (m.imagePaths != null && !m.imagePaths.isEmpty()))) {
            addBubbleUserWithRefs(m.content, m.refs);
        } else if ("assistant".equals(m.role)) {
            if (m.reasoning != null && !m.reasoning.trim().isEmpty()) {
                addSavedThinkCard(m.reasoning);
            }
            if (m.content != null && !m.content.isEmpty()) {
                addBubbleAssistant(m.content);
            }
            if (m.toolCalls != null) {
                for (ToolCall tc : m.toolCalls) {
                    LinearLayout row = newToolRow(tc);
                    outs.put(tc.id, lastToolOut);
                    pills.put(tc.id, lastToolPill);
                    attachTranscript(row);
                }
            }
        } else if ("tool".equals(m.role) && m.toolCallId != null && m.content != null) {
            TextView out = outs.remove(m.toolCallId);
            if (out != null) {
                String t = IOUtil.truncate(m.content, 10000);
                out.setText(t);
                out.setTextColor(getColor(startsWithError(t) ? R.color.accent_red : R.color.tool_text));
                TextView pill = pills.remove(m.toolCallId);
                if (pill != null) applyStatusPill(pill, t);
            }
        }
    }

    /** 展开完整历史：后台读全量，主线程一次性重渲染。 */
    private void showFullHistory() {
        final File f = replayFile;
        if (f == null) return;
        executor.execute(() -> {
            try {
                final List<ChatMessage> all = JsonlSessionLog.load(f);
                main.post(() -> {
                    transcript.removeAllViews();
                    replayTruncated = false;
                    renderSavedSession(all, 0);
                });
            } catch (Exception ignored) {
            }
        });
    }

    /** Collapsed Thinking card for a replayed session (same visual as the live one). */
    private void addSavedThinkCard(String reasoning) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(16);
        row.setLayoutParams(rp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(4));

        TextView chevron = tvGlyph("▸", 12, R.color.text_secondary);
        chevron.setPadding(0, 0, dp(8), 0);
        header.addView(chevron);

        TextView title = tvGlyph(getString(R.string.think), 14, R.color.text_secondary);
        header.addView(title);

        final TextView body = new TextView(this);
        body.setTextSize(13);
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(4, 1.0f);
        body.setTextColor(getColor(R.color.text_tertiary));
        body.setPadding(dp(22), dp(2), 0, dp(2));
        // 折叠卡片默认不解析 Markdown：长会话里几十张卡一起渲染会明显拖慢启动
        body.setVisibility(View.GONE);

        final boolean[] open = {false};
        final boolean[] rendered = {false};
        header.setOnClickListener(v -> {
            open[0] = !open[0];
            chevron.setText(open[0] ? "▾" : "▸");
            if (open[0] && !rendered[0]) {
                MarkdownRenderer.setText(body, reasoning);
                rendered[0] = true;
            }
            body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
        });

        row.addView(header);
        row.addView(body);
        attachTranscript(row);
    }

    private void rebuildSessionList() {
        sessionList.removeAllViews();
        File dir = new File(getFilesDir(), "sessions");
        final File[] files = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            sessionList.addView(sessionEmpty(getString(R.string.no_sessions)));
            return;
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));

        // 搜索：命中标题就直接平铺，不再按置顶/今天/昨天分组
        if (!sessionQuery.isEmpty()) {
            int hits = 0;
            for (File f : files) {
                String title = sessionTitle(f);
                if (!title.toLowerCase(Locale.ROOT).contains(sessionQuery)) continue;
                sessionList.addView(sessionItem(f, title, f.equals(currentSessionFile)));
                hits++;
            }
            if (hits == 0) sessionList.addView(sessionEmpty(getString(R.string.no_match)));
            return;
        }

        java.util.Set<String> pins = pinnedSessions();
        List<File> pinned = new ArrayList<>();
        List<File> rest = new ArrayList<>();
        for (File f : files) (pins.contains(f.getName()) ? pinned : rest).add(f);
        if (!pinned.isEmpty()) {
            sessionList.addView(sessionGroupHeader(getString(R.string.drawer_pinned)));
            for (File f : pinned) sessionList.addView(sessionItem(f, sessionTitle(f),
                    f.equals(currentSessionFile)));
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.border_light));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(dp(10), dp(12), dp(10), 0);
            sessionList.addView(divider, lp);
        }
        String curGroup = null;
        for (File f : rest) {
            String group = sessionGroup(f.getName());
            if (!group.equals(curGroup)) {
                curGroup = group;
                sessionList.addView(sessionGroupHeader(group));
            }
            sessionList.addView(sessionItem(f, sessionTitle(f), f.equals(currentSessionFile)));
        }
    }

    private TextView sessionEmpty(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(getColor(R.color.text_caption));
        tv.setPadding(dp(10), dp(16), dp(10), dp(16));
        return tv;
    }

    private java.util.Set<String> pinnedSessions() {
        return new java.util.HashSet<>(getSharedPreferences("sessions", MODE_PRIVATE)
                .getStringSet("pinned", new java.util.HashSet<>()));
    }

    private void setPinned(File f, boolean pin) {
        android.content.SharedPreferences sp = getSharedPreferences("sessions", MODE_PRIVATE);
        java.util.Set<String> cur = new java.util.HashSet<>(sp.getStringSet("pinned", new java.util.HashSet<>()));
        if (pin) cur.add(f.getName());
        else cur.remove(f.getName());
        sp.edit().putStringSet("pinned", cur).apply();
    }

    /** Long-press quick menu on a sidebar entry: pin/unpin and delete. */
    private void showSessionMenu(View anchor, final File f, final boolean pinned) {
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        pm.setGravity(android.view.Gravity.END);
        pm.getMenu().add(0, 1, 0, getString(pinned ? R.string.unpin : R.string.pin));
        pm.getMenu().add(0, 2, 0, getString(R.string.delete_session));
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                setPinned(f, !pinned);
                rebuildSessionList();
            } else if (id == 2) {
                confirmDeleteSession(f);
            }
            return true;
        });
        pm.show();
    }

    private void confirmDeleteSession(final File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_session)
                .setMessage(R.string.delete_session_msg)
                .setPositiveButton(R.string.delete_ok, (d, w) -> deleteSession(f))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteSession(File f) {
        boolean isCurrent = f.equals(currentSessionFile) || f.equals(activeSession);
        if (isCurrent && busy) {
            toast(getString(R.string.busy_wait));
            return;
        }
        setPinned(f, false);
        f.delete();
        if (f.equals(activeSession)) activeSession = null;
        if (f.equals(currentSessionFile)) {
            currentSessionFile = null;
            transcript.removeAllViews();
            if (home != null) home.setVisibility(View.VISIBLE);
            input.setHint(getString(R.string.input_hint_hero));
        }
        refreshTopTitle();
        rebuildSessionList();
    }

    private TextView sessionGroupHeader(String group) {
        TextView tv = new TextView(this);
        tv.setText(group);
        tv.setTextSize(14);
        tv.setTextColor(getColor(R.color.text_caption));
        tv.setPadding(dp(10), dp(18), dp(10), dp(4));
        return tv;
    }

    /** 会话行：选中/按下时蓝底浅蓝字的悬停药丸，右侧挂一个 ⋯ 菜单。 */
    private View sessionItem(File f, String title, boolean active) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(4), dp(4));
        row.setBackgroundResource(R.drawable.bg_session_row);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(18);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(0, dp(8), 0, dp(8));
        tv.setTextColor(getColorStateList(R.color.session_row_text));
        row.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_horiz);
        more.setColorFilter(getColor(R.color.brand_muted));
        more.setPadding(dp(5), dp(5), dp(5), dp(5));
        more.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        more.setOnClickListener(v -> showSessionMenu(v, f, pinnedSessions().contains(f.getName())));
        row.addView(more, new LinearLayout.LayoutParams(dp(30), dp(30)));

        // 选中态要在子控件的状态选择器生效前同步：setSelected 只在当时往下派发一次
        row.setSelected(active);
        row.setClickable(true);
        row.setOnClickListener(v -> switchSession(f));
        row.setOnLongClickListener(v -> {
            showSessionMenu(v, f, pinnedSessions().contains(f.getName()));
            return true;
        });
        return row;
    }

    /** 抽屉底部账号行的 "…" 菜单：新建对话 / 设置。 */
    private void showSidebarMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        pm.setGravity(android.view.Gravity.END);
        pm.getMenu().add(0, 1, 0, getString(R.string.new_chat));
        pm.getMenu().add(0, 2, 1, getString(R.string.settings));
        pm.setOnMenuItemClickListener(item -> {
            drawerLayout.closeDrawers();
            if (item.getItemId() == 1) startNewChat();
            else showSettings();
            return true;
        });
        pm.show();
    }

    private String sessionTitle(File f) {
        String fallback = f.getName().replace(".jsonl", "");
        String s = JsonlSessionLog.firstUserText(f, 30);
        if (s == null) return fallback;
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 26 ? s.substring(0, 26) + "…" : s;
    }

    /** "今天" / "昨天" / "yyyy-MM-dd" / "yyyy-MM" fallback, from file name yyyyMMdd-HHmmss. */
    private String sessionGroup(String name) {
        try {
            String day = name.substring(0, 8);
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
            Date d = f.parse(day);
            Date today = new Date();
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            long todayStart = c.getTimeInMillis();
            long start = d.getTime();
            if (start >= todayStart) return getString(R.string.drawer_today);
            if (start >= todayStart - 86400000L) return getString(R.string.drawer_yesterday);
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
        } catch (Exception e) {
            return name;
        }
    }

    private void loadLatestSession() {
        File dir = new File(getFilesDir(), "sessions");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            input.setHint(getString(R.string.input_hint_hero));
            return;
        }
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        final File latestFile = latest;   // for 循环里改过值，lambda 要用 final 副本
        activeSession = latestFile;
        currentSessionFile = latestFile;
        replayFile = latestFile;
        // 会话日志读取 + 重放全部挪出 onCreate：冷启动不再随上下文长度变慢
        executor.execute(() -> {
            try {
                final int total = JsonlSessionLog.countLines(latestFile);
                final List<ChatMessage> tail = JsonlSessionLog.loadTail(latestFile, REPLAY_LIMIT);
                main.post(() -> {
                    replayTruncated = total > tail.size();
                    renderSavedSession(tail, replayTruncated ? total - tail.size() : 0);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private static String buildSystemPrompt() {
        return "You are Pocket Agent, a coding agent inside an Android app, working in a Debian rootfs.\n"
                + "Work autonomously: inspect before you change, and verify with a command when possible.\n\n"
                + "Tool routing:\n"
                + "- bash: run programs, tests, package managers; use it for anything the other tools do not cover.\n"
                + "- glob / grep: find files by name or content. Use these instead of find/grep via bash.\n"
                + "- str_replace_editor: view, create and edit files under the workspace.\n"
                + "- read / write / edit: structured file tools. Prefer read over cat/sed/head/tail; "
                + "prefer edit for targeted changes; read before write or edit.\n"
                + "- read_image: read a PNG/JPEG/WebP/GIF file and actually see the image.\n"
                + "- web_search: look up current facts, versions or unfamiliar errors; then web_fetch the best hit.\n"
                + "- ask_user_question: ask before irreversible or genuinely ambiguous choices, one question at a time.\n\n"
                + "Containers (no docker daemon here):\n"
                + "- pimg pull <repo>:<tag>   pull a linux/arm64 image from a registry and seed it "
                + "(layers are applied, glibc is patched; fails loudly if unsupported)\n"
                + "- pimg ls / info / rm     manage images (stored in /images/<name>)\n"
                + "- container {image, command}  run a command inside an image (docker-run equivalent)\n"
                + "- Only Debian-family glibc images work (no Alpine/musl); ports must be >=1024; "
                + "there are no namespaces, so services share the network and 127.0.0.1.\n"
                + "- Long-running services: start them with nohup ... > /tmp/<svc>.log 2>&1 &, then tail the log.\n"
                + "- Unix-domain sockets work (bind/connect paths are translated), so daemons can keep their default socket dir.\n"
                + "- Services are children of the app: they stop when the app process is killed; nothing supervises them.\n"
                + "- Example (postgres): initdb -D /var/lib/postgresql/data -U postgres --locale=C -E UTF8; "
                + "pg_ctl -D /var/lib/postgresql/data -l /tmp/pg.log start; "
                + "psql -h /var/run/postgresql -U postgres -c'select 1'\n"
                + "- Example (mysql): mysqld --initialize-insecure --datadir=/var/lib/mysql --basedir=/usr "
                + "(retry once if it aborts); mysqld --datadir=/var/lib/mysql --socket=/tmp/m.sock --port=3306 "
                + "--bind-address=127.0.0.1 --mysqlx=0 & ; mysql -h 127.0.0.1 -u root -e'select 1'\n"
                + "- Local .deb: dpkg -i --force-not-root x.deb (we are not root); "
                + "verify downloads with dpkg-deb -f x.deb Version (truncated files fail silently)\n\n"
                + "Rules:\n"
                + "- The workspace is the user's home; put everything you create there.\n"
                + "- Use the read tool — not shell commands like cat, sed, head, or tail — to inspect text files. "
                + "Use offset and limit to read a line range or continue a large file.\n"
                + "- Read a file before overwriting it with write and prefer edit for targeted changes.\n"
                + "- Read a file before editing it unless you just created or edited it in this session.\n"
                + "- Keep answers short; quote only the output that matters.\n"
                + "- If a tool fails the same way twice, change approach instead of retrying.\n"
                + "- Reply in the user's language.";
    }

    private void hideHome() {
        if (home != null && home.getVisibility() != View.GONE) home.setVisibility(View.GONE);
        input.setHint(getString(R.string.input_hint));
    }

    /** 连续贴底跟随：内容长多少就滚多少，不重启动画、也不每次瞬跳到底。
     *  用户主动回看历史时不再强行把他拉回底部。 */
    private void scrollDown() {
        if (replaying || scrollPending) return;
        scrollPending = true;
        scroll.post(() -> {
            scrollPending = false;
            View content = scroll.getChildCount() > 0 ? scroll.getChildAt(0) : null;
            if (content == null) return;
            int gap = content.getBottom() - (scroll.getScrollY() + scroll.getHeight());
            if (userPeeking && gap > dp(48)) return;
            programmaticScroll = true;
            scroll.scrollTo(0, Math.max(0, content.getBottom() - scroll.getHeight()));
            programmaticScroll = false;
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}