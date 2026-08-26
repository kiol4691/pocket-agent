package com.dsh.android.agent;

import com.dsh.android.llm.DeepSeekClient;
import com.dsh.android.session.JsonlSessionLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Log;

/**
 * Reimplementation of the upstream agent-loop: repeatedly call the model with tool schemas,
 * execute any requested tool calls, feed results back, until the model answers without
 * tool calls or we hit the iteration cap.
 */
public final class AgentLoop {

    private static final String TAG = "DSH";
    private static final int MAX_ITERATIONS = 30;
    private static final int MAX_HISTORY_MESSAGES = 40;

    public interface Listener {
        /** Fired once when an agent turn begins (before the first model call). */
        default void onTurnStart() {
        }

        void onAssistantDelta(String delta);

        void onReasoningDelta(String delta);

        void onToolStart(ToolCall call);

        void onToolResult(ToolCall call, String output);

        void onError(String message);

        void onFinished(String finalAnswer);
    }

    private final DeepSeekClient client;
    private final com.dsh.android.tools.ToolRegistry tools;
    private final String systemPrompt;
    private final JsonlSessionLog log;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final com.dsh.android.plugin.hooks.PluginHooks hooks;

    public AgentLoop(DeepSeekClient client, com.dsh.android.tools.ToolRegistry tools,
                     String systemPrompt, JsonlSessionLog log) {
        this(client, tools, systemPrompt, log, null);
    }

    public AgentLoop(DeepSeekClient client, com.dsh.android.tools.ToolRegistry tools,
                     String systemPrompt, JsonlSessionLog log,
                     com.dsh.android.plugin.hooks.PluginHooks hooks) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.log = log;
        this.hooks = hooks;
    }

    public void cancel() {
        cancel.set(true);
    }

    public void run(String task, Listener listener) {
        run(task, null, listener);
    }

    public void run(String task, List<ChatMessage> history, Listener listener) {
        cancel.set(false);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        int maxHistory = hooks != null ? hooks.historyLimit() : MAX_HISTORY_MESSAGES;
        if (history != null && !history.isEmpty()) {
            List<ChatMessage> tail = new ArrayList<>(history.subList(
                    Math.max(0, history.size() - maxHistory), history.size()));
            // Repair a dangling tail from an interrupted/errored turn instead of dropping
            // it: keep the partial assistant text and the tool calls that WERE answered,
            // strip only unanswered tool_calls (the API rejects tool_calls without a
            // following tool message). The model thus continues where it left off.
            if (!tail.isEmpty()) {
                List<ChatMessage> toolMsgs = new ArrayList<>();
                while (!tail.isEmpty() && "tool".equals(tail.get(tail.size() - 1).role)) {
                    toolMsgs.add(0, tail.remove(tail.size() - 1));
                }
                if (!tail.isEmpty() && tail.get(tail.size() - 1).toolCalls != null) {
                    ChatMessage a = tail.remove(tail.size() - 1);
                    java.util.Set<String> answered = new java.util.HashSet<>();
                    for (ChatMessage t : toolMsgs) {
                        if (t.toolCallId != null) answered.add(t.toolCallId);
                    }
                    List<ToolCall> kept = new ArrayList<>();
                    if (a.toolCalls != null) {
                        for (ToolCall c : a.toolCalls) {
                            if (answered.contains(c.id)) kept.add(c);
                        }
                    }
                    boolean hasText = a.content != null && !a.content.isEmpty();
                    if (hasText || !kept.isEmpty()) {
                        tail.add(ChatMessage.assistant(a.content,
                                kept.isEmpty() ? null : kept, a.reasoning));
                        tail.addAll(toolMsgs);
                    }
                }
            }
            // Safety net: anything still dangling above (legacy/corrupt files) is dropped —
            // the API rejects unanswered tool_calls followed by a fresh user message.
            while (!tail.isEmpty()) {
                ChatMessage last = tail.get(tail.size() - 1);
                if ("tool".equals(last.role) || last.toolCalls != null) {
                    tail.remove(tail.size() - 1);
                } else {
                    break;
                }
            }
            for (ChatMessage m : tail) {
                if ("system".equals(m.role)) continue; // we add a fresh system prompt
                messages.add(m);
            }
        }
        messages.add(ChatMessage.user(task));
        appendLog(messages.get(messages.size() - 1));
        listener.onTurnStart();

        for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
            if (cancel.get()) {
                listener.onFinished("(cancelled)");
                return;
            }

            DeepSeekClient.AssistantResult res;
            final StringBuilder reasoningBuf = new StringBuilder();
            final StringBuilder textBuf = new StringBuilder();
            try {
                res = client.chat(messages, tools.schemas(),
                        new DeepSeekClient.Listener() {
                            @Override public void onTextDelta(String delta) {
                                if (delta != null) textBuf.append(delta);
                                listener.onAssistantDelta(delta);
                            }

                            @Override public void onReasoningDelta(String delta) {
                                if (delta != null) reasoningBuf.append(delta);
                                listener.onReasoningDelta(delta);
                            }
                        }, cancel);
            } catch (Exception e) {
                Log.w(TAG, "LLM call failed", e);
                persistPartial(textBuf, reasoningBuf, messages);
                listener.onError("LLM error: " + e.getMessage());
                return;
            }
            if (cancel.get()) {
                // Persist the partially streamed answer so a later "Continue" turn
                // (and session replay) still has the interrupted work in context.
                persistPartial(
                        textBuf.length() > 0 ? textBuf : new StringBuilder(res.content == null ? "" : res.content),
                        reasoningBuf, messages);
                listener.onFinished("(cancelled)");
                return;
            }

            String content = res.content == null ? "" : res.content;
            List<ToolCall> calls = res.toolCalls;
            boolean hasCalls = calls != null && !calls.isEmpty();

            ChatMessage assistantMsg = ChatMessage.assistant(content, hasCalls ? calls : null,
                    reasoningBuf.length() == 0 ? null : reasoningBuf.toString());
            messages.add(assistantMsg);
            appendLog(assistantMsg);

            if (!hasCalls) {
                listener.onFinished(content);
                return;
            }

            for (ToolCall call : calls) {
                if (cancel.get()) break;
                Log.i(TAG, "tool call: " + call);
                ToolCall effective = call;
                if (hooks != null) {
                    com.dsh.android.plugin.hooks.PluginHooks.HookResult pre =
                            hooks.beforeToolCall(call.name, call.arguments);
                    if (pre != null && pre.isVeto()) {
                        String result = "[vetoed by plugin: " + pre.reason + "]";
                        listener.onToolStart(call);
                        listener.onToolResult(call, result);
                        ChatMessage toolMsg = ChatMessage.tool(call.id, result);
                        messages.add(toolMsg);
                        appendLog(toolMsg);
                        continue;
                    }
                    if (pre != null && pre.isRewrite()) {
                        effective = new ToolCall(call.id, call.name, pre.text);
                    }
                }
                listener.onToolStart(effective);
                String result = tools.execute(effective);
                if (hooks != null) {
                    com.dsh.android.plugin.hooks.PluginHooks.HookResult post =
                            hooks.afterToolResult(effective.name, effective.arguments, result);
                    if (post != null && post.isVeto()) {
                        result = "[result withheld by plugin: " + post.reason + "]";
                    } else if (post != null && post.isRewrite()) {
                        result = post.text;
                    }
                }
                listener.onToolResult(effective, result);
                ChatMessage toolMsg = ChatMessage.tool(effective.id, result);
                messages.add(toolMsg);
                appendLog(toolMsg);
            }
        }

        if (cancel.get()) {
            listener.onFinished("(cancelled)");
        } else {
            listener.onError("Reached max iterations (" + MAX_ITERATIONS + ") without a final answer.");
        }
    }

    /** Log a partially streamed assistant message (no tool calls) so interrupted work
     * survives in the session; skipped when there is nothing at all to keep. */
    private void persistPartial(StringBuilder text, StringBuilder reasoning, List<ChatMessage> messages) {
        boolean hasText = text.length() > 0;
        boolean hasReasoning = reasoning.length() > 0;
        if (!hasText && !hasReasoning) return;
        ChatMessage m = ChatMessage.assistant(
                hasText ? text.toString() : null, null,
                hasReasoning ? reasoning.toString() : null);
        messages.add(m);
        appendLog(m);
    }

    private void appendLog(ChatMessage msg) {
        try {
            if (log != null) log.append(msg);
        } catch (Exception e) {
            Log.w(TAG, "session append failed", e);
        }
    }
}