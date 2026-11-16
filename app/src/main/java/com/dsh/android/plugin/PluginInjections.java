package com.dsh.android.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code bridge.tool.invoke({inject:true})} 的上下文注入队列。
 *
 * 插件可能在工具 handler、页面/动作/事件回调里调用；AgentLoop 在下一次请求前
 * {@link #takeForTurn(int)} 取走并作为 system 备注插入 messages。单次注入在入队时
 * 截到 8 KB，单轮总量由 AgentLoop 用 16 KB 预算控制；单会话积压另有 64 条 / 64 KB 上限，
 * 超出丢最旧的。预算不足时未取走的条目留到下一轮。
 *
 * <p>队列按会话隔离：入队时记下 {@link #activeSession()}，只有同一会话的回合才会取走。
 * 否则在 A 会话里排队的备注会串到 B 会话，切会话后第一条消息就带上上一段的私货。
 */
public final class PluginInjections {

    public static final int MAX_ONE = 8 * 1024;
    public static final int MAX_TURN = 16 * 1024;
    /** 单会话积压上限：超出后丢最旧的，避免插件高频注入把内存吃穿。 */
    public static final int MAX_NOTES_PER_SESSION = 64;
    public static final int MAX_CHARS_PER_SESSION = 64 * 1024;
    /** 最多保留多少个会话的待注入队列；没人取的旧会话按入队顺序淘汰。 */
    private static final int MAX_SESSIONS = 8;

    /** 会话 id → 待注入备注；{@code ""} 表示没有会话（启动/调试）。 */
    private static final Map<String, List<String>> pending =
            new LinkedHashMap<String, List<String>>(8, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            };
    /** 当前会话 id：MainActivity 在会话切换时更新，bridge 入队时打标。 */
    private static volatile String activeSession = "";

    private PluginInjections() {
    }

    /** 当前会话 id（空串表示尚未建立会话）。 */
    public static String activeSession() {
        return activeSession;
    }

    /** 会话切换时由 MainActivity 调用；只影响之后入队的备注。 */
    public static void setActiveSession(String id) {
        activeSession = id == null ? "" : id;
    }

    /** 入队一条注入备注；整条备注（含标题/截断提示）不超过 8 KB。 */
    public static void inject(String pluginId, String tool, String text) {
        String note = "[plugin " + pluginId + " · " + tool + "]\n" + (text == null ? "" : text);
        note = clamp(note, MAX_ONE, "\n…(注入内容超过 8 KB，已截断)");
        synchronized (pending) {
            List<String> notes = pending.computeIfAbsent(activeSession, k -> new ArrayList<>());
            notes.add(note);
            trim(notes);
        }
    }

    /** 单会话积压裁剪：先按条数，再按字符总数，都是丢最旧的（队头）。 */
    private static void trim(List<String> notes) {
        while (notes.size() > MAX_NOTES_PER_SESSION) notes.remove(0);
        int chars = 0;
        for (String s : notes) chars += s.length();
        while (chars > MAX_CHARS_PER_SESSION && notes.size() > 1) {
            chars -= notes.remove(0).length();
        }
    }

    /**
     * 取走指定会话本轮可注入的备注；其它会话的队列保留。
     * 预算用尽时只移除已取走的条目（最后一条会按剩余预算截断），剩下的留到下一轮，
     * 不能整桶丢弃 —— 否则超预算的那批备注永久丢失。
     */
    public static List<String> takeForTurn(String sessionId, int maxChars) {
        String sid = sessionId == null ? "" : sessionId;
        List<String> out = new ArrayList<>();
        int left = Math.max(0, maxChars);
        synchronized (pending) {
            List<String> notes = pending.get(sid);
            if (notes == null) return out;
            int taken = 0;
            for (String note : notes) {
                if (left <= 0) break;
                String take = clamp(note, left, "\n…(本轮注入预算已用尽)");
                out.add(take);
                left -= take.length();
                taken++;
            }
            if (taken > 0) {
                notes.subList(0, taken).clear();
                if (notes.isEmpty()) pending.remove(sid);
            }
        }
        return out;
    }

    /** 取走当前会话本轮可注入的备注（调试/旧入口）。 */
    public static List<String> takeForTurn(int maxChars) {
        return takeForTurn(activeSession, maxChars);
    }

    /** 截到 limit 字符以内；截断时保留 suffix（suffix 本身超限则只截 suffix）。 */
    private static String clamp(String text, int limit, String suffix) {
        if (text.length() <= limit) return text;
        int cut = Math.max(0, limit - suffix.length());
        String out = text.substring(0, cut) + suffix;
        return out.length() <= limit ? out : out.substring(0, limit);
    }

    /** 所有会话的待注入条数（调试/看板用）。 */
    public static int pendingCount() {
        synchronized (pending) {
            int n = 0;
            for (List<String> notes : pending.values()) n += notes.size();
            return n;
        }
    }

    /** 指定会话的待注入条数。 */
    public static int pendingCount(String sessionId) {
        String sid = sessionId == null ? "" : sessionId;
        synchronized (pending) {
            List<String> notes = pending.get(sid);
            return notes == null ? 0 : notes.size();
        }
    }

    /** 所有会话的待注入字符数。 */
    public static int pendingChars() {
        synchronized (pending) {
            int n = 0;
            for (List<String> notes : pending.values()) {
                for (String s : notes) n += s.length();
            }
            return n;
        }
    }

    public static void clear() {
        synchronized (pending) {
            pending.clear();
        }
    }

    public static String describe() {
        synchronized (pending) {
            StringBuilder sb = new StringBuilder("pending=")
                    .append(pendingCount()).append(" chars=").append(pendingChars())
                    .append(" sessions=").append(pending.size());
            for (Map.Entry<String, List<String>> e : pending.entrySet()) {
                String sid = e.getKey().isEmpty() ? "(no session)" : e.getKey();
                sb.append('\n').append('[').append(sid).append(']');
                for (String s : e.getValue()) {
                    String head = s.length() > 160 ? s.substring(0, 160) + "…" : s;
                    sb.append('\n').append(head.replace('\n', ' '));
                }
            }
            return sb.toString();
        }
    }
}
