package com.dsh.android;

import java.util.Locale;

/**
 * 预处理基准入口：纯 Java 循环 vs JNI 调同一个 C 实现。
 *
 * 用 am start 触发（结果落在 files/cmd.log）：
 *   adb shell am force-stop com.dsh.android.pkr
 *   adb shell am start -n com.dsh.android.pkr/com.dsh.android.MainActivity \
 *       --es pocketroot_cmd 'prep:'
 *
 * .so 是 freestanding 的（不链接 libc），源码在 app/src/main/jni/prep_bench.c，
 * 编译命令见文件头注释。与 screendet/data.py 的 to_tensor 逐元素一致。
 */
public final class PrepBench {
    private static final int H = 1612;
    private static final int W = 720;
    private static final int N = H * W;
    private static final int REPEAT = 30;

    static {
        System.loadLibrary("prepbench");
    }

    static native void nativePrep(byte[] rgb, float[] out, int n, int channels);

    private static native void nativeNoop();

    private PrepBench() {}

    /** 避免 Math.abs(float)：ART 对它的 intrinsic 不稳定，实测会让 2ch 循环慢一个数量级。 */
    private static float absf(float x) { return x < 0 ? -x : x; }

    /** 与 prep_bench.c 的 prep_core 等价，一次遍历 + 预分配 NCHW 输出。 */
    private static void javaPrep(byte[] rgb, float[] out, int n, int C, boolean mathAbs) {
        double[] sum = new double[3];
        double[] sumsq = new double[3];
        for (int i = 0; i < n; i++) {
            float r = (rgb[3 * i] & 0xff) / 255.0f;
            float g = (rgb[3 * i + 1] & 0xff) / 255.0f;
            float b = (rgb[3 * i + 2] & 0xff) / 255.0f;
            if (C == 3) {
                out[i] = r; out[n + i] = g; out[2 * n + i] = b;
                sum[0] += r; sumsq[0] += (double) r * r;
                sum[1] += g; sumsq[1] += (double) g * g;
                sum[2] += b; sumsq[2] += (double) b * b;
            } else {
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                out[i] = lum;
                sum[0] += lum; sumsq[0] += (double) lum * lum;
                if (C == 2) {
                    float cd = mathAbs
                            ? (Math.abs(r - g) + Math.abs(g - b) + Math.abs(b - r)) / 3.0f
                            : (absf(r - g) + absf(g - b) + absf(b - r)) / 3.0f;
                    out[n + i] = cd;
                    sum[1] += cd; sumsq[1] += (double) cd * cd;
                }
            }
        }
        if (C == 3) {
            double s = sum[0] + sum[1] + sum[2], sq = sumsq[0] + sumsq[1] + sumsq[2];
            double m = s / (3.0 * n), v = sq / (3.0 * n) - m * m;
            float inv = (float) (1.0 / (Math.sqrt(v) + 1e-6));
            for (int j = 0; j < 3 * n; j++) out[j] = (out[j] - (float) m) * inv;
            return;
        }
        for (int c = 0; c < C; c++) {
            double m = sum[c] / n, v = sumsq[c] / n - m * m;
            float inv = (float) (1.0 / (Math.sqrt(v) + 1e-6));
            int base = c * n;
            for (int i = 0; i < n; i++) out[base + i] = (out[base + i] - (float) m) * inv;
        }
    }

    private interface Task { void run(); }

    private static double medianMs(Task t) {
        for (int i = 0; i < 8; i++) t.run();           // JIT 预热
        long[] ts = new long[REPEAT];
        for (int i = 0; i < REPEAT; i++) {
            long s = System.nanoTime();
            t.run();
            ts[i] = System.nanoTime() - s;
        }
        java.util.Arrays.sort(ts);
        return ts[REPEAT / 2] / 1e6;
    }

    public static String run() {
        try {
            byte[] rgb = new byte[3 * N];
            new java.util.Random(12345).nextBytes(rgb);
            float[] outJava = new float[3 * N];
            float[] outJni = new float[3 * N];

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "PrepBench %dx%d n=%d  repeat=%d median (ARM64, app process)%n", H, W, N, REPEAT));
            double noop = medianMs(PrepBench::nativeNoop);
            sb.append(String.format(Locale.ROOT, "JNI noop  %.3f ms%n", noop));
            for (int C : new int[]{1, 2, 3}) {
                final int ch = C;
                double tj = medianMs(() -> javaPrep(rgb, outJava, N, ch, false));
                double tn = medianMs(() -> nativePrep(rgb, outJni, N, ch));
                float maxdiff = 0;
                for (int i = 0; i < C * N; i++) {
                    float d = Math.abs(outJava[i] - outJni[i]);
                    if (d > maxdiff) maxdiff = d;
                }
                String name = C == 1 ? "gray 1ch" : C == 2 ? "cd2  2ch" : "rgb  3ch";
                sb.append(String.format(Locale.ROOT,
                        "%-9s java=%7.2f ms  jni=%7.2f ms  speedup=%.2fx  maxdiff=%.6f%n",
                        name, tj, tn, tj / tn, maxdiff));
                if (ch == 2) {
                    double tma = medianMs(() -> javaPrep(rgb, outJava, N, ch, true));
                    sb.append(String.format(Locale.ROOT,
                            "           java(Math.abs)=%7.2f ms   <-- ART intrinsic 缺失的坑%n", tma));
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return "PrepBench error: " + e;
        }
    }
}
