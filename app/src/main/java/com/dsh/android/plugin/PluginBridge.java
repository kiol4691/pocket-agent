package com.dsh.android.plugin;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.StatFs;
import android.util.DisplayMetrics;

import com.dsh.android.guard.CommandGuard;
import com.dsh.android.ui.framework.DefaultTokenResolver;
import com.dsh.android.ui.framework.HostState;
import com.dsh.android.ui.framework.HostTheme;
import com.dsh.android.ui.framework.ThemePack;
import com.dsh.android.util.DebugLog;
import com.dsh.android.util.SecretStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 插件 JS 的 {@code bridge.*} 宿主实现。
 *
 * 分两类：
 *  - capability（静态能力）：log / config / storage / state / device / secret / nav / ui /
 *    tool.list / context / session / runtime / display / theme / capabilities / plugins；
 *  - grant（外部资源，走 {@link PluginGrants} 网关）：exec / fs / http / tool.invoke /
 *    adb / time / clipboard / notify / overlay.system。
 *
 * 线程模型：JavaCallback 在 QuickJS 线程执行，这里**绝不获取 HookRegistry 的锁**；
 * 只读 reload 末尾生成的 volatile 快照。阻塞式调用由 HookEngine 暂停引擎中断，
 * 且不能在钩子/事件回调/主线程里调用（抛 {@code E_BLOCKED}）。
 */
public final class PluginBridge {

    public static final int STORAGE_LIMIT = 256 * 1024;
    public static final int STATE_VALUE_LIMIT = 4 * 1024;
    public static final int FS_READ_LIMIT = 256 * 1024;
    public static final int HTTP_BODY_LIMIT = 256 * 1024;

    private static final Pattern EXIT_CODE =
            Pattern.compile("\\n\\[exit code: (-?\\d+)]\\s*$");

    /** 钩子/事件回调里禁止阻塞式 bridge。 */
    private static final ThreadLocal<Integer> NON_BLOCKING =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> ACTION_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private final Context context;
    private final HookRegistry registry;
    private volatile PluginHost host;
    private volatile SecretStore secrets;
    private volatile OkHttpClient http;

    public PluginBridge(Context context, HookRegistry registry, PluginHost host) {
        this.context = context.getApplicationContext();
        this.registry = registry;
        this.host = host;
    }

    public void setHost(PluginHost host) {
        this.host = host;
    }

    public static void enterNonBlocking() {
        NON_BLOCKING.set(NON_BLOCKING.get() + 1);
    }

    public static void exitNonBlocking() {
        int n = NON_BLOCKING.get() - 1;
        if (n <= 0) NON_BLOCKING.remove();
        else NON_BLOCKING.set(n);
    }

    /** 由 HookEngine 的 JavaCallback 调用；args 已转成 JSONArray。 */
    public Object call(HookManifest m, String ns, String method, JSONArray args) throws Exception {
        String key = (ns == null || ns.isEmpty()) ? method : ns + "." + method;
        switch (key) {
            // ------------------------------------------------------------ 核心
            case "log.log":
                return log(m, args);
            case "config.get":
                return configGet(m, args);
            case "storage.get":
                return storageGet(m, args);
            case "storage.set":
                storageSet(m, args);
                return Boolean.TRUE;

            // ------------------------------------------------------------ 状态 / 环境
            case "state.set":
                stateSet(m, args);
                return Boolean.TRUE;
            case "state.get":
                return stateGet(m, args);
            case "state.all":
                stateAll(m);
                return new JSONObject(HostState.snapshot());
            case "device.model":
                require(m, "device.info");
                return Build.MODEL == null ? "" : Build.MODEL;
            case "device.sdk":
                require(m, "device.info");
                return Build.VERSION.SDK_INT;
            case "device.density":
                require(m, "device.info");
                return density();
            case "device.language":
                require(m, "device.info");
                return Locale.getDefault().toLanguageTag();
            case "device.freeStorage":
                require(m, "device.info");
                return freeStorage();
            case "time.now":
                return now(m);
            case "time.text":
                return formatNow(m, "HH:mm");
            case "time.date":
                return formatNow(m, "yyyy-MM-dd");
            case "time.zone":
                requireTime(m, new JSONObject());
                return TimeZone.getDefault().getID();
            case "time.format":
                return timeFormat(m, args);

            // ------------------------------------------------------------ grant：执行 / 文件 / 网络
            case "exec":
                return exec(m, args);
            case "fs.read":
                return fsRead(m, args);
            case "fs.write":
                fsWrite(m, args);
                return Boolean.TRUE;
            case "fs.list":
                return fsList(m, args);
            case "fs.remove":
                return fsRemove(m, args);
            case "http.request":
                return httpRequest(m, args);

            // ------------------------------------------------------------ 交互
            case "ui.toast":
                require(m, "ui.slot");
                host().toast(args.optString(0, ""));
                return Boolean.TRUE;
            case "ui.confirm":
                return uiConfirm(m, args);
            case "ui.patch":
                requireAny(m, "ui.slot", "ui.overlay");
                return host().patchNode(m.id, args.optString(0, ""), obj(args, 1));
            case "ui.bounds":
                requireAny(m, "ui.slot", "ui.overlay");
                JSONObject b = host().boundsNode(m.id, args.optString(0, ""));
                return b == null ? JSONObject.NULL : b;
            case "notify":
                grant(m, "notify", null);
                return host().notify(args.optString(0, ""), args.optString(1, ""));
            case "clipboard.read":
                grant(m, "clipboard", null);
                return host().clipboardGet();
            case "clipboard.write":
                grant(m, "clipboard", null);
                return host().clipboardSet(args.optString(0, ""));
            case "secret.get":
                return secretGet(m, args);

            // ------------------------------------------------------------ 定位
            case "location.available":
                return host().locationAvailable();
            case "location.status":
                return locationStatus(m);
            case "location.get":
                return locationGet(m, args);

            // ------------------------------------------------------------ 工具 / 导航 / 动作
            case "tool.list":
                return host().listTools();
            case "tool.invoke":
                return toolInvoke(m, args);
            case "nav.push":
                require(m, "ui.page");
                return host().navPush(args.optString(0, ""), obj(args, 1));
            case "nav.back":
                require(m, "ui.page");
                return host().navBack();
            case "nav.current":
                require(m, "ui.page");
                JSONObject cur = host().navCurrent();
                return cur == null ? JSONObject.NULL : cur;
            case "action.available":
                require(m, "action.bind");
                return registry.actionDeclView(args.optString(0, "")) != null;
            case "action.invoke":
                return actionInvoke(m, args);

            // ------------------------------------------------------------ 权限网关
            case "permission.status":
                return registry.grants().status(m, args.optString(0, ""), host);
            case "permission.describe":
                return registry.grants().describe(m, args.optString(0, ""), host);
            case "permission.request":
                return permissionRequest(m, args);

            // ------------------------------------------------------------ 浮层
            case "overlay.show":
                return overlayShow(m, args, false);
            case "overlay.update":
                require(m, "ui.overlay");
                host().overlayUpdate(m.id, args.optString(0, ""), obj(args, 1));
                return Boolean.TRUE;
            case "overlay.hide":
                require(m, "ui.overlay");
                host().overlayHide(m.id, args.optString(0, ""));
                return Boolean.TRUE;
            case "overlay.hideAll":
                require(m, "ui.overlay");
                host().overlayHideAll(m.id);
                return Boolean.TRUE;
            case "overlay.system.status":
                return "granted".equals(registry.grants().status(m, "overlay.system", host))
                        ? "granted" : "denied";
            case "overlay.system.request":
                if (!m.declaresGrant("overlay.system")) return Boolean.FALSE;
                return registry.grants().request(m, "overlay.system", "", 60_000L, host());
            case "overlay.system.show":
                return systemOverlayShow(m, args);
            case "overlay.system.hide":
                require(m, "overlay.system");
                host().systemOverlayHide(m.id);
                return Boolean.TRUE;

            // ------------------------------------------------------------ adb
            case "adb.available":
                return host().adbAvailable();
            case "adb.provider":
                return host().adbProvider();
            case "adb.shell":
                return adbShell(m, args);
            case "adb.exec":
                return adbExec(m, args);

            // ------------------------------------------------------------ 只读环境
            case "context.usage":
                require(m, "state.read");
                return contextUsage();
            case "session.info":
                require(m, "state.read");
                return sessionInfo();
            case "runtime.status":
                require(m, "state.read");
                return runtimeStatus();
            case "display.metrics":
                return displayMetrics();
            case "theme.get":
                return themeGet(args.optString(0, ""));
            case "capabilities":
                return capabilities(m);
            case "plugins.list":
                require(m, "state.read");
                return pluginsList();
            default:
                throw new IllegalArgumentException("E_BRIDGE: 未支持的 bridge." + key);
        }
    }

    // ================================================================ 核心

    private Object log(HookManifest m, JSONArray args) {
        String level = args.optString(0, "i");
        String msg = args.optString(1, "");
        if ("e".equals(level) || "error".equals(level)) {
            DebugLog.w("plugin " + m.id + ": " + msg);
        } else {
            DebugLog.i("plugin " + m.id + ": " + msg);
        }
        return Boolean.TRUE;
    }

    private Object configGet(HookManifest m, JSONArray args) {
        String key = args.optString(0, "");
        Object fallback = args.length() > 1 ? args.opt(1) : "";
        return PluginConfig.get(m, key, fallback);
    }

    private Object storageGet(HookManifest m, JSONArray args) throws Exception {
        String key = args.optString(0, "");
        JSONObject all = readStorage(m);
        if (all.has(key)) return all.get(key);
        return args.length() > 1 ? args.opt(1) : JSONObject.NULL;
    }

    private void storageSet(HookManifest m, JSONArray args) throws Exception {
        String key = args.optString(0, "");
        if (key.isEmpty()) throw new IllegalArgumentException("E_ARGS: storage key 不能为空");
        JSONObject all = readStorage(m);
        Object value = args.length() > 1 ? args.opt(1) : null;
        if (value == null || value == JSONObject.NULL) all.remove(key);
        else all.put(key, value);
        String text = all.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > STORAGE_LIMIT) {
            throw new IllegalArgumentException("E_LIMIT: storage 超过 256 KB");
        }
        File f = new File(m.dir, "storage.json");
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject readStorage(HookManifest m) {
        File f = new File(m.dir, "storage.json");
        if (!f.isFile()) return new JSONObject();
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ================================================================ 状态

    private void stateSet(HookManifest m, JSONArray args) throws Exception {
        require(m, "state.provide");
        String id = args.optString(0, "");
        StateDecl d = registry.stateDecl(id);
        if (d == null) throw new IllegalArgumentException("E_ARGS: 状态未声明: " + id);
        if (!d.pluginId.equals(m.id)) {
            throw new IllegalArgumentException("E_PERM: 不能写其它插件的状态: " + id);
        }
        Object value = args.length() > 1 ? args.opt(1) : null;
        if (value == null || value == JSONObject.NULL) value = "";
        if (!d.accepts(value)) {
            throw new IllegalArgumentException("E_ARGS: 状态 " + id + " 需要 " + d.type);
        }
        int size = String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        if (size > STATE_VALUE_LIMIT) throw new IllegalArgumentException("E_LIMIT: 状态值超过 4 KB");
        HostState.publish(d.fullName(), value);
    }

    private Object stateGet(HookManifest m, JSONArray args) {
        String id = args.optString(0, "");
        // pocket.display.* 是布局自适应必需，且不含设备标识（§2）。
        if (id.startsWith("pocket.") && !id.startsWith("pocket.display.")) require(m, "state.read");
        else if (!id.startsWith("pocket.")) {
            StateDecl d = registry.stateDecl(id);
            if (d == null || !d.pluginId.equals(m.id)) require(m, "state.read");
        }
        Object v = HostState.snapshot().get(id);
        return v == null ? JSONObject.NULL : v;
    }

    private void stateAll(HookManifest m) {
        require(m, "state.read");
    }

    // ================================================================ 时间 / 设备

    private Object now(HookManifest m) throws Exception {
        requireTime(m, new JSONObject());
        return System.currentTimeMillis();
    }

    private Object formatNow(HookManifest m, String pattern) throws Exception {
        requireTime(m, new JSONObject());
        return new SimpleDateFormat(pattern, Locale.ROOT).format(new Date());
    }

    private void requireTime(HookManifest m, JSONObject opts) throws Exception {
        grant(m, "env.time", opts);
    }

    private Object timeFormat(HookManifest m, JSONArray args) throws Exception {
        requireTime(m, new JSONObject());
        long epoch = args.optLong(0, System.currentTimeMillis());
        String pattern = args.optString(1, "yyyy-MM-dd HH:mm");
        validatePattern(pattern);
        return new SimpleDateFormat(pattern, Locale.ROOT).format(new Date(epoch));
    }

    /** 只支持 yyyy MM dd HH mm ss SSS 与字面量分隔符（§7.12）。 */
    static void validatePattern(String p) {
        if (p == null || p.isEmpty()) throw new IllegalArgumentException("E_ARGS: pattern 为空");
        if (p.length() > 64) throw new IllegalArgumentException("E_ARGS: pattern 过长");
        String[] allowed = {"yyyy", "MM", "dd", "HH", "mm", "ss", "SSS"};
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            boolean ok = false;
            for (String a : allowed) {
                if (p.startsWith(a, i)) {
                    i += a.length();
                    ok = true;
                    break;
                }
            }
            if (!ok) throw new IllegalArgumentException("E_ARGS: 不支持的 pattern 片段: " + p.substring(i));
        }
    }

    private String freeStorage() {
        try {
            StatFs fs = new StatFs(context.getFilesDir().getAbsolutePath());
            long bytes = fs.getAvailableBytes();
            return "剩余 " + humanBytes(bytes);
        } catch (Throwable e) {
            return "未知";
        }
    }

    private static String humanBytes(long n) {
        if (n >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", n / (double) (1L << 30));
        if (n >= 1L << 20) return String.format(Locale.ROOT, "%.1f MB", n / (double) (1L << 20));
        return n + " B";
    }

    // ================================================================ grant：exec / fs / http

    private Object exec(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.exec");
        String cmd = args.optString(0, "");
        if (cmd.trim().isEmpty()) throw new IllegalArgumentException("E_ARGS: command 为空");
        JSONObject opts = obj(args, 1);
        long timeout = clamp(opts.optLong("timeoutMs", m.toolMs), 1_000L, 600_000L);
        // cwd 统一成绝对路径：护栏与实际执行用同一个值；
        // 不指定时固定 /root/workspace，避免持久 shell 之前 cd 过导致的“护栏算工作区、实际在根目录”。
        String cwd = CommandGuard.normalizeCwd(opts.optString("cwd", ""));
        // 护栏必须按实际 cwd 解析相对路径，否则 {cwd:"/"} 下 'rm -rf *' 会被当成工作区清理。
        CommandGuard.Verdict v = CommandGuard.check(cmd, cwd);
        if (v.action == CommandGuard.Action.BLOCK) {
            throw new IllegalArgumentException("E_PERM: 已拦截（安全护栏）：" + v.reason);
        }
        grant(m, "exec.guest", opts);
        if (v.action == CommandGuard.Action.ASK) {
            String answer = host().confirm("插件请求执行命令",
                    cmd + "\n\n" + v.reason, 60_000L);
            if (!"ok".equals(answer)) {
                throw new IllegalArgumentException("E_PERM: 用户拒绝了命令执行");
            }
        }
        String out = host().execGuest(cmd, timeout, cwd);
        return execResult(out);
    }

    static JSONObject execResult(String out) {
        JSONObject o = new JSONObject();
        try {
            String text = out == null ? "" : out;
            boolean timedOut = text.startsWith("TIMEOUT after ");
            int exit = 0;
            Matcher m = EXIT_CODE.matcher(text);
            if (m.find()) {
                try {
                    exit = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
                text = text.substring(0, m.start());
            } else if (text.startsWith("Error:")) {
                exit = -1;
            }
            o.put("exitCode", exit);
            o.put("output", text.trim());
            o.put("timedOut", timedOut);
        } catch (Exception ignored) {
        }
        return o;
    }

    private Object fsRead(HookManifest m, JSONArray args) throws Exception {
        File f = resolveFs(m, args.optString(0, ""));
        if (!f.isFile()) throw new IllegalArgumentException("E_ARGS: 不是文件: " + args.optString(0, ""));
        if (f.length() > FS_READ_LIMIT) throw new IllegalArgumentException("E_LIMIT: 文件超过 256 KB");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private void fsWrite(HookManifest m, JSONArray args) throws Exception {
        File f = resolveFs(m, args.optString(0, ""));
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalArgumentException("E_ARGS: 无法创建目录 " + parent);
        }
        byte[] body = args.optString(1, "").getBytes(StandardCharsets.UTF_8);
        if (body.length > FS_READ_LIMIT) throw new IllegalArgumentException("E_LIMIT: 写入超过 256 KB");
        Files.write(f.toPath(), body);
    }

    private Object fsList(HookManifest m, JSONArray args) throws Exception {
        File dir = resolveFs(m, args.optString(0, "."));
        File[] files = dir.listFiles();
        JSONArray out = new JSONArray();
        if (files == null) return out;
        for (File f : files) {
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            o.put("dir", f.isDirectory());
            o.put("size", f.isFile() ? f.length() : 0);
            out.put(o);
        }
        return out;
    }

    private Object fsRemove(HookManifest m, JSONArray args) throws Exception {
        File f = resolveFs(m, args.optString(0, ""));
        if (f.isDirectory()) throw new IllegalArgumentException("E_ARGS: 不递归删除目录");
        return f.delete();
    }

    /** 把相对路径 / /root/workspace / /sdcard 映射到宿主文件，并强制边界。 */
    private File resolveFs(HookManifest m, String raw) throws Exception {
        String path = raw == null ? "" : raw.trim();
        if (path.isEmpty()) path = ".";
        boolean shared = path.equals("/sdcard") || path.startsWith("/sdcard/")
                || path.equals("/storage/emulated/0") || path.startsWith("/storage/emulated/0/");
        File root;
        String rel;
        if (shared) {
            grant(m, "fs.shared", null);
            if (!host().storageAccessGranted()) {
                throw new IllegalArgumentException("E_PERM: 需要系统“所有文件访问”");
            }
            root = host().sharedDir();
            rel = path.replaceFirst("^/sdcard/?", "")
                    .replaceFirst("^/storage/emulated/0/?", "");
        } else {
            grant(m, "fs.workspace", null);
            root = host().workspaceDir();
            String prefix = "/root/workspace";
            if (path.equals(prefix)) {
                rel = "";
            } else if (path.startsWith(prefix + "/")) {
                rel = path.substring(prefix.length());
            } else if (path.startsWith("/")) {
                throw new IllegalArgumentException("E_ARGS: 路径越界: " + path);
            } else {
                rel = path;
            }
        }
        File target = new File(root, rel);
        String canonicalRoot = root.getCanonicalPath();
        String canonicalTarget = target.getCanonicalPath();
        if (!canonicalTarget.equals(canonicalRoot)
                && !canonicalTarget.startsWith(canonicalRoot + File.separator)) {
            throw new IllegalArgumentException("E_ARGS: 路径越界: " + path);
        }
        return target;
    }

    private Object httpRequest(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.http");
        JSONObject spec = obj(args, 0);
        grant(m, "net.http", spec);
        String url = spec.optString("url", "");
        if (url.isEmpty()) throw new IllegalArgumentException("E_ARGS: url 为空");
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("E_ARGS: 只允许 https");
        }
        String hostName = hostOf(url);
        if (!hostAllowed(hostName, m.netAllow)) {
            throw new IllegalArgumentException("E_PERM: 域名不在 net.allow: " + hostName);
        }
        String method = spec.optString("method", "GET").toUpperCase(Locale.ROOT);
        long timeout = clamp(spec.optLong("timeoutMs", 15_000L), 1_000L, 60_000L);
        Request.Builder rb = new Request.Builder().url(url);
        JSONObject headers = spec.optJSONObject("headers");
        if (headers != null) {
            Iterator<String> it = headers.keys();
            while (it.hasNext()) {
                String k = it.next();
                rb.header(k, String.valueOf(headers.opt(k)));
            }
        }
        String body = spec.optString("body", "");
        if (!body.isEmpty() && !"GET".equals(method) && !"HEAD".equals(method)) {
            rb.method(method, RequestBody.create(body,
                    MediaType.parse(spec.optString("contentType", "application/json; charset=utf-8"))));
        } else {
            rb.method(method, null);
        }
        OkHttpClient client = httpClient().newBuilder()
                .callTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
        try (Response resp = client.newCall(rb.build()).execute()) {
            ResponseBody rb2 = resp.body();
            String text = "";
            if (rb2 != null) {
                try (InputStream in = rb2.byteStream()) {
                    byte[] data = readLimited(in, HTTP_BODY_LIMIT);
                    if (data == null) throw new IllegalArgumentException("E_LIMIT: 响应体超过 256 KB");
                    text = new String(data, StandardCharsets.UTF_8);
                }
            }
            JSONObject out = new JSONObject();
            out.put("status", resp.code());
            JSONObject hs = new JSONObject();
            Headers h = resp.headers();
            for (String name : h.names()) hs.put(name, h.get(name));
            out.put("headers", hs);
            out.put("body", text);
            return out;
        }
    }

    private OkHttpClient httpClient() {
        OkHttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    http = new OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            // 重定向可能换域名，绕过 net.allow；插件自己处理 3xx。
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .build();
                }
                c = http;
            }
        }
        return c;
    }

    static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /** net.allow：精确域名或 *.example.com；大小写不敏感。 */
    static boolean hostAllowed(String host, List<String> allow) {
        if (host == null || host.isEmpty() || allow == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String raw : allow) {
            if (raw == null) continue;
            String a = raw.trim().toLowerCase(Locale.ROOT);
            if (a.isEmpty()) continue;
            if (a.startsWith("*.")) {
                String suffix = a.substring(1);
                if (h.endsWith(suffix) || h.equals(a.substring(2))) return true;
            } else if (h.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readLimited(InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bos.size() + n > limit) return null;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ================================================================ 交互

    private Object uiConfirm(HookManifest m, JSONArray args) throws Exception {
        require(m, "ui.slot");
        checkBlocking("bridge.ui.confirm");
        long timeout = clamp(args.optLong(2, 60_000L), 1_000L, 60_000L);
        String r = host().confirm(args.optString(0, ""), args.optString(1, ""), timeout);
        return "ok".equals(r);
    }

    private Object secretGet(HookManifest m, JSONArray args) {
        require(m, "secret.get");
        String key = args.optString(0, "");
        if (key.isEmpty()) return JSONObject.NULL;
        String v = secrets().get(m.id + ":" + key);
        return v == null ? JSONObject.NULL : v;
    }

    private Object locationStatus(HookManifest m) {
        JSONObject o;
        try {
            o = host().locationStatus();
        } catch (Throwable e) {
            o = new JSONObject();
        }
        try {
            o.put("declared", m.declaresGrant("location"));
            o.put("granted", m.declaresGrant("location")
                    && registry.grants().isGranted(m, "location", host));
        } catch (Exception ignored) {
        }
        return o;
    }

    private Object locationGet(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.location");
        boolean fine = m.declaresGrant("android.permission.ACCESS_FINE_LOCATION");
        boolean coarse = m.declaresGrant("android.permission.ACCESS_COARSE_LOCATION");
        if (!fine && !coarse) {
            throw new IllegalArgumentException(
                    "E_PERM: manifest.androidPermissions 未声明 ACCESS_FINE/COARSE_LOCATION");
        }
        JSONObject opts = obj(args, 0);
        grant(m, "location", opts);
        long timeout = clamp(opts.optLong("timeoutMs", 15_000L), 1_000L, 60_000L);
        boolean highAccuracy = opts.optBoolean("highAccuracy", true);
        return host().locationGet(timeout, highAccuracy);
    }

    private Object permissionRequest(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.permission.request");
        String cap = args.optString(0, "");
        if (!HookManifest.isGrant(cap) || !m.declaresGrant(cap)) return Boolean.FALSE;
        JSONObject opts = obj(args, 1);
        long timeout = clamp(opts.optLong("timeoutMs", 60_000L), 1_000L, 60_000L);
        return registry.grants().request(m, cap, opts.optString("reason", ""), timeout, host());
    }

    private SecretStore secrets() {
        SecretStore s = secrets;
        if (s == null) {
            synchronized (this) {
                if (secrets == null) secrets = new SecretStore(context);
                s = secrets;
            }
        }
        return s;
    }

    // ================================================================ 工具 / 导航 / 动作

    private Object toolInvoke(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.tool.invoke");
        String tool = args.optString(0, "");
        if (tool.isEmpty()) throw new IllegalArgumentException("E_ARGS: 工具名为空");
        if (!m.allowsTool(tool)) throw new IllegalArgumentException("E_PERM: toolAccess.allow 未包含 " + tool);
        JSONObject params = obj(args, 1);
        JSONObject opts = obj(args, 2);
        boolean show = opts.optBoolean("show", false);
        boolean inject = opts.optBoolean("inject", false);
        // 注入会改变下一轮模型上下文，必须单独声明 hook.context。
        if (inject && !m.can("hook.context")) {
            throw new IllegalArgumentException("E_PERM: inject 需要 hook.context 能力");
        }
        grant(m, "tool.invoke", opts);
        boolean wait = opts.optBoolean("wait", true);
        if (!wait && host().isAgentBusy()) {
            JSONObject busy = new JSONObject();
            busy.put("ok", false);
            busy.put("error", "E_BUSY");
            return busy;
        }
        long timeout = clamp(opts.optLong("timeoutMs", m.toolMs), 1_000L, 600_000L);
        JSONObject out = new JSONObject();
        try {
            String result = host().invokeTool(m.id, tool, params.toString(), timeout);
            out.put("ok", true);
            out.put("result", result == null ? "" : result);
            // show 只插展示卡（不进 jsonl/上下文）；inject 排队给下一轮 system 备注。
            if (show) host().showToolCard(m.id, tool, params.toString(), result, true);
            if (inject) PluginInjections.inject(m.id, tool, result);
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            out.put("ok", false);
            out.put("error", why);
            if (show) host().showToolCard(m.id, tool, params.toString(), "Error: " + why, false);
        }
        return out;
    }

    private Object actionInvoke(HookManifest m, JSONArray args) throws Exception {
        require(m, "action.bind");
        checkBlocking("bridge.action.invoke");
        String id = args.optString(0, "");
        JSONObject params = obj(args, 1);
        ActionDecl d = registry.actionDeclView(id);
        JSONObject out = new JSONObject();
        if (d == null) {
            out.put("ok", false);
            out.put("error", "E_ARGS: 动作不存在: " + id);
            return out;
        }
        // 同步调用正在执行的 JS runtime 会重入 QuickJS（自调用/Cycle 会卡死）。
        if (d.pluginId.equals(m.id) || registry.isPluginExecuting(d.pluginId)) {
            out.put("ok", false);
            out.put("error", "E_REENTRANT: 不能同步调用正在执行的插件动作: " + id);
            return out;
        }
        int depth = ACTION_DEPTH.get();
        if (depth >= 3) {
            throw new IllegalArgumentException("E_CYCLE: 动作调用嵌套超过 3 层");
        }
        ACTION_DEPTH.set(depth + 1);
        try {
            String text = registry.invokeAction(d, params, "bridge:" + m.id);
            if (text != null && text.startsWith("Error:")) {
                out.put("ok", false);
                out.put("error", text);
            } else {
                out.put("ok", true);
                out.put("value", text == null ? "" : text);
            }
        } finally {
            ACTION_DEPTH.set(depth);
        }
        return out;
    }

    // ================================================================ 浮层

    private Object overlayShow(HookManifest m, JSONArray args, boolean system) throws Exception {
        require(m, "ui.overlay");
        JSONObject spec = obj(args, 0);
        if (spec.optJSONObject("view") == null) {
            throw new IllegalArgumentException("E_ARGS: overlay 缺少 view");
        }
        String id = spec.optString("id", m.id + "_" + (System.currentTimeMillis() % 100000));
        host().overlayShow(m.id, id, spec);
        return id;
    }

    private Object systemOverlayShow(HookManifest m, JSONArray args) throws Exception {
        if (!m.declaresGrant("overlay.system")) {
            throw new IllegalArgumentException("E_PERM: 未声明 overlay.system");
        }
        // 逐插件确认（仅本次/始终/拒绝）；系统特殊权限本身由 ensure() 先跳设置页。
        grant(m, "overlay.system", obj(args, 0));
        if (!host().specialGranted("overlay.system")) {
            throw new IllegalArgumentException("E_PERM: 需要系统“在其他应用上层显示”权限");
        }
        JSONObject spec = obj(args, 0);
        if (spec.optJSONObject("view") == null) {
            throw new IllegalArgumentException("E_ARGS: overlay 缺少 view");
        }
        host().systemOverlayShow(m.id, spec);
        return Boolean.TRUE;
    }

    // ================================================================ adb

    private Object adbShell(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.adb");
        grant(m, "bridge.adb", obj(args, 1));
        if (!host().adbAvailable()) {
            if (host().adbBinderAlive()) {
                host().adbRequestPermission();
                throw new IllegalArgumentException("E_PERM: 已请求 Shizuku 授权，请允许后重试");
            }
            throw new IllegalArgumentException("E_BRIDGE: Shizuku 未运行或未授权");
        }
        String command = args.optString(0, "");
        // adb 是 shell uid：一次 grant 不应该等于“任意命令永久放行”。
        // 未显式信任该插件时逐条确认；信任入口在插件页，文案会说明包含 ADB。
        if (!registry.grants().isTrusted(m)) {
            String answer = host().confirm("插件请求执行 ADB 命令",
                    command + "\n\n只有在你信任该插件时才应允许。", 60_000L);
            if (!"ok".equals(answer)) {
                throw new IllegalArgumentException("E_PERM: 用户拒绝了 ADB 命令");
            }
        }
        long timeout = clamp(obj(args, 1).optLong("timeoutMs", 15_000L), 1_000L, 60_000L);
        return execResult(host().adbShell(command, timeout));
    }

    private Object adbExec(HookManifest m, JSONArray args) throws Exception {
        checkBlocking("bridge.adb");
        grant(m, "bridge.adb", null);
        if (!host().adbAvailable()) {
            if (host().adbBinderAlive()) {
                host().adbRequestPermission();
                throw new IllegalArgumentException("E_PERM: 已请求 Shizuku 授权，请允许后重试");
            }
            throw new IllegalArgumentException("E_BRIDGE: Shizuku 未运行或未授权");
        }
        String program = args.optString(0, "");
        JSONArray argv = args.optJSONArray(1);
        String argsJson = argv == null ? "[]" : argv.toString();
        if (!registry.grants().isTrusted(m)) {
            String answer = host().confirm("插件请求执行 ADB 命令",
                    program + " " + argsJson + "\n\n只有在你信任该插件时才应允许。", 60_000L);
            if (!"ok".equals(answer)) {
                throw new IllegalArgumentException("E_PERM: 用户拒绝了 ADB 命令");
            }
        }
        return execResult(host().adbExec(program, argsJson, 15_000L));
    }

    // ================================================================ 只读环境

    private Object contextUsage() {
        Map<String, Object> s = HostState.snapshot();
        JSONObject o = new JSONObject();
        put(o, "tokens", s.get("pocket.context.tokens"));
        put(o, "limit", s.get("pocket.context.limit"));
        put(o, "ratio", s.get("pocket.context.ratio"));
        put(o, "summary", s.get("pocket.context.summary"));
        put(o, "compacting", s.get("pocket.context.compacting"));
        Object messages = s.get("pocket.context.messages");
        put(o, "messages", messages != null ? messages : s.get("pocket.session.messages"));
        return o;
    }

    private Object sessionInfo() {
        Map<String, Object> s = HostState.snapshot();
        JSONObject o = new JSONObject();
        put(o, "id", s.get("pocket.session.id"));
        put(o, "title", s.get("pocket.session.title"));
        put(o, "messages", s.get("pocket.session.messages"));
        return o;
    }

    private Object runtimeStatus() {
        Map<String, Object> s = HostState.snapshot();
        JSONObject o = new JSONObject();
        put(o, "state", s.get("pocket.runtime.state"));
        put(o, "backend", s.get("pocket.runtime.backend"));
        put(o, "workspace", s.get("pocket.runtime.workspace"));
        return o;
    }

    private Object displayMetrics() {
        Map<String, Object> s = HostState.snapshot();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        JSONObject o = new JSONObject();
        try {
            o.put("width", s.containsKey("pocket.display.width")
                    ? s.get("pocket.display.width") : Math.round(dm.widthPixels / dm.density));
            o.put("height", s.containsKey("pocket.display.height")
                    ? s.get("pocket.display.height") : Math.round(dm.heightPixels / dm.density));
            o.put("density", s.containsKey("pocket.display.density")
                    ? s.get("pocket.display.density") : dm.density);
            o.put("orientation", s.containsKey("pocket.display.orientation")
                    ? s.get("pocket.display.orientation")
                    : (dm.widthPixels > dm.heightPixels ? "landscape" : "portrait"));
            o.put("dark", s.containsKey("pocket.display.dark")
                    ? s.get("pocket.display.dark") : HostTheme.isDark());
            o.put("fontScale", s.containsKey("pocket.display.fontScale")
                    ? s.get("pocket.display.fontScale") : dm.scaledDensity / dm.density);
            o.put("reduceMotion", s.containsKey("pocket.display.reduceMotion")
                    ? s.get("pocket.display.reduceMotion") : Boolean.FALSE);
            Object insets = s.get("pocket.display.insets");
            JSONObject ins = new JSONObject();
            if (insets instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) insets).entrySet()) {
                    ins.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else if (insets instanceof JSONObject) {
                ins = (JSONObject) insets;
            }
            o.put("insets", ins);
        } catch (Exception ignored) {
        }
        return o;
    }

    private Object themeGet(String name) {
        if (name == null || name.isEmpty()) return JSONObject.NULL;
        ThemePack pack = HostTheme.pack();
        boolean dark = HostTheme.isDark();
        if (pack != null) {
            if (name.startsWith("color.")) {
                Integer c = pack.color(name, dark);
                if (c != null) return hex(c);
            } else {
                Integer n = pack.number(name);
                if (n != null) return n;
                String s = pack.string(name);
                if (s != null) return s;
            }
        }
        Integer def = defaultToken(name);
        return def == null ? JSONObject.NULL : (name.startsWith("color.") ? hex(def) : def);
    }

    private Integer defaultToken(String name) {
        DefaultTokenResolver d = new DefaultTokenResolver(context);
        switch (name) {
            case "color.bg": return context.getColor(com.dsh.android.R.color.bg_base);
            case "color.surface": return context.getColor(com.dsh.android.R.color.layer1);
            case "color.surfaceAlt": return context.getColor(com.dsh.android.R.color.layer2);
            case "color.text": return context.getColor(com.dsh.android.R.color.text_primary);
            case "color.textSecondary": return context.getColor(com.dsh.android.R.color.text_secondary);
            case "color.caption": return context.getColor(com.dsh.android.R.color.text_caption);
            case "color.border": return context.getColor(com.dsh.android.R.color.border);
            case "color.brand": return d.brandColor();
            case "color.ok": return context.getColor(com.dsh.android.R.color.accent_green);
            case "color.warn": return context.getColor(com.dsh.android.R.color.accent_amber);
            case "color.danger": return context.getColor(com.dsh.android.R.color.accent_red);
            case "color.codeBg": return d.codeBackground();
            case "radius.card": return d.radiusDp("card");
            case "radius.button": return d.radiusDp("button");
            case "radius.chip": return d.radiusDp("chip");
            case "space.xs": return 4;
            case "space.s": return 6;
            case "space.m": return 12;
            case "space.l": return 16;
            case "space.gutter": return 16;
            case "font.title": return 17;
            case "font.body": return 15;
            case "font.caption": return 12;
            case "font.mono": return 13;
            default: return null;
        }
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private Object capabilities(HookManifest m) {
        JSONObject o = new JSONObject();
        putCap(o, m, "exec", "exec.guest");
        putCap(o, m, "net", "net.http");
        putCap(o, m, "fs", "fs.workspace");
        putCap(o, m, "fsShared", "fs.shared");
        putCap(o, m, "tool", "tool.invoke");
        putCap(o, m, "clipboard", "clipboard");
        putCap(o, m, "notify", "notify");
        putCap(o, m, "overlaySystem", "overlay.system");
        try {
            PluginHost h = host;
            o.put("adb", h != null && h.adbAvailable() && m.declaresGrant("bridge.adb"));
            o.put("time", m.declaresGrant("env.time")
                    && registry.grants().isGranted(m, "env.time", h));
        } catch (Exception ignored) {
        }
        return o;
    }

    private void putCap(JSONObject o, HookManifest m, String key, String cap) {
        try {
            o.put(key, m.declaresGrant(cap) && registry.grants().isGranted(m, cap, host));
        } catch (Exception ignored) {
        }
    }

    private Object pluginsList() {
        JSONArray arr = new JSONArray();
        for (HookManifest m : registry.manifestsView()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("version", m.version);
                o.put("enabled", registry.enabled(m.id));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    // ================================================================ 权限 / 参数

    private void grant(HookManifest m, String capability, JSONObject opts) throws Exception {
        PluginHost h = host();
        // 钩子/事件/主线程里不能弹授权框；已授权则放行（non-blocking 调用如 notify 仍可用）。
        boolean blocked = NON_BLOCKING.get() > 0
                || Looper.myLooper() == Looper.getMainLooper();
        if (blocked) {
            // 先原子抢「仅本次」令牌：抢到直接放行；抢不到再看持久授权。
            // 否则 status 与 consume 之间并发调用会双放行。
            if (registry.grants().takeOnce(m, capability)) return;
            String status = registry.grants().status(m, capability, h);
            if (!"granted".equals(status)) {
                throw new IllegalArgumentException("E_BLOCKED: " + capability
                        + " 未授权，不能在钩子/事件回调/主线程中申请");
            }
            return;
        }
        registry.grants().ensure(m, capability, opts, h);
        registry.grants().consumeOnce(m, capability);
    }

    private void requireGranted(HookManifest m, String capability) {
        if (!registry.grants().isGranted(m, capability, host())) {
            throw new IllegalArgumentException("E_PERM: 未授权 " + capability);
        }
    }

    private void require(HookManifest m, String capability) {
        if (!m.can(capability)) {
            throw new IllegalArgumentException("E_PERM: 缺少能力 " + capability);
        }
    }

    private void requireAny(HookManifest m, String... capabilities) {
        for (String c : capabilities) {
            if (m.can(c)) return;
        }
        throw new IllegalArgumentException("E_PERM: 缺少能力 " + String.join("/", capabilities));
    }

    private PluginHost host() {
        PluginHost h = host;
        if (h == null) throw new IllegalStateException("E_BRIDGE: 宿主未就绪");
        return h;
    }

    private static JSONObject obj(JSONArray args, int index) {
        Object v = args.opt(index);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    private static void checkBlocking(String what) {
        if (NON_BLOCKING.get() > 0) {
            throw new IllegalArgumentException("E_BLOCKED: " + what + " 不能在钩子/事件回调中调用");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalArgumentException("E_BLOCKED: " + what + " 不能在主线程调用");
        }
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(v, max));
    }

    private static void put(JSONObject o, String key, Object v) {
        try {
            o.put(key, v == null ? JSONObject.NULL : v);
        } catch (Exception ignored) {
        }
    }

    private double density() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm.density;
    }
}
