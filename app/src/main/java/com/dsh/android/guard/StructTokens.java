package com.dsh.android.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 输入层结构标记化 —— 与 tools/guard/struct_tokens.py 逐行对应。 */
public final class StructTokens {

    private static final Pattern ASSIGN = Pattern.compile("^(?:export\\s+)?([A-Za-z_]\\w*)=(.+)$");
    private static final Pattern VARREF = Pattern.compile("\\$\\{(\\w+)\\}|\\$(\\w+)");
    private static final Pattern REDIR = Pattern.compile(">>?\\s*(\\S+)");
    private static final Pattern SUBST = Pattern.compile("\\$\\(([^()]*)\\)");
    private static final Pattern SUBST_LIT =
            Pattern.compile("^(?:echo|printf\\s+%s|echo\\s+-n)\\s+(\\S+)$");
    private static final Set<String> PROTECTED_CLASSES =
            new HashSet<>(Arrays.asList("root", "rootsub", "share", "device"));

    private StructTokens() {
    }

    private static Map<String, String> preset() {
        Map<String, String> m = new HashMap<>();
        m.put("HOME", "/root");
        m.put("PWD", "/root/workspace");
        m.put("TMPDIR", "/tmp");
        m.put("USER", "root");
        return m;
    }

    private static String clsOf(String path, String cwd) {
        String np = Features.normPath(path, cwd);
        String c = Features.pclass(np);
        if (c == null) c = "other";
        if (c.equals("other") && np != null && np.startsWith("/root/")
                && !np.startsWith("/root/workspace")) {
            return "rootsub";
        }
        return c;
    }

    private static boolean isProtected(String c) {
        return c.equals("root") || c.startsWith("sys:") || PROTECTED_CLASSES.contains(c);
    }

    private static Map<String, String>[] collectVars(List<String[]> segs) {
        Map<String, String> varz = preset();
        Map<String, String> vcls = new HashMap<>();
        for (String[] seg : segs) {
            Matcher am = ASSIGN.matcher(seg[1].trim());
            if (!am.matches()) continue;
            String name = am.group(1);
            String val = Features.unquote(Features.expand(am.group(2).trim(), varz));
            String lit = Features.substLiteral(val);
            varz.put(name, val);
            vcls.put(name, clsOf(lit != null ? lit : val, "/root/workspace"));
        }
        @SuppressWarnings("unchecked")
        Map<String, String>[] r = new Map[]{varz, vcls};
        return r;
    }

    private static List<String> varNames(List<String[]> segs) {
        List<String> out = new ArrayList<>();
        for (String[] seg : segs) {
            Matcher am = ASSIGN.matcher(seg[1].trim());
            if (am.matches()) out.add(am.group(1));
        }
        return out;
    }

    private static String expandedSeg(String seg, Map<String, String> varz) {
        String lit = Features.substLiteral(seg);
        return Features.expand(lit != null ? lit : seg, varz);
    }

    /** 规范化命令串：丢赋值段、还原命令替换、展开变量。 */
    public static String normalizeForChar(String cmd) {
        List<String[]> segs = Features.segments(cmd);
        Map<String, String> varz = collectVars(segs)[0];
        List<String> kept = new ArrayList<>();
        for (String[] seg : segs) {
            if (seg[1].isEmpty() || ASSIGN.matcher(seg[1].trim()).matches()) continue;
            String s = expandedSeg(seg[1], varz).trim();
            if (!s.isEmpty()) kept.add(s);
        }
        return kept.isEmpty() ? cmd : String.join(" ; ", kept);
    }

    private static List<String> varClasses(List<String> raw, Map<String, String> vcls) {
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            Matcher m = VARREF.matcher(t);
            while (m.find()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                if (vcls.containsKey(name)) out.add(vcls.get(name));
            }
        }
        return out;
    }

    private static String flags(List<String> toks, String expanded) {
        Set<String> f = new TreeSet<>();
        for (int i = 1; i < toks.size(); i++) {
            String x = toks.get(i);
            if (x.startsWith("-") && !x.startsWith("--") && x.length() > 1) {
                if (x.indexOf('r') >= 0 || x.indexOf('R') >= 0) f.add("rec");
                if (x.indexOf('f') >= 0) f.add("force");
            }
        }
        String joined = String.join(" ", toks);
        if (joined.contains("--recursive")) f.add("rec");
        if (joined.contains("--force")) f.add("force");
        if (expanded.contains(">")) f.add("write");
        return f.isEmpty() ? "plain" : String.join("+", f);
    }

    /** 结构标记列表，顺序无关。 */
    public static List<String> tokens(String cmd) {
        List<String[]> segs = Features.segments(cmd);
        Map<String, String>[] vars = collectVars(segs);
        Map<String, String> varz = vars[0];
        Map<String, String> vcls = vars[1];
        List<String> out = new ArrayList<>();
        List<String> names = varNames(segs);
        if (!names.isEmpty()) out.add("t:envprefix");
        for (String name : names) {
            out.add("t:vdef:" + vcls.get(name));
            String val = varz.get(name);
            String head = val == null || val.isEmpty() ? "" : Features.unquote(val).split(" ")[0];
            if (Features.VERBS.contains(head) || val.startsWith("./") || val.startsWith("/.")) {
                out.add("t:vdef_cmd");
            }
        }

        List<String> seq = new ArrayList<>();
        String cwd = "/root/workspace";
        for (String[] sg : segs) {
            String seg = sg[1];
            if (seg.isEmpty() || ASSIGN.matcher(seg.trim()).matches()) continue;
            List<String> raw = Features.tokens(seg);
            String expanded = expandedSeg(seg, varz);
            List<String> toks = Features.tokens(expanded);
            if (toks.isEmpty()) continue;
            if (toks.get(0).equals("cd") && toks.size() > 1) {
                String np = Features.normPath(toks.get(1), cwd);
                if (np != null) cwd = np;
                continue;
            }
            String act = Features.base(Features.unquote(toks.get(0)));
            if (act.isEmpty()) act = "unknown";

            for (String vc : varClasses(raw, vcls)) {
                out.add("t:vuse:" + vc);
                out.add("t:vchain:" + vc + ":" + act);
                if (isProtected(vc)) out.add("t:vchain:protected:" + act);
            }

            String flagtag = flags(toks, expanded);
            List<String> classes = new ArrayList<>();
            for (int i = 1; i < toks.size(); i++) {
                String t = toks.get(i);
                if (t.startsWith("-") || !t.contains("/") || t.contains("://")) continue;
                if (t.equals("/dev/null") || t.equals("/dev/zero") || t.startsWith("/dev/std")) continue;
                String c = clsOf(t, cwd);
                classes.add(c);
                out.add("t:act:" + act + ":" + c);
                if (t.contains("*") || t.contains("?")) out.add("t:glob:" + c);
                if (isProtected(c)) {
                    int depth = 0;
                    for (String part : t.split("/")) if (!part.isEmpty()) depth++;
                    out.add("t:depth:" + Math.min(depth, 5));
                }
            }
            if (classes.isEmpty()) classes.add("none");
            out.add("t:act:" + act);
            out.add("t:skel:" + act + ":" + flagtag + ":" + classes.get(0));
            seq.add(classes.get(0));

            Matcher rm = REDIR.matcher(expanded);
            if (rm.find()) {
                String target = rm.group(1);
                if (!target.equals("/dev/null") && !target.equals("/dev/zero")
                        && !target.equals("&1") && !target.equals("&2")
                        && !target.startsWith("/dev/std")) {
                    String rc = clsOf(target, cwd);
                    out.add("t:redir:" + rc);
                    if (isProtected(rc)) out.add("t:redir:protected");
                }
            }
            Matcher sm = SUBST.matcher(seg);
            while (sm.find()) {
                Matcher mm = SUBST_LIT.matcher(sm.group(1).trim());
                if (mm.matches()) out.add("t:subst:" + clsOf(mm.group(1), cwd));
                else out.add("t:subst:unresolved");
            }
            if (seg.indexOf((char) 96) >= 0 && !seg.contains("$(")) {
                out.add("t:subst:unresolved");
            }
            if (act.equals("ln")) out.add("t:symlink:" + classes.get(classes.size() - 1));
            if (act.equals("eval") || act.equals("source")
                    || (Features.SHELLS.contains(act) && toks.size() > 1 && toks.get(1).equals("-c"))) {
                out.add("t:eval");
            }
        }
        for (int i = 0; i + 1 < seq.size(); i++) {
            out.add("t:seq:" + seq.get(i) + ":" + seq.get(i + 1));
        }
        return out;
    }
}
