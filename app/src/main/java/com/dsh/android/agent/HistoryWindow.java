package com.dsh.android.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从会话历史里截出一个**协议合法**的请求窗口。
 *
 * 为什么需要：OpenAI 兼容协议要求 tool 消息必须紧跟在带对应 tool_calls 的 assistant 之后。
 * 单纯"取最近 N 条"会在两个地方切出非法序列：
 *
 *   1. 头部切在 tool 上 —— 它的 assistant 被切掉了，窗口第一句就是孤儿 tool 回复。
 *      DeepSeek 直接回 400：Messages with role 'tool' must be a response to a preceding
 *      message with 'tool_calls'。（GitHub issue #1，2026-09-14，真机上每 40 条就会撞一次）
 *   2. 中间残留孤儿 —— 上一次被打断的会话里，assistant(tool_calls) 的回复没落盘，
 *      或者回复落盘了但 assistant 没落盘。
 *
 * 做法：按"块"重建。assistant(tool_calls) 和它的全部 tool 回复算一块，回复不齐就整块丢弃；
 * 不属于任何块的 tool 消息直接丢。这样剩下的序列一定合法。
 */
public final class HistoryWindow {

    private HistoryWindow() {
    }

    /** 取最近 max 条并修正协议合法性。返回的列表可能比 max 短。 */
    public static List<ChatMessage> sanitize(List<ChatMessage> history, int max) {
        if (history == null || history.isEmpty()) return new ArrayList<>();
        int from = Math.max(0, history.size() - Math.max(1, max));
        List<ChatMessage> tail = new ArrayList<>(history.subList(from, history.size()));

        List<ChatMessage> out = new ArrayList<>(tail.size());
        for (int i = 0; i < tail.size(); i++) {
            ChatMessage m = tail.get(i);
            if ("tool".equals(m.role)) {
                continue;                            // 孤儿 tool：前面没有配对的 assistant
            }
            if ("assistant".equals(m.role) && m.toolCalls != null && !m.toolCalls.isEmpty()) {
                Set<String> need = new HashSet<>();
                for (ToolCall c : m.toolCalls) need.add(c.id);
                List<ChatMessage> block = new ArrayList<>();
                block.add(m);
                int j = i + 1;
                while (j < tail.size() && "tool".equals(tail.get(j).role)) {
                    ChatMessage t = tail.get(j);
                    if (t.toolCallId == null || !need.remove(t.toolCallId)) break;
                    block.add(t);
                    j++;
                }
                if (!need.isEmpty()) {
                    continue;                        // 回复不齐：整个 assistant 块丢掉
                }
                out.addAll(block);
                i = j - 1;
                continue;
            }
            out.add(m);
        }
        return out;
    }

    /** 去掉 system 消息（调用方会自己加新的 system 提示）。 */
    public static List<ChatMessage> withoutSystem(List<ChatMessage> msgs) {
        List<ChatMessage> out = new ArrayList<>(msgs.size());
        for (ChatMessage m : msgs) {
            if (!"system".equals(m.role)) out.add(m);
        }
        return out;
    }
}
