package com.dsh.android.pluginrt;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.quickjs.JSContext;
import com.quickjs.JavaVoidCallback;
import com.quickjs.JSObject;
import com.quickjs.QuickJS;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * QuickJS spike：只量三件事，不接主工程。
 *
 *  1. 冷启动成本（createRuntime + createContext + 首次 eval）
 *  2. 热路径成本（小脚本、Java 回调、5KB 脚本编译）
 *  3. ES 支持与全局可裁剪性（可选链、async、能否删掉 Date）
 *
 * 结果同时写 logcat 和 files/qjs-bench.txt —— 华为会吞 logcat，文件更可靠。
 * 方法名带序号：javaCallback 放在最后，万一它崩了前面的结果也落盘了。
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class QuickJsBenchTest {

    private static final String TAG = "QJS-SPIKE";

    private static void report(String line) {
        Log.i(TAG, line);
        try {
            File dir = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "qjs-bench.txt");
            String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((stamp + "  " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static QuickJS rt() {
        return QuickJS.createRuntime();
    }

    @Test
    public void t1_coldStart() {
        int n = 3;
        long[] ms = new long[n];
        for (int i = 0; i < n; i++) {
            try {
                long t0 = System.nanoTime();
                QuickJS rt = rt();
                JSContext ctx = rt.createContext();
                ctx.executeIntegerScript("1+1", "boot.js");
                ctx.close();
                rt.close();
                ms[i] = (System.nanoTime() - t0) / 1_000_000;
            } catch (Throwable e) {
                report("coldStart[" + i + "] EX " + e);
            }
        }
        report("coldStart ms=" + Arrays.toString(ms));
    }

    @Test
    public void t2_warmEval() {
        QuickJS rt = rt();
        JSContext ctx = rt.createContext();
        try {
            ctx.executeVoidScript("function add(a,b){return a+b}", "def.js");
            for (int i = 0; i < 200; i++) ctx.executeIntegerScript("add(1,2)", "warm.js");

            int n = 10_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) ctx.executeIntegerScript("add(1,2)", "bench.js");
            report("eval us/call=" + ((System.nanoTime() - t0) / 1000 / n) + "  n=" + n);
        } finally {
            ctx.close();
            rt.close();
        }
    }

    @Test
    public void t3_compileCost() {
        StringBuilder sb = new StringBuilder("var acc=0;");
        for (int i = 0; i < 200; i++) sb.append("acc+=").append(i).append(";");
        sb.append("acc;");
        String src = sb.toString();

        QuickJS rt = rt();
        JSContext ctx = rt.createContext();
        try {
            int n = 50;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) ctx.executeIntegerScript(src, "compile.js");
            report("compile+run us/call(5KB)=" + ((System.nanoTime() - t0) / 1000 / n)
                    + "  srcLen=" + src.length());
        } finally {
            ctx.close();
            rt.close();
        }
    }

    @Test
    public void t4_es2020AndDate() {
        QuickJS rt = rt();
        JSContext ctx = rt.createContext();
        try {
            String opt = ctx.executeStringScript("({a:{b:7}})?.a?.b", "opt.js");
            String nullish = ctx.executeStringScript("null ?? 'fallback'", "n.js");
            String asyncType = ctx.executeStringScript("(async()=>1) instanceof Function ? 'yes':'no'", "a.js");
            String bigint = ctx.executeStringScript("typeof 10n", "big.js");
            String dateBefore = ctx.executeStringScript("typeof Date", "d1.js");
            ctx.executeVoidScript("delete globalThis.Date", "d2.js");
            String dateAfter = ctx.executeStringScript("typeof Date", "d3.js");
            report("es2020 optionalChain=" + opt + " nullish=" + nullish
                    + " asyncFn=" + asyncType + " bigint=" + bigint
                    + " Date=" + dateBefore + " DateAfterDelete=" + dateAfter);
        } finally {
            ctx.close();
            rt.close();
        }
    }

    /** 一个 runtime 上反复建 context：验证"runtime 常驻 + 插件各自 context"的模型。 */
    @Test
    public void t5_contextCreate() {
        QuickJS rt = rt();
        try {
            int n = 100;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                JSContext ctx = rt.createContext();
                ctx.executeIntegerScript("1+1", "c.js");
                ctx.close();
            }
            report("contextCreate us/call=" + ((System.nanoTime() - t0) / 1000 / n) + "  n=" + n);
        } finally {
            rt.close();
        }
    }

    /** fork 新增：内存上限。8MB 限制下疯狂分配，应该抛异常而不是把进程打死。 */
    @Test
    public void t7_memoryLimit() {
        QuickJS rt = rt();
        rt.setMemoryLimit(8L * 1024 * 1024);
        rt.setMaxStackSize(512 * 1024);
        JSContext ctx = rt.createContext();
        try {
            // 先确认正常代码还能跑
            int ok = ctx.executeIntegerScript("1+1", "ok.js");
            long t0 = System.nanoTime();
            String err;
            try {
                ctx.executeVoidScript(
                        "var a=[];for(;;){a.push(new Array(4096).fill(1))}", "oom.js");
                err = "NO_EXCEPTION";
            } catch (Throwable e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            report("memoryLimit ok=" + ok + "  ms=" + ms + "  err=" + trunc(err));
        } finally {
            ctx.close();
            rt.close();
        }
    }

    /** fork 新增：死循环应由引擎中断，进程存活。 */
    @Test
    public void t8_interrupt() {
        QuickJS rt = rt();
        JSContext ctx = rt.createContext();
        try {
            rt.setTimeoutMs(300);
            long t0 = System.nanoTime();
            String err;
            try {
                ctx.executeVoidScript("while(true){}", "loop.js");
                err = "NO_EXCEPTION";
            } catch (Throwable e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;

            // 中断后 runtime 还能不能继续用
            rt.setTimeoutMs(0);
            String after;
            try {
                after = "ok=" + ctx.executeIntegerScript("2+2", "after.js");
            } catch (Throwable e) {
                after = "AFTER_EX: " + e;
            }
            report("interrupt ms=" + ms + "  err=" + trunc(err) + "  after=" + after);
        } finally {
            ctx.close();
            rt.close();
        }
    }

    private static String trunc(String s) {
        if (s == null) return "null";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** 放最后：这个路径最可能出问题。 */
    @Test
    public void t6_javaCallback() {
        QuickJS rt = rt();
        JSContext ctx = rt.createContext();
        final int[] hits = new int[1];
        try {
            JSObject host = new JSObject(ctx);
            host.registerJavaMethod((JavaVoidCallback) (receiver, args) -> hits[0]++, "ping");
            ctx.set("host", host);
            ctx.executeVoidScript("function loop(n){for(var i=0;i<n;i++)host.ping()}", "def.js");
            ctx.executeVoidScript("loop(20)", "warm.js");

            int n = 10_000;
            long t0 = System.nanoTime();
            ctx.executeVoidScript("loop(" + n + ")", "bench.js");
            long us = (System.nanoTime() - t0) / 1000 / n;
            report("javaCallback us/call=" + us + "  hits=" + hits[0]);
        } catch (Throwable e) {
            report("javaCallback EX " + e);
            throw e;
        } finally {
            ctx.close();
            rt.close();
        }
    }
}
