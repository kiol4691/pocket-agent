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

    public JSONArray schemas() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Tool t : tools.values()) arr.put(t.schema());
        return arr;
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