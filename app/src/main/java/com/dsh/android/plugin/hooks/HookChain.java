package com.dsh.android.plugin.hooks;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs every enabled hook engine in registration order at one seam.
 * - veto short-circuits immediately
 * - rewrite feeds the new text into the next engine's context
 * - engines returning nothing are treated as "allow" */
public final class HookChain implements PluginHooks {

    private final List<RhinoHookEngine> engines;

    public HookChain(List<RhinoHookEngine> engines) {
        this.engines = engines;
    }

    @Override
    public HookResult beforeSend(String text) {
        for (RhinoHookEngine e : engines) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("text", text);
            HookResult r = e.call("onBeforeSend", ctx);
            if (r == null) continue;
            if (r.isVeto()) return r;
            if (r.isRewrite()) text = r.text;
        }
        return null;
    }

    @Override
    public HookResult beforeToolCall(String toolName, String argsJson) {
        for (RhinoHookEngine e : engines) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("name", toolName);
            ctx.put("args", argsJson);
            HookResult r = e.call("onBeforeToolCall", ctx);
            if (r == null) continue;
            if (r.isVeto()) return r;
            if (r.isRewrite()) argsJson = r.text;
        }
        return null;
    }

    @Override
    public HookResult afterToolResult(String toolName, String argsJson, String result) {
        for (RhinoHookEngine e : engines) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("name", toolName);
            ctx.put("args", argsJson);
            ctx.put("result", result);
            HookResult r = e.call("onAfterToolResult", ctx);
            if (r == null) continue;
            if (r.isVeto()) return r;
            if (r.isRewrite()) result = r.text;
        }
        return null;
    }
}
