package com.dsh.android.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令的语义特征提取 —— **与 tools/guard/feat.py 逐行对应**。
 *
 * 这份代码只做一件事：把一条 bash 命令变成一张 名称 -> 计数 的表。
 * 训练时用 Python 版，线上用这份 Java 版，两者必须逐条算出同样的特征，
 * 否则模型看到的输入分布和训练时不一致（不报错，只是悄悄变笨）。
 * 所以：**改这里必须同步改 feat.py，并跑 parity 对拍**。
 */
public final class Features {

    static final Set<String> SYSROOT = new HashSet<>(Arrays.asList(
            "/", "/bin", "/sbin", "/lib", "/lib64", "/usr", "/etc", "/boot", "/var",
            "/opt", "/srv", "/run", "/proc", "/sys", "/dev", "/root", "/home", "/media", "/mnt"));

    static final Set<String> CRITICAL_TOP = new HashSet<>(Arrays.asList(
            "/usr", "/etc", "/bin", "/sbin", "/lib", "/lib64", "/boot"));

    static final Set<String> SHARE = new HashSet<>(Arrays.asList(
            "/sdcard", "/storage", "/mnt/sdcard", "/storage/emulated/0"));

    static final Set<String> SHELLS = new HashSet<>(Arrays.asList(
            "sh", "bash", "zsh", "dash", "ksh", "fish"));

    static final List<String> VERBS = Arrays.asList(
            "rm", "find", "dd", "chmod", "chown", "mv", "cp", "truncate", "shred", "mkfs",
            "fdisk", "kill", "pkill", "killall", "apt", "apt-get", "dpkg", "curl", "wget",
            "nc", "ncat", "socat", "python", "python3", "perl", "php", "sed", "tee", "rsync",
            "wipefs", "blkdiscard", "parted", "sgdisk", "fallocate", "yes", "seq", "tar");

    static final String[] CRED = {"/.ssh", "id_rsa", "id_ed25519", "authorized_keys",
            "/etc/shadow", "/etc/sudoers", ".aws/credentials", ".config/gh/hosts.yml", ".netrc"};

    private static final Pattern FORKBOMB = Pattern.compile("\\)\\s*\\{.*\\|.*&");
    private static final Pattern FORKWHILE = Pattern.compile("fork\\s*while\\s*fork");
    private static final Pattern BASHC = Pattern.compile("bash\\s+-c|sh\\s+-c");
    private static final Pattern REDIRECT = Pattern.compile(">>?\\s*(\\S+)");
    private static final Pattern DEV_ACTION = Pattern.compile(
            "\\b(venv|virtualenv|activate|pip3?\\s+install|python3?\\s+-m\\s+pip"
                    + "|npm\\s+(run|install|ci)|yarn\\s|pnpm\\s|\\bmake\\b|cmake|pytest|cargo\\s"
                    + "|go\\s+(build|test|run)|javac|\\bnode\\s)");
    private static final Pattern CURLWGET = Pattern.compile("\\b(curl|wget)\\b");
    private static final Pattern NET_TOOL = Pattern.compile("\\b(curl|wget|nc|ncat|socat|scp|telnet|ftp)\\b");
    private static final Pattern NET_ANY = Pattern.compile("\\b(curl|wget|nc|ncat|socat|scp|telnet)\\b");
    private static final Pattern REVERSE = Pattern.compile("/dev/tcp/|\\bnc\\s+-[a-z]*e\\b|ncat\\s+--exec");
    private static final Pattern PIPE_NET = Pattern.compile("\\b(nc|ncat|socat|curl|wget|telnet)\\b");
    private static final Pattern PERSIST = Pattern.compile(
            "\\b(crontab|systemctl\\s+(disable|mask|stop)|usermod|userdel|chattr|chpasswd)\\b");
    private static final Pattern RC_WRITE = Pattern.compile(
            ">>?\\s*\\S*(\\.bashrc|\\.profile|authorized_keys|/etc/sudoers)");
    private static final Pattern PROC_WRITE = Pattern.compile(">\\s*/proc/(sys|self|1)/");
    private static final Pattern SERVE_ROOT = Pattern.compile("--directory\\s+/(\\s|$)");
    private static final Pattern ENCODE_PIPE = Pattern.compile(
            "\\|\\s*(base64|xxd|uudecode|openssl)\\s+-[dr]\\S*.*\\|\\s*(sh|bash)");
    private static final Pattern EVAL_REMOTE = Pattern.compile("\\b(eval|source)\\b");
    private static final Pattern SVC = Pattern.compile("\\b(crontab|systemctl)\\b");
    private static final Pattern PKG_REMOVE = Pattern.compile("\\b(remove|purge|-r\\b|--remove|--purge)\\b");
    private static final Pattern DISK_FILL = Pattern.compile(
            "(count|bs)=\\S*0000|head\\s+-c\\s+\\d{6,}|yes\\s*>|/dev/zero\\s*>|fallocate\\s+-l\\s+\\d+G");
    private static final Pattern URL = Pattern.compile("https?://");
    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)\\}|\\$(\\w+)");
    private static final Pattern SUBST = Pattern.compile("\\$\\(([^()]*)\\)");
    private static final Pattern ASSIGN = Pattern.compile("^(?:export\\s+)?([A-Za-z_]\\w*)=(.+)$");
    private static final Pattern ASSIGN_HEAD =
            Pattern.compile("^\\s*(export\\s+)?[A-Za-z_]\\w*=");
    private static final Pattern SUBST_LIT =
            Pattern.compile("^(?:echo|printf\\s+%s|printf\\s+%s\\\\n|echo\\s+-n)\\s+(\\S+)$");

    private Features() {
    }

    /** 切段：返回 [分隔符, 文本]，分隔符是**这一段左边**的那个（第一段是 ""）。 */
    public static List<String[]> segments(String cmd) {
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

    public static List<String> tokens(String seg) {
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

    public static String unquote(String s) {
        String t = s.trim();
        while (t.length() >= 2 && ((t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')
                || (t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    public static String base(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /** 相对路径按 cwd 解析、展开 ~、折叠 . 与 ..；纯选项/URL/数字返回 null。 */
    public static String normPath(String tok, String cwd) {
        String p = unquote(tok);
        if (p.isEmpty() || p.startsWith("-")) return null;
        if ((p.contains("=") && !p.startsWith("/")) || p.contains("://")) return null;
        if (p.startsWith("~")) p = "/root" + p.substring(1);
        if (!p.startsWith("/")) p = cwd + "/" + p;
        List<String> stack = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(s);
            }
        }
        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : stack) sb.append('/').append(s);
        return sb.toString();
    }

    /** 目标分类。**逐行对应 feat.py 的 pclass**（注意：训练时没有剥通配段）。 */
    public static String pclass(String p) {
        if (p == null) return null;
        if (p.equals("/root/workspace")) return "workspace_root";
        if (p.startsWith("/root/workspace/")) return "workspace";
        if (p.equals("/")) return "root";
        if (p.equals("/root")) return "sys:/root";
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) if (!s.isEmpty()) parts.add(s);
        String top = "/" + (parts.isEmpty() ? "" : parts.get(0));
        if (SHARE.contains(top)) return "share";
        if (p.startsWith("/dev/") && !p.equals("/dev")) return "device";
        if (CRITICAL_TOP.contains(top)) return "sys:" + top;
        if (parts.size() == 1 && SYSROOT.contains(top)) return "sys:" + top;
        if (p.startsWith("/tmp") || p.startsWith("/var/tmp")) return "tmp";
        return "other";
    }

    /** 展开已知变量（对应 feat.py expand）；解析不出的原样保留。 */
    static String expand(String in, Map<String, String> varz) {
        String s = in;
        for (int d = 0; d < 4 && s.indexOf('$') >= 0; d++) {
            Matcher m = VAR.matcher(s);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                String v = varz.get(name);
                if (v != null) changed = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
            }
            m.appendTail(sb);
            String out = sb.toString();
            if (!changed || out.equals(s)) break;
            s = out;
        }
        return s;
    }

    /** $(echo /var) 这类命令替换取字面量；还有没解析出来的就返回 null。 */
    static String substLiteral(String in) {
        if (!in.contains("$(")) return null;
        Matcher m = SUBST.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1).trim();
            Matcher mm = SUBST_LIT.matcher(inner);
            String rep = mm.matches() ? mm.group(1) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        String out = sb.toString();
        return out.contains("$(") ? null : out;
    }

    /** char/word n-gram 只看"干活"的段：环境准备语句会把明确信号淹掉。 */
    public static String operative(String cmd) {
        List<String> keep = new ArrayList<>();
        for (String[] seg : segments(cmd)) {
            if (!seg[1].isEmpty() && !ASSIGN_HEAD.matcher(seg[1]).find()) keep.add(seg[1]);
        }
        return keep.isEmpty() ? cmd : String.join(" ; ", keep);
    }

    public static Map<String, Integer> features(String cmd) {
        // 与 feat.py 一致：先抹掉"丢弃输出"的重定向（> /dev/null、2>&1），
        // 否则 'ls > /dev/null' 会被当成写设备文件，而 'rm -rf / 2>/dev/null' 又会因此变"安全"。
        cmd = cmd.replaceAll("\\d?>>?\\s*/dev/null|\\d?>&\\d|\\d?>>?\\s*&[12]", " ");
        Map<String, Integer> f = new HashMap<>();
        List<String[]> segs = segments(cmd);
        String cwd = "/root/workspace";
        Map<String, String> varz = new HashMap<>();
        varz.put("HOME", "/root");
        varz.put("PWD", "/root/workspace");
        varz.put("TMPDIR", "/tmp");
        varz.put("USER", "root");
        put(f, "len>200", cmd.length() > 200 ? 1 : 0);
        put(f, "multiline", cmd.indexOf('\n') >= 0 ? 1 : 0);
        put(f, "forkbomb", FORKBOMB.matcher(cmd).find() || FORKWHILE.matcher(cmd).find() ? 1 : 0);
        put(f, "has_subst", cmd.contains("$(") || cmd.indexOf((char) 96) >= 0 ? 1 : 0);
        put(f, "has_bash_c", BASHC.matcher(cmd).find() ? 1 : 0);

        for (int idx = 0; idx < segs.size(); idx++) {
            String sep = segs.get(idx)[0];
            String seg = segs.get(idx)[1];
            // $(echo /var)/log/* 这种先还原再分词，否则空格会把 token 拆断
            String litSeg = substLiteral(seg);
            if (litSeg != null) seg = litSeg;
            List<String> toks = tokens(seg);
            if (toks.isEmpty()) continue;

            if (toks.get(0).equals("cd") && toks.size() > 1) {
                String np = normPath(toks.get(1), cwd);
                if (np != null) cwd = np;
                inc(f, "cd");
                if ("/".equals(np)) inc(f, "cd_root");
                continue;
            }

            // 纯赋值段：记进变量表，后面 $X 才能还原成真实路径
            Matcher am = ASSIGN.matcher(seg.trim());
            if (am.matches()) {
                String name = am.group(1);
                String val = unquote(expand(am.group(2).trim(), varz));
                varz.put(name, val);
                inc(f, "var_assign");
                String lit = substLiteral(val);
                String pc = pclass(normPath(lit != null ? lit : val, cwd));
                if (pc != null && !pc.equals("other") && !pc.equals("tmp")
                        && !pc.equals("workspace")) {
                    inc(f, "var_assign_protected");
                }
                String uv = unquote(val);
                String head = uv.isEmpty() ? "" : uv.split(" ")[0];
                if (VERBS.contains(head) || uv.startsWith("./") || uv.startsWith("/.")) {
                    inc(f, "var_assign_cmd");
                }
                continue;
            }

            String verb = base(unquote(toks.get(0)));
            if (SHELLS.contains(verb)) inc(f, "shell");
            if (VERBS.contains(verb)) inc(f, "verb:" + verb);

            boolean recursive = false;
            boolean force = false;
            boolean deleteFlag = false;
            for (int k = 1; k < toks.size(); k++) {
                String t = toks.get(k);
                if (t.equals("--recursive")) recursive = true;
                if (t.equals("--force")) force = true;
                if (t.equals("-delete")) deleteFlag = true;
                if (t.startsWith("-") && !t.startsWith("--") && t.length() > 1) {
                    if (t.indexOf('r') >= 0 || t.indexOf('R') >= 0) recursive = true;
                    if (t.indexOf('f') >= 0) force = true;
                }
            }
            deleteFlag = deleteFlag || (toks.contains("-exec") && containsRm(toks));

            List<String> targets = new ArrayList<>();
            List<String> classes = new ArrayList<>();
            for (int k = 1; k < toks.size(); k++) {
                String t = toks.get(k);
                if (t.startsWith("-")) continue;
                if (isDigits(t) || t.contains("://") || t.equals("-") || t.equals("|")
                        || t.equals("&") || t.equals(">")) {
                    continue;
                }
                if (t.contains("=") && !t.startsWith("/")) {
                    String key = t.substring(0, t.indexOf('='));
                    if (key.equals("of") || key.equals("target") || key.equals("path")
                            || key.equals("file") || key.equals("output")) {
                        t = t.substring(t.indexOf('=') + 1);
                    } else {
                        continue;
                    }
                }
                if (verb.equals("find") && !targets.isEmpty()) continue;
                // 先把 $X / $(...) 还原成字面量，再判目标类别
                if (t.contains("$")) {
                    String litT = substLiteral(t);
                    String t2 = expand(litT != null ? litT : t, varz);
                    if (t2.contains("$") || t2.indexOf((char) 96) >= 0) {
                        inc(f, "dyn_target");
                        continue;
                    }
                    t = t2;
                }
                String np = normPath(t, cwd);
                if (np == null) continue;
                targets.add(np);
                String c = pclass(np);
                if (c != null) {
                    classes.add(c);
                    inc(f, "tgt:" + c);
                    if (c.startsWith("sys:")) inc(f, "tgt_sys");
                    if (c.equals("root")) inc(f, "tgt_root");
                    if (c.equals("share")) inc(f, "tgt_share");
                    // /root 下非 workspace 的部分（.ssh / 配置）才是要害
                    if (np.startsWith("/root/") && !np.startsWith("/root/workspace")) {
                        inc(f, "tgt_root_sub");
                    }
                }
            }

            boolean protectedTarget = false;
            for (String t : targets) {
                if (t.startsWith("/root/") && !t.startsWith("/root/workspace")) {
                    protectedTarget = true;
                    break;
                }
            }
            for (String c : classes) {
                if (c.equals("root") || c.startsWith("sys:") || c.equals("share")
                        || c.equals("device")) {
                    protectedTarget = true;
                    break;
                }
            }
            if (protectedTarget) inc(f, "tgt_protected");

            if (verb.equals("rm") && recursive && !targets.isEmpty() && allCache(targets)) {
                inc(f, "cache_clean");
            }
            if ((verb.equals("chmod") || verb.equals("chown")) && !targets.isEmpty()
                    && allWorkspace(targets)) {
                inc(f, "workspace_perm");
            }
            if (recursive) inc(f, "recursive");
            if (force) inc(f, "force");
            if (recursive && force) inc(f, "rf");

            if (verb.equals("rm") && protectedTarget) {
                inc(f, "rm_protected");
                if (recursive && force) inc(f, "rm_rf_protected");
            }
            if (verb.equals("find") && deleteFlag && protectedTarget) inc(f, "find_del_protected");
            if ((verb.equals("chmod") || verb.equals("chown")) && recursive && protectedTarget) {
                inc(f, "perm_protected");
            }
            if (verb.equals("mv") && protectedTarget) inc(f, "mv_protected");
            if ((verb.equals("truncate") || verb.equals("shred")) && protectedTarget) {
                inc(f, "trunc_protected");
            }
            if (verb.equals("dd") && classes.contains("device")) inc(f, "dd_device");
            if (verb.equals("mkfs") || verb.equals("fdisk") || verb.equals("wipefs")
                    || verb.equals("blkdiscard") || verb.equals("parted") || verb.equals("sgdisk")) {
                inc(f, "fs_tool");
            }
            if (verb.equals("kill") || verb.equals("pkill") || verb.equals("killall")) {
                inc(f, "kill");
                boolean allProc = toks.contains("-1")
                        || (verb.equals("kill") && toks.size() > 1 && toks.get(1).equals("-1"));
                if (allProc) {
                    inc(f, "kill_all");
                } else if (verb.equals("kill") && hasNumericArg(toks)) {
                    inc(f, "kill_pid");
                }
                if (verb.equals("pkill") || verb.equals("killall")) inc(f, "kill_by_name");
            }

            Matcher red = REDIRECT.matcher(seg);
            if (red.find()) {
                String tgt = red.group(1);
                String litT = substLiteral(tgt);
                String np = normPath(expand(litT != null ? litT : tgt, varz), cwd);
                if (np != null) {
                    inc(f, "redirect");
                    String c = pclass(np);
                    if (c != null && (c.startsWith("sys:") || c.equals("share"))) {
                        inc(f, "redirect_protected");
                    } else if ("workspace".equals(c) || "tmp".equals(c)) {
                        inc(f, "redirect_local");
                    }
                }
            }

            String low = seg.toLowerCase(java.util.Locale.ROOT);
            for (String t : targets) {
                if (t.endsWith(".log") || t.startsWith("/var/log/")) {
                    inc(f, "log_path");
                    break;
                }
            }
            if (DEV_ACTION.matcher(low).find()) inc(f, "dev_action");
            boolean pipedNext = idx < segs.size() - 1 && "|".equals(segs.get(idx + 1)[0]);
            if (CURLWGET.matcher(low).find() && !seg.contains("|") && !pipedNext) {
                inc(f, "download_only");
            }
            if (containsCred(low)) {
                if (low.contains(".pub")) inc(f, "pub_key");
                else inc(f, "cred_path");
            }
            if (NET_TOOL.matcher(low).find()) inc(f, "net_tool");
            if (REVERSE.matcher(low).find()) inc(f, "reverse_shell");
            if (pipedNext) {
                String nxt = idx + 1 < segs.size() ? segs.get(idx + 1)[1] : "";
                List<String> nt = tokens(nxt);
                String nv = nt.isEmpty() ? "" : base(unquote(nt.get(0)));
                if (nv.equals("sudo") && nt.size() > 1) nv = base(unquote(nt.get(1)));
                if (SHELLS.contains(nv)) inc(f, "pipe_shell");
                if (PIPE_NET.matcher(nxt).find()) inc(f, "pipe_net");
            }
            if (verb.equals("xargs") && seg.contains("rm")) inc(f, "xargs_rm");
            if (verb.equals("tar") && seg.contains("--remove-files")) inc(f, "remove_files");
            if (verb.equals("find") && toks.contains("-exec")
                    && (toks.contains("chmod") || toks.contains("chown") || toks.contains("rm"))) {
                inc(f, "find_exec_mutate");
            }
            if (PERSIST.matcher(low).find()) inc(f, "persistence");
            if (RC_WRITE.matcher(seg).find()) inc(f, "shell_rc_write");
            if (PROC_WRITE.matcher(seg).find() || low.contains("sysrq")) inc(f, "proc_write");
            if (SERVE_ROOT.matcher(seg).find()) inc(f, "serve_root");
            if (ENCODE_PIPE.matcher(cmd).find()) inc(f, "encode_pipe");
            if (EVAL_REMOTE.matcher(low).find() && low.contains("curl")) inc(f, "eval_remote");
            if (verb.equals("ln")) {
                if (seg.contains("/dev/null")) inc(f, "ln_devnull");
                // 软链顶掉配置/密钥：不删不写，效果等同替换
                if (protectedTarget) inc(f, "ln_protected");
            }
            if (verb.equals("eval")
                    || (SHELLS.contains(verb) && toks.size() > 1 && toks.get(1).equals("-c"))) {
                String body = unquote(String.join(" ", toks.subList(1, toks.size())));
                for (String piece : body.split("[\\s;|&]+")) {
                    if (!piece.startsWith("/")) continue;
                    String pc = pclass(normPath(piece, cwd));
                    if (pc != null && !pc.equals("other") && !pc.equals("tmp")
                            && !pc.equals("workspace")) {
                        inc(f, "eval_protected");
                        break;
                    }
                }
            }
            if (SVC.matcher(low).find()) inc(f, "svc_change");
            if (verb.equals("apt") || verb.equals("apt-get") || verb.equals("dpkg")) {
                if (PKG_REMOVE.matcher(seg).find()) inc(f, "pkg_remove");
            }
            if (DISK_FILL.matcher(seg).find()) inc(f, "disk_fill");
            if (URL.matcher(low).find()) inc(f, "url");
        }

        String lowAll = cmd.toLowerCase(java.util.Locale.ROOT);
        if (containsCred(lowAll) && NET_ANY.matcher(lowAll).find()) inc(f, "cred_net");
        return f;
    }

    private static boolean containsRm(List<String> toks) {
        for (String t : toks) if (t.equals("rm")) return true;
        return false;
    }

    private static boolean hasNumericArg(List<String> toks) {
        for (int i = 1; i < toks.size(); i++) {
            String t = toks.get(i);
            if (!t.startsWith("-") && isDigits(t)) return true;
        }
        return false;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static boolean allCache(List<String> targets) {
        for (String t : targets) {
            if (!(t.startsWith("/var/cache/") || t.startsWith("/var/lib/apt/lists")
                    || t.startsWith("/var/tmp/") || t.startsWith("/var/log/")
                    || t.startsWith("/tmp/") || t.startsWith("/root/workspace/"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allWorkspace(List<String> targets) {
        for (String t : targets) if (!t.startsWith("/root/workspace/")) return false;
        return true;
    }

    private static boolean containsCred(String s) {
        for (String k : CRED) if (s.contains(k)) return true;
        return false;
    }

    private static void put(Map<String, Integer> f, String k, int v) {
        f.put(k, v);
    }

    private static void inc(Map<String, Integer> f, String k) {
        f.merge(k, 1, Integer::sum);
    }
}