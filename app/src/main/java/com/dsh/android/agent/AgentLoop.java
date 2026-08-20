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

    public AgentLoop(DeepSeekClient client, com.dsh.android.tools.ToolRegistry tools,
                     String systemPrompt, JsonlSessionLog log) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.log = log;
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
        if (history != null && !history.isEmpty()) {
            List<ChatMessage> tail = new ArrayList<>(history.subList(
                    Math.max(0, history.size() - MAX_HISTORY_MESSAGES), history.size()));
            // Drop a dangling tail from an interrupted session (assistant-with-tool_calls or a
            // trailing tool reply): the API rejects an unanswered tool_calls message followed by
            // a fresh user message.
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
            try {
                res = client.chat(messages, tools.schemas(),
                        new DeepSeekClient.Listener() {
                            @Override public void onTextDelta(String delta) {
                                listener.onAssistantDelta(delta);
                            }

                            @Override public void onReasoningDelta(String delta) {
                                if (delta != null) reasoningBuf.append(delta);
                                listener.onReasoningDelta(delta);
                            }
                        }, cancel);
            } catch (Exception e) {
                Log.w(TAG, "LLM call failed", e);
                listener.onError("LLM error: " + e.getMessage());
                return;
            }
            if (cancel.get()) {
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
                listener.onToolStart(call);
                String result = tools.execute(call);
                listener.onToolResult(call, result);
                ChatMessage toolMsg = ChatMessage.tool(call.id, result);
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

    private void appendLog(ChatMessage msg) {
        try {
            if (log != null) log.append(msg);
        } catch (Exception e) {
            Log.w(TAG, "session append failed", e);
        }
    }
}