package com.dsh.android.runtime;

import android.content.Context;

import com.dsh.android.util.IOUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Installs and bootstraps the Ubuntu arm64 (glibc) rootfs that proroot requires. */
public final class UbuntuRootfs {

    public interface Progress {
        void onProgress(String message);
    }

    public static final String RELEASE = "24.04";
    /** Apt dist paths use the codename, not the release number (dists/noble/..., not dists/24.04/...). */
    public static final String CODENAME = "noble";
    /** Written after a successful extraction; lets us detect rootfs from a buggy extractor. */
    public static final String EXTRACT_MARKER = ".dsh-extract-v2";
    /**
     * Written after the base packages were installed successfully. Absent when apt failed, so
     * setup() re-runs the apt install on the next attempt (self-healing).
     */
    public static final String APT_MARKER = ".dsh-apt-ok";
    /**
     * The ubuntu-base tarball bundled in the APK (app/src/main/assets), still gzip-compressed on
     * disk but named .bin: AGP 8's asset merger re-encodes any asset whose extension is .gz/.tgz
     * (it gunzips it and strips the extension, so getAssets().open() then fails). With a .bin name
     * the gzip bytes are bundled untouched and opened as a stream.
     */
    public static final String BUNDLED_ASSET = "ubuntu-base-arm64.bin";
    /** Candidates tried in order (fallback when the bundled asset is missing); first that returns HTTP 200 wins. TUNA first for CN networks. */
    public static final String[] ROOTFS_URLS = {
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/"
                    + RELEASE + "/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/"
                    + RELEASE + "/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/"
                    + RELEASE + "/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
    };

    public static final String[] BASE_PACKAGES = {
            "bash", "coreutils", "ca-certificates", "git", "curl", "wget",
            "python3", "python3-pip", "unzip", "zip", "jq", "file", "less"
    };

    /** How many times apt update+install is retried before giving up (flaky CN networks). */
    public static final int AUTO_INSTALL_ATTEMPTS = 3;
    private static final long AUTO_INSTALL_TIMEOUT_MS = 30 * 60_000L;

    private final Context context;
    private final File rootfsDir;
    private final ProrootRuntime proroot;
    private final OkHttpClient http;

    public UbuntuRootfs(ProrootRuntime proroot, Context context) {
        this.proroot = proroot;
        this.context = context.getApplicationContext();
        this.rootfsDir = proroot.rootfsDir();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public boolean installed() {
        return new File(rootfsDir, "bin/bash").isFile()
                && new File(rootfsDir, EXTRACT_MARKER).isFile();
    }

    /** True when the rootfs is extracted and the common packages were installed (setup complete). */
    public boolean fullyInstalled() {
        return installed() && new File(rootfsDir, APT_MARKER).isFile();
    }

    /** Full first-time setup: extract rootfs, resolv.conf, install base packages (idempotent). */
    public synchronized void bootstrap(Progress progress) throws IOException {
        proroot.ensureDirectories();
        com.dsh.android.util.DebugLog.i( "bootstrap: installed? " + installed());
        if (fullyInstalled()) {
            progress.onProgress("Runtime ready.");
            return;
        }
        if (!installed()) {
            if (new File(rootfsDir, "bin/bash").isFile() || rootfsDir.isDirectory()) {
                com.dsh.android.util.DebugLog.i( "bootstrap: discarding stale/corrupt rootfs");
                com.dsh.android.util.IOUtil.deleteRecursively(rootfsDir);
            }
            if (!extractBundledRootfs(progress)) {
                progress.onProgress("Downloading Ubuntu " + RELEASE + " arm64 base rootfs…");
                File tarball = new File(rootfsDir.getParentFile(), "ubuntu-base.tar.gz");
                download(ROOTFS_URLS, tarball, progress);
                com.dsh.android.util.DebugLog.i( "bootstrap: downloaded, size=" + tarball.length());
                progress.onProgress("Extracting rootfs (this can take a minute)…");
                com.dsh.android.util.IOUtil.extractTarGz(tarball, rootfsDir);
                tarball.delete();
            }
            com.dsh.android.util.IOUtil.writeText(
                    new File(rootfsDir, EXTRACT_MARKER), "ok\n");
        }
        configureNetwork();
        String pkgs = String.join(" ", BASE_PACKAGES);
        String aptOpts = "--no-install-recommends "
                + "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold";
        // Base rootfs lacks libtinfo6 (bash's dependency) until apt runs, so bootstrap via dash.
        // runSh exit code is useless here ('; echo RC=$rc' makes the shell exit 0), so check RC=$rc.
        boolean ok = false;
        String res = "";
        for (int attempt = 1; attempt <= AUTO_INSTALL_ATTEMPTS && !ok; attempt++) {
            progress.onProgress("Installing packages (" + attempt + "/" + AUTO_INSTALL_ATTEMPTS
                    + "): " + pkgs + " …");
            String fix = attempt > 1 ? "dpkg --configure -a; apt-get -f install -y; " : "";
            String cmd = "rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*; " + fix
                    + "apt-get update -y && apt-get install -y " + aptOpts + " " + pkgs
                    + " > /root/workspace/apt-install.log 2>&1; rc=$?; "
                    + "echo '===APTLOG==='; tail -30 /root/workspace/apt-install.log; echo RC=$rc";
            com.dsh.android.util.DebugLog.i("bootstrap: apt attempt " + attempt + " cmd len=" + cmd.length());
            res = proroot.runSh(cmd, AUTO_INSTALL_TIMEOUT_MS);
            ok = res.contains("RC=0");
            if (!ok) {
                com.dsh.android.util.DebugLog.w("bootstrap: apt attempt " + attempt + " failed, retrying");
            }
        }
        com.dsh.android.util.DebugLog.i("bootstrap: apt result tail=" + IOUtil.truncate(res, 3000));
        if (ok) {
            IOUtil.writeText(new File(rootfsDir, APT_MARKER), "ok\n");
            progress.onProgress("Runtime ready.");
        } else {
            progress.onProgress("Package install reported a problem:\n" + IOUtil.truncate(res, 1200));
        }
    }

    /**
     * Extract the ubuntu-base tarball bundled in the APK assets. Returns false when the asset is
     * missing (so the caller falls back to downloading); other extraction failures propagate.
     */
    private boolean extractBundledRootfs(Progress progress) throws IOException {
        try (InputStream in = context.getAssets().open(BUNDLED_ASSET)) {
            progress.onProgress("Extracting bundled Ubuntu " + RELEASE + " rootfs…");
            com.dsh.android.util.DebugLog.i("bootstrap: extracting bundled rootfs asset");
            com.dsh.android.util.IOUtil.extractTarGz(in, rootfsDir);
            return true;
        } catch (FileNotFoundException e) {
            com.dsh.android.util.DebugLog.w("bootstrap: bundled rootfs asset " + BUNDLED_ASSET
                    + " not found, falling back to download");
            return false;
        }
    }

    /**
     * The base image ships an empty/dangling {@code /etc/resolv.conf} (symlink on 24.04) and a
     * slow default apt source (https + ports.ubuntu.com deb822, which needs ca-certificates we
     * don't have yet). Replace both: write a regular resolv.conf with CN-friendly DNS, disable
     * the default deb822 source, and point apt at the official http mirror with signature
     * checking disabled so it works before ca-certificates are installed.
     */
    private void configureNetwork() throws IOException {
        File rc = new File(rootfsDir, "etc/resolv.conf");
        if (rc.exists() || java.nio.file.Files.isSymbolicLink(rc.toPath())) {
            rc.delete();
        }
        IOUtil.writeText(rc, "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 114.114.114.114\n"
                + "options timeout:2 attempts:2 rotate\n");

        File sourcesListD = new File(rootfsDir, "etc/apt/sources.list.d");
        File ubuntuSources = new File(sourcesListD, "ubuntu.sources");
        if (ubuntuSources.exists() || java.nio.file.Files.isSymbolicLink(ubuntuSources.toPath())) {
            ubuntuSources.delete();
        }

        File sources = new File(rootfsDir, "etc/apt/sources.list");
        sources.getParentFile().mkdirs();
        IOUtil.writeText(sources,
                "deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports/ " + CODENAME + " main universe\n"
                        + "deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports/ " + CODENAME
                        + "-updates main universe\n"
                        + "deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports/ " + CODENAME
                        + "-security main universe\n");

        File hosts = new File(rootfsDir, "etc/hosts");
        IOUtil.writeText(hosts,
                "127.0.0.1 localhost\n"
                        + "::1 localhost ip6-localhost ip6-loopback\n"
                        + "104.20.28.246 ports.ubuntu.com\n"
                        + "172.66.152.176 ports.ubuntu.com\n"
                        + "185.125.190.82 ports.ubuntu.com\n"
                        + "91.189.91.82 archive.ubuntu.com\n"
                        + "185.125.190.83 archive.ubuntu.com\n");

        File aptConf = new File(rootfsDir, "etc/apt/apt.conf.d/99-dsh");
        IOUtil.writeText(aptConf,
                "Acquire::Retries \"3\";\n"
                        + "Acquire::Retry-Spacing \"3\";\n"
                        + "Acquire::http::Timeout \"30\";\n"
                        + "Acquire::https::Timeout \"30\";\n"
                        + "Acquire::Languages \"none\";\n"
                        + "Acquire::CompressionTypes::Order \"gz\";\n");
    }

    private void download(String[] urls, File dst, Progress progress) throws IOException {
        IOException last = null;
        for (String url : urls) {
            try {
                downloadOne(url, dst, progress);
                return;
            } catch (IOException e) {
                last = e;
                com.dsh.android.util.DebugLog.w( "rootfs download failed for " + url + ": " + e.getMessage());
                dst.delete();
            }
        }
        throw new IOException("All rootfs mirrors failed. Last error: "
                + (last != null ? last.getMessage() : "unknown"));
    }

    private void downloadOne(String url, File dst, Progress progress) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Download failed HTTP " + resp.code() + " for " + url);
            }
            if (resp.body() == null) throw new IOException("Empty download body");
            long total = resp.body().contentLength();
            long got = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = resp.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(dst)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    got += n;
                    if (total > 0 && got % (256 * 1024) == 0) {
                        progress.onProgress(String.format(Locale.US, "Downloading… %d%%",
                                (int) (got * 100 / total)));
                    }
                }
            }
        }
    }
}