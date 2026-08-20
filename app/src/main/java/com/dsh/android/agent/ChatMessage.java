package com.dsh.android.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A chat message in OpenAI/DeepSeek chat-completions format. */
public final class ChatMessage {

    public final String role;      // system | user | assistant | tool
    public final String content;
    public final List<ToolCall> toolCalls; // assistant only
    public final String toolCallId;        // tool only
    public final String reasoning;         // assistant only, session log only (not sent to API)

    private ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String reasoning) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.reasoning = reasoning;
    }

    public static ChatMessage system(String s) {
        return new ChatMessage("system", s, null, null, null);
    }

    public static ChatMessage user(String s) {
        return new ChatMessage("user", s, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls, String reasoning) {
        return new ChatMessage("assistant", content, calls, null, reasoning);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId, null);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("role", role);
        if (content != null) o.put("content", content);
        if (toolCallId != null) o.put("tool_call_id", toolCallId);
        if (toolCalls != null) {
            JSONArray arr = new JSONArray();
            for (ToolCall tc : toolCalls) {
                JSONObject fn = new JSONObject();
                fn.put("name", tc.name);
                fn.put("arguments", tc.arguments);
                JSONObject entry = new JSONObject();
                entry.put("id", tc.id);
                entry.put("type", "function");
                entry.put("function", fn);
                arr.put(entry);
            }
            o.put("tool_calls", arr);
        }
        return o;
    }

    /** Session-log serialization: like {@link #toJson()} plus the local-only reasoning field. */
    public JSONObject toLogJson() throws JSONException {
        JSONObject o = toJson();
        if (reasoning != null && !reasoning.isEmpty()) o.put("reasoning", reasoning);
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) throws JSONException {
        String role = o.optString("role");
        String content = o.has("content") && !o.isNull("content") ? o.optString("content") : null;
        String toolCallId = o.has("tool_call_id") && !o.isNull("tool_call_id") ? o.optString("tool_call_id") : null;
        String reasoning = o.has("reasoning") && !o.isNull("reasoning") ? o.optString("reasoning") : null;
        List<ToolCall> calls = null;
        JSONArray arr = o.optJSONArray("tool_calls");
        if (arr != null) {
            calls = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject tc = arr.optJSONObject(i);
                JSONObject fn = tc != null ? tc.optJSONObject("function") : null;
                calls.add(new ToolCall(
                        tc != null ? tc.optString("id", "call_" + i) : "call_" + i,
                        fn != null ? fn.optString("name", "") : "",
                        fn != null ? fn.optString("arguments", "") : ""));
            }
        }
        return new ChatMessage(role, content, calls, toolCallId, reasoning);
    }
}