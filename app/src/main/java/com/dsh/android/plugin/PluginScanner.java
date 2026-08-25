package com.dsh.android.plugin;

import android.content.Context;

import com.dsh.android.util.DebugLog;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Scans files/plugins/&lt;id&gt;/manifest.json and extracts bundled sample plugins on first run. */
public final class PluginScanner {

    /** Copy assets/plugins/* into files/plugins/* (missing dirs only), then parse manifests. */
    public static List<PluginInfo> scan(Context context) {
        extractSamples(context);
        List<PluginInfo> out = new ArrayList<>();
        File root = new File(context.getFilesDir(), "plugins");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            try {
                File mf = new File(d, "manifest.json");
                if (!mf.isFile()) continue;
                JSONObject m = new JSONObject(new String(
                        java.nio.file.Files.readAllBytes(mf.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                PluginInfo info = new PluginInfo(d, m);
                if (info.error != null) {
                    DebugLog.w("plugin " + d.getName() + " invalid: " + info.error);
                    continue;
                }
                out.add(info);
            } catch (Exception e) {
                DebugLog.w("plugin scan failed for " + d.getName() + ": " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.id.compareTo(b.id));
        return out;
    }

    private static void extractSamples(Context context) {
        File root = new File(context.getFilesDir(), "plugins");
        if (!root.isDirectory() && !root.mkdirs()) return;
        try {
            String[] samples = context.getAssets().list("plugins");
            if (samples == null) return;
            for (String id : samples) {
                File dst = new File(root, id);
                if (dst.isDirectory()) continue; // never overwrite user-editable copies
                dst.mkdirs();
                copyAssetDir(context, "plugins/" + id, dst);
            }
        } catch (Exception e) {
            DebugLog.w("plugin sample extraction failed: " + e.getMessage());
        }
    }

    private static void copyAssetDir(Context context, String assetPath, File dstDir) throws Exception {
        String[] entries = context.getAssets().list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(context, assetPath, dstDir);
            return;
        }
        for (String e : entries) {
            String child = assetPath + "/" + e;
            String[] sub = context.getAssets().list(child);
            if (sub != null && sub.length > 0) {
                File d = new File(dstDir, e);
                d.mkdirs();
                copyAssetDir(context, child, d);
            } else {
                copyAssetFile(context, child, dstDir);
            }
        }
    }

    private static void copyAssetFile(Context context, String assetPath, File dstDir) throws Exception {
        File out = new File(dstDir, assetPath.substring(assetPath.lastIndexOf('/') + 1));
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        out.setExecutable(true, false); // scripts must be runnable inside the sandbox
    }
}
