package com.dsh.android.plugin.hooks;

/** Chain of enabled hook plugins, consulted at three seams of the agent loop.
 * Hooks are pure functions of their context: they may veto or rewrite, but must not
 * perform side effects (no tool calls from hooks — that would re-enter the loop). */
public interface PluginHooks {

    /** ctx = user message text. Return veto (abort the send) or rewrite (replace text). */
    HookResult beforeSend(String text);

    /** ctx = tool name + arguments JSON. Veto → the call is not executed; rewrite → args replaced. */
    HookResult beforeToolCall(String toolName, String argsJson);

    /** ctx = tool name + arguments JSON + result text. Rewrite → result text replaced. */
    HookResult afterToolResult(String toolName, String argsJson, String result);

    /** How many history messages to keep (default 40). Chain takes the smallest value. */
    int historyLimit();

    /** Verdict of one hook: allow (null-ish), veto (with reason) or rewrite (with new text). */
    final class HookResult {
        public final String action;   // "allow" | "veto" | "rewrite"
        public final String text;
        public final String reason;

        private HookResult(String action, String text, String reason) {
            this.action = action;
            this.text = text;
            this.reason = reason;
        }

        public static HookResult veto(String reason) {
            return new HookResult("veto", null, reason == null ? "no reason given" : reason);
        }

        public static HookResult rewrite(String newText) {
            return new HookResult("rewrite", newText, null);
        }

        public boolean isVeto() { return "veto".equals(action); }

        public boolean isRewrite() { return "rewrite".equals(action) && text != null; }
    }
}
