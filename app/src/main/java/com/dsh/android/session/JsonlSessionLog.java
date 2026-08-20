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
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jsonl";
        this.file = new File(sessionsDir, name);
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