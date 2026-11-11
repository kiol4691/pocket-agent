package com.dsh.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真机 YUV 采集链路基准：Camera2 + ImageReader(YUV_420_888) 拿真实帧，
 * 测「从 Y / U / V planes 直接产出模型输入张量」的成本。
 *
 * 调试通道：--es pocketroot_cmd 'yuv:'（需先 pm grant CAMERA）
 *
 * 注意：屏幕采集（MediaProjection/VirtualDisplay）输出是 RGBA_8888，拿不到 YUV planes；
 * YUV_420_888 只有相机/解码器路径才有。这里用它验证「YUV 直取能省多少」这个前提。
 */
public final class YuvBench {
    private static final int REPEAT = 30;

    static {
        System.loadLibrary("prepbench");
    }

    private static native void nativeYuvPrep(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                                             float[] out, int w, int h,
                                             int yRow, int uvRow, int uvPix,
                                             int yPos, int uPos, int vPos, int channels);

    private YuvBench() {}

    private interface Task { void run(); }

    private static double medianMs(Task t) {
        for (int i = 0; i < 8; i++) t.run();
        long[] ts = new long[REPEAT];
        for (int i = 0; i < REPEAT; i++) {
            long s = System.nanoTime();
            t.run();
            ts[i] = System.nanoTime() - s;
        }
        Arrays.sort(ts);
        return ts[REPEAT / 2] / 1e6;
    }

    private static Size pickSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long target = 1612L * 720;
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long d = Math.abs((long) s.getWidth() * s.getHeight() - target);
            if (d < bestDiff) { bestDiff = d; best = s; }
        }
        return best;
    }

    public static String run(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return "YuvBench error: CAMERA not granted. Run: adb shell pm grant "
                    + ctx.getPackageName() + " android.permission.CAMERA";
        }
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return "YuvBench error: no CameraManager";
        try {
            String camId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    camId = id; break;
                }
            }
            if (camId == null) camId = cm.getCameraIdList()[0];
            StreamConfigurationMap map = cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map);
            final int w = size.getWidth(), h = size.getHeight(), n = w * h;

            HandlerThread ht = new HandlerThread("YuvBench");
            ht.start();
            Handler handler = new Handler(ht.getLooper());
            ImageReader reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean done = new AtomicBoolean(false);
            AtomicReference<String> result = new AtomicReference<>("(no frame)");
            AtomicReference<CameraDevice> cameraRef = new AtomicReference<>();

            reader.setOnImageAvailableListener(r -> {
                if (!done.compareAndSet(false, true)) return;
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) { result.set("YuvBench error: acquireLatestImage null"); return; }
                    Image.Plane[] p = img.getPlanes();
                    ByteBuffer by = p[0].getBuffer(), bu = p[1].getBuffer(), bv = p[2].getBuffer();
                    final int yRow = p[0].getRowStride();
                    final int uvRow = p[1].getRowStride();
                    final int uvPix = p[1].getPixelStride();
                    final int yPos = by.position(), uPos = bu.position(), vPos = bv.position();
                    final int yCap = by.remaining();

                    float[] out2 = new float[2 * n];
                    float[] out1 = new float[n];
                    byte[] fakeRgb = new byte[3 * n];
                    new java.util.Random(7).nextBytes(fakeRgb);
                    float[] rgbOut = new float[2 * n];

                    final Image frame = img;
                    double tFrame = medianMs(() -> {
                        Image.Plane[] q = frame.getPlanes();
                        q[0].getBuffer(); q[1].getBuffer(); q[2].getBuffer();
                    });
                    double tYuv2 = medianMs(() -> nativeYuvPrep(by, bu, bv, out2, w, h,
                            yRow, uvRow, uvPix, yPos, uPos, vPos, 2));
                    double tYuv1 = medianMs(() -> nativeYuvPrep(by, bu, bv, out1, w, h,
                            yRow, uvRow, uvPix, yPos, uPos, vPos, 1));
                    double tRgb2 = medianMs(() -> PrepBench.nativePrep(
                            fakeRgb, rgbOut, n, 2));

                    // 数据合理性：Y 平面不应全 0；U/V 色度幅度应非零（证明 cd 通道有信号）
                    int yFirst = by.get(yPos) & 0xff;
                    double chroma = 0;
                    int chromaCnt = 0;
                    for (int yy = 0; yy < h; yy += 8) {
                        for (int xx = 0; xx < w; xx += 8) {
                            int xi = (xx >> 1) * uvPix;
                            int u = bu.get(uPos + (yy >> 1) * uvRow + xi) & 0xff;
                            int v = bv.get(vPos + (yy >> 1) * uvRow + xi) & 0xff;
                            chroma += (Math.abs(u - 128) + Math.abs(v - 128)) / 2.0 / 255.0;
                            chromaCnt++;
                        }
                    }
                    chroma /= chromaCnt;

                    double px = n / 1e6;
                    result.set(String.format(Locale.ROOT,
                            "YuvBench %dx%d (%.2f Mpx)  YUV_420_888 real camera frame%n"
                                    + "strides: yRow=%d uvRow=%d uvPix=%d yCap=%d y[0]=%d chroma=%.4f (rowPad=%d)%n"
                                    + "framework getPlanes+getBuffer   %7.3f ms%n"
                                    + "YUV planes -> 1ch gray (jni)    %7.3f ms   (%.2f ms/Mpx)%n"
                                    + "YUV planes -> 2ch g+cd (jni)    %7.3f ms   (%.2f ms/Mpx)%n"
                                    + "RGB byte[] -> 2ch (jni, control) %7.3f ms   (%.2f ms/Mpx)%n"
                                    + "YUV 2ch / RGB 2ch = %.3f%n",
                            w, h, px, yRow, uvRow, uvPix, yCap, yFirst, chroma, yRow - w,
                            tFrame, tYuv1, tYuv1 / px, tYuv2, tYuv2 / px,
                            tRgb2, tRgb2 / px, tYuv2 / tRgb2));
                } catch (Throwable t) {
                    result.set("YuvBench error: " + t);
                } finally {
                    if (img != null) img.close();
                    CameraDevice c = cameraRef.get();
                    if (c != null) c.close();
                    latch.countDown();
                }
            }, handler);

            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraRef.set(camera);
                    try {
                        camera.createCaptureSession(
                                Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        try {
                                            CaptureRequest.Builder b = camera.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(reader.getSurface());
                                            s.setRepeatingRequest(b.build(), null, handler);
                                        } catch (Exception e) {
                                            result.set("YuvBench error: request " + e);
                                            latch.countDown();
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        result.set("YuvBench error: configureFailed");
                                        latch.countDown();
                                    }
                                }, handler);
                    } catch (Exception e) {
                        result.set("YuvBench error: session " + e);
                        latch.countDown();
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    result.set("YuvBench error: camera disconnected");
                    latch.countDown();
                }
                @Override public void onError(CameraDevice camera, int error) {
                    result.set("YuvBench error: camera error " + error);
                    latch.countDown();
                }
            }, handler);

            if (!latch.await(25, TimeUnit.SECONDS)) {
                result.set("YuvBench error: timeout waiting for frame (25s)");
            }
            CameraDevice c = cameraRef.get();
            if (c != null) c.close();
            reader.close();
            ht.quitSafely();
            return result.get();
        } catch (Throwable e) {
            return "YuvBench error: " + e;
        }
    }
}
