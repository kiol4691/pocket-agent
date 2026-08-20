package com.dsh.android.llm;

import com.dsh.android.agent.ChatMessage;
import com.dsh.android.agent.ToolCall;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Streaming OpenAI-compatible chat-completions client with tool calling (DeepSeek API by default). */
public final class DeepSeekClient {

    public interface Listener {
        void onTextDelta(String delta);

        void onReasoningDelta(String delta);
    }

    public static final class AssistantResult {
        public final String content;
        public final List<ToolCall> toolCalls;
        public final String finishReason;
        public final String reasoning;

        AssistantResult(String content, List<ToolCall> toolCalls, String finishReason, String reasoning) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
            this.reasoning = reasoning;
        }
    }

    private static final class ToolAccumulator {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    private final OkHttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public DeepSeekClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /** Call the model. Blocks until the turn completes (streaming deltas via {@code listener}). */
    public AssistantResult chat(List<ChatMessage> messages, JSONArray toolSchemas,
                                Listener listener, AtomicBoolean cancelFlag) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", true);
        body.put("temperature", 0.2);
        body.put("tool_choice", "auto");
        JSONArray msgs = new JSONArray();
        for (ChatMessage m : messages) msgs.put(m.toJson());
        body.put("messages", msgs);
        if (toolSchemas != null && toolSchemas.length() > 0) body.put("tools", toolSchemas);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("DeepSeek API " + response.code() + ": " + err);
            }
            if (response.body() == null) throw new IOException("Empty response body");

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            TreeMap<Integer, ToolAccumulator> tools = new TreeMap<>();
            String finishReason = "";

            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelFlag != null && cancelFlag.get()) break;
                String s = line.trim();
                if (!s.startsWith("data:")) continue;
                String data = s.substring(5).trim();
                if (data.equals("[DONE]")) break;
                JSONObject chunk = new JSONObject(data);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) continue;
                String fr = safe(choice, "finish_reason", "");
                if (!fr.isEmpty()) finishReason = fr;

                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;
                String rz = safe(delta, "reasoning_content", "");
                if (!rz.isEmpty()) {
                    reasoning.append(rz);
                    if (listener != null) listener.onReasoningDelta(rz);
                }
                String txt = safe(delta, "content", "");
                if (!txt.isEmpty()) {
                    content.append(txt);
                    if (listener != null) listener.onTextDelta(txt);
                }
                JSONArray tcs = delta.optJSONArray("tool_calls");
                if (tcs != null) {
                    for (int i = 0; i < tcs.length(); i++) {
                        JSONObject tc = tcs.optJSONObject(i);
                        if (tc == null) continue;
                        int index = tc.optInt("index", i);
                        ToolAccumulator acc = tools.get(index);
                        if (acc == null) {
                            acc = new ToolAccumulator();
                            tools.put(index, acc);
                        }
                        String idv = safe(tc, "id", "");
                        if (!idv.isEmpty()) acc.id = idv;
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null) {
                            String nm = safe(fn, "name", "");
                            if (!nm.isEmpty()) acc.name = nm;
                            String argsPart = safe(fn, "arguments", "");
                            if (!argsPart.isEmpty()) acc.args.append(argsPart);
                        }
                    }
                }
            }

            List<ToolCall> calls = new ArrayList<>();
            for (Map.Entry<Integer, ToolAccumulator> e : tools.entrySet()) {
                ToolAccumulator a = e.getValue();
                if (a.name.isEmpty()) continue;
                calls.add(new ToolCall(a.id.isEmpty() ? "call_" + e.getKey() : a.id, a.name, a.args.toString()));
            }
            return new AssistantResult(content.toString(), calls, finishReason,
                    reasoning.length() == 0 ? null : reasoning.toString());
        }
    }

    /** optString but JSON nulls map to the fallback instead of the literal string "null". */
    private static String safe(JSONObject o, String key, String fallback) {
        return o.has(key) && !o.isNull(key) ? o.optString(key) : fallback;
    }
}