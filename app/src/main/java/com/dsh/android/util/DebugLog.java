package com.dsh.android.util;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes to {@code filesDir/dsh.log}. Use for on-device diagnosis when logcat is unreliable. */
public final class DebugLog {

    private static File logFile;

    public static void init(Context ctx) {
        logFile = new File(ctx.getFilesDir(), "dsh.log");
    }

    public static void i(String msg) {
        write("I", msg);
    }

    public static void w(String msg) {
        write("W", msg);
    }

    public static void e(String msg, Throwable t) {
        write("E", msg + (t != null ? "\n  " + t : ""));
    }

    private static void write(String level, String msg) {
        android.util.Log.i("DSH", "[" + level + "] " + msg);
        if (logFile == null) return;
        try {
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = ts + " " + level + " " + msg + "\n";
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.write(line);
            fw.close();
        } catch (Exception ignored) {
        }
    }
}