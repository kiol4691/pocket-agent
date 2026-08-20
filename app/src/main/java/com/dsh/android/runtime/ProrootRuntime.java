package com.dsh.android.runtime;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs bash commands inside the Ubuntu rootfs using proroot (the LD_PRELOAD-based,
 * zero-ptrace proot replacement). Requires arm64-v8a and Android 8.0+.
 *
 * The launcher {@code libproroot.so} and its companions are exec'd straight from the APK
 * native library directory ({@code /data/app/.../lib/arm64}, SELinux type {@code apk_data_file}).
 * That is the only app-accessible location where exec() is still allowed on Android 10+
 * (targetSdk 29+): executing a binary that lives in the app's own writable data directory
 * ({@code app_data_file}) is blocked by the W^X hardening introduced in Android 10.
 *
 * Commands are launched via {@code exec setsid ...} so we can kill the whole process group
 * on timeout, avoiding orphaned children.
 */
public final class ProrootRuntime {

    public static final long MAX_OUTPUT_BYTES = 256 * 1024L;

    /* Persistent bash session (minimal-mode "persistent bash"): one long-lived proroot bash keeps
     * cwd/env/state across tool calls. Each call writes the command then a unique marker echo line;
     * the reader thread returns the output accumulated up to the marker. On per-call timeout the
     * whole session process group is killed and restarted lazily on the next call. */
    private static final long PB_DEFAULT_TIMEOUT_MS = 300_000L;
    private final Object pbLock = new Object();
    private Process pbProc;
    private java.io.Writer pbIn;
    private long pbPid;
    private String pbMarker;
    private StringBuilder pbOut;
    private boolean pbWait;
    private boolean pbDone;
    private String pbRc;
    private volatile boolean pbDead = true;

    private final File runDir;
    private final File rootfsDir;
    private final File workspaceDir;
    private final File tmpDir;
    private final File nativeLibDir;

    public ProrootRuntime(Context context) {
        File filesDir = context.getFilesDir();
        this.runDir = new File(filesDir, "run");
        this.rootfsDir = new File(filesDir, "rootfs");
        this.workspaceDir = new File(filesDir, "workspace");
        this.tmpDir = filesDir;
        this.nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public File rootfsDir() {
        return rootfsDir;
    }

    public File workspaceDir() {
        return workspaceDir;
    }

    /**
     * True when the user granted All-Files-Access (MANAGE_EXTERNAL_STORAGE). Only then can the
     * app process stat/read /storage/emulated/0 (Huawei's scoped storage denies even statfs
     * without it), so that's when we bind the shared storage into the rootfs at /sdcard.
     * Without it, Ubuntu still sees the phone's /data partition via {@code df -h /} because the
     * rootfs lives on /data.
     */
    public static boolean storageAccessGranted() {
        return android.os.Environment.isExternalStorageManager();
    }

    /** Bind points for the phone's shared storage, added only when All-Files-Access is granted. */
    private static final String HOST_STORAGE = "/storage/emulated/0";
    private static final String GUEST_STORAGE = "/sdcard";
    /** Rootfs-relative dir backing the guest mount point (a leading '/' would escape rootfsDir). */
    private static final String GUEST_STORAGE_REL = "sdcard";

    public void ensureDirectories() throws IOException {
        for (File d : new File[]{runDir, rootfsDir, workspaceDir}) {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot create directory " + d);
        }
    }

    /** Verify the proroot launcher and its companions exist in the APK native library dir. */
    public synchronized void verifyBinaries() throws IOException {
        ensureDirectories();
        File launcher = new File(nativeLibDir, "libproroot.so");
        if (!launcher.isFile()) {
            throw new IOException("proroot libraries not found in " + nativeLibDir
                    + ". Drop the 5 libproroot*.so files from the proroot release "
                    + "into app/src/main/jniLibs/arm64-v8a/ and rebuild, then retry setup.");
        }
    }

    public boolean binariesInstalled() {
        return new File(nativeLibDir, "libproroot.so").isFile();
    }

    /** Run a bash command; returns combined stdout+stderr (capped) with the exit code appended. */
    public synchronized String runBash(String command, long timeoutMs) {
        try {
            return runInternal(command, timeoutMs, "/bin/bash");
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Run via /bin/sh (dash). Bash needs libtinfo6 which is not in the base rootfs, so
     * first-time apt bootstrap must go through dash. */
    public synchronized String runSh(String command, long timeoutMs) {
        try {
            return runInternal(command, timeoutMs, "/bin/sh");
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Run a command in the shared persistent bash session; state (cwd, exports, packages) persists
     * across calls. Timeout kills and restarts the session. */
    public synchronized String runBashPersistent(String command, long timeoutMs) {
        try {
            return runPersistentInternal(command, timeoutMs);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runPersistentInternal(String command, long timeoutMs) throws IOException {
        if (command.trim().isEmpty()) return "Error: empty command";
        if (timeoutMs <= 0) timeoutMs = PB_DEFAULT_TIMEOUT_MS;
        ensurePersistentBash();

        StringBuilder out = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean done, timedOut;
        synchronized (pbLock) {
            pbOut = out;
            pbRc = null;
            pbDone = false;
            pbWait = true;
            try {
                pbIn.write(command);
                pbIn.write("\n");
                pbIn.write("echo \"");
                pbIn.write(pbMarker);
                pbIn.write("\"=$?\n");
                pbIn.flush();
            } catch (IOException e) {
                pbWait = false;
                pbDead = true;
                return "Error: could not write to persistent shell: " + e.getMessage();
            }
            while (!pbDone && System.currentTimeMillis() < deadline) {
                try {
                    pbLock.wait(Math.max(20L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            done = pbDone;
            timedOut = !done;
            pbWait = false;
        }

        String text = out.toString().trim();
        if (done && pbRc != null) {
            String rc = pbRc.startsWith("=") ? pbRc.substring(1) : pbRc;
            return (text.isEmpty() ? "(no output)" : text) + "\n[exit code: " + rc + "]";
        }
        if (done) {
            killPersistentBash();
            return (text.isEmpty() ? "(no output)" : text) + "\n[persistent shell exited; it will restart on the next call]";
        }
        killPersistentBash();
        return "TIMEOUT after " + timeoutMs + " ms; the persistent shell was killed and restarts on the next call.\n"
                + (text.isEmpty() ? "(no output)" : text);
    }

    /** Spawn the single persistent bash (setsid'd so the whole group can be killed on timeout). */
    private void ensurePersistentBash() throws IOException {
        if (!pbDead && pbProc != null && pbProc.isAlive()) return;
        ensureDirectories();
        File launcher = new File(nativeLibDir, "libproroot.so");
        if (!launcher.isFile()) throw new IOException("proroot not installed; run runtime setup first.");
        if (!rootfsDir.isDirectory()) throw new IOException("Ubuntu rootfs not installed; run runtime setup first.");

        File guestWorkspace = new File(rootfsDir, "root/workspace");
        if (!guestWorkspace.isDirectory() && !guestWorkspace.mkdirs()) {
            throw new IOException("Cannot create " + guestWorkspace);
        }

        List<String> args = new ArrayList<>();
        args.add(launcher.getAbsolutePath());
        args.add("-r");
        args.add(rootfsDir.getAbsolutePath());
        args.add("-0");
        args.add("--link2symlink");
        args.add("-b");
        args.add("/proc:/proc");
        args.add("-b");
        args.add("/dev:/dev");
        args.add("-b");
        args.add("/sys:/sys");
        args.add("-b");
        args.add(workspaceDir.getAbsolutePath() + ":/root/workspace");
        if (storageAccessGranted()) {
            File guestSdcard = new File(rootfsDir, GUEST_STORAGE_REL);
            if (guestSdcard.isDirectory() || guestSdcard.mkdirs()) {
                args.add("-b");
                args.add(HOST_STORAGE + ":" + GUEST_STORAGE);
            }
        }
        args.add("-w");
        args.add("/root/workspace");
        args.add("/bin/bash");
        String quoted = q(args);

        File pidFile = new File(runDir, "persistent.pid");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                "printf '%s\\n' \"$$\" > " + shq(pidFile.getAbsolutePath()) + "; exec setsid " + quoted);
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("PROROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("HOME", "/root");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("DEBIAN_FRONTEND", "noninteractive");

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("Error launching persistent proroot bash: " + e.getMessage());
        }
        long pid = readPidSoon(pidFile);

        pbProc = proc;
        pbPid = pid;
        pbDead = false;
        pbMarker = "__DSH_RC_" + Integer.toHexString((int) (Math.random() * 0xffffff)) + "__";
        pbIn = new java.io.OutputStreamWriter(proc.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
        startPersistentReader(proc);
    }

    private void startPersistentReader(Process proc) {
        Thread t = new Thread(() -> {
            InputStream is = proc.getInputStream();
            byte[] line = new byte[16384];
            int used = 0;
            try {
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\n') {
                        deliverPersistentLine(line, used);
                        used = 0;
                    } else if (used < line.length) {
                        line[used++] = (byte) c;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
                synchronized (pbLock) {
                    if (used > 0) {
                        String s = new String(line, 0, used, java.nio.charset.StandardCharsets.UTF_8);
                        if (pbWait && !s.startsWith(pbMarker)) appendCappedLine(pbOut, s);
                        else if (pbWait && s.startsWith(pbMarker)) {
                            pbRc = s.substring(pbMarker.length());
                            pbDone = true;
                        }
                    }
                    pbDead = true;
                    pbDone = true;
                    pbLock.notifyAll();
                }
            }
        }, "proroot-persistent-reader");
        t.setDaemon(true);
        t.start();
    }

    private void deliverPersistentLine(byte[] line, int used) {
        String s = new String(line, 0, used, java.nio.charset.StandardCharsets.UTF_8);
        synchronized (pbLock) {
            if (!pbWait) return;
            if (s.startsWith(pbMarker)) {
                pbRc = s.substring(pbMarker.length());
                pbDone = true;
                pbLock.notifyAll();
            } else {
                appendCappedLine(pbOut, s);
            }
        }
    }

    private static void appendCappedLine(StringBuilder out, String line) {
        if (out.length() >= MAX_OUTPUT_BYTES) return;
        int allowed = (int) Math.min(line.length(), MAX_OUTPUT_BYTES - out.length());
        out.append(line, 0, allowed);
        if (out.length() >= MAX_OUTPUT_BYTES) out.append("\n…[output truncated]");
        out.append('\n');
    }

    private void killPersistentBash() {
        if (pbPid > 0) killProcessGroup(pbPid);
        if (pbProc != null) {
            try {
                pbProc.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        try {
            if (pbIn != null) pbIn.close();
        } catch (IOException ignored) {
        }
        pbProc = null;
        pbPid = 0;
        pbDead = true;
    }

    private String runInternal(String command, long timeoutMs, String shell) throws IOException {
        ensureDirectories();
        File launcher = new File(nativeLibDir, "libproroot.so");
        if (!launcher.isFile()) throw new IOException("proroot not installed; run runtime setup first.");
        if (!rootfsDir.isDirectory()) throw new IOException("Ubuntu rootfs not installed; run runtime setup first.");

        File guestWorkspace = new File(rootfsDir, "root/workspace");
        if (!guestWorkspace.isDirectory() && !guestWorkspace.mkdirs()) {
            throw new IOException("Cannot create " + guestWorkspace);
        }

        List<String> args = new ArrayList<>();
        args.add(launcher.getAbsolutePath());
        args.add("-r");
        args.add(rootfsDir.getAbsolutePath());
        args.add("-0");
        args.add("--link2symlink");
        args.add("-b");
        args.add("/proc:/proc");
        args.add("-b");
        args.add("/dev:/dev");
        args.add("-b");
        args.add("/sys:/sys");
        args.add("-b");
        args.add(workspaceDir.getAbsolutePath() + ":/root/workspace");
        if (storageAccessGranted()) {
            File guestSdcard = new File(rootfsDir, GUEST_STORAGE_REL);
            if (guestSdcard.isDirectory() || guestSdcard.mkdirs()) {
                args.add("-b");
                args.add(HOST_STORAGE + ":" + GUEST_STORAGE);
            }
        }
        args.add("-w");
        args.add("/root/workspace");
        args.add(shell);
        args.add("-c");
        args.add(command);
        String quoted = q(args);

        // exec setsid: the launcher keeps sh's pid, becomes session+group leader, and the
        // whole group (bash + descendants) can be killed on timeout with kill -9 -<pid>.
        // The launcher pid is captured in a pidfile by the wrapper shell (Process.pid()
        // needs core library desugaring and is deliberately avoided).
        File pidFile = new File(runDir, "last.pid");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                "printf '%s\\n' \"$$\" > " + shq(pidFile.getAbsolutePath()) + "; exec setsid " + quoted);
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("PROROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("HOME", "/root");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("DEBIAN_FRONTEND", "noninteractive");

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return "Error launching proroot: " + e.getMessage();
        }
        long pid = readPidSoon(pidFile);

        StringBuilder out = new StringBuilder();
        AtomicBoolean done = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (InputStream is = proc.getInputStream()) {
                int n;
                while ((n = is.read(buf)) != -1) appendCapped(out, buf, n);
            } catch (IOException ignored) {
            } finally {
                done.set(true);
            }
        }, "proroot-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean timedOut = false;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                timedOut = true;
                break;
            }
        }
        if (!done.get()) timedOut = true;

        long grace = Math.min(timeoutMs, 30_000L);
        if (timedOut) {
            proc.destroy();
            try {
                proc.waitFor(grace, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            if (proc.isAlive()) {
                proc.destroyForcibly();
                if (pid > 0) killProcessGroup(pid);
            }
        } else {
            try {
                proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int exit = proc.exitValue();
        StringBuilder result = new StringBuilder();
        if (timedOut) result.append("TIMEOUT after ").append(timeoutMs).append(" ms; process group was killed.\n");
        final String text;
        synchronized (out) {
            text = out.toString().trim();
        }
        result.append(text.isEmpty() ? "(no output)" : text);
        result.append("\n[exit code: ").append(exit).append("]");
        return result.toString();
    }

    private static void appendCapped(StringBuilder out, byte[] buf, int n) {
        synchronized (out) {
            if (out.length() < MAX_OUTPUT_BYTES) {
                int allowed = (int) Math.min(n, MAX_OUTPUT_BYTES - out.length());
                out.append(new String(buf, 0, allowed, java.nio.charset.StandardCharsets.UTF_8));
                if (out.length() >= MAX_OUTPUT_BYTES) {
                    out.append("\n…[output truncated]");
                }
            }
        }
    }

    private static void killProcessGroup(long pid) {
        runQuiet(new String[]{"/system/bin/toybox", "kill", "-9", "-" + pid});
        runQuiet(new String[]{"/system/bin/sh", "-c", "kill -9 -" + pid + " 2>/dev/null"});
    }

    private static void runQuiet(String[] cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static long readPidSoon(File pidFile) {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            String s = readFirstLine(pidFile);
            if (s != null) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    private static String readFirstLine(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return r.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String q(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(shq(a));
        }
        return sb.toString();
    }
}