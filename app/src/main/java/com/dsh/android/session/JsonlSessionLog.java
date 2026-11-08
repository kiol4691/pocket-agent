package com.dsh.android.session;

import com.dsh.android.agent.ChatMessage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Append-only JSONL session transcript (mirrors the upstream jsonl session format). */
public final class JsonlSessionLog {

    private final File file;

    public JsonlSessionLog(File sessionsDir) throws IOException {
        if (!sessionsDir.isDirectory() && !sessionsDir.mkdirs()) {
            throw new IOException("Cannot create session dir " + sessionsDir);
        }
        String base = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File f = new File(sessionsDir, base + ".jsonl");
        int n = 1;
        while (f.exists()) {
            f = new File(sessionsDir, base + "-" + (n++) + ".jsonl");
        }
        this.file = f;
    }

    /** Resume an existing session file: new messages append to it. */
    public JsonlSessionLog(File existingFile, boolean resumeExisting) {
        this.file = existingFile;
    }

    public File file() {
        return file;
    }

    public synchronized void append(ChatMessage msg) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            w.write(msg.toLogJson().toString());
            w.write('\n');
        } catch (org.json.JSONException e) {
            throw new IOException("Message serialization failed", e);
        }
    }

    /** 只解析最后 maxLines 行：启动重放用，避免把整份日志都反序列化一遍。 */
    public static List<ChatMessage> loadTail(File f, int maxLines) throws IOException {
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (tail.size() == maxLines) tail.pollFirst();
                tail.addLast(line);
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        for (String s : tail) {
            try {
                out.add(ChatMessage.fromJson(new JSONObject(s)));
            } catch (org.json.JSONException ignored) {
            }
        }
        return out;
    }

    /** 只数行数，不解析 JSON：用于判断是否有更早的消息需要折叠。 */
    public static int countLines(File f) throws IOException {
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) n++;
            }
        }
        return n;
    }

    /** 只读前 scanLines 行找第一条 user 文本：侧栏会话标题用，避免整份日志解析。 */
    public static String firstUserText(File f, int scanLines) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < scanLines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    if (!"user".equals(o.optString("role"))) continue;
                    Object cv = o.opt("content");
                    if (cv instanceof String) {
                        String c = (String) cv;
                        if (!c.trim().isEmpty()) return c;
                    }
                } catch (org.json.JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public static List<ChatMessage> load(File f) throws IOException {
        List<ChatMessage> msgs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    msgs.add(ChatMessage.fromJson(new JSONObject(line)));
                } catch (org.json.JSONException ignored) {
                }
            }
        }
        return msgs;
    }
}