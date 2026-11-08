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

        /** 一条不该进对话流的提示（例如上下文被压缩），UI 通常渲染成灰色小字。 */
        default void onNotice(String note) {
        }

        void onAssistantDelta(String delta);

        void onReasoningDelta(String delta);

        void onToolStart(ToolCall call);

        void onToolResult(ToolCall call, String output);

        void onError(String message);

        void onFinished(String finalAnswer);
    }

    /**
     * 单条工具输出超过这个字符数就落盘，上下文里只留首尾。
     * 256 KB 的 bash 输出（runtime 的上限）直接塞进 messages，下一次请求必然超过模型的
     * 上下文窗口 —— 那是 400 的直接来源。
     */
    private static final int TOOL_INLINE_MAX = 24_000;
    private static final int TOOL_HEAD = 16_000;
    private static final int TOOL_TAIL = 6_000;
    /** 落盘目录在工作区里，guest 侧看到的是 /root/workspace/.toolout/。 */
    private static final String TOOLOUT_GUEST_PATH = "/root/workspace/.toolout/";

    private final DeepSeekClient client;
    private final com.dsh.android.tools.ToolRegistry tools;
    private final String systemPrompt;
    private final JsonlSessionLog log;
    private final java.io.File workspaceDir;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    /** Type B 钩子；为 null 表示没有装插件系统。 */
    private com.dsh.android.plugin.HookRegistry hooks;
    /** 需要用户确认危险命令时用（与 ask_user_question 同一个 UI 桥）。 */
    private com.dsh.android.tools.AskUserTool.Ui asker;
    /** 第二道：学习模型（确定性护栏判 ALLOW 之后再打分），null 表示没装载。 */
    private com.dsh.android.guard.RiskModel riskModel;

    public AgentLoop withRiskModel(com.dsh.android.guard.RiskModel model) {
        this.riskModel = model;
        return this;
    }

    public AgentLoop withAsker(com.dsh.android.tools.AskUserTool.Ui ui) {
        this.asker = ui;
        return this;
    }

    /** 装上钩子链（可选）。 */
    public AgentLoop withHooks(com.dsh.android.plugin.HookRegistry registry) {
        this.hooks = registry;
        return this;
    }

    public AgentLoop(DeepSeekClient client, com.dsh.android.tools.ToolRegistry tools,
                     String systemPrompt, JsonlSessionLog log) {
        this(client, tools, systemPrompt, log, null);
    }

    public AgentLoop(DeepSeekClient client, com.dsh.android.tools.ToolRegistry tools,
                     String systemPrompt, JsonlSessionLog log, java.io.File workspaceDir) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.log = log;
        this.workspaceDir = workspaceDir;
    }

    public void cancel() {
        cancel.set(true);
    }

    public void run(String task, Listener listener) {
        run(task, null, listener);
    }

    public void run(String task, List<ChatMessage> history, Listener listener) {
        run(ChatMessage.user(task), history, listener);
    }

    /** 带图片附件的用户消息入口：content 是 text + image_url 的多模态数组。 */
    public void run(ChatMessage userMsg, List<ChatMessage> history, Listener listener) {
        cancel.set(false);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (history != null && !history.isEmpty()) {
            // 窗口必须协议合法：见 HistoryWindow 的注释（GitHub issue #1）。
            // 简单的"取最近 40 条"会让窗口以孤儿 tool 开头，DeepSeek 直接 400。
            for (ChatMessage m : HistoryWindow.withoutSystem(
                    HistoryWindow.sanitize(history, MAX_HISTORY_MESSAGES))) {
                messages.add(m);
            }
        }
        messages.add(userMsg);
        appendLog(messages.get(messages.size() - 1));
        listener.onTurnStart();

        // 模型摘要是额外一次网络往返，只在回合开始时做一次；
        // 迭代过程中工具输出再膨胀，用纯本地裁剪兜住，绝不重复调模型。
        if (ContextCompactor.needed(messages)) compact(messages, listener);

        boolean retriedOverflow = false;
        for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
            if (cancel.get()) {
                listener.onFinished("(cancelled)");
                return;
            }

            if (ContextCompactor.chars(messages) > ContextCompactor.HARD_LIMIT) {
                List<ChatMessage> cut = ContextCompactor.truncate(messages);
                messages.clear();
                messages.addAll(cut);
                listener.onNotice("上下文增长过快，已裁剪较早内容");
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
                if (!retriedOverflow && looksLikeContextOverflow(e)) {
                    // 兜底：估算总是不够准，模型直接回 400 时再压一次（不调模型，纯本地裁剪）。
                    retriedOverflow = true;
                    Log.w(TAG, "context overflow from API, emergency compaction", e);
                    List<ChatMessage> out = ContextCompactor.emergency(messages);
                    messages.clear();
                    messages.addAll(out);
                    listener.onNotice("上下文超出模型上限，已紧急压缩后重试");
                    iter--;                     // 这次不算一轮
                    continue;
                }
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

                // Type B：执行前让钩子过一遍。否决就不执行，改写就换参数执行。
                ToolCall effective = call;
                String blocked = null;
                if (hooks != null) {
                    com.dsh.android.plugin.HookEngine.Decision d =
                            hooks.beforeToolCall(call.name, call.arguments);
                    if (!d.allow) {
                        blocked = d.reason;
                    } else if (d.args != null) {
                        effective = new ToolCall(call.id, call.name, d.args);
                    }
                }

                // 确定性护栏：在钩子之后跑（钩子可能改写参数），按解析后的目标判断。
                if (blocked == null) blocked = guardCheck(effective);

                listener.onToolStart(effective);
                String result;
                java.util.List<String> imagePaths = null;
                if (blocked != null) {
                    result = "已拦截（hook）: " + blocked + "\n（这次调用没有执行）";
                    listener.onNotice("已拦截：" + blocked);
                } else {
                    com.dsh.android.tools.Tool.Result rich = tools.executeRich(effective);
                    imagePaths = rich.imagePaths;
                    result = spillIfHuge(rich.text, effective.name);
                    if (hooks != null) {
                        result = hooks.afterToolResult(effective.name, effective.arguments, result);
                    }
                }
                listener.onToolResult(effective, result);
                ChatMessage toolMsg = ChatMessage.tool(call.id, result);
                messages.add(toolMsg);
                appendLog(toolMsg);
                // role=tool 里塞 image_url 端点兼容性差；按上游「请求期投影」的思路，
                // 追加一条 user 多模态消息，让模型在下一轮确实看见工具读到的图片。
                if (imagePaths != null && !imagePaths.isEmpty()) {
                    ChatMessage imgMsg = ChatMessage.user(
                            "（这是上一条 read_image 工具结果里的图片）", imagePaths);
                    messages.add(imgMsg);
                    appendLog(imgMsg);
                }
            }
        }

        if (cancel.get()) {
            listener.onFinished("(cancelled)");
        } else {
            listener.onError("Reached max iterations (" + MAX_ITERATIONS + ") without a final answer.");
        }
    }

    /**
     * 压缩当前上下文。摘要要额外调一次模型，失败就退化成丢弃中段 ——
     * 宁可信息少一点，也不能让这一回合因为"压缩"本身挂掉。
     */
    private void compact(List<ChatMessage> messages, Listener listener) {
        int before = ContextCompactor.chars(messages);
        try {
            List<ChatMessage> out = ContextCompactor.compact(messages, client, cancel);
            messages.clear();
            messages.addAll(out);
            int after = ContextCompactor.chars(messages);
            Log.i(TAG, "context compacted " + before + " -> " + after);
            listener.onNotice("上下文已压缩：" + before + " → " + after + " 字符");
        } catch (Exception e) {
            Log.w(TAG, "compact failed, falling back to truncation", e);
            List<ChatMessage> out = ContextCompactor.truncate(messages);
            messages.clear();
            messages.addAll(out);
            listener.onNotice("上下文过长，较早的内容已丢弃（摘要失败：" + e.getMessage() + "）");
        }
    }

    /**
     * 危险命令护栏。返回非 null 表示这次调用不能执行（原因写在返回值里）。
     */
    private String guardCheck(ToolCall call) {
        if (!"bash".equals(call.name)) return null;
        String command;
        try {
            command = new org.json.JSONObject(call.arguments).optString("command", "");
        } catch (Exception e) {
            // 解析不出来就别执行：可能是参数里藏了命令，静默放行等于把护栏关掉
            return "无法解析 bash 参数，已阻止执行（" + e.getMessage() + "）";
        }
        if (command.isEmpty()) return null;
        com.dsh.android.guard.CommandGuard.Verdict v =
                com.dsh.android.guard.CommandGuard.check(command);
        if (v.action == com.dsh.android.guard.CommandGuard.Action.ALLOW) {
            // 第二道：模型兜底。只把结论升级成"问用户"，绝不硬拦（模型会有误报）。
            if (riskModel != null) {
                double s = riskModel.score(command);
                if (s >= 0.5) {
                    return askAbout(command, String.format(java.util.Locale.ROOT,
                            "模型风险评分 %.2f（0.5 以上建议确认）", s));
                }
            }
            return null;
        }
        if (v.action == com.dsh.android.guard.CommandGuard.Action.BLOCK) {
            return "已拦截（安全护栏）：" + v.reason
                    + "\n这条命令没有执行。换一种不会破坏运行环境/用户数据的做法。";
        }
        return askAbout(command, v.reason);
    }

    /** 弹卡片问用户；同意返回 null（继续执行），否则返回给模型的说明。 */
    private String askAbout(String command, String reason) {
        if (asker == null) {
            return "需要用户确认才能执行（" + reason + "），但当前没有可用的确认通道。";
        }
        List<String> opts = new java.util.ArrayList<>();
        opts.add("执行");
        opts.add("取消");
        String ans = asker.ask("即将执行这条命令：\n\n" + command + "\n\n风险：" + reason
                + "\n\n是否执行？", opts);
        if (ans != null && ans.startsWith("执行")) return null;
        return "用户拒绝执行这条命令（" + reason + "）。不要重试，换一种方式或询问用户原因。";
    }

    /**
     * 工具输出太长就落盘到工作区，只把首尾塞进上下文。
     *
     * 落盘而不是硬截断：模型还能自己用 sed/grep 把需要的那段捞回来，
     * 不会因为"看不到后面"而误判。这正是上游 harness 里 spill 的思路。
     */
    private String spillIfHuge(String output, String toolName) {
        if (output == null || output.length() <= TOOL_INLINE_MAX) return output;
        String head = output.substring(0, TOOL_HEAD);
        String tail = output.substring(output.length() - TOOL_TAIL);
        String note;
        java.io.File dir = workspaceDir == null ? null : new java.io.File(workspaceDir, ".toolout");
        if (dir != null) {
            if (!dir.isDirectory() && !dir.mkdirs()) dir = null;
        }
        if (dir == null) {
            note = "\n…[输出过长已截断：原始 " + output.length() + " 字符]…\n";
            return head + note + tail;
        }
        String name = toolName + "-" + System.currentTimeMillis() + ".txt";
        try {
            com.dsh.android.util.IOUtil.writeText(new java.io.File(dir, name), output);
            note = "\n…[输出过长已截断：完整 " + output.length() + " 字符保存在 "
                    + TOOLOUT_GUEST_PATH + name + "，需要更多内容请用 "
                    + "sed -n '1,200p' 或 grep -n <关键字> 分段读取]…\n";
        } catch (Exception e) {
            note = "\n…[输出过长已截断：原始 " + output.length() + " 字符（落盘失败：" + e.getMessage() + "）]…\n";
        }
        return head + note + tail;
    }

    /** 400 且带上下文长度字样 —— 只有这种才值得压缩重试。 */
    private static boolean looksLikeContextOverflow(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (!m.contains("400")) return false;
        return m.contains("context") || m.contains("too long") || m.contains("token")
                || m.contains("maximum");
    }

    private void appendLog(ChatMessage msg) {
        try {
            if (log != null) log.append(msg);
        } catch (Exception e) {
            Log.w(TAG, "session append failed", e);
        }
    }
}