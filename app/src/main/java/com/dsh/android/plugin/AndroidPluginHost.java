package com.dsh.android.plugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.MainActivity;
import com.dsh.android.R;
import com.dsh.android.guard.CommandGuard;
import com.dsh.android.runtime.ProrootFallback;
import com.dsh.android.runtime.ProrootRuntime;
import com.dsh.android.tools.Tool;
import com.dsh.android.tools.ToolRegistry;
import com.dsh.android.ui.PluginUiHost;
import com.dsh.android.ui.framework.ChatComponents;
import com.dsh.android.ui.framework.UiFramework;
import com.dsh.android.ui.framework.UiNode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@link PluginHost} 的 Android 实现：执行、工具、Toast/确认框、剪贴板、通知、
 * 导航、节点 patch/bounds、应用内/系统浮层、权限弹窗。
 *
 * 线程约定：bridge 回调来自 QuickJS 线程；所有 View/Dialog/WindowManager 操作都
 * 通过 {@link #main} 投递，阻塞式 API 用 CountDownLatch 等结果。
 */
public final class AndroidPluginHost implements PluginHost {

    private static final String CHANNEL_NOTIFY = "plugin_notify";
    private static final String CHANNEL_OVERLAY = "plugin_overlay";
    private static final AtomicInteger REQUEST_CODE = new AtomicInteger(0x9100);
    private static final java.util.Set<String> DANGEROUS_TOOLS =
            new java.util.HashSet<>(java.util.Arrays.asList("bash", "write", "edit",
                    "str_replace_editor", "container"));

    private final Activity activity;
    private final Context context;
    private final ProrootRuntime runtime;
    private final Supplier<HookRegistry> registry;
    private final Supplier<PluginUiHost> uiHost;
    private final Supplier<ToolRegistry> builtinTools;
    private final Supplier<Boolean> agentBusy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService overlayWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-overlay");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, OverlayEntry> overlays = new LinkedHashMap<>();
    private final Map<String, SystemOverlay> systemOverlays = new LinkedHashMap<>();
    private final Map<Integer, PermWaiter> permWaiters = new ConcurrentHashMap<>();
    private volatile boolean foreground = true;
    private volatile ToolRegistry builtinCache;

    private static final class PermWaiter {
        final String[] permissions;
        final AtomicReference<String> result;
        final CountDownLatch latch;

        PermWaiter(String[] permissions, AtomicReference<String> result, CountDownLatch latch) {
            this.permissions = permissions;
            this.result = result;
            this.latch = latch;
        }
    }

    private static final class OverlayEntry {
        final String pluginId;
        final String id;
        final HookManifest owner;
        final FrameLayout root;
        final FrameLayout content;
        final UiFramework fw;

        OverlayEntry(String pluginId, String id, HookManifest owner,
                     FrameLayout root, FrameLayout content, UiFramework fw) {
            this.pluginId = pluginId;
            this.id = id;
            this.owner = owner;
            this.root = root;
            this.content = content;
            this.fw = fw;
        }
    }

    private static final class SystemOverlay {
        final String pluginId;
        final View root;
        final FrameLayout content;
        final UiFramework fw;
        final WindowManager.LayoutParams params;

        SystemOverlay(String pluginId, View root, FrameLayout content,
                      UiFramework fw, WindowManager.LayoutParams params) {
            this.pluginId = pluginId;
            this.root = root;
            this.content = content;
            this.fw = fw;
            this.params = params;
        }
    }

    public AndroidPluginHost(Activity activity, ProrootRuntime runtime,
                             Supplier<HookRegistry> registry,
                             Supplier<PluginUiHost> uiHost,
                             Supplier<ToolRegistry> builtinTools,
                             Supplier<Boolean> agentBusy) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.runtime = runtime;
        this.registry = registry;
        this.uiHost = uiHost;
        this.builtinTools = builtinTools;
        this.agentBusy = agentBusy;
        AdbBridge.init(this.context);
    }

    @Override
    public Context context() {
        return context;
    }

    // ================================================================ 执行 / 工具

    @Override
    public String execGuest(String command, long timeoutMs, String cwd) {
        String cmd = command;
        if (cwd != null && !cwd.trim().isEmpty()) {
            // 显式 cwd 用子 shell：不把宿主的持久 shell 的 cwd 改掉。
            cmd = "( cd -- " + shellQuote(cwd.trim()) + " || exit 1\n" + command + "\n)";
        }
        if (ProrootFallback.needsFallback(cmd)) {
            return "[proroot-fallback]\n" + ProrootFallback.run(context,
                    runtime.rootfsDir(), runtime.runDir(), cmd, timeoutMs);
        }
        return runtime.runBashPersistent(cmd, timeoutMs);
    }

    @Override
    public String invokeTool(String callerPluginId, String tool, String argsJson, long timeoutMs)
            throws Exception {
        HookRegistry reg = registry.get();
        HookManifest caller = reg == null ? null : reg.manifestView(callerPluginId);
        Tool t = builtinTools().get(tool);
        if (t == null) {
            PluginTool pt = reg == null ? null : reg.pluginToolView(tool);
            if (pt == null) throw new IllegalArgumentException("E_ARGS: 工具不存在: " + tool);
            if (pt.pluginId().equals(callerPluginId) || reg.isPluginExecuting(pt.pluginId())) {
                throw new IllegalArgumentException("E_REENTRANT: 不能同步调用正在执行的插件的工具");
            }
            t = pt;
        }
        // bridge.tool.invoke 也必须过 CommandGuard：它和 bridge.exec 是两条路径，
        // 以前只有 exec 过护栏，插件可以用 tool.invoke("bash") 绕过确定性拦截。
        boolean guardAsked = false;
        if ("bash".equals(tool)) {
            String command;
            try {
                command = new JSONObject(argsJson == null ? "{}" : argsJson)
                        .optString("command", "");
            } catch (Exception e) {
                throw new IllegalArgumentException("E_ARGS: bash 参数不是合法 JSON");
            }
            if (!command.trim().isEmpty()) {
                CommandGuard.Verdict v = CommandGuard.check(command);
                if (v.action == CommandGuard.Action.BLOCK) {
                    throw new IllegalArgumentException("E_PERM: 已拦截（安全护栏）：" + v.reason);
                }
                if (v.action == CommandGuard.Action.ASK) {
                    String answer = confirm(
                            activity.getString(R.string.plugin_exec_confirm_title),
                            activity.getString(R.string.plugin_exec_confirm_msg, command, v.reason),
                            60_000L);
                    if (!"ok".equals(answer)) {
                        throw new IllegalArgumentException("E_PERM: 用户拒绝了这条命令");
                    }
                    // 用户已经看过完整命令与风险，不再弹第二次“工具调用确认”。
                    guardAsked = true;
                }
            }
        }
        // manifest 的 confirm:false 只在用户显式"信任此插件"后才生效；否则危险工具仍逐次确认。
        // 带 manifest 的 isTrusted 会先校验来源哈希；拿不到 manifest 一律按不信任处理。
        boolean trusted = reg != null && caller != null && reg.grants().isTrusted(caller);
        // 无法确认调用方时不能直接执行危险工具，即使 bash 已经过了护栏。
        if (DANGEROUS_TOOLS.contains(tool) && caller == null) {
            throw new IllegalArgumentException("E_PERM: 无法确认调用方插件，拒绝执行危险工具 " + tool);
        }
        if (DANGEROUS_TOOLS.contains(tool) && !guardAsked
                && (caller.toolConfirm || !trusted)) {
            String summary = argsJson == null ? "" : argsJson;
            if (summary.length() > 200) summary = summary.substring(0, 200) + "…";
            String answer = confirm(activity.getString(R.string.plugin_tool_confirm_title,
                            caller.name, tool),
                    activity.getString(R.string.plugin_tool_confirm_msg, summary), 60_000L);
            if (!"ok".equals(answer)) throw new IllegalArgumentException("E_PERM: 用户拒绝了工具调用");
        }
        return t.execute(argsJson);
    }

    @Override
    public void showToolCard(String pluginId, String tool, String argsJson,
                             String result, boolean ok) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showPluginToolCard(pluginId, tool, argsJson, result, ok);
        }
    }

    @Override
    public JSONArray listTools() {
        JSONArray out = new JSONArray();
        try {
            ToolRegistry tools = builtinTools();
            for (Tool t : tools.all()) {
                try {
                    out.put(t.schema());
                } catch (Exception ignored) {
                }
            }
            HookRegistry reg = registry.get();
            if (reg != null) {
                for (PluginTool t : reg.pluginToolsView()) {
                    try {
                        out.put(t.schema());
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private ToolRegistry builtinTools() {
        ToolRegistry t = builtinCache;
        if (t == null) {
            synchronized (this) {
                if (builtinCache == null) builtinCache = builtinTools.get();
                t = builtinCache;
            }
        }
        return t;
    }

    // ================================================================ UI / 系统

    @Override
    public void toast(String text) {
        if (text == null || text.isEmpty()) return;
        main.post(() -> {
            try {
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public String confirm(String title, String text, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        main.post(() -> {
            if (activity.isFinishing()) {
                latch.countDown();
                return;
            }
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        answer.set("ok");
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        answer.set("cancel");
                        d.dismiss();
                    })
                    .setCancelable(false)
                    .create();
            dialog.setOnDismissListener(d -> {
                if (finished.compareAndSet(false, true)) latch.countDown();
            });
            dialog.show();
            main.postDelayed(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            }, Math.max(1_000L, timeoutMs));
        });
        try {
            latch.await(Math.max(1_000L, timeoutMs) + 1_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return answer.get();
    }

    @Override
    public String promptGrant(HookManifest m, String capability, String label,
                              String scope, String reason, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        String risk = riskOf(capability);
        String extra = reason == null || reason.isEmpty() ? ""
                : activity.getString(R.string.plugin_grant_reason, reason);
        String message = activity.getString(R.string.plugin_grant_message, label, scope, risk, extra);
        main.post(() -> {
            if (activity.isFinishing()) {
                latch.countDown();
                return;
            }
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.plugin_grant_title, m.name))
                    .setMessage(message)
                    .setPositiveButton(R.string.plugin_grant_always, (d, w) -> {
                        answer.set("always");
                        d.dismiss();
                    })
                    .setNeutralButton(R.string.plugin_grant_once, (d, w) -> {
                        answer.set("once");
                        d.dismiss();
                    })
                    .setNegativeButton(R.string.plugin_grant_deny, (d, w) -> {
                        answer.set("deny");
                        d.dismiss();
                    })
                    .setCancelable(false)
                    .create();
            dialog.setOnDismissListener(d -> {
                if (finished.compareAndSet(false, true)) latch.countDown();
            });
            dialog.show();
            main.postDelayed(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            }, Math.max(1_000L, timeoutMs));
        });
        try {
            latch.await(Math.max(1_000L, timeoutMs) + 1_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return answer.get();
    }

    private static String riskOf(String cap) {
        if ("overlay.system".equals(cap)) return "极高";
        if ("net.http".equals(cap) || "fs.shared".equals(cap) || "exec.guest".equals(cap)
                || "tool.invoke".equals(cap) || "bridge.adb".equals(cap)) return "高";
        if ("notify".equals(cap)) return "低";
        return "中";
    }

    @Override
    public boolean specialGranted(String capability) {
        if ("fs.shared".equals(capability)) {
            return Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
        }
        if ("overlay.system".equals(capability)) {
            return Settings.canDrawOverlays(context);
        }
        return false;
    }

    @Override
    public boolean openSpecialSettings(String capability) {
        try {
            Intent i;
            if ("fs.shared".equals(capability)) {
                if (Build.VERSION.SDK_INT >= 30) {
                    i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + context.getPackageName()));
                } else {
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.getPackageName()));
                }
            } else if ("overlay.system".equals(capability)) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
            } else {
                return false;
            }
            main.post(() -> {
                try {
                    activity.startActivity(i);
                } catch (Throwable ignored) {
                }
            });
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public String androidPermissionStatus(String permission) {
        if (permission == null || !permission.startsWith("android.permission.")) {
            return "unsupported";
        }
        if (Build.VERSION.SDK_INT < 23) return "granted";
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? "granted" : "denied";
    }

    @Override
    public String requestAndroidPermission(String permission, long timeoutMs) {
        if (permission == null || !permission.startsWith("android.permission.")) return "unsupported";
        return requestAndroidPermissions(new String[]{permission}, timeoutMs);
    }

    @Override
    public String requestAndroidPermissions(String[] permissions, long timeoutMs) {
        if (permissions == null || permissions.length == 0) return "denied";
        for (String p : permissions) {
            if (androidPermissionStatus(p).equals("granted")) return "granted";
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("denied");
        int code = REQUEST_CODE.getAndIncrement();
        main.post(() -> {
            try {
                permWaiters.put(code, new PermWaiter(permissions, result, latch));
                activity.requestPermissions(permissions, code);
            } catch (Throwable e) {
                permWaiters.remove(code);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(Math.max(2_000L, timeoutMs), TimeUnit.MILLISECONDS)) return "timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    /** MainActivity.onRequestPermissionsResult → 这里。 */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        PermWaiter w = permWaiters.remove(requestCode);
        if (w == null) return;
        boolean granted = false;
        if (grantResults != null) {
            for (int r : grantResults) {
                if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
        }
        w.result.set(granted ? "granted" : "denied");
        w.latch.countDown();
    }

    // ---------------------------------------------------------------- 定位

    @Override
    public boolean locationAvailable() {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public JSONObject locationStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("serviceEnabled", locationAvailable());
            o.put("fineGranted", androidPermissionStatus(
                    android.Manifest.permission.ACCESS_FINE_LOCATION).equals("granted"));
            o.put("coarseGranted", androidPermissionStatus(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION).equals("granted"));
            JSONArray providers = new JSONArray();
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.put("gps");
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.put("network");
            }
            o.put("providers", providers);
        } catch (Throwable ignored) {
        }
        return o;
    }

    @Override
    public JSONObject locationGet(long timeoutMs, boolean highAccuracy) throws Exception {
        long timeout = Math.max(1_000L, Math.min(timeoutMs, 60_000L));
        boolean fine = androidPermissionStatus(
                android.Manifest.permission.ACCESS_FINE_LOCATION).equals("granted");
        boolean coarse = androidPermissionStatus(
                android.Manifest.permission.ACCESS_COARSE_LOCATION).equals("granted");
        if (!fine && !coarse) {
            String r = requestAndroidPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, timeout);
            if (!"granted".equals(r)) {
                throw new IllegalArgumentException("E_PERM: 定位权限被拒绝");
            }
            fine = androidPermissionStatus(
                    android.Manifest.permission.ACCESS_FINE_LOCATION).equals("granted");
            coarse = androidPermissionStatus(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION).equals("granted");
        }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) throw new IllegalArgumentException("E_BRIDGE: 定位服务不可用");
        boolean gps = false;
        boolean network = false;
        try {
            gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable ignored) {
        }
        if (!gps && !network) {
            throw new IllegalArgumentException("E_BRIDGE: 定位服务未开启");
        }
        Location last = bestLastKnown(lm);
        String provider = highAccuracy
                ? (gps ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER)
                : (network ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER);
        Location got = requestSingle(lm, provider, timeout);
        Location result = got != null ? got : last;
        if (result == null) {
            throw new IllegalArgumentException("E_BUDGET: " + (timeout / 1000) + "s 内未拿到定位");
        }
        JSONObject o = new JSONObject();
        o.put("latitude", result.getLatitude());
        o.put("longitude", result.getLongitude());
        o.put("accuracy", result.hasAccuracy() ? result.getAccuracy() : -1);
        o.put("altitude", result.hasAltitude() ? result.getAltitude() : JSONObject.NULL);
        o.put("speed", result.hasSpeed() ? result.getSpeed() : JSONObject.NULL);
        o.put("bearing", result.hasBearing() ? result.getBearing() : JSONObject.NULL);
        o.put("time", result.getTime());
        o.put("provider", result.getProvider() == null ? "" : result.getProvider());
        o.put("stale", got == null);
        o.put("fine", fine);
        o.put("coarse", coarse);
        return o;
    }

    private Location bestLastKnown(LocationManager lm) {
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()
                        || (l.getTime() == best.getTime() && l.getAccuracy() < best.getAccuracy())) {
                    best = l;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private Location requestSingle(LocationManager lm, String provider, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Location> got = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        final LocationListener[] holder = new LocationListener[1];
        main.post(() -> {
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (done.compareAndSet(false, true)) {
                        got.set(location);
                        try {
                            lm.removeUpdates(holder[0]);
                        } catch (Throwable ignored) {
                        }
                        latch.countDown();
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            holder[0] = listener;
            try {
                lm.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
                main.postDelayed(() -> {
                    if (done.compareAndSet(false, true)) {
                        try {
                            lm.removeUpdates(listener);
                        } catch (Throwable ignored) {
                        }
                        latch.countDown();
                    }
                }, timeoutMs);
            } catch (Throwable e) {
                if (done.compareAndSet(false, true)) latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs + 1_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        main.post(() -> {
            try {
                if (holder[0] != null) lm.removeUpdates(holder[0]);
            } catch (Throwable ignored) {
            }
        });
        return got.get();
    }

    @Override
    public boolean notify(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_NOTIFY) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_NOTIFY,
                        context.getString(R.string.plugin_notify_channel),
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(context, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(context, CHANNEL_NOTIFY)
                    .setSmallIcon(R.drawable.ic_dsh_logo)
                    .setContentTitle(title == null ? "" : title)
                    .setContentText(text == null ? "" : text)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(("plugin:" + title + ":" + text).hashCode(), b.build());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public String clipboardGet() {
        if (Looper.myLooper() == Looper.getMainLooper()) return "";
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>("");
        main.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence t = clip.getItemAt(0).coerceToText(context);
                        out.set(t == null ? "" : t.toString());
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    @Override
    public boolean clipboardSet(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        main.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("plugin", text == null ? "" : text));
                    ok.set(true);
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    // ================================================================ 导航 / 视图

    @Override
    public boolean navPush(String page, JSONObject params) {
        HookRegistry reg = registry.get();
        if (reg == null) return false;
        boolean exists = false;
        for (PageDecl p : reg.pagesView()) {
            if (p.fullId().equals(page)) {
                exists = true;
                break;
            }
        }
        if (!exists) return false;
        final JSONObject p = params == null ? new JSONObject() : params;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            PluginUiHost ui = uiHost.get();
            return ui != null && ui.pushPage(page, p);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        main.post(() -> {
            try {
                PluginUiHost ui = uiHost.get();
                ok.set(ui != null && ui.pushPage(page, p));
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    @Override
    public boolean navBack() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            PluginUiHost ui = uiHost.get();
            return ui != null && ui.backPage();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        main.post(() -> {
            try {
                PluginUiHost ui = uiHost.get();
                ok.set(ui != null && ui.backPage());
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    @Override
    public JSONObject navCurrent() {
        try {
            PluginUiHost ui = uiHost.get();
            return ui == null ? null : ui.currentPageInfo();
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public boolean patchNode(String id, JSONObject props) {
        return patchNode(null, id, props);
    }

    @Override
    public boolean patchNode(String callerPluginId, String id, JSONObject props) {
        if (props == null || props.length() == 0) return false;
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<String> it = props.keys();
        while (it.hasNext()) {
            String k = it.next();
            map.put(k, props.opt(k));
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return patchOnMain(callerPluginId, id, map);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        main.post(() -> {
            try {
                ok.set(patchOnMain(callerPluginId, id, map));
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    /** 只允许调用方 patch 自己的浮层/页面；caller 为 null 时是宿主内部调用。 */
    private boolean patchOnMain(String caller, String id, Map<String, Object> map) {
        PluginUiHost ui = uiHost.get();
        if (ui != null && ui.patchNode(caller, id, map)) return true;
        for (OverlayEntry e : overlays.values()) {
            if (caller != null && !caller.equals(e.pluginId)) continue;
            if (e.fw.patch(id, map)) return true;
        }
        for (SystemOverlay o : systemOverlays.values()) {
            if (caller != null && !caller.equals(o.pluginId)) continue;
            if (o.fw.patch(id, map)) return true;
        }
        return false;
    }

    @Override
    public JSONObject boundsNode(String id) {
        return boundsNode(null, id);
    }

    @Override
    public JSONObject boundsNode(String callerPluginId, String id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return boundsOnMain(callerPluginId, id);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> out = new AtomicReference<>();
        main.post(() -> {
            try {
                out.set(boundsOnMain(callerPluginId, id));
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    private JSONObject boundsOnMain(String caller, String id) {
        PluginUiHost ui = uiHost.get();
        JSONObject b = ui == null ? null : ui.boundsNode(caller, id);
        if (b != null) return b;
        return overlayBounds(caller, id);
    }

    private JSONObject overlayBounds(String caller, String id) {
        float density = density();
        for (OverlayEntry e : overlays.values()) {
            if (caller != null && !caller.equals(e.pluginId)) continue;
            JSONObject o = boundsOf(e.fw, id, density);
            if (o != null) return o;
        }
        for (SystemOverlay o : systemOverlays.values()) {
            if (caller != null && !caller.equals(o.pluginId)) continue;
            JSONObject j = boundsOf(o.fw, id, density);
            if (j != null) return j;
        }
        return null;
    }

    private static JSONObject boundsOf(UiFramework fw, String id, float density) {
        int[] b = new int[4];
        if (fw == null || !fw.bounds(id, b)) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("x", b[0] / density);
            o.put("y", b[1] / density);
            o.put("w", b[2] / density);
            o.put("h", b[3] / density);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ================================================================ 应用内浮层

    @Override
    public void overlayShow(String pluginId, String overlayId, JSONObject spec) {
        main.post(() -> showOverlay(pluginId, overlayId, spec));
    }

    @Override
    public void overlayUpdate(String pluginId, String overlayId, JSONObject spec) {
        main.post(() -> {
            OverlayEntry e = overlays.get(key(pluginId, overlayId));
            if (e != null) updateOverlay(e, spec);
        });
    }

    @Override
    public void overlayHide(String pluginId, String overlayId) {
        main.post(() -> {
            OverlayEntry e = overlays.remove(key(pluginId, overlayId));
            if (e != null) removeOverlay(e);
        });
    }

    @Override
    public void overlayHideAll(String pluginId) {
        main.post(() -> {
            List<String> ids = new ArrayList<>();
            for (OverlayEntry e : overlays.values()) {
                if (e.pluginId.equals(pluginId)) ids.add(e.id);
            }
            for (String id : ids) {
                OverlayEntry e = overlays.remove(key(pluginId, id));
                if (e != null) removeOverlay(e);
            }
        });
    }

    private void showOverlay(String pluginId, String id, JSONObject spec) {
        if (activity.isFinishing()) return;
        String key = key(pluginId, id);
        OverlayEntry existing = overlays.get(key);
        if (existing != null) {
            updateOverlay(existing, spec);
            return;
        }
        while (overlays.size() >= 3) {
            Iterator<OverlayEntry> it = overlays.values().iterator();
            if (!it.hasNext()) break;
            OverlayEntry oldest = it.next();
            it.remove();
            removeOverlay(oldest);
        }
        HookManifest owner = manifest(pluginId);
        if (owner == null) return;
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(rounded(0xE6232324, dp(12)));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(4), dp(4), 0);
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextSize(13);
        close.setTextColor(0xFFCFD3D6);
        close.setPadding(dp(8), dp(2), dp(8), dp(2));
        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(close);
        box.addView(header);

        FrameLayout content = new FrameLayout(activity);
        box.addView(content);
        UiFramework fw = new UiFramework(content);
        // 浮层与聊天/插槽共用 DSL，聊天组件也要注册，否则 table/codeCard/toolCard 被跳过。
        ChatComponents.install(fw.renderer());
        fw.setToastSink(this::toast);

        OverlayEntry entry = new OverlayEntry(pluginId, id, owner, root, content, fw);
        wireOverlay(entry);
        overlays.put(key, entry);

        ViewGroup parent = overlayParent();
        if (parent != null) parent.addView(root);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.leftMargin = dp(spec.optInt("x", 24));
        lp.topMargin = dp(spec.optInt("y", 120));
        root.setLayoutParams(lp);
        close.setOnClickListener(v -> overlayHide(pluginId, id));
        if (spec.optBoolean("draggable", false)) makeDraggable(root, lp);
        applyOverlayView(entry, spec.optJSONObject("view"));
    }

    private void updateOverlay(OverlayEntry e, JSONObject spec) {
        if (spec == null) return;
        if (e.root.getParent() == null) {
            ViewGroup parent = overlayParent();
            if (parent != null) parent.addView(e.root);
        }
        if (spec.has("x") || spec.has("y")) {
            ViewGroup.LayoutParams lp = e.root.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                if (spec.has("x")) flp.leftMargin = dp(spec.optInt("x", 24));
                if (spec.has("y")) flp.topMargin = dp(spec.optInt("y", 120));
                e.root.setLayoutParams(flp);
            }
        }
        applyOverlayView(e, spec.optJSONObject("view"));
    }

    private void applyOverlayView(OverlayEntry e, JSONObject view) {
        if (view == null) return;
        try {
            UiNode node = UiNode.fromJson(view);
            if (node != null) e.fw.render(node);
        } catch (Throwable ignored) {
        }
    }

    private void removeOverlay(OverlayEntry e) {
        try {
            e.fw.clear();
            if (e.root.getParent() instanceof ViewGroup) {
                ((ViewGroup) e.root.getParent()).removeView(e.root);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 浮层挂到当前插件页 Dialog 上，避免被 Dialog 盖住。 */
    private ViewGroup overlayParent() {
        try {
            PluginUiHost ui = uiHost.get();
            if (ui != null) {
                ViewGroup g = ui.overlayHost();
                if (g != null) return g;
            }
        } catch (Throwable ignored) {
        }
        View decor = activity.getWindow().getDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    private void makeDraggable(View view, FrameLayout.LayoutParams lp) {
        final float[] down = new float[4];
        view.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    down[2] = lp.leftMargin;
                    down[3] = lp.topMargin;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.leftMargin = (int) (down[2] + ev.getRawX() - down[0]);
                    lp.topMargin = (int) (down[3] + ev.getRawY() - down[1]);
                    v.setLayoutParams(lp);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void wireOverlay(OverlayEntry e) {
        e.fw.actions().setHandler(action -> {
            String name = action.action;
            if (name == null) return Boolean.TRUE;
            if (name.startsWith("action:")) {
                dispatchAction(e, name.substring(7), overlayParams(action.params));
                return Boolean.TRUE;
            }
            JSONObject ctx = overlayCtx(action.params);
            put(ctx, "action", name);
            dispatchFn(e, name, ctx, false);
            return Boolean.TRUE;
        });
        e.fw.events().setHandler(ev -> {
            JSONObject ctx = overlayCtx(null);
            put(ctx, "event", ev.event);
            put(ctx, "node", toJson(ev.node));
            put(ctx, "value", toJson(ev.value));
            if (ev.pointer != null) put(ctx, "pointer", toJson(ev.pointer));
            dispatchFn(e, ev.handlerName, ctx, true);
            return null;
        });
    }

    private JSONObject overlayCtx(Map<String, Object> raw) {
        JSONObject ctx = new JSONObject();
        try {
            ctx.put("session", new JSONObject());
            ctx.put("state", new JSONObject());
        } catch (Exception ignored) {
        }
        if (raw != null) {
            for (Map.Entry<String, Object> en : raw.entrySet()) {
                put(ctx, en.getKey(), toJson(en.getValue()));
            }
        }
        return ctx;
    }

    private void dispatchFn(OverlayEntry e, String fn, JSONObject ctx, boolean nonBlocking) {
        if (fn == null || fn.isEmpty() || e.owner == null) return;
        overlayWorker.execute(() -> {
            String out = null;
            if (nonBlocking) PluginBridge.enterNonBlocking();
            try {
                HookRegistry reg = registry.get();
                if (reg != null) out = reg.invokeUiFn(e.owner, fn.startsWith("action:")
                        ? fn.substring(7) : fn, ctx);
            } catch (Throwable ignored) {
            } finally {
                if (nonBlocking) PluginBridge.exitNonBlocking();
            }
            if (out == null) return;
            final String result = out;
            main.post(() -> applyOverlayResult(e, result));
        });
    }

    private void dispatchAction(OverlayEntry e, String id, JSONObject params) {
        HookRegistry reg = registry.get();
        ActionDecl d = reg == null ? null : reg.actionDeclView(id);
        if (d == null) {
            toast("动作不可用: " + id);
            return;
        }
        overlayWorker.execute(() -> {
            String out = reg.invokeAction(d, params, "overlay");
            main.post(() -> applyOverlayResult(e, out));
        });
    }

    private void applyOverlayResult(OverlayEntry e, String out) {
        if (out == null) return;
        String text = out.trim();
        if (text.startsWith("{")) {
            try {
                UiNode node = UiNode.fromJson(new JSONObject(text));
                if (node != null) {
                    e.fw.render(node);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!text.isEmpty()) toast(text);
    }

    private static JSONObject overlayParams(Map<String, Object> raw) {
        JSONObject out = new JSONObject();
        if (raw == null) return out;
        Object node = raw.get("node");
        if (node instanceof Map) {
            Object p = ((Map<?, ?>) node).get("params");
            Object j = toJson(p);
            if (j instanceof JSONObject) out = (JSONObject) j;
        }
        return out;
    }

    // ================================================================ 系统浮层

    @Override
    public String systemOverlayStatus() {
        return Settings.canDrawOverlays(context) ? "granted" : "denied";
    }

    @Override
    public boolean systemOverlayRequest() {
        return openSpecialSettings("overlay.system");
    }

    @Override
    public void systemOverlayShow(String pluginId, JSONObject spec) {
        main.post(() -> showSystemOverlay(pluginId, spec));
    }

    @Override
    public void systemOverlayHide(String pluginId) {
        main.post(() -> {
            SystemOverlay o = systemOverlays.remove(pluginId);
            if (o != null) removeSystemOverlay(pluginId, o);
        });
    }

    private void showSystemOverlay(String pluginId, JSONObject spec) {
        if (!Settings.canDrawOverlays(context)) return;
        SystemOverlay existing = systemOverlays.get(pluginId);
        if (existing != null) {
            applySystemView(existing, spec.optJSONObject("view"));
            return;
        }
        if (systemOverlays.size() >= 2) {
            Iterator<SystemOverlay> it = systemOverlays.values().iterator();
            if (it.hasNext()) {
                SystemOverlay oldest = it.next();
                it.remove();
                removeSystemOverlay(oldest.pluginId, oldest);
            }
        }
        HookManifest owner = manifest(pluginId);
        if (owner == null) return;

        FrameLayout root = new FrameLayout(activity);
        root.setBackground(rounded(0xE6232324, dp(12)));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(0xFFCFD3D6);
        close.setTextSize(13);
        close.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(close);
        box.addView(header);
        FrameLayout content = new FrameLayout(activity);
        box.addView(content);
        UiFramework fw = new UiFramework(content);
        ChatComponents.install(fw.renderer());
        fw.setToastSink(this::toast);

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int wantW = spec.optInt("width", -1);
        int wantH = spec.optInt("height", -1);
        int width = wantW > 0 ? Math.min(dp(wantW), (int) (screenW * 0.6f))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        int height = wantH > 0 ? Math.min(dp(wantH), (int) (screenH * 0.6f))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(spec.optInt("x", 24));
        params.y = dp(spec.optInt("y", 120));

        SystemOverlay so = new SystemOverlay(pluginId, root, content, fw, params);
        systemOverlays.put(pluginId, so);
        wireSystemOverlay(so);
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            wm.addView(root, params);
        } catch (Throwable e) {
            systemOverlays.remove(pluginId);
            return;
        }
        close.setOnClickListener(v -> systemOverlayHide(pluginId));
        if (spec.optBoolean("draggable", false)) {
            final float[] down = new float[4];
            header.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = ev.getRawX();
                        down[1] = ev.getRawY();
                        down[2] = params.x;
                        down[3] = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = (int) (down[2] + ev.getRawX() - down[0]);
                        params.y = (int) (down[3] + ev.getRawY() - down[1]);
                        try {
                            WindowManager wm = (WindowManager)
                                    context.getSystemService(Context.WINDOW_SERVICE);
                            if (wm != null) wm.updateViewLayout(root, params);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    default:
                        return false;
                }
            });
        }
        applySystemView(so, spec.optJSONObject("view"));
        showOverlayNotification(owner);
    }

    private void applySystemView(SystemOverlay so, JSONObject view) {
        if (view == null) return;
        try {
            UiNode node = UiNode.fromJson(view);
            if (node != null) so.fw.render(node);
        } catch (Throwable ignored) {
        }
    }

    private void removeSystemOverlay(String pluginId, SystemOverlay o) {
        try {
            o.fw.clear();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(o.root);
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(("overlay:" + pluginId).hashCode());
        } catch (Throwable ignored) {
        }
    }

    private void showOverlayNotification(HookManifest m) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_OVERLAY) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_OVERLAY,
                        context.getString(R.string.plugin_overlay_channel),
                        NotificationManager.IMPORTANCE_LOW));
            }
            PendingIntent pi = PendingIntent.getActivity(context, 1,
                    new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(context, CHANNEL_OVERLAY)
                    .setSmallIcon(R.drawable.ic_dsh_logo)
                    .setContentTitle(context.getString(R.string.plugin_overlay_channel))
                    .setContentText(context.getString(R.string.plugin_overlay_notify, m.name))
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            nm.notify(("overlay:" + m.id).hashCode(), n);
        } catch (Throwable ignored) {
        }
    }

    // 系统浮层的事件接线需要在 addView 后做一次；这里复用 wireOverlay 但改为 public 入口。
    private void wireSystemOverlay(SystemOverlay so) {
        OverlayEntry proxy = new OverlayEntry(so.pluginId, "system", manifest(so.pluginId),
                null, so.content, so.fw);
        wireOverlay(proxy);
    }

    // ================================================================ 生命周期

    /** MainActivity.onResume / onPause 调用。 */
    public void setForeground(boolean fg) {
        foreground = fg;
        if (!fg) main.post(this::hideAppOverlays);
    }

    private void hideAppOverlays() {
        for (OverlayEntry e : new ArrayList<>(overlays.values())) {
            removeOverlay(e);
        }
        overlays.clear();
    }

    /** 插件禁用时清掉它的浮层。 */
    public void onPluginDisabled(String pluginId) {
        overlayHideAll(pluginId);
        systemOverlayHide(pluginId);
    }

    @Override
    public String adbProvider() {
        return AdbBridge.provider();
    }

    @Override
    public boolean adbAvailable() {
        return AdbBridge.available();
    }

    @Override
    public boolean adbBinderAlive() {
        return AdbBridge.binderAlive();
    }

    @Override
    public boolean adbRequestPermission() {
        return AdbBridge.requestPermission();
    }

    @Override
    public String adbShell(String command, long timeoutMs) throws Exception {
        return AdbBridge.shell(command, timeoutMs);
    }

    @Override
    public String adbExec(String program, String argsJson, long timeoutMs) throws Exception {
        return AdbBridge.exec(program, argsJson, timeoutMs);
    }

    @Override
    public java.io.File workspaceDir() {
        return runtime == null ? new java.io.File(context.getFilesDir(), "workspace")
                : runtime.workspaceDir();
    }

    @Override
    public boolean isAgentBusy() {
        try {
            return agentBusy != null && Boolean.TRUE.equals(agentBusy.get());
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public boolean isForeground() {
        return foreground;
    }

    // ================================================================ 工具

    private HookManifest manifest(String id) {
        try {
            HookRegistry reg = registry.get();
            return reg == null ? null : reg.manifestView(id);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String key(String pluginId, String overlayId) {
        return pluginId + "/" + (overlayId == null ? "" : overlayId);
    }

    private int dp(int v) {
        return (int) (v * density());
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void put(JSONObject o, String key, Object v) {
        try {
            o.put(key, v == null ? JSONObject.NULL : v);
        } catch (Exception ignored) {
        }
    }

    private static Object toJson(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                put(o, String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return o;
        }
        if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object item : (List<?>) v) a.put(toJson(item));
            return a;
        }
        return v;
    }
}
