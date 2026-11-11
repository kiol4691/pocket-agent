/* JNI 预处理基准：RGB uint8 -> NCHW float32，一次遍历。
 *
 * 与 screendet/data.py 的 to_tensor 逐元素一致（maxdiff <= 1e-6）。
 * 用 freestanding 方式编译，不链接任何 libc —— 与 libzng.so 同样的做法，
 * 因此无需 NDK/CMake，产物可直接放进 app/src/main/jniLibs/arm64-v8a/。
 *
 *   见 tools/build-prepbench.sh（zig cc + freestanding，无 NDK/CMake）。
 *   jni.h 从本机 JDK 取，stdio.h/stdarg.h 用 jni/stub 下的占位头，
 *   这样 -ffreestanding 也能编译，且不链接任何 libc。
 */
#include <jni.h>
#include <stdint.h>

typedef __SIZE_TYPE__ size_t;

static inline float fabsf_(float x) { return __builtin_fabsf(x); }
static inline float sqrtf_(float x) { return __builtin_sqrtf(x); }

static void prep_core(const uint8_t *rgb, float *out, int n, int C) {
    double sum[3] = {0, 0, 0}, sumsq[3] = {0, 0, 0};
    for (int i = 0; i < n; i++) {
        float r = rgb[3 * i] / 255.0f;
        float g = rgb[3 * i + 1] / 255.0f;
        float b = rgb[3 * i + 2] / 255.0f;
        if (C == 3) {
            out[i] = r; out[n + i] = g; out[2 * n + i] = b;
            sum[0] += r; sumsq[0] += (double)r * r;
            sum[1] += g; sumsq[1] += (double)g * g;
            sum[2] += b; sumsq[2] += (double)b * b;
        } else {
            float lum = 0.299f * r + 0.587f * g + 0.114f * b;
            out[i] = lum;
            sum[0] += lum; sumsq[0] += (double)lum * lum;
            if (C == 2) {
                float cd = (fabsf_(r - g) + fabsf_(g - b) + fabsf_(b - r)) / 3.0f;
                out[n + i] = cd;
                sum[1] += cd; sumsq[1] += (double)cd * cd;
            }
        }
    }
    if (C == 3) {
        double s = sum[0] + sum[1] + sum[2], sq = sumsq[0] + sumsq[1] + sumsq[2];
        double m = s / (3.0 * n), v = sq / (3.0 * n) - m * m;
        float inv = 1.0f / (sqrtf_((float)v) + 1e-6f);
        for (int j = 0; j < 3 * n; j++) out[j] = (out[j] - (float)m) * inv;
        return;
    }
    for (int c = 0; c < C; c++) {
        double m = sum[c] / n, v = sumsq[c] / n - m * m;
        float inv = 1.0f / (sqrtf_((float)v) + 1e-6f);
        float *p = out + c * n;
        for (int i = 0; i < n; i++) p[i] = (p[i] - (float)m) * inv;
    }
}

JNIEXPORT void JNICALL
Java_com_dsh_android_PrepBench_nativePrep(JNIEnv *env, jclass cls,
        jbyteArray rgbArr, jfloatArray outArr, jint n, jint channels) {
    (void)cls;
    jbyte *rgb = (*env)->GetByteArrayElements(env, rgbArr, NULL);
    jfloat *out = (*env)->GetFloatArrayElements(env, outArr, NULL);
    if (rgb == NULL || out == NULL) return;
    prep_core((const uint8_t *)rgb, out, n, channels);
    (*env)->ReleaseByteArrayElements(env, rgbArr, rgb, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, outArr, out, 0);
}

JNIEXPORT void JNICALL
Java_com_dsh_android_PrepBench_nativeNoop(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
}

/* YUV_420_888 planes -> NCHW float32，直接读 direct ByteBuffer，无中间拷贝。
 * U/V 是 2x2 子采样：cd 用色度幅度 (|U-128|+|V-128|)/2 近似 RGB 通道差。 */
JNIEXPORT void JNICALL
Java_com_dsh_android_YuvBench_nativeYuvPrep(JNIEnv *env, jclass cls,
        jobject yBuf, jobject uBuf, jobject vBuf, jfloatArray outArr,
        jint w, jint h, jint yRow, jint uvRow, jint uvPix,
        jint yPos, jint uPos, jint vPos, jint channels) {
    (void)cls;
    const uint8_t *Y = (const uint8_t *)(*env)->GetDirectBufferAddress(env, yBuf);
    const uint8_t *U = (const uint8_t *)(*env)->GetDirectBufferAddress(env, uBuf);
    const uint8_t *V = (const uint8_t *)(*env)->GetDirectBufferAddress(env, vBuf);
    jfloat *out = (*env)->GetFloatArrayElements(env, outArr, NULL);
    if (!Y || !U || !V || !out) return;
    Y += yPos; U += uPos; V += vPos;
    int n = w * h;
    double s0 = 0, q0 = 0, s1 = 0, q1 = 0;
    for (int y = 0; y < h; y++) {
        const uint8_t *yr = Y + (size_t)y * yRow;
        const uint8_t *ur = U + (size_t)(y >> 1) * uvRow;
        const uint8_t *vr = V + (size_t)(y >> 1) * uvRow;
        for (int x = 0; x < w; x++) {
            float lum = yr[x] / 255.0f;
            out[y * w + x] = lum;
            s0 += lum; q0 += (double)lum * lum;
            if (channels == 2) {
                int xi = (x >> 1) * uvPix;
                float u = (ur[xi] - 128) / 255.0f;
                float v = (vr[xi] - 128) / 255.0f;
                float cd = (fabsf_(u) + fabsf_(v)) / 2.0f;
                out[n + y * w + x] = cd;
                s1 += cd; q1 += (double)cd * cd;
            }
        }
    }
    double m0 = s0 / n, v0 = q0 / n - m0 * m0;
    float i0 = 1.0f / (sqrtf_((float)v0) + 1e-6f);
    for (int i = 0; i < n; i++) out[i] = (out[i] - (float)m0) * i0;
    if (channels == 2) {
        double m1 = s1 / n, v1 = q1 / n - m1 * m1;
        float i1 = 1.0f / (sqrtf_((float)v1) + 1e-6f);
        for (int i = 0; i < n; i++) out[n + i] = (out[n + i] - (float)m1) * i1;
    }
    (*env)->ReleaseFloatArrayElements(env, outArr, out, 0);
}
