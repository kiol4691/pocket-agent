package com.dsh.android.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ask_user_question}：把"拿不准"变成一次明确的提问。
 *
 * 为什么值得单独做一个工具：模型最贵的错误不是不会做，而是**自作主张地做错方向**。
 * 给它一个"停下来问一句"的出口，比在提示词里反复叮嘱有效得多。
 *
 * 阻塞语义：工具在 agent 线程上阻塞，UI 线程渲染问题卡片；用户点选项/发输入后放行。
 * 超时（10 分钟）或用户按下停止都返回 null，由调用方告诉模型"没人回答，按最合理的假设继续"。
 */
public final class AskUserTool implements Tool {

    /** 宿主提供的 UI 桥；返回 null 表示没拿到回答。 */
    public interface Ui {
        String ask(String question, List<String> options);
    }

    private final Ui ui;

    public AskUserTool(Ui ui) {
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ask_user_question";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("question", new JSONObject()
                .put("type", "string")
                .put("description", "要问用户的问题，一句话说清背景与需要决定什么"));
        props.put("options", new JSONObject()
                .put("type", "array")
                .put("description", "可选项（2–5 个），用户点一下即可；留空则给一个输入框")
                .put("items", new JSONObject().put("type", "string")));
        return Tool.function(name(),
                "向用户提问并等待回答。适用于：需求有歧义、要在几个方案里选、即将执行有风险的操作需要确认。"
                        + "一次只问一个问题；能在文件里查到的信息不要问。",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", new JSONArray().put("question")));
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        String q = a.optString("question", "").trim();
        if (q.isEmpty()) return "Error: question 不能为空";

        List<String> opts = new ArrayList<>();
        JSONArray arr = a.optJSONArray("options");
        if (arr != null) {
            for (int i = 0; i < arr.length() && opts.size() < 5; i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) opts.add(s);
            }
        }

        String ans = ui.ask(q, opts);
        if (ans == null || ans.trim().isEmpty()) {
            return "（用户没有回答：超时或被停止。请按最合理的假设继续，并在最终答复里说明你假设了什么。）";
        }
        return "用户回答：" + ans.trim();
    }
}
