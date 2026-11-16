package com.dsh.android.plugin;

import com.dsh.android.agent.ChatMessage;
import com.dsh.android.agent.ToolCall;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** onContextBuild 的硬限制：只能裁剪 / system 追加，tool 配对与 20% 上限。 */
public class ContextBuildValidationTest {

    private static List<ChatMessage> base() {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system("system prompt " + "x".repeat(200)));
        out.add(ChatMessage.user("hello " + "y".repeat(200)));
        out.add(ChatMessage.assistant("answer", null));
        out.add(ChatMessage.user("next"));
        return out;
    }

    @Test
    public void trimIsAllowed() {
        List<ChatMessage> original = base();
        List<ChatMessage> proposed = Arrays.asList(original.get(0), original.get(1));
        assertNull(HookRegistry.validateContextBuild(original, proposed));
    }

    @Test
    public void systemAppendIsAllowed() {
        List<ChatMessage> original = base();
        List<ChatMessage> proposed = new ArrayList<>(original);
        proposed.set(0, ChatMessage.system(original.get(0).content + " [plugin note]"));
        assertNull(HookRegistry.validateContextBuild(original, proposed));
    }

    @Test
    public void reorderAndFabricationRejected() {
        List<ChatMessage> original = base();
        List<ChatMessage> reordered = Arrays.asList(original.get(0), original.get(3), original.get(1));
        assertNotNull(HookRegistry.validateContextBuild(original, reordered));

        List<ChatMessage> fabricated = new ArrayList<>(original);
        fabricated.add(ChatMessage.user("injected"));
        assertNotNull(HookRegistry.validateContextBuild(original, fabricated));
    }

    @Test
    public void systemCannotShrink() {
        List<ChatMessage> original = base();
        List<ChatMessage> proposed = new ArrayList<>(original);
        proposed.set(0, ChatMessage.system("short"));
        assertNotNull(HookRegistry.validateContextBuild(original, proposed));
    }

    @Test
    public void toolPairingIsEnforced() {
        List<ChatMessage> original = new ArrayList<>();
        original.add(ChatMessage.system("s"));
        original.add(ChatMessage.user("u"));
        original.add(ChatMessage.assistant("a",
                Arrays.asList(new ToolCall("c1", "bash", "{}"))));
        original.add(ChatMessage.tool("c1", "out"));

        // 丢掉声明 tool_calls 的 assistant，只留 tool：拒绝
        assertNotNull(HookRegistry.validateContextBuild(original,
                Arrays.asList(original.get(0), original.get(1), original.get(3))));
        // 留下 assistant 但丢掉 tool 结果：拒绝
        assertNotNull(HookRegistry.validateContextBuild(original,
                Arrays.asList(original.get(0), original.get(1), original.get(2))));
        // 原样返回：允许
        assertNull(HookRegistry.validateContextBuild(original, original));
    }

    @Test
    public void twentyPercentLimit() {
        List<ChatMessage> original = new ArrayList<>();
        original.add(ChatMessage.system("s"));
        original.add(ChatMessage.user("u"));
        List<ChatMessage> proposed = new ArrayList<>(original);
        proposed.set(0, ChatMessage.system("s" + "z".repeat(50)));
        assertNotNull(HookRegistry.validateContextBuild(original, proposed));
    }
}
