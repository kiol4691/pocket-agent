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

    /** Copy assets/plugins/* into files/plugins/*. A plugin whose manifest was never
     * edited by the user (hash matches the .dsh-pristine marker written at extraction)
     * is fully refreshed from assets on every launch — bundled fixes reach the device.
     * A user-customized plugin is only filled in for missing files, never overwritten. */
    public static void extractSamples(Context context) {
        File root = new File(context.getFilesDir(), "plugins");
        if (!root.isDirectory() && !root.mkdirs()) return;
        try {
            String[] samples = context.getAssets().list("plugins");
            if (samples == null) return;
            for (String id : samples) {
                File dst = new File(root, id);
                if (!dst.isDirectory() && !dst.mkdirs()) return;
                boolean pristine = isPristine(context, "plugins/" + id, dst);
                copyAssetDir(context, "plugins/" + id, dst, pristine);
                writePristineMarker(context, "plugins/" + id, dst);
            }
        } catch (Exception e) {
            DebugLog.w("plugin sample extraction failed: " + e.getMessage());
        }
    }

    /** sha256 of the extracted manifest, hex-encoded, or null. */
    private static String manifestHash(File dstDir) {
        try {
            File mf = new File(dstDir, "manifest.json");
            if (!mf.isFile()) return null;
            byte[] b = java.nio.file.Files.readAllBytes(mf.toPath());
            return ioHash(b);
        } catch (Exception e) {
            return null;
        }
    }

    private static String ioHash(byte[] b) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Pristine = no manifest yet, or manifest hash equals the recorded extraction hash. */
    private static boolean isPristine(Context context, String assetDir, File dstDir) {
        String extracted = manifestHash(dstDir);
        if (extracted == null) return true; // fresh install
        File marker = new File(dstDir, ".dsh-pristine");
        if (!marker.isFile()) {
            // legacy extraction without a marker: pristine iff manifest matches bundled
            try (InputStream in = context.getAssets().open(assetDir + "/manifest.json")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                return extracted.equals(ioHash(bo.toByteArray()));
            } catch (Exception e) {
                return false;
            }
        }
        try {
            String recorded = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return recorded.equals(extracted);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writePristineMarker(Context context, String assetDir, File dstDir) {
        try {
            String h = manifestHash(dstDir);
            if (h == null) return;
            java.nio.file.Files.write(new File(dstDir, ".dsh-pristine").toPath(),
                    h.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
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
