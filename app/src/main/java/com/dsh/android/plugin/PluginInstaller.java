package com.dsh.android.plugin;

import android.content.Context;
import android.content.res.AssetManager;

import com.dsh.android.util.DebugLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把 APK 里自带的示例插件铺到 files/plugins/。
 *
 * 只在目标目录不存在时拷贝：用户改过的脚本永远不会被版本升级覆盖
 * （v0.2.0 里"未改动的示例自动刷新"那套覆盖逻辑容易误伤，这里取最小行为）。
 */
public final class PluginInstaller {

    private PluginInstaller() {
    }

    /** 读本地插件的 manifest 版本；读不到就当 0，保证会用 APK 里的覆盖。 */
    private static String versionOf(File dir) {
        try {
            HookManifest m = HookManifest.load(dir);
            return m.version;
        } catch (Exception e) {
            return "0";
        }
    }

    private static String versionOfAsset(AssetManager am, String id) {
        try (InputStream in = am.open("plugins/" + id + "/manifest.json")) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            String text = new String(buf, 0, Math.max(n, 0), "UTF-8");
            return new org.json.JSONObject(text).optString("version", "0");
        } catch (Exception e) {
            return "0";
        }
    }

    /** 按点分数字比版本；非法段按 0 处理。 */
    private static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0;
            int yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) return xi < yi ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void seedSamples(Context ctx, File pluginsDir) {
        if (!pluginsDir.isDirectory() && !pluginsDir.mkdirs()) return;
        AssetManager am = ctx.getAssets();
        try {
            String[] ids = am.list("plugins");
            if (ids == null) return;
            for (String id : ids) {
                File target = new File(pluginsDir, id);
                if (target.isDirectory()) {
                    // 已存在：只有示例版本更高才覆盖（安全修复要能到达老用户），
                    // 版本相同就完全不动，留给用户改。
                    if (compare(versionOf(target), versionOfAsset(am, id)) >= 0) continue;
                    DebugLog.i("plugin: upgrading sample " + id + " "
                            + versionOf(target) + " -> " + versionOfAsset(am, id));
                }
                String[] files = am.list("plugins/" + id);
                if (files == null || files.length == 0) continue;
                if (!target.isDirectory() && !target.mkdirs()) continue;
                for (String name : files) {
                    try (InputStream in = am.open("plugins/" + id + "/" + name);
                         OutputStream out = new FileOutputStream(new File(target, name))) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
                DebugLog.i("plugin: seeded sample " + id);
            }
        } catch (IOException e) {
            DebugLog.w("plugin: seed failed: " + e.getMessage());
        }
    }
}
