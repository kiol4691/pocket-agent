package com.dsh.android.agent;

import com.dsh.android.util.ImageStore;

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
    /** 用户消息附带的图片：app 私有目录里的 JPEG 路径。仅 user 消息用。 */
    public final List<String> imagePaths;
    /** 用户引用的文件（guest 绝对路径，如 /root/workspace/refs/a.pdf），内容交给模型用工具读。 */
    public final List<String> refs;

    private ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                        String reasoning) {
        this(role, content, toolCalls, toolCallId, reasoning, null, null);
    }

    private ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                        String reasoning, List<String> imagePaths) {
        this(role, content, toolCalls, toolCallId, reasoning, imagePaths, null);
    }

    private ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                        String reasoning, List<String> imagePaths, List<String> refs) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.reasoning = reasoning;
        this.imagePaths = imagePaths;
        this.refs = refs;
    }

    public static ChatMessage system(String s) {
        return new ChatMessage("system", s, null, null, null);
    }

    public static ChatMessage user(String s) {
        return new ChatMessage("user", s, null, null, null);
    }

    /** 带图片的用户消息：content 会转成 text + image_url 的多模态数组。 */
    public static ChatMessage user(String s, List<String> imagePaths) {
        return new ChatMessage("user", s, null, null, null, imagePaths);
    }

    /** 带引用文件的用户消息：引用以绝对路径清单附在正文后面，内容由模型用工具读取。 */
    public static ChatMessage userWithRefs(String s, List<String> refs) {
        return new ChatMessage("user", s, null, null, null, null, refs);
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

    private JSONObject skeleton() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("role", role);
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

    /** 发给模型的正文：引用以路径清单附在末尾，模型据此用 read / read_image / bash 去读。 */
    private String contentForApi() {
        if (refs == null || refs.isEmpty()) return content;
        StringBuilder sb = new StringBuilder(content == null ? "" : content);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("[Referenced files \u2014 read them with the read / read_image / bash tools]\n");
        for (String r : refs) sb.append("- ").append(r).append('\n');
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = skeleton();
        String apiContent = contentForApi();
        if (imagePaths != null && !imagePaths.isEmpty()) {
            JSONArray parts = new JSONArray();
            if (apiContent != null && !apiContent.isEmpty()) {
                JSONObject t = new JSONObject();
                t.put("type", "text");
                t.put("text", apiContent);
                parts.put(t);
            }
            for (String p : imagePaths) {
                String uri = ImageStore.dataUri(p);
                if (uri == null) continue;      // 文件丢了就跳过，别让整条消息发不出去
                JSONObject iu = new JSONObject();
                iu.put("url", uri);
                JSONObject im = new JSONObject();
                im.put("type", "image_url");
                im.put("image_url", iu);
                parts.put(im);
            }
            o.put("content", parts);
        } else if (apiContent != null) {
            o.put("content", apiContent);
        }
        return o;
    }

    /**
     * Session-log serialization: 图片只记路径，不把 base64 写进 JSONL，
     * 否则几张图就能把会话日志撑到几 MB。重放时按路径读回。
     */
    public JSONObject toLogJson() throws JSONException {
        JSONObject o = skeleton();
        if (content != null) o.put("content", content);
        if (imagePaths != null && !imagePaths.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String p : imagePaths) arr.put(p);
            o.put("images", arr);
        }
        if (refs != null && !refs.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String r : refs) arr.put(r);
            o.put("refs", arr);
        }
        if (reasoning != null && !reasoning.isEmpty()) o.put("reasoning", reasoning);
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) throws JSONException {
        String role = o.optString("role");
        Object cv = o.opt("content");
        String content;
        if (cv instanceof String) {
            content = (String) cv;
        } else if (cv instanceof JSONArray) {
            JSONArray arr = (JSONArray) cv;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && "text".equals(p.optString("type"))) {
                    sb.append(p.optString("text"));
                }
            }
            content = sb.toString();
        } else {
            content = null;
        }
        String toolCallId = o.has("tool_call_id") && !o.isNull("tool_call_id")
                ? o.optString("tool_call_id") : null;
        String reasoning = o.has("reasoning") && !o.isNull("reasoning")
                ? o.optString("reasoning") : null;
        List<String> imagePaths = null;
        JSONArray imgs = o.optJSONArray("images");
        if (imgs != null && imgs.length() > 0) {
            imagePaths = new ArrayList<>();
            for (int i = 0; i < imgs.length(); i++) imagePaths.add(imgs.optString(i));
        }
        List<String> refs = null;
        JSONArray refArr = o.optJSONArray("refs");
        if (refArr != null && refArr.length() > 0) {
            refs = new ArrayList<>();
            for (int i = 0; i < refArr.length(); i++) refs.add(refArr.optString(i));
        }
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
        return new ChatMessage(role, content, calls, toolCallId, reasoning, imagePaths, refs);
    }
}
