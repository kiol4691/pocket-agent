package com.dsh.android.guard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 护栏绕过回归：合并短选项、busybox/xargs/eval 包装、find -exec、cp/mv/ln 敏感目标。
 * 纯 Java（不依赖 Android）。
 */
public class GuardBypassTest {

    private static CommandGuard.Action action(String cmd) {
        return CommandGuard.check(cmd).action;
    }

    @Test
    public void mergedShellOptionsAreParsed() {
        // sh -ec 'rm -rf /'：-c 被合并在 -ec 里，之前 indexOf("-c") 找不到
        assertEquals(CommandGuard.Action.BLOCK, action("sh -ec 'rm -rf /'"));
        assertEquals(CommandGuard.Action.BLOCK, action("bash -lc 'rm -rf /'"));
    }

    @Test
    public void busyboxWrapperCannotHideDestructiveVerb() {
        assertEquals(CommandGuard.Action.BLOCK, action("busybox rm -rf /"));
        assertEquals(CommandGuard.Action.BLOCK, action("busybox sh -c 'rm -rf /'"));
    }

    @Test
    public void evalAndXargsAreInspected() {
        assertEquals(CommandGuard.Action.BLOCK, action("eval 'rm -rf /'"));
        assertEquals(CommandGuard.Action.ASK, action("echo /etc | xargs rm -rf"));
    }

    @Test
    public void findExecIsInspected() {
        assertEquals(CommandGuard.Action.BLOCK, action("find /etc -exec rm {} ;"));
        assertEquals(CommandGuard.Action.BLOCK, action("find / -execdir rm -rf {} +"));
        // 只读 -exec 不应误伤
        assertEquals(CommandGuard.Action.ALLOW,
                action("find /root/workspace -name '*.log' -exec ls {} ;"));
    }

    @Test
    public void copyMoveLinkToSensitivePaths() {
        assertEquals(CommandGuard.Action.BLOCK, action("cp /tmp/x /etc/passwd"));
        assertEquals(CommandGuard.Action.BLOCK, action("mv /tmp/x /system/app/x"));
        assertEquals(CommandGuard.Action.BLOCK, action("ln -sf /tmp/k /etc/hosts"));
        // /data 属于用户数据：交给用户确认
        assertEquals(CommandGuard.Action.ASK, action("cp /tmp/x /data/local/tmp/y"));
        // 工作区内正常复制
        assertEquals(CommandGuard.Action.ALLOW,
                action("cp -r /root/workspace/a /root/workspace/b"));
    }

    @Test
    public void cwdIsUsedForRelativeTargets() {
        assertEquals(CommandGuard.Action.ALLOW, action("rm secret.txt"));
        // 同样一条命令，cwd=/etc 时目标变成 /etc/secret.txt
        assertEquals(CommandGuard.Action.BLOCK,
                CommandGuard.check("rm secret.txt", "/etc").action);
        // 空 / 相对 cwd 规范化后不应崩溃
        assertEquals(CommandGuard.Action.ALLOW, CommandGuard.check("ls", "").action);
        assertEquals(CommandGuard.Action.ALLOW, CommandGuard.check("ls", "sub/dir").action);
    }

    @Test
    public void commonCommandsStillAllowed() {
        assertEquals(CommandGuard.Action.ALLOW, action("ls -la /root/workspace"));
        assertEquals(CommandGuard.Action.ALLOW, action("sh -c 'ls /tmp'"));
        assertEquals(CommandGuard.Action.ALLOW, action("env ls /tmp"));
    }
}
