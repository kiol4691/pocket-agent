package com.dsh.android.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.text.Normalizer;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令护栏：在 bash 工具真正执行前判断"这条命令会不会毁掉运行环境/用户数据"。
 *
 * 与之前的做法（shell 里覆盖 rm、JS 钩子用正则）的区别：
 *   - **先解析再判断**：按 ; && || | 切段，跟踪 cd，把相对路径按 cwd 解析成绝对路径，
 *     再去掉 . 和 .. 段。所以 'cd /usr && rm -rf share' 能看到它是 /usr/share，
 *     'cd / && rm -rf *' 能看到目标就是 /bin /etc 这些顶层目录；
 *   - **区分动词和目标**：rm 删 /tmp 放行，删 /usr 拦截 —— 判据是解析后的目标，
 *     不是命令里有没有 "rm"；
 *   - **分级处置**：BLOCK（确定性、零误报）/ ASK（需要用户点头）/ ALLOW。
 *
 * 这份逻辑与 tools/guard/feat.py 的特征提取是同一套规则（训练模型时用），
 * 改动任何一边都要同步，否则训练与线上会不一致。
 */
public final class CommandGuard {

    public enum Action { ALLOW, ASK, BLOCK }

    public static final class Verdict {
        public final Action action;
        public final String reason;
        public final String category;

        Verdict(Action action, String category, String reason) {
            this.action = action;
            this.category = category;
            this.reason = reason;
        }

        static Verdict allow() {
            return new Verdict(Action.ALLOW, "", "");
        }
    }

    /** 顶层系统目录：删/改它们 = 毁掉 rootfs。 */
    private static final Set<String> SYSROOT = new HashSet<>(Arrays.asList(
            "/", "/bin", "/sbin", "/lib", "/lib64", "/usr", "/etc", "/boot", "/var",
            "/opt", "/srv", "/run", "/proc", "/sys", "/dev", "/root", "/home", "/media", "/mnt"));

    /** 宿主共享存储：删/写它们 = 毁用户数据。 */
    private static final Set<String> SHARE = new HashSet<>(Arrays.asList(
            "/sdcard", "/storage", "/storage/emulated/0", "/mnt/sdcard"));

    private static final Set<String> SHELLS = new HashSet<>(Arrays.asList(
            "sh", "bash", "zsh", "dash", "ksh", "fish"));

    /** 这些目录下面任何一层都不能删（删了系统就废）。 */
    private static final Set<String> CRITICAL_TOP = new HashSet<>(Arrays.asList(
            "/usr", "/etc", "/bin", "/sbin", "/lib", "/lib64", "/boot"));

    private static final Set<String> FS_TOOLS = new HashSet<>(Arrays.asList(
            "mkfs", "fdisk", "wipefs", "blkdiscard", "parted", "sgdisk"));

    private static final Set<String> DESTRUCTIVE = new HashSet<>(Arrays.asList(
            "rm", "rmdir", "mv", "chmod", "chown", "truncate", "shred", "tee", "rsync", "cp"));

    /** 这些前缀不改变命令语义，但会让 verb 变成 sudo/env/... 从而绕过动词匹配。 */
    private static final Set<String> WRAPPERS = new HashSet<>(Arrays.asList(
            "sudo", "env", "nice", "nohup", "command", "time", "exec", "setsid",
            "ionice", "taskset", "stdbuf", "timeout", "strace", "ltrace"));

    private static final Pattern CRED = Pattern.compile(
            "/(\\.ssh/|id_rsa|id_ed25519|authorized_keys)|/etc/shadow|/etc/sudoers"
                    + "|\\.aws/credentials|\\.netrc|\\.config/gh/hosts.yml");

    private static final Pattern NET = Pattern.compile(
            "\\b(curl|wget|nc|ncat|socat|scp|telnet|ftp)\\b");

    private static final Pattern REVERSE = Pattern.compile(
            "/dev/tcp/|\\bnc\\s+-[a-z]*e\\b|ncat\\s+--exec");

    private static final Pattern FORK_BOMB = Pattern.compile(
            "\\)\\s*\\{.*\\|.*&|fork\\s*while\\s*fork");

    private static final Pattern DISK_FILL = Pattern.compile(
            "(count|bs)=\\S*0000|head\\s+-c\\s+\\d{6,}|yes\\s*>|/dev/zero\\s*>|"
                    + "fallocate\\s+-l\\s+\\d+G");

    private CommandGuard() {
    }

    /** 入口：判断这条命令该怎么处置。 */
    public static Verdict check(String command) {
        return check(command, 0);
    }

    private static Verdict check(String command, int depth) {
        if (command == null) return Verdict.allow();
        if (depth > 3) return Verdict.allow();
        String cmd = command;

        if (FORK_BOMB.matcher(cmd).find()) {
            return new Verdict(Action.BLOCK, "fork_bomb", "fork 炸弹会把手机拖死");
        }

        List<String[]> items = segments(cmd);
        String cwd = "/root/workspace";
        // 变量表：R=rm; $R -rf / 这种间接写法必须先还原，否则只看得到 $R
        java.util.Map<String, String> vars = new java.util.HashMap<>();
        // 常用环境变量：shell 里一定有值，静态分析也要给上，否则 "$HOME"/"$PWD" 永远解析不了
        vars.put("HOME", "/root");
        vars.put("PWD", cwd);
        boolean pathRisk = false;

        for (int i = 0; i < items.size(); i++) {
            String sep = items.get(i)[0];
            // 字符级归一化：反斜杠转义、ANSI-C 转义、零宽字符、全角字符
            String seg = canonicalize(items.get(i)[1]);
            List<String> toks = tokens(seg);
            if (toks.isEmpty()) continue;

            // 前导 VAR=value（含 PATH=...:$PATH）不是命令本身，跳过并记账
            int vi = 0;
            while (vi < toks.size() && isAssignment(toks.get(vi))) {
                String t = toks.get(vi);
                int eq = t.indexOf('=');
                String name = t.substring(0, eq);
                // 赋值值里也可能有 $'\x2fetc' 这类写法，先剥壳再展开
                String val = canonicalize(unquote(t.substring(eq + 1)));
                vars.put(name, resolveVars(val, vars, 0));
                // PATH 被指到可写目录 = 可以在系统命令前插个假的；先记下，别盖过更严重的 BLOCK
                if ("PATH".equals(name) && pointsToWritable(val)) pathRisk = true;
                vi++;
            }
            if (vi >= toks.size()) continue;      // 纯赋值段，没有命令

            String head = resolveVars(unquote(toks.get(vi)), vars, 0);
            // X="rm -rf /"; $X —— 变量里装的是**整条命令**，展开后再判一遍
            if (head.indexOf(' ') >= 0) {
                StringBuilder inner = new StringBuilder(head);
                for (int k = vi + 1; k < toks.size(); k++) {
                    inner.append(' ').append(resolveVars(toks.get(k), vars, 0));
                }
                Verdict nested = check(inner.toString(), depth + 1);
                if (nested.action != Action.ALLOW) return nested;
            }
            String verb = base(unquote(head));

            // sudo/env/nice/nohup/command/time/exec 等包装动词会让 verb 不是危险动词，
            // 从而绕过 DESTRUCTIVE。找到内层真正的命令后递归判一遍。
            if (WRAPPERS.contains(verb)) {
                for (int k = vi + 1; k < toks.size() && k < vi + 8; k++) {
                    if (!looksLikeCommand(toks.get(k))) continue;
                    StringBuilder inner = new StringBuilder();
                    for (int q = k; q < toks.size(); q++) {
                        if (inner.length() > 0) inner.append(' ');
                        inner.append(toks.get(q));
                    }
                    Verdict nested = check(inner.toString(), depth + 1);
                    if (nested.action != Action.ALLOW) return nested;
                    break;
                }
            }

            // sh -c '...' / bash -c "..."：把内层命令**递归**判一遍，
            // 否则一层 shell 包裹就能绕过（"sh -c 'rm -rf /'"）。
            if (SHELLS.contains(verb)) {
                int ci = toks.indexOf("-c");
                if (ci >= 0) {
                    StringBuilder inner = new StringBuilder();
                    for (int k = ci + 1; k < toks.size(); k++) {
                        if (inner.length() > 0) inner.append(' ');
                        inner.append(toks.get(k));
                    }
                    Verdict v = check(unquote(inner.toString()), depth + 1);
                    if (v.action != Action.ALLOW) return v;
                }
            }

            // cd 跟踪：这是能看穿 'cd /usr && rm -rf share' 的关键
            if ("cd".equals(verb) && toks.size() > 1) {
                String np = normalize(resolveVars(toks.get(1), vars, 0), cwd);
                if (np != null) {
                    cwd = np;
                    vars.put("PWD", cwd);        // cd 之后 $PWD 跟着变
                }
                continue;
            }

            boolean recursive = false;
            boolean force = false;
            boolean deleteFlag = false;
            for (String t : toks.subList(1, toks.size())) {
                if (t.startsWith("-")) {
                    String f = t;
                    if (f.equals("--recursive")) recursive = true;
                    else if (f.equals("--force")) force = true;
                    else if (f.equals("-delete")) deleteFlag = true;
                    else if (!f.startsWith("--")) {
                        if (f.indexOf('r') >= 0 || f.indexOf('R') >= 0) recursive = true;
                        if (f.indexOf('f') >= 0) force = true;
                    }
            } else if (t.equals("-delete")) {
                deleteFlag = true;
            }
            }

            List<String> targets = new ArrayList<>();
            List<String> classes = new ArrayList<>();
            for (int k = vi + 1; k < toks.size(); k++) {
                String t = resolveVars(toks.get(k), vars, 0);
                if (t.startsWith("-")) continue;
                if (t.contains("=") && !t.startsWith("/")) {
                    String key = t.substring(0, t.indexOf('='));
                    if (key.equals("of") || key.equals("target") || key.equals("path")
                            || key.equals("file") || key.equals("output")) {
                        t = t.substring(t.indexOf('=') + 1);
                    } else {
                        continue;
                    }
                }
                // 纯数字是参数值（truncate -s 0），不是路径
                if (t.matches("[0-9]+")) continue;
                // 目标里还有命令替换（$(...) 或反引号）：静态分析不了，交给下一道问用户
                if (t.contains("$(") || t.indexOf((char) 96) >= 0) {
                    return new Verdict(Action.ASK, "dynamic_path",
                            "目标路径是运行时才确定的（" + t + "），无法静态判断指向哪里");
                }
                String np = normalize(t, cwd);
                if (np == null) continue;
                targets.add(np);
                String cls = classify(np);
                if (cls != null) classes.add(cls);
                if ("find".equals(verb)) break;      // find 只看起点
            }

            boolean protectedTarget = false;
            for (String c : classes) {
                if (c.equals("root") || c.equals("share") || c.startsWith("sys:")
                        || c.equals("device") || c.equals("workspace_root")) {
                    protectedTarget = true;
                    break;
                }
            }

            // ln -sf 顶掉凭据/配置：不删不写，效果等同替换，DESTRUCTIVE 里没有它，
            // 之前 'ln -sf /tmp/k /root/.ssh/id_rsa' 一路 ALLOW。
            if ("ln".equals(verb) || "link".equals(verb)) {
                String link = null;
                for (int k = vi + 1; k < toks.size(); k++) {
                    String t = resolveVars(toks.get(k), vars, 0);
                    if (t.startsWith("-") || (t.contains("=") && !t.startsWith("/"))) continue;
                    link = t;                       // 最后一个非选项参数 = 被创建的链接
                }
                if (link != null) {
                    String np = normalize(link, cwd);
                    String cls = np == null ? null : classify(np);
                    if (np != null && isCriticalLink(np)) {
                        return new Verdict(Action.BLOCK, "symlink_overwrite",
                                "软链会顶掉受保护文件：" + np);
                    }
                    if (force && cls != null && (cls.startsWith("sys:") || cls.equals("share")
                            || cls.equals("device") || cls.equals("root"))) {
                        // /usr/local/bin 这类是日常软链操作，不硬拦，交给用户确认
                        return new Verdict(Action.ASK, "symlink_target",
                                "强制软链指向受保护路径：" + np + "（" + cls + "）");
                    }
                }
            }

            if (protectedTarget && DESTRUCTIVE.contains(verb) && !"cp".equals(verb)) {
                return new Verdict(Action.BLOCK, "destroy_protected",
                        "会破坏受保护路径：" + first(classes) + "（" + verb + " " + shorten(targets) + "）");
            }
            if ("find".equals(verb) && deleteFlag && protectedTarget) {
                return new Verdict(Action.BLOCK, "destroy_protected",
                        "find -delete 作用在受保护路径：" + shorten(targets));
            }
            if ("dd".equals(verb) && classes.contains("device")) {
                return new Verdict(Action.BLOCK, "block_device",
                        "dd 直接写块设备：" + shorten(targets));
            }
            if (FS_TOOLS.contains(verb) || verb.startsWith("mkfs.")) {
                return new Verdict(Action.BLOCK, "block_device",
                        "文件系统级操作（" + verb + "）会毁掉设备数据");
            }
            if ("kill".equals(verb) && (toks.contains("-1") || toks.contains("--1"))) {
                return new Verdict(Action.BLOCK, "kill_all", "kill -1 会杀掉所有进程");
            }

            // 重定向/管道危险
            Matcher red = Pattern.compile(">>?\\s*(\\S+)").matcher(seg);
            if (red.find()) {
                String tgt = resolveVars(red.group(1), vars, 0);
                boolean discard = tgt.startsWith("/dev/null") || tgt.startsWith("/dev/std")
                        || tgt.startsWith("&");
                String np = discard ? null : normalize(tgt, cwd);
                String cls = np == null ? null : classify(np);
                if (cls != null && (cls.startsWith("sys:") || cls.equals("share")
                        || cls.equals("root") || cls.equals("device"))) {
                    return new Verdict(Action.BLOCK, "overwrite_protected",
                            "重定向会覆盖受保护文件：" + np);
                }
            }
            if (i + 1 < items.size() && "|".equals(items.get(i + 1)[0])) {
                List<String> nt = tokens(items.get(i + 1)[1]);
                String nv = nt.isEmpty() ? "" : base(unquote(nt.get(0)));
                if (SHELLS.contains(nv)) {
                    return new Verdict(Action.ASK, "curl_pipe_sh",
                            "把内容直接管道给 shell 执行（" + nv + "）：请先确认来源可信");
                }
            }

            if (CRED.matcher(seg).find() && NET.matcher(seg).find()) {
                return new Verdict(Action.ASK, "creds_exfil",
                        "疑似读取凭证并发送到网络");
            }
            if (REVERSE.matcher(seg).find()) {
                return new Verdict(Action.ASK, "reverse_shell", "疑似反弹 shell");
            }
            if (("apt".equals(verb) || "apt-get".equals(verb) || "dpkg".equals(verb))
                    && seg.matches("(?s).*\\b(remove|purge|--remove|--purge|-r)\\b.*")) {
                return new Verdict(Action.ASK, "pkg_remove", "卸载软件包是不可逆的");
            }
            if (DISK_FILL.matcher(seg).find()) {
                return new Verdict(Action.ASK, "disk_fill", "可能在把磁盘写满");
            }
        }
        if (pathRisk) {
            return new Verdict(Action.ASK, "path_hijack",
                    "PATH 被指向可写目录，可能劫持系统命令");
        }
        return Verdict.allow();
    }

    private static boolean isPipe(List<String> segs, int i) {
        // segments() 已按分隔符切好；这里用"下一段存在且原串里是 |"的近似判断
        return true;
    }

    private static String first(List<String> l) {
        return l.isEmpty() ? "" : l.get(0);
    }

    private static String shorten(List<String> l) {
        if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 3; i++) {
            if (i > 0) sb.append(' ');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // ---------- 解析 ----------

    /** 切段并记住"这一段左边是什么分隔符"，管道判断要用。 */
    private static List<String[]> segments(String cmd) {
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String pending = "";
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            char n = i + 1 < cmd.length() ? cmd.charAt(i + 1) : 0;
            String sep = null;
            if ((c == '&' && n == '&') || (c == '|' && n == '|')) {
                sep = "" + c + n;
                i++;
            } else if (c == ';' || c == '|' || c == '\n') {
                sep = String.valueOf(c);
            }
            if (sep != null) {
                add(out, cur, pending);
                pending = sep;
                continue;
            }
            cur.append(c);
        }
        add(out, cur, pending);
        return out;
    }

    private static void add(List<String[]> out, StringBuilder cur, String sep) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) out.add(new String[]{sep, s});
        cur.setLength(0);
    }

    private static List<String> tokens(String seg) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String unquote(String s) {
        String t = s.trim();
        // bash 的 $'...'：内部 \xNN / \NNN 已由 canonicalize 展开，这里只剥壳
        if (t.length() >= 3 && t.startsWith("$'") && t.endsWith("'")) {
            return t.substring(2, t.length() - 1);
        }
        while (t.length() >= 2 && ((t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')
                || (t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** NAME=value（不改写 $VAR 展开后的值）。 */
    private static boolean isAssignment(String t) {
        int eq = t.indexOf('=');
        if (eq <= 0) return false;
        for (int i = 0; i < eq; i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        char c0 = t.charAt(0);
        return !(c0 >= '0' && c0 <= '9');
    }

    /** 展开 $VAR / 美元花括号 VAR / 单引号包裹的同名写法，最多 3 轮（A=$B; B=rm）。 */
    static String resolveVars(String s, java.util.Map<String, String> vars, int depth) {
        if (s == null || s.indexOf('$') < 0 || depth > 3) return s;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '$' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(i + 1);
            String name = null;
            int end = i + 1;                 // 变量引用结束后的第一个下标
            if (n == '{') {
                int close = s.indexOf('}', i + 2);
                if (close > i + 2) {
                    name = s.substring(i + 2, close);
                    end = close + 1;
                }
            } else if (Character.isLetter(n) || n == '_') {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j))
                        || s.charAt(j) == '_')) {
                    j++;
                }
                name = s.substring(i + 1, j);
                end = j;
            }
            String val = name == null ? null : vars.get(name);
            if (val == null) {
                out.append(c);
                continue;
            }
            out.append(resolveVars(val, vars, depth + 1));
            i = end - 1;                     // for 循环还会 ++，正好落在 end
        }
        return out.toString();
    }

    private static boolean pointsToWritable(String val) {
        return val.contains("/root/workspace") || val.contains("/tmp") || val.contains("/sdcard");
    }

    private static String base(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /** 哪些链接目标一旦被顶掉就是确定性破坏 —— 走 BLOCK。 */

    /** 是否像一个"真正干活"的命令（包装动词后面要找的就是它）。 */
    private static boolean looksLikeCommand(String t) {
        String v = base(unquote(canonicalize(t)));
        return DESTRUCTIVE.contains(v) || FS_TOOLS.contains(v) || v.startsWith("mkfs")
                || v.equals("ln") || v.equals("link") || v.equals("unlink")
                || v.equals("find") || v.equals("dd") || v.equals("kill")
                || v.equals("pkill") || v.equals("killall")
                || SHELLS.contains(v) || v.startsWith("apt") || v.equals("dpkg");
    }

    private static boolean isCriticalLink(String np) {
        if (np.equals("/")) return true;
        if (CRED.matcher(np).find()) return true;
        if (np.startsWith("/etc/")) return true;
        return np.startsWith("/root/") && !np.startsWith("/root/workspace");
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** 零宽/格式字符、C0 控制字符：肉眼看不见，但能让字符串匹配失效。 */
    private static boolean invisible(char c) {
        return (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2060 && c <= 0x206F) || c == 0xFEFF || c == 0x00AD
                || (c < 0x20 && c != '\n' && c != '\t' && c != '\r');
    }

    /**
     * 字符级归一化：把 shell 解析阶段会消掉的转义先消掉，再做 Unicode NFKC。
     * 覆盖：反斜杠转义、\\xHH、\\0NNN、反斜杠续行、ANSI-C 引号外壳、
     * 零宽字符、全角斜杠/点。所有规则都基于归一化后的文本。
     */
    static String canonicalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 3 && t.startsWith("$'") && t.endsWith("'")) {
            t = t.substring(2, t.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (invisible(c)) continue;
            if (c == '\\' && i + 1 < t.length()) {
                char n = t.charAt(i + 1);
                if (n == '\n') { i++; continue; }
                if ((n == 'x' || n == 'X') && i + 3 < t.length()) {
                    int hi = hexVal(t.charAt(i + 2)), lo = hexVal(t.charAt(i + 3));
                    if (hi >= 0 && lo >= 0) {
                        sb.append((char) ((hi << 4) | lo));
                        i += 3;
                        continue;
                    }
                }
                if (n >= '0' && n <= '7') {
                    int j = i + 1, v = 0, d = 0;
                    while (j < t.length() && d < 3 && t.charAt(j) >= '0' && t.charAt(j) <= '7') {
                        v = v * 8 + (t.charAt(j) - '0');
                        j++;
                        d++;
                    }
                    sb.append((char) (v & 0xFF));
                    i = j - 1;
                    continue;
                }
                sb.append(n);
                i++;
                continue;
            }
            sb.append(c);
        }
        String out = Normalizer.normalize(sb.toString(), Normalizer.Form.NFKC);
        return out.replace('\uFF0F', '/').replace('\u2215', '/')
                .replace('\uFF0E', '.').replace('\uFF3C', '\\');
    }

    /** 解析成绝对路径（相对 cwd，展开 ~，折叠 . 与 ..）；纯选项返回 null。 */
    static String normalize(String tok, String cwd) {
        // 路径里的引号一律去掉：shell 在解析阶段就去掉了，"$HOME"/ 这类必须当成 /root/
        String p = unquote(tok).replace("\"", "").replace("'", "");
        if (p.isEmpty() || p.startsWith("-")) return null;
        if (p.contains("=") && !p.startsWith("/")) return null;
        if (p.startsWith("~")) p = "/root" + p.substring(1);
        if (!p.startsWith("/")) p = cwd + "/" + p;
        String[] parts = p.split("/");
        List<String> stack = new ArrayList<>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                continue;
            }
            stack.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : stack) sb.append('/').append(s);
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /**
     * 目标分类：root / sys:<top> / share / device / workspace_root / workspace / tmp / other。
     *
     * 关键一步是**剥掉尾部通配段**：'rm -rf *'（在 / 下）解析出来是 '/*'，
     * 它等价于删掉所有顶层目录，必须按 '/' 对待；'/sdcard/*' 同理按 /sdcard。
     */
    static String classify(String p) {
        if (p == null) return null;
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if (!s.isEmpty()) parts.add(s);
        }
        while (!parts.isEmpty() && isGlob(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : parts) sb.append('/').append(s);
        String path = sb.length() == 0 ? "/" : sb.toString();

        if (path.equals("/root/workspace")) return "workspace_root";
        if (path.startsWith("/root/workspace/")) return "workspace";
        if (path.equals("/")) return "root";
        if (path.equals("/root")) return "sys:/root";
        String top = "/" + (parts.isEmpty() ? "" : parts.get(0));
        if (SHARE.contains(top)) return "share";
        if (path.startsWith("/dev/") && !path.equals("/dev")) return "device";
        // 系统目录：任意深度都算受保护（删 /usr/share/doc 也会毁掉系统）
        if (CRITICAL_TOP.contains(top)) return "sys:" + top;
        // 其它顶层目录：只有删它本身才算（/var 危险，/var/lib/apt/lists 是正常清理）
        if (parts.size() == 1 && SYSROOT.contains(top)) return "sys:" + top;
        if (path.startsWith("/tmp") || path.startsWith("/var/tmp")) return "tmp";
        return "other";
    }

    private static boolean isGlob(String seg) {
        return seg.indexOf('*') >= 0 || seg.indexOf('?') >= 0 || seg.indexOf('[') >= 0;
    }
}
