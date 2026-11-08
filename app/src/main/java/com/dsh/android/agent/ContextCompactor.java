package com.dsh.android.agent;

import com.dsh.android.llm.DeepSeekClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 上下文压缩：把"较早的一大段对话"换成一条摘要。
 *
 * 为什么必须有：一次 agent 回合里工具输出是主要体积来源（一个 bash 结果动辄上万字符，
 * 读一个文件又是几万），API 的上下文窗口再大也会被顶满；顶满之后表现不是报错，
 * 而是模型开始丢前面的约束、反复犯错。上游 harness 的 /compact 是同一件事。
 *
 * 策略（保守优先，宁可不压也别压坏）：
 *  - 只在总量超过 {@link #TRIGGER_CHARS} 时触发；
 *  - 永远保留 system 提示与最近 {@link #KEEP_RECENT} 条消息（模型需要眼前这一步的上下文）；
 *  - 中段交给模型自己总结，要求保留：任务目标、已确认的事实/路径/命令、未完成的待办、
 *    用户明确提出的约束；
 *  - 摘要插在 system 之后、作为一条 user 消息（OpenAI 兼容端点对 user 位置的容忍度最高）；
 *  - 摘要请求失败就退化成"丢弃 + 一行说明"，绝不让回合因为压缩失败而中断。
 */
public final class ContextCompactor {

    /** 触发阈值（字符）。DeepSeek 128k 上下文按中文约 1.5 字符/token 折算，这里约 40k token。 */
    public static final int TRIGGER_CHARS = 80_000;
    /** 迭代过程中的硬上限：超过它只做本地裁剪，不再调模型生成摘要。 */
    public static final int HARD_LIMIT = 130_000;
    /** 保留最近多少条消息不动。 */
    private static final int KEEP_RECENT = 8;
    /**
     * 保留部分的字符上限。只按"条数"保留不够：一条 256 KB 的 bash 输出就能顶爆窗口，
     * 所以保留窗口同时受条数和体积约束。
     */
    private static final int KEEP_BUDGET = 24_000;
    /** 喂给摘要模型的原文字符上限。 */
    private static final int FEED_CHARS = 12_000;
    /** 单条消息喂进去时的截断长度。 */
    private static final int PER_MSG_CHARS = 1_200;

    private static final String SUMMARY_PROMPT =
            "你是上下文压缩器。下面是智能体与用户的一段较早对话（含工具调用结果）。"
                    + "请把它压缩成不超过 300 字的中文摘要，务必保留：\n"
                    + "1) 用户的原始目标与后续追加的要求、约束；\n"
                    + "2) 已经确认的事实：文件路径、命令、版本号、接口名、关键结论；\n"
                    + "3) 已经做过且失败过的尝试（不要重复建议）；\n"
                    + "4) 尚未完成的待办。\n"
                    + "只输出摘要正文，不要寒暄、不要复述这段说明。";

    /** 当前内容是否大到需要压缩。 */
    public static boolean needed(List<ChatMessage> msgs) {
        return chars(msgs) > TRIGGER_CHARS;
    }

    /** 估算字符数：content + 工具调用参数。 */
    public static int chars(List<ChatMessage> msgs) {
        int n = 0;
        for (ChatMessage m : msgs) {
            if (m.content != null) n += m.content.length();
            if (m.toolCalls != null) {
                for (ToolCall c : m.toolCalls) n += c.name.length() + c.arguments.length();
            }
        }
        return n;
    }

    private ContextCompactor() {
    }

    /**
     * 压缩。成功返回新列表（调用方自行替换），失败抛异常由调用方决定退化策略。
     */
    public static List<ChatMessage> compact(List<ChatMessage> msgs, DeepSeekClient client,
                                            AtomicBoolean cancel) throws Exception {
        int keepFrom = keepFrom(msgs, KEEP_BUDGET, KEEP_RECENT, 2);
        if (keepFrom <= 1) return msgs;                 // 全在保留区，没得压

        // 不能把 assistant(tool_calls) 和它的 tool 回复拆开：窗口左边界若落在 tool 上就往回退。
        while (keepFrom > 1 && "tool".equals(msgs.get(keepFrom).role)) keepFrom--;

        StringBuilder feed = new StringBuilder();
        int dropped = 0;
        for (int i = 1; i < keepFrom; i++) {
            ChatMessage m = msgs.get(i);
            if (feed.length() > FEED_CHARS) {
                feed.append("…（更早的内容已省略）\n");
                break;
            }
            feed.append('[').append(m.role).append("] ");
            String c = m.content == null ? "" : m.content;
            if (c.length() > PER_MSG_CHARS) c = c.substring(0, PER_MSG_CHARS) + "…";
            feed.append(c.replace('\n', ' ')).append('\n');
            if (m.toolCalls != null) {
                for (ToolCall tc : m.toolCalls) feed.append("  → 调用 ").append(tc.name).append('\n');
            }
            dropped++;
        }

        List<ChatMessage> req = new ArrayList<>();
        req.add(ChatMessage.system(SUMMARY_PROMPT));
        req.add(ChatMessage.user(feed.toString()));
        // 摘要是一次额外的模型往返：限 400 token + temperature 0，
        // 否则长上下文时每回合都要等十几秒才开始真正干活。
        DeepSeekClient.AssistantResult res = client.chat(req, null, null, cancel, 400, 0.0);
        String summary = res.content == null ? "" : res.content.trim();
        if (summary.isEmpty()) throw new java.io.IOException("摘要为空");

        List<ChatMessage> out = new ArrayList<>(msgs.size() - keepFrom + 2);
        out.add(msgs.get(0));                           // system
        out.add(ChatMessage.user("（自动生成的历史摘要，覆盖之前 " + dropped
                + " 条消息；这不是新的用户指令，仅作为上下文）\n" + summary));
        for (int i = keepFrom; i < msgs.size(); i++) out.add(msgs.get(i));
        return out;
    }

    /**
     * 从后往前累加，同时受"条数"和"体积"两个上限约束，返回保留窗口的左边界。
     * 至少要留 minKeep 条（否则一条巨型消息也能把窗口压空）。
     */
    private static int keepFrom(List<ChatMessage> msgs, int budget, int maxCount, int minKeep) {
        int from = msgs.size();
        int acc = 0;
        while (from > 1) {
            int kept = msgs.size() - from;
            if (kept >= maxCount) break;
            int c = size(msgs.get(from - 1));
            if (acc + c > budget && kept >= minKeep) break;
            acc += c;
            from--;
        }
        // 左边界不能落在 tool 回复上：那会把 assistant(tool_calls) 拆开，API 直接 400。
        while (from > 1 && "tool".equals(msgs.get(from).role)) from--;
        return from;
    }

    private static int size(ChatMessage m) {
        int n = m.content == null ? 0 : m.content.length();
        if (m.toolCalls != null) {
            for (ToolCall c : m.toolCalls) n += c.arguments.length() + c.name.length();
        }
        return n;
    }

    /** 摘要失败时的兜底：直接丢掉中段，留一行说明。 */
    public static List<ChatMessage> truncate(List<ChatMessage> msgs) {
        int keepFrom = keepFrom(msgs, KEEP_BUDGET, KEEP_RECENT, 2);
        List<ChatMessage> out = new ArrayList<>(KEEP_RECENT + 2);
        out.add(msgs.get(0));
        out.add(ChatMessage.user("（更早的对话因上下文过长已丢弃，共 " + (keepFrom - 1)
                + " 条；如需细节请重新读取相关文件或重新执行命令）"));
        // 保留段也要封顶：单条巨型消息（理论上一整段 256 KB 输出）会把预算直接吃掉。
        for (int i = keepFrom; i < msgs.size(); i++) out.add(clip(msgs.get(i), 24_000));
        return out;
    }

    /**
     * 紧急压缩：模型已经回了 400（上下文超限）时用。不调模型（大概率也会失败），
     * 直接留 system + 最近 4 条，并把每条再截到 8k 字符。
     */
    public static List<ChatMessage> emergency(List<ChatMessage> msgs) {
        int from = keepFrom(msgs, 16_000, 4, 2);
        List<ChatMessage> out = new ArrayList<>();
        out.add(msgs.get(0));
        out.add(ChatMessage.user("（上下文已超出模型上限，较早的 " + (from - 1)
                + " 条消息被丢弃；如需细节请重新读取文件或重新执行命令）"));
        for (int i = from; i < msgs.size(); i++) out.add(clip(msgs.get(i), 8_000));
        return out;
    }

    /** 单条消息过长时截断（保留开头与结尾各一半）。 */
    private static ChatMessage clip(ChatMessage m, int max) {
        if (m.content == null || m.content.length() <= max) return m;
        String c = m.content;
        int head = max * 2 / 3;
        int tail = max - head;
        String clipped = c.substring(0, head) + "\n…[已截断]…\n"
                + c.substring(c.length() - tail);
        // 角色必须原样保留：把 user/assistant 变成 tool 会让请求直接非法。
        if ("tool".equals(m.role)) return ChatMessage.tool(m.toolCallId, clipped);
        if ("assistant".equals(m.role)) return ChatMessage.assistant(clipped, m.toolCalls);
        if ("user".equals(m.role)) return ChatMessage.user(clipped, m.imagePaths);
        return ChatMessage.system(clipped);
    }
}
