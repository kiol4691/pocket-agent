package com.dsh.android.plugin;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.dsh.android.util.DebugLog;

import org.json.JSONArray;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * {@code bridge.adb} 的 Shizuku provider。
 *
 * 通过 Shizuku 的 binder 以 shell（uid 2000）执行命令；设备没运行 Shizuku 或未授权时
 * provider 为 {@code none}，调用抛 {@code E_BRIDGE}。本地无线 adb 配对暂未接线。
 *
 * 输出上限 64 KB，超时默认由调用方给（15s，上限 60s）；结果格式与 guest exec 一致：
 * 正文末尾追加 {@code [exit code: N]}，超时以 {@code TIMEOUT after ...} 开头。
 */
public final class AdbBridge {

    public static final int REQUEST_CODE = 0xADB0;
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static volatile boolean inited;
    private static volatile String lastError = "";

    private AdbBridge() {
    }

    public static void init(android.content.Context context) {
        if (inited) return;
        synchronized (AdbBridge.class) {
            if (inited) return;
            inited = true;
            try {
                Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                    if (requestCode != REQUEST_CODE) return;
                    DebugLog.i("shizuku permission result: "
                            + (grantResult == PackageManager.PERMISSION_GRANTED ? "granted" : "denied"));
                });
            } catch (Throwable e) {
                lastError = e.toString();
                DebugLog.w("Shizuku listener unavailable: " + e);
            }
        }
    }

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean available() {
        try {
            return binderAlive() && hasPermission();
        } catch (Throwable e) {
            return false;
        }
    }

    public static String provider() {
        return binderAlive() ? "shizuku" : "none";
    }

    public static String lastError() {
        return lastError;
    }

    private static boolean hasPermission() {
        try {
            if (Shizuku.isPreV11()) return true;   // pre-v11 没有运行时授权，服务在即放行
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 触发 Shizuku 授权弹窗；已授权返回 true。 */
    public static boolean requestPermission() {
        try {
            if (!binderAlive()) {
                lastError = "Shizuku 未运行";
                return false;
            }
            if (hasPermission()) return true;
            Shizuku.requestPermission(REQUEST_CODE);
            return false;
        } catch (Throwable e) {
            lastError = e.toString();
            DebugLog.w("Shizuku requestPermission failed: " + e);
            return false;
        }
    }

    /** {@code adb.shell}：经 /system/bin/sh -c 执行。 */
    public static String shell(String command, long timeoutMs) throws Exception {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("E_ARGS: command 为空");
        }
        return run(new String[]{"/system/bin/sh", "-c", command}, timeoutMs);
    }

    /** {@code adb.exec}：不经 shell，参数原样传给程序。 */
    public static String exec(String program, String argsJson, long timeoutMs) throws Exception {
        if (program == null || program.trim().isEmpty()) {
            throw new IllegalArgumentException("E_ARGS: program 为空");
        }
        JSONArray arr = argsJson == null || argsJson.trim().isEmpty()
                ? new JSONArray() : new JSONArray(argsJson);
        String[] cmd = new String[arr.length() + 1];
        cmd[0] = program;
        for (int i = 0; i < arr.length(); i++) cmd[i + 1] = String.valueOf(arr.opt(i));
        return run(cmd, timeoutMs);
    }

    private static String run(String[] cmd, long timeoutMs) throws Exception {
        if (!binderAlive()) throw new IllegalStateException("E_BRIDGE: Shizuku 未运行");
        if (!hasPermission()) throw new IllegalStateException("E_BRIDGE: Shizuku 未授权");
        long timeout = Math.max(1_000L, Math.min(timeoutMs, 60_000L));
        IShizukuService service = IShizukuService.Stub.asInterface(
                new ShizukuBinderWrapper(Shizuku.getBinder()));
        // env 必须显式给：Shizuku 服务端对 null 环境数组会抛 "name == null"。
        String[] env = {
                "PATH=/sbin:/system/sbin:/system/bin:/system/xbin",
                "HOME=/"
        };
        IRemoteProcess remote = service.newProcess(cmd, env, "/");

        StringBuilder out = new StringBuilder();
        AtomicInteger budget = new AtomicInteger(OUTPUT_LIMIT);
        ParcelFileDescriptor outPfd = remote.getInputStream();
        ParcelFileDescriptor errPfd = remote.getErrorStream();
        Thread stdout = drain(outPfd, out, budget);
        Thread stderr = drain(errPfd, out, budget);
        boolean done;
        try {
            // 第二个参数是 TimeUnit 名字（服务端 TimeUnit.valueOf），不能传 null。
            done = remote.waitForTimeout(timeout, "MILLISECONDS");
        } catch (Throwable e) {
            // IRemoteProcess 只有 destroy()（AIDL 没有 destroyForcibly）；关掉流让 drain 线程退出。
            remote.destroy();
            closeQuietly(outPfd);
            closeQuietly(errPfd);
            throw e;
        }
        if (!done) {
            remote.destroy();
            closeQuietly(outPfd);
            closeQuietly(errPfd);
        }
        int code = -1;
        try {
            if (done) code = remote.exitValue();
        } catch (Throwable ignored) {
        }
        join(stdout);
        join(stderr);
        String text = out.toString().trim();
        if (!done) {
            return "TIMEOUT after " + timeout + "ms\n[exit code: -1]\n" + text;
        }
        return (text.isEmpty() ? "" : text + "\n") + "[exit code: " + code + "]";
    }

    private static Thread drain(ParcelFileDescriptor pfd, StringBuilder out, AtomicInteger budget) {
        Thread t = new Thread(() -> {
            if (pfd == null) return;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    int left = budget.get();
                    if (left <= 0) break;
                    int take = Math.min(n, left);
                    synchronized (out) {
                        out.append(new String(buf, 0, take));
                    }
                    budget.addAndGet(-take);
                }
            } catch (Throwable ignored) {
            }
        }, "shizuku-adb");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Throwable ignored) {
        }
    }
}
