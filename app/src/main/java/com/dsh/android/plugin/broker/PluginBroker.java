package com.dsh.android.plugin.broker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.dsh.android.plugin.PluginManager;
import com.dsh.android.util.DebugLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Loopback capability broker for sandbox script plugins. The guest reaches it at
 * 127.0.0.1:&lt;port&gt; (proroot shares the host network stack) via the `dsh` shell function.
 * Protocol: one request line "pluginId|capability|base64(payload)", one response line
 * "OK|base64(message)" or "ERR|base64(message)", then close. Every request is checked
 * against the plugin's user-granted capabilities before it is honored. */
public final class PluginBroker {

    private static final int BASE_PORT = 7901;
    private static final int MAX_PORT_TRIES = 20;

    private static volatile boolean started;
    private static volatile int port = -1;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private PluginBroker() { }

    public static synchronized void ensure(Context context) {
        if (started) return;
        for (int i = 0; i < MAX_PORT_TRIES; i++) {
            int p = BASE_PORT + i;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("127.0.0.1", p));
                port = p;
                started = true;
                ensureChannel(context);
                Thread t = new Thread(() -> acceptLoop(context, ss), "dsh-broker-accept");
                t.setDaemon(true);
                t.start();
                DebugLog.i("plugin broker listening on 127.0.0.1:" + p);
                return;
            } catch (Exception ignored) {
            }
        }
        DebugLog.w("plugin broker could not bind any port");
    }

    public static int port() { return port; }

    private static void acceptLoop(Context context, ServerSocket ss) {
        while (started) {
            try {
                Socket s = ss.accept();
                Thread t = new Thread(() -> handle(context.getApplicationContext(), s), "dsh-broker-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (started) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    private static void handle(Context context, Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            sock.setTcpNoDelay(true);
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null || line.length() > 8192) return;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) return;
            String pluginId = parts[0].trim();
            String cap = parts[1].trim();
            String payload = new String(java.util.Base64.getMimeDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            PluginManager pm = PluginManager.get();
            if (pm == null || !pm.granted(pluginId, cap)) {
                respond(sock, "ERR", b64("capability '" + cap + "' not granted to plugin '" + pluginId + "'"));
                return;
            }
            String result;
            switch (cap) {
                case "notify":
                    result = doNotify(context, pluginId, payload);
                    break;
                case "toast":
                    result = doToast(payload);
                    break;
                case "intent":
                    result = doIntent(context, payload);
                    break;
                case "clipboard":
                    result = doClipboard(context, payload);
                    break;
                case "device":
                    result = doDevice(context);
                    break;
                default:
                    result = "unknown capability '" + cap + "'";
                    break;
            }
            boolean ok = !result.startsWith("Error:");
            respond(sock, ok ? "OK" : "ERR", b64(result));
        } catch (Exception e) {
            DebugLog.w("broker conn failed: " + e.getMessage());
        }
    }

    private static String doNotify(Context context, String pluginId, String payload) {
        String title = payload;
        String body = "";
        int cut = payload.indexOf('|');
        if (cut >= 0) {
            title = payload.substring(0, cut);
            body = payload.substring(cut + 1);
        }
        if (title.isEmpty()) return "Error: empty title";
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) return "Error: notification permission not granted";
        Notification n = new Notification.Builder(context, "dsh_plugins")
                .setSmallIcon(com.dsh.android.R.drawable.ic_dsh_logo)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build();
        nm.notify(("plugin_" + pluginId).hashCode(), n);
        return "notification posted";
    }

    private static String doToast(String payload) {
        if (payload.isEmpty()) return "Error: empty message";
        main.post(() -> Toast.makeText(PluginManager.context(), payload, Toast.LENGTH_SHORT).show());
        return "toast shown";
    }

    /** payload = "kind|data": view|<url>, share|<text>, app|<package>. */
    private static String doIntent(Context context, String payload) {
        int cut = payload.indexOf('|');
        if (cut <= 0) return "Error: expected kind|data (view|share|app)";
        String kind = payload.substring(0, cut);
        String data = payload.substring(cut + 1);
        try {
            Intent i;
            switch (kind) {
                case "view":
                    if (data.isEmpty()) return "Error: empty url";
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(data));
                    break;
                case "share":
                    i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, data);
                    break;
                case "app":
                    if (data.isEmpty()) return "Error: empty package";
                    i = context.getPackageManager().getLaunchIntentForPackage(data.trim());
                    if (i == null) return "Error: no launcher activity for " + data.trim();
                    break;
                default:
                    return "Error: unknown intent kind '" + kind + "'";
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return "intent fired: " + kind;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String doClipboard(Context context, String payload) {
        if (payload.isEmpty()) return "Error: empty text";
        main.post(() -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", payload));
            }
        });
        return "copied to clipboard (" + payload.length() + " chars)";
    }

    private static String doDevice(Context context) {
        StringBuilder sb = new StringBuilder("{");
        try {
            android.content.Intent bat = context.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int level = bat.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                int status = bat.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                int plugged = bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                if (level >= 0 && scale > 0) {
                    sb.append("\"battery_pct\":").append(level * 100 / scale).append(',');
                }
                sb.append("\"charging\":").append(plugged != 0).append(',');
                sb.append("\"status\":\"").append(
                        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                                : status == android.os.BatteryManager.BATTERY_STATUS_FULL ? "full"
                                : "discharging").append('"');
            }
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                sb.append(",\"screen_on\":").append(pm.isInteractive());
            }
        } catch (Exception e) {
            sb.append("\"error\":\"").append(String.valueOf(e.getMessage())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static void ensureChannel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel("dsh_plugins", "Plugins",
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);
    }

    private static void respond(Socket s, String status, String b64payload) {
        try {
            OutputStream o = s.getOutputStream();
            o.write((status + "|" + b64payload + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (Exception ignored) {
        }
    }

    private static String b64(String s) {
        return java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
