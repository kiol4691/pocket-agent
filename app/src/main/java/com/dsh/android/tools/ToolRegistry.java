package com.dsh.android.tools;

import com.dsh.android.agent.ToolCall;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool t) {
        tools.put(t.name(), t);
    }

    /** 当前已注册的工具名（插件工具注册前用它判断重名）。 */
    public java.util.Set<String> names() {
        return new java.util.LinkedHashSet<>(tools.keySet());
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public java.util.Collection<Tool> all() {
        return new java.util.ArrayList<>(tools.values());
    }

    public JSONArray schemas() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Tool t : tools.values()) arr.put(t.schema());
        return arr;
    }

    /** 结构化执行：文本 + 可选图片附件。失败也转成文本，让模型能反应。 */
    public Tool.Result executeRich(ToolCall call) {
        Tool t = tools.get(call.name);
        if (t == null) {
            return Tool.Result.of("Error: unknown tool '" + call.name + "'. Available: " + tools.keySet());
        }
        try {
            return t.executeRich(call.arguments);
        } catch (Exception e) {
            return Tool.Result.of("Error executing " + call.name + ": " + e.getMessage());
        }
    }

    /** Execute a tool call; failures are returned as text so the model can react. */
    public String execute(ToolCall call) {
        Tool t = tools.get(call.name);
        if (t == null) return "Error: unknown tool '" + call.name + "'. Available: " + tools.keySet();
        try {
            return t.execute(call.arguments);
        } catch (Exception e) {
            return "Error executing " + call.name + ": " + e.getMessage();
        }
    }
}