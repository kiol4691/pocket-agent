package com.dsh.android;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.Configuration;
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
import android.widget.Switch;
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

import com.dsh.android.agent.AgentLoop;
import com.dsh.android.agent.ChatMessage;
import com.dsh.android.agent.ToolCall;
import com.dsh.android.ui.BubbleBuilder;
import com.dsh.android.ui.MarkdownRenderer;
import com.dsh.android.llm.DeepSeekClient;
import com.dsh.android.runtime.RuntimeManager;
import com.dsh.android.session.JsonlSessionLog;
import com.dsh.android.tools.BashTool;
import com.dsh.android.tools.StrReplaceEditorTool;
import com.dsh.android.tools.ToolRegistry;
import com.dsh.android.util.DebugLog;
import com.dsh.android.util.IOUtil;
import com.dsh.android.util.SecretStore;

/** Pocket Agent — lightweight on-device agent harness for Android. */
public final class MainActivity extends AppCompatActivity {

    private static final String[] MODELS = {"deepseek-chat", "deepseek-reasoner", "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1"};
    private static final String CUSTOM_MODEL_OPTION = "Custom model…";

    private LinearLayout transcript;
    private ScrollView scroll;
    private EditText input;
    private FrameLayout send;
    private ImageView sendIcon;
    private TextView runtimeStatus;
    private TextView modelChip;
    private TextView topTitle;
    private View home;
    private DrawerLayout drawerLayout;
    private float downX;
    private float downY;
    private LinearLayout sessionList;
    private File activeSession;
    private File currentSessionFile;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private SecretStore secrets;
    private RuntimeManager runtime;
    private AgentLoop activeLoop;

    private boolean busy;
    private boolean runtimeReady;

    // turn runtime state
    private long turnStart;
    private TextView statusRow;
    private ValueAnimator statusAnimator;
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
        send = findViewById(R.id.send);
        sendIcon = findViewById(R.id.sendIcon);
        runtimeStatus = findViewById(R.id.runtimeStatus);
        modelChip = findViewById(R.id.modelChip);
        topTitle = findViewById(R.id.topTitle);
        home = findViewById(R.id.home);
        drawerLayout = findViewById(R.id.drawerLayout);
        sessionList = findViewById(R.id.sessionList);
        MarkdownRenderer.init(this);
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("keep_alive", false)) {
            KeepAliveService.start(this);
        }

        findViewById(R.id.btnMenu).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.btnNewChat).setOnClickListener(v -> startNewChat());
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                rebuildSessionList();
            }
        });

        send.setOnClickListener(v -> {
            if (busy) {
                if (activeLoop != null) activeLoop.cancel();
                new Thread(() -> runtime.proroot().abortWork(), "abort-work").start();
            } else {
                onSend();
            }
        });
        modelChip.setOnClickListener(v -> showModelMenu());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
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
        runtimeStatus.setOnLongClickListener(v -> {
            runSelfTest();
            return true;
        });
        refreshRuntimeStatus();
        loadLatestSession();
        refreshTopTitle();
        if (!runtimeReady) autoSetup();
        main.postDelayed(this::promptStorageAccess, 2500);
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
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        scroll.postDelayed(() -> scroll.fullScroll(View.FOCUS_DOWN), 350);
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
        if (statusAnimator != null) statusAnimator.cancel();
    }

    /** One-time guidance to grant All-Files-Access so Ubuntu can browse the phone's shared
     * storage at /sdcard. Space info (df -h / inside Ubuntu) works without it. */
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

    private boolean pendingKeepAlive;

    private boolean notificationsEnabled() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled();
    }

    private void promptNotificationPermission() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notif_perm_title)
                .setMessage(R.string.notif_perm_msg)
                .setPositiveButton(R.string.notif_perm_open, (d, w) -> {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
                        return;
                    }
                    try {
                        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyGrantedKeepAlive() {
        pendingKeepAlive = false;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("keep_alive", true).apply();
        KeepAliveService.start(this);
        toast(getString(R.string.keepalive_on));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0) {
            if (grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && pendingKeepAlive) {
                applyGrantedKeepAlive();
            } else if (grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingKeepAlive = false;
            }
        }
    }

    /** Re-check storage access when returning from the Settings grant. */
    @Override
    protected void onResume() {
        super.onResume();
        refreshRuntimeStatus();
        if (pendingKeepAlive && notificationsEnabled()) {
            applyGrantedKeepAlive();
        }
    }

    private void autoSetup() {
        executor.execute(() -> {
            try {
                runtime.setup(msg -> main.post(() -> runtimeStatus.setText(msg)));
                main.post(this::refreshRuntimeStatus);
                DebugLog.i("autoSetup: runtime ready");
            } catch (Exception e) {
                final String m = IOUtil.truncate(e.getMessage(), 200);
                DebugLog.e("autoSetup failed", e);
                main.post(() -> runtimeStatus.setText(getString(R.string.setup_failed) + m));
            }
        });
    }

    // ---------- runtime ----------

    private void refreshRuntimeStatus() {
        runtimeReady = runtime.state() == RuntimeManager.State.READY;
        runtimeStatus.setText(getString(runtimeReady ? R.string.runtime_ready : R.string.runtime_offline));
        runtimeStatus.setTextColor(getColor(runtimeReady ? R.color.accent_green : R.color.accent_red));
        android.content.SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE);
        String model = s.getString("model", "deepseek-chat");
        if (modelChip != null) modelChip.setText(shortModel(model) + " ▾");
    }

    private static String shortModel(String model) {
        if (model == null) return "";
        return model.replace("deepseek-ai/", "").replace("deepseek-chat", "DS-Chat")
                .replace("deepseek-reasoner", "DS-Think").replace("DeepSeek-R1", "R1")
                .replace("DeepSeek-V3", "V3").replace("deepseek-v4-flash", "v4-flash");
    }

    // ---------- chat ----------

    private void onSend() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        String apiKey = secrets.get("api_key");
        if (apiKey == null || apiKey.isEmpty()) {
            showSettings();
            return;
        }
        input.setText("");
        addBubbleUser(text);
        setBusy(true);
        executor.execute(() -> {
            if (!runtimeReady) {
                try {
                    runtime.setup(msg -> main.post(() -> runtimeStatus.setText(msg)));
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
            startAgent(text);
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        sendIcon.setImageResource(b ? R.drawable.ic_stop : R.drawable.ic_send);
        send.setAlpha(b ? 0.92f : 1f);
        if (b) {
            KeepAliveService.start(this);
        } else if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("keep_alive", false)) {
            KeepAliveService.stop(this);
        }
    }

    private void startAgent(String task) {
        final JsonlSessionLog log;
        final List<ChatMessage> history;
        try {
            File sessionsDir = new File(getFilesDir(), "sessions");
            if (activeSession != null && activeSession.isFile()) {
                history = JsonlSessionLog.load(activeSession);
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
        ToolRegistry tools = new ToolRegistry();
        tools.register(new BashTool(runtime.proroot()));
        tools.register(new StrReplaceEditorTool(runtime.proroot().workspaceDir().toPath()));
        AgentLoop loop = new AgentLoop(new DeepSeekClient(baseUrl, apiKey, model), tools, buildSystemPrompt(), log);
        activeLoop = loop;
        final LinearLayout[] assistant = new LinearLayout[1];
        final StringBuilder[] assistantBuf = new StringBuilder[1];
        loop.run(task, history, new AgentLoop.Listener() {

            @Override
            public void onTurnStart() {
                turnStart = System.currentTimeMillis();
                main.post(MainActivity.this::showStatusRow);
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
                        BubbleBuilder.sync(ab, md, MainActivity.this, bubbleStyle());
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
                        queueRender(() -> thinkBody.setText(MarkdownRenderer.render(thinkText.toString())));
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
                    assistant[0] = null;
                    assistantBuf[0] = null;
                    lastToolRow = newToolRow(call);
                    transcript.addView(lastToolRow);
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

    private final Runnable pillTick = new Runnable() {
        @Override
        public void run() {
            if (!pillAnimRunning || animPill == null) return;
            pillDots = (pillDots + 1) % 4;
            StringBuilder sb = new StringBuilder(getString(R.string.pill_running_base));
            for (int i = 0; i < pillDots; i++) sb.append('.');
            animPill.setText(sb.toString());
            main.postDelayed(this, 450);
        }
    };
    private TextView animPill;
    private int pillDots;
    private boolean pillAnimRunning;

    private void startPillAnimation(TextView pill) {
        animPill = pill;
        pillDots = 0;
        if (pillAnimRunning) return;
        pillAnimRunning = true;
        main.postDelayed(pillTick, 450);
    }

    private void stopPillAnimation() {
        pillAnimRunning = false;
        animPill = null;
        main.removeCallbacks(pillTick);
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
        transcript.addView(statusRow, lp);
        final int brand = getColor(R.color.brand);
        final int tertiary = getColor(R.color.text_tertiary);
        statusAnimator = ValueAnimator.ofInt(tertiary, brand, tertiary);
        statusAnimator.setDuration(2200);
        statusAnimator.setRepeatMode(ValueAnimator.REVERSE);
        statusAnimator.setEvaluator(new android.animation.ArgbEvaluator());
        statusAnimator.addUpdateListener(a -> statusRow.setTextColor((Integer) a.getAnimatedValue()));
        statusAnimator.start();
        scrollDown();
    }

    private void dismissStatusRow() {
        if (statusRow != null) {
            transcript.removeView(statusRow);
            statusRow = null;
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
        transcript.addView(tv, lp);
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
        transcript.addView(box, lp);
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

    private void addMetaOnly(String text) {
        TextView tv = new TextView(this);
        tv.setText("· " + text);
        tv.setTextSize(11);
        tv.setTextColor(getColor(R.color.text_caption));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.gravity = Gravity.START;
        transcript.addView(tv, lp);
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
        transcript.addView(tv, lp);
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
            body.setText(MarkdownRenderer.render(buf.toString()));
            if (!open[0]) {
                summary.setText(done[0] ? firstLine(buf.toString()) : lastLine(buf.toString()));
            }
        });

        row.addView(header);
        row.addView(body);
        transcript.addView(row);
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

    /** Hidden diagnostic: long-press the runtime status to run a compile-and-run check inside proroot. */
    private void runSelfTest() {
        Toast.makeText(this, getString(R.string.selftest_running), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String res;
            try {
                res = runtime.proroot().runBash(
                        "echo === ; id ; uname -m ; "
                                + "echo ===DF=== ; df -h / ; "
                                + "echo ===SDCARD=== ; df -h /sdcard 2>&1 ; ls -d /sdcard 2>&1 ; "
                                + "(timeout 420 bash -c 'rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*; apt-get update -y' > /root/workspace/apt.log 2>&1; echo URC=$? >> /root/workspace/apt.log; "
                                + " timeout 420 apt-get install -y --no-install-recommends bash coreutils git ca-certificates curl >> /root/workspace/apt.log 2>&1; "
                                + " echo IRC=$? >> /root/workspace/apt.log) ; "
                                + "echo ===APTLOG=== ; tail -25 /root/workspace/apt.log ; "
                                + "echo ===WHICH=== ; which bash git curl python3",
                        900_000L);
            } catch (Exception e) {
                res = "Error: " + e;
            }
            final String out = res;
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
        if (modelChip != null) modelChip.setText(shortModel(m) + " ▾");
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

        final Switch sw = new Switch(this);
        sw.setChecked(s.getBoolean("keep_alive", false));
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !notificationsEnabled()) {
                // Gate keep-alive on the persistent-notification permission: without it the
                // foreground service runs invisibly on API 33+ and users think it is broken.
                sw.setChecked(false);
                pendingKeepAlive = true;
                promptNotificationPermission();
                return;
            }
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("keep_alive", checked).apply();
            if (checked) {
                KeepAliveService.start(MainActivity.this);
            } else if (!busy) {
                KeepAliveService.stop(MainActivity.this);
            }
        });

        LinearLayout kaRow = new LinearLayout(this);
        kaRow.setOrientation(LinearLayout.HORIZONTAL);
        kaRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView kaLabel = tvGlyph(getString(R.string.keepalive_label), 14, R.color.text_primary);
        kaLabel.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        kaRow.addView(kaLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        kaRow.addView(sw);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.topMargin = dp(18);
        layout.addView(kaRow, kp);

        TextView kaSub = tvGlyph(getString(R.string.keepalive_sub), 11, R.color.text_caption);
        LinearLayout.LayoutParams ksp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ksp.topMargin = dp(2);
        layout.addView(kaSub, ksp);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(layout)
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
            renderSavedSession(JsonlSessionLog.load(file));
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
    private void renderSavedSession(List<ChatMessage> msgs) {
        Map<String, TextView> outs = new HashMap<>();
        Map<String, TextView> pills = new HashMap<>();
        for (ChatMessage m : msgs) {
            if (m == null) continue;
            if ("user".equals(m.role) && m.content != null) {
                addBubbleUser(m.content);
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
                        transcript.addView(row);
                    }
                }
            } else if ("tool".equals(m.role) && m.toolCallId != null && m.content != null) {
                TextView out = outs.remove(m.toolCallId);
                if (out != null) {
                    String s = IOUtil.truncate(m.content, 10000);
                    out.setText(s);
                    out.setTextColor(getColor(startsWithError(s) ? R.color.accent_red : R.color.tool_text));
                    TextView pill = pills.remove(m.toolCallId);
                    if (pill != null) applyStatusPill(pill, s);
                }
            }
        }
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
        body.setText(MarkdownRenderer.render(reasoning));
        body.setVisibility(View.GONE);

        final boolean[] open = {false};
        header.setOnClickListener(v -> {
            open[0] = !open[0];
            chevron.setText(open[0] ? "▾" : "▸");
            body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
        });

        row.addView(header);
        row.addView(body);
        transcript.addView(row);
    }

    private void rebuildSessionList() {
        sessionList.removeAllViews();
        File dir = new File(getFilesDir(), "sessions");
        final File[] files = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.no_sessions));
            empty.setTextSize(13);
            empty.setTextColor(getColor(R.color.text_caption));
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            sessionList.addView(empty);
            return;
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        java.util.Set<String> pins = pinnedSessions();
        List<File> pinned = new ArrayList<>();
        List<File> rest = new ArrayList<>();
        for (File f : files) (pins.contains(f.getName()) ? pinned : rest).add(f);
        if (!pinned.isEmpty()) {
            sessionList.addView(sessionGroupHeader(getString(R.string.drawer_pinned)));
            for (File f : pinned) sessionList.addView(sessionItem(f, sessionTitle(f),
                    f.equals(currentSessionFile), true));
        }
        String curGroup = null;
        for (File f : rest) {
            String group = sessionGroup(f.getName());
            if (!group.equals(curGroup)) {
                curGroup = group;
                sessionList.addView(sessionGroupHeader(group));
            }
            sessionList.addView(sessionItem(f, sessionTitle(f), f.equals(currentSessionFile), false));
        }
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
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.text_caption));
        tv.setPadding(dp(4), dp(14), dp(4), dp(6));
        return tv;
    }

    private TextView sessionItem(File f, String title, boolean active, boolean pinned) {
        TextView tv = new TextView(this);
        tv.setText(pinned ? "📌 " + title : title);
        tv.setTextSize(14);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(dp(4), dp(10), dp(4), dp(10));
        tv.setTextColor(getColor(active ? R.color.brand : R.color.text_primary));
        if (active) tv.setBackgroundColor(0x14FFFFFF);
        tv.setClickable(true);
        tv.setOnClickListener(v -> switchSession(f));
        tv.setOnLongClickListener(v -> {
            showSessionMenu(v, f, pinned);
            return true;
        });
        return tv;
    }

    private String sessionTitle(File f) {
        String fallback = f.getName().replace(".jsonl", "");
        try {
            List<ChatMessage> msgs = JsonlSessionLog.load(f);
            for (ChatMessage m : msgs) {
                if ("user".equals(m.role) && m.content != null && !m.content.trim().isEmpty()) {
                    String s = m.content.replaceAll("\\s+", " ").trim();
                    return s.length() > 26 ? s.substring(0, 26) + "…" : s;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
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
        activeSession = latest;
        currentSessionFile = latest;
        try {
            renderSavedSession(JsonlSessionLog.load(latest));
        } catch (Exception ignored) {
        }
    }

    private static String buildSystemPrompt() {
        return "You are a helpful software engineer assistant.";
    }

    private void hideHome() {
        if (home != null && home.getVisibility() != View.GONE) home.setVisibility(View.GONE);
        input.setHint(getString(R.string.input_hint));
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}