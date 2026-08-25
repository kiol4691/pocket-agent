package com.dsh.android.plugin;

import android.content.Context;

import com.dsh.android.util.DebugLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
                    // keep it in the list so the manager UI can surface the problem
                }
                out.add(info);
            } catch (Exception e) {
                DebugLog.w("plugin scan failed for " + d.getName() + ": " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.id.compareTo(b.id));
        return out;
    }

    /** Copy assets/plugins/* into files/plugins/*. Per-file, never overwrites existing
     * files — EXCEPT: when the extracted manifest.json is byte-identical to the bundled
     * one (i.e. the user never customized the plugin), the whole folder is refreshed so
     * sample fixes shipped in a new APK actually reach the device. */
    public static void extractSamples(Context context) {
        File root = new File(context.getFilesDir(), "plugins");
        if (!root.isDirectory() && !root.mkdirs()) return;
        try {
            String[] samples = context.getAssets().list("plugins");
            if (samples == null) return;
            for (String id : samples) {
                File dst = new File(root, id);
                if (!dst.isDirectory() && !dst.mkdirs()) return;
                boolean pristine = isPristineCopy(context, "plugins/" + id, dst);
                copyAssetDir(context, "plugins/" + id, dst, pristine);
            }
        } catch (Exception e) {
            DebugLog.w("plugin sample extraction failed: " + e.getMessage());
        }
    }

    /** True when the extracted manifest matches the bundled one byte-for-byte. */
    private static boolean isPristineCopy(Context context, String assetDir, File dstDir) {
        File mf = new File(dstDir, "manifest.json");
        if (!mf.isFile()) return false;
        try {
            byte[] assetBytes;
            try (InputStream in = context.getAssets().open(assetDir + "/manifest.json")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                assetBytes = bo.toByteArray();
            }
            byte[] fileBytes = java.nio.file.Files.readAllBytes(mf.toPath());
            return java.util.Arrays.equals(assetBytes, fileBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyAssetDir(Context context, String assetPath, File dstDir,
                                     boolean overwrite) throws Exception {
        // Discriminate file vs directory with open(): AssetManager.list() is unreliable
        // across OEMs (some Huawei builds return a non-empty array for plain files,
        // which would turn scripts into empty directories).
        boolean isFile;
        try (InputStream t = context.getAssets().open(assetPath)) {
            isFile = true;
        } catch (java.io.FileNotFoundException e) {
            isFile = false;
        }
        if (isFile) {
            copyAssetFile(context, assetPath, dstDir, overwrite);
            return;
        }
        String[] entries = context.getAssets().list(assetPath);
        if (entries == null) return;
        for (String e : entries) {
            copyAssetDir(context, assetPath + "/" + e, dstDir, overwrite);
        }
    }

    private static void copyAssetFile(Context context, String assetPath, File dstDir,
                                      boolean overwrite) throws Exception {
        File out = new File(dstDir, assetPath.substring(assetPath.lastIndexOf('/') + 1));
        if (out.isDirectory()) deleteRecursively(out); // heal a bogus dir from old extractions
        if (out.isFile() && !overwrite) return;        // never clobber user edits by default
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        out.setExecutable(true, false); // scripts must be runnable inside the sandbox
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
