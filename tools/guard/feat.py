#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""把一条 bash 命令拆成**语义特征**（而不是字符 n-gram）。

为什么换特征：
  纯 char n-gram 在迁移集上只有 DANGER 80% / SAFE 67% —— 它学的是"这些模板长什么样"，
  换个写法就废。真正能迁移的是结构：**动词 + 解析后的目标 + 管道去向**。

Java 侧（CommandGuard.java）按同样规则实现，两边用同一批用例对拍。
"""
import re, collections

SYSROOT = {"/", "/bin", "/sbin", "/lib", "/lib64", "/usr", "/etc", "/boot", "/var",
           "/opt", "/srv", "/run", "/proc", "/sys", "/dev", "/root", "/home", "/media", "/mnt"}
SHARE = {"/sdcard", "/storage", "/mnt/sdcard", "/storage/emulated/0"}
CRED = ("/.ssh", "id_rsa", "id_ed25519", "authorized_keys", "/etc/shadow", "/etc/sudoers",
        ".aws/credentials", ".config/gh/hosts.yml", ".netrc")
SHELLS = {"sh", "bash", "zsh", "dash", "ksh", "fish"}
BACKTICK = chr(96)

VERBS = ["rm", "find", "dd", "chmod", "chown", "mv", "cp", "truncate", "shred", "mkfs",
         "fdisk", "kill", "pkill", "killall", "apt", "apt-get", "dpkg", "curl", "wget",
         "nc", "ncat", "socat", "python", "python3", "perl", "php", "sed", "tee", "rsync",
         "wipefs", "blkdiscard", "parted", "sgdisk", "fallocate", "yes", "seq", "tar"]

def split_segments(cmd):
    parts, cur, i, seps = [], "", 0, []
    while i < len(cmd):
        two = cmd[i:i+2]
        if two in ("&&", "||"):
            parts.append(cur); seps.append(two); cur = ""; i += 2; continue
        ch = cmd[i]
        if ch in ";|\n":
            parts.append(cur); seps.append(ch); cur = ""; i += 1; continue
        cur += ch; i += 1
    parts.append(cur)
    out = [p.strip() for p in parts]
    return out, seps

def tokens(seg):
    out, cur, q = [], "", ""
    for ch in seg:
        if q:
            cur += ch
            if ch == q: q = ""
            continue
        if ch in "\"'":
            q = ch; cur += ch; continue
        if ch in " \t":
            if cur: out.append(cur); cur = ""
            continue
        cur += ch
    if cur: out.append(cur)
    return out

def unquote(s):
    # 与 Java Features.unquote 一致：只剥**成对**的引号。
    # 之前用 strip("'\"") 会把 "/etc"/passwd 这种剥成 /etc"/passwd，
    # 训练时判成 sys:/etc，线上（Java）判成相对路径 —— 两边悄悄不一致。
    t = s.strip()
    while len(t) >= 2 and ((t[0] == "'" and t[-1] == "'") or (t[0] == '"' and t[-1] == '"')):
        t = t[1:-1]
    return t

def norm_path(p, cwd):
    p = unquote(p)
    if not p or p.startswith("-"):
        return None
    if "=" in p and not p.startswith("/"):
        return None
    if p.startswith("~"):
        p = "/root" + p[1:]
    if not p.startswith("/"):
        # 相对路径（含裸文件名）一律相对当前 cwd 解析 —— 这正是 shell 的行为。
        # 之前对裸文件名返回 None，导致 "cd /usr && rm -rf share" 看不出危险。
        p = cwd.rstrip("/") + "/" + p
    parts = []
    for seg in p.split("/"):
        if seg in ("", "."): continue
        if seg == "..":
            if parts: parts.pop()
            continue
        parts.append(seg)
    return "/" + "/".join(parts)

CRITICAL_TOP = {"/usr", "/etc", "/bin", "/sbin", "/lib", "/lib64", "/boot"}

def pclass(p):
    """与 Java 的 CommandGuard.classify 保持一致：
    关键目录任意深度都危险；其它顶层目录只有删它本身才算。"""
    if p is None: return None
    if p == "/root/workspace": return "workspace_root"
    if p.startswith("/root/workspace/"): return "workspace"
    if p == "/": return "root"
    if p == "/root": return "sys:/root"
    parts = [x for x in p.split("/") if x]
    top = "/" + (parts[0] if parts else "")
    if top in SHARE: return "share"
    if p.startswith("/dev/") and p != "/dev": return "device"
    if top in CRITICAL_TOP: return "sys:" + top
    if len(parts) == 1 and top in SYSROOT: return "sys:" + top
    if p.startswith("/tmp") or p.startswith("/var/tmp"): return "tmp"
    return "other"

VAR_RE = re.compile(r"\$\{(\w+)\}|\$(\w+)")
ASSIGN_SEG = re.compile(r"^(?:export\s+|\w+=\S*\s+)*([A-Za-z_]\w*)=(.*)$")
SUBST_RE = re.compile(r"\$\(([^()]*)\)")


def expand(s, varz, depth=0):
    """展开命令里已知的变量。解析不出来就原样留着，交给调用方判 dyn_target。"""
    if depth > 4 or "$" not in s:
        return s
    out = VAR_RE.sub(lambda m: varz.get(m.group(1) or m.group(2), m.group(0)), s)
    return out if out == s else expand(out, varz, depth + 1)


def subst_literal(s):
    """$(echo /var/log) 这类命令替换，取里面的字面量；有解析不出的就返回 None。

    注意要处理"部分可解析"：$(echo /var)/log/* 只有括号那段能还原，
    还原后剩下的 /log/* 仍然是有意义的路径片段。
    """
    if "$(" not in s:
        return None

    def rep(m):
        inner = m.group(1).strip()
        mm = re.match(r"^(?:echo|printf\s+%s|printf\s+%s\\n|echo\s+-n)\s+(\S+)$", inner)
        return mm.group(1) if mm else m.group(0)

    out = SUBST_RE.sub(rep, s)
    return None if "$(" in out else out


def features(cmd):
    f = collections.Counter()
    varz = {"HOME": "/root", "PWD": "/root/workspace", "TMPDIR": "/tmp", "USER": "root"}
    # 先把"丢弃输出"这类重定向抹掉再分析：
    #   ls > /dev/null 与 ls 等价，rm -rf / 2>/dev/null 与 rm -rf / 等价。
    # 抹掉而不是给它一个"安全"特征，才能避免"2>/dev/null 让危险命令变安全"的副作用。
    cmd = re.sub(r"\d?>>?\s*/dev/null|\d?>&\d|\d?>>?\s*&[12]", " ", cmd)
    segs, seps = split_segments(cmd)
    cwd = "/root/workspace"
    f["len>200"] = 1 if len(cmd) > 200 else 0
    f["multiline"] = 1 if "\n" in cmd else 0
    f["forkbomb"] = 1 if re.search(r"\)\s*\{.*\|.*&", cmd) or re.search(r"fork\s*while\s*fork", cmd) else 0
    f["has_subst"] = 1 if ("$(" in cmd or BACKTICK in cmd) else 0
    f["has_bash_c"] = 1 if re.search(r"bash\s+-c|sh\s+-c", cmd) else 0

    for idx, seg in enumerate(segs):
        # $(echo /var)/log/* 这种，先还原再分词，否则空格会把 token 拆断
        lit_seg = subst_literal(seg)
        if lit_seg:
            seg = lit_seg
        toks = tokens(seg)
        if not toks: continue
        if toks[0] == "cd" and len(toks) > 1:
            np = norm_path(toks[1], cwd)
            if np: cwd = np
            f["cd"] += 1
            if np == "/": f["cd_root"] += 1
            continue
        # 纯赋值段：记进变量表，后面 $X 才能解析成真实路径
        am = re.match(r"^(?:export\s+)?([A-Za-z_]\w*)=(.+)$", seg.strip())
        if am:
            name = am.group(1)
            val = unquote(expand(am.group(2).strip(), varz))
            varz[name] = val
            f["var_assign"] += 1
            lit = subst_literal(val) or val
            if pclass(norm_path(lit, cwd)) not in (None, "other", "tmp", "workspace"):
                f["var_assign_protected"] += 1
            if unquote(val).split(" ")[0] in VERBS or unquote(val)[:2] in ("./", "/."):
                f["var_assign_cmd"] += 1
            continue

        verb = unquote(toks[0]).split("/")[-1]
        if verb in SHELLS: f["shell"] += 1
        if verb in VERBS: f["verb:" + verb] += 1

        # 与 Java 保持一致：短选项里**任意位置**出现 r/f 都算（Java 用的是 indexOf）。
        # 之前 Python 用 ^-[a-zA-Z]*[rR] 严格前缀，'-systemd-journal' 两边判得到不同结果。
        recursive = any(
            t == "--recursive" or (t.startswith("-") and not t.startswith("--")
                                   and len(t) > 1 and ("r" in t or "R" in t))
            for t in toks[1:])
        force = any(
            t == "--force" or (t.startswith("-") and not t.startswith("--")
                               and len(t) > 1 and "f" in t)
            for t in toks[1:])
        # 与 Java Features.containsRm 一致：只认精确的 "rm"，
        # 否则 'rmdir' 也会因为包含 "rm" 被当成删除动词
        delete = ("-delete" in toks) or ("-exec" in toks and "rm" in toks)

        targets, classes = [], []
        for t in toks[1:]:
            if t.startswith("-"): continue
            if t.isdigit() or "://" in t or t in ("-", "|", "&", ">"):
                continue          # PID、URL、占位符不是路径
            if "=" in t and not t.startswith("/"):
                key, _, val = t.partition("=")
                if key in ("of", "target", "path", "file", "output"):
                    t = val
                else:
                    continue
            if verb == "find" and targets: continue
            # 先把 $X / $(...) 还原成字面量，再判目标类别
            if "$" in t:
                lit = subst_literal(t)
                t2 = expand(lit if lit else t, varz)
                if "$" in t2 or BACKTICK in t2:
                    f["dyn_target"] += 1
                    continue
                t = t2
            np = norm_path(t, cwd)
            if np:
                targets.append(np)
                c = pclass(np)
                if c:
                    classes.append(c)
                    f["tgt:" + c] += 1
                    if c.startswith("sys:"): f["tgt_sys"] += 1
                    if c == "root": f["tgt_root"] += 1
                    if c == "share": f["tgt_share"] += 1
                    # /root 下面非 workspace 的部分（.ssh/.profile/.config）才是要害
                    if np.startswith("/root/") and not np.startswith("/root/workspace"):
                        f["tgt_root_sub"] += 1
        # /root 下非 workspace 的部分（.ssh / .profile / 配置）也算要害
        root_sub = any(t.startswith("/root/") and not t.startswith("/root/workspace")
                       for t in targets)
        protected = root_sub or any(c == "root" or c.startswith("sys:") or c == "share"
                                    or c == "device" for c in classes)
        if protected: f["tgt_protected"] += 1
        # 包缓存/日志清理：正常运维动作，给个明确的负向信号
        if verb == "rm" and recursive and all(
                t.startswith(("/var/cache/", "/var/lib/apt/lists", "/var/tmp/", "/var/log/",
                              "/tmp/", "/root/workspace/"))
                for t in targets) and targets:
            f["cache_clean"] += 1
        if verb in ("chmod", "chown") and all(t.startswith("/root/workspace/") for t in targets) and targets:
            f["workspace_perm"] += 1
        if recursive: f["recursive"] += 1
        if force: f["force"] += 1
        if recursive and force: f["rf"] += 1

        if verb == "rm" and protected:
            f["rm_protected"] += 1
            if recursive and force: f["rm_rf_protected"] += 1
        if verb == "find" and delete and protected: f["find_del_protected"] += 1
        if verb in ("chmod", "chown") and recursive and protected: f["perm_protected"] += 1
        if verb == "mv" and protected: f["mv_protected"] += 1
        if verb in ("truncate", "shred") and protected: f["trunc_protected"] += 1
        if verb == "dd" and any(c == "device" for c in classes): f["dd_device"] += 1
        if verb in ("mkfs", "fdisk", "wipefs", "blkdiscard", "parted", "sgdisk"): f["fs_tool"] += 1
        if verb in ("kill", "pkill", "killall"):
            f["kill"] += 1
            all_proc = ("-1" in toks) or (verb == "kill" and len(toks) > 1 and toks[1] == "-1")
            if all_proc:
                f["kill_all"] += 1
            elif verb == "kill" and any(t.isdigit() for t in toks[1:] if not t.startswith("-")):
                f["kill_pid"] += 1          # kill -9 12345：正常操作
            if verb in ("pkill", "killall"):
                f["kill_by_name"] += 1

        m = re.search(r">>?\s*(\S+)", seg)
        if m:
            tgt = m.group(1)
            lit = subst_literal(tgt)
            np = norm_path(expand(lit if lit else tgt, varz), cwd)
            if np:
                f["redirect"] += 1
                c = pclass(np)
                if c and (c.startswith("sys:") or c == "share"):
                    f["redirect_protected"] += 1
                elif c in ("workspace", "tmp"):
                    f["redirect_local"] += 1

        low = seg.lower()
        # 日志文件：截断/轮转是日常运维，不该算危险
        for t in targets:
            if t.endswith(".log") or t.startswith("/var/log/"):
                f["log_path"] += 1
                break
        # 研发动作：venv / import / 构建
        if re.search(r"\b(venv|virtualenv|activate|pip3?\s+install|python3?\s+-m\s+pip"
                     r"|npm\s+(run|install|ci)|yarn\s|pnpm\s|\bmake\b|cmake|pytest|cargo\s"
                     r"|go\s+(build|test|run)|javac|\bnode\s)", low):
            f["dev_action"] += 1
        # 下载但不执行：只有当它后面**没有管道**时才算"安全下载"
        piped_next = (idx < len(seps) and seps[idx] == "|")
        if re.search(r"\b(curl|wget)\b", low) and "|" not in seg and not piped_next:
            f["download_only"] += 1
        if any(k in low for k in CRED):
            if ".pub" in low:
                f["pub_key"] += 1          # 公钥随便看
            else:
                f["cred_path"] += 1
        if re.search(r"\b(curl|wget|nc|ncat|socat|scp|telnet|ftp)\b", low): f["net_tool"] += 1
        if re.search(r"/dev/tcp/|\bnc\s+-[a-z]*e\b|ncat\s+--exec", low): f["reverse_shell"] += 1
        if idx < len(seps) and seps[idx] == "|":
            nxt = segs[idx + 1] if idx + 1 < len(segs) else ""
            nt = tokens(nxt)
            nv = unquote(nt[0]).split("/")[-1] if nt else ""
            if nv == "sudo" and len(nt) > 1:
                nv = unquote(nt[1]).split("/")[-1]      # | sudo bash
            if nv in SHELLS: f["pipe_shell"] += 1
            if re.search(r"\b(nc|ncat|socat|curl|wget|telnet)\b", nxt): f["pipe_net"] += 1
        # --- 间接执行 / 持久化 / 敏感写入 ---
        if verb == "xargs" and "rm" in seg:
            f["xargs_rm"] += 1
        if verb == "tar" and "--remove-files" in seg:
            f["remove_files"] += 1
        if verb == "find" and "-exec" in toks and any(t in ("chmod", "chown", "rm") for t in toks):
            f["find_exec_mutate"] += 1
        if re.search(r"\b(crontab|systemctl\s+(disable|mask|stop)|usermod|userdel|chattr|chpasswd)\b", low):
            f["persistence"] += 1
        if re.search(r">>?\s*\S*(\.bashrc|\.profile|authorized_keys|/etc/sudoers)", seg):
            f["shell_rc_write"] += 1
        if re.search(r">\s*/proc/(sys|self|1)/", seg) or "sysrq" in low:
            f["proc_write"] += 1
        if re.search(r"--directory\s+/(\s|$)", seg):
            f["serve_root"] += 1
        if re.search(r"\|\s*(base64|xxd|uudecode|openssl)\s+-[dr]\S*.*\|\s*(sh|bash)", cmd):
            f["encode_pipe"] += 1
        if re.search(r"\b(eval|source)\b", low) and "curl" in low:
            f["eval_remote"] += 1
        if verb == "ln":
            if "/dev/null" in seg:
                f["ln_devnull"] += 1
            # 软链顶掉配置/密钥：不删不写，但效果等同替换
            if protected:
                f["ln_protected"] += 1
        if verb == "eval" or (verb in SHELLS and len(toks) > 1 and toks[1] == "-c"):
            body = " ".join(toks[1:])
            body = unquote(body)
            if any(pclass(norm_path(x, cwd)) not in (None, "other", "tmp", "workspace")
                   for x in re.split(r"[\s;|&]+", body) if x.startswith("/")):
                f["eval_protected"] += 1
        if re.search(r"\b(crontab|systemctl)\b", low):
            f["svc_change"] += 1

        if verb in ("apt", "apt-get", "dpkg"):
            if re.search(r"\b(remove|purge|-r\b|--remove|--purge)\b", seg): f["pkg_remove"] += 1
        if re.search(r"(count|bs)=\S*0000|head\s+-c\s+\d{6,}|yes\s*>|/dev/zero\s*>|fallocate\s+-l\s+\d+G", seg):
            f["disk_fill"] += 1
        if re.search(r"https?://", low): f["url"] += 1
    # 交叉特征：线性模型学不到"凭证 + 外网"的组合，显式给一个
    low_all = cmd.lower()
    if any(k in low_all for k in CRED) and re.search(r"\b(curl|wget|nc|ncat|socat|scp|telnet)\b", low_all):
        f["cred_net"] += 1
    return f

if __name__ == "__main__":
    import sys
    for c in sys.argv[1:]:
        feats = features(c)
        print(c[:70])
        print("   ", dict(sorted(feats.items(), key=lambda kv: -kv[1])[:8]))
