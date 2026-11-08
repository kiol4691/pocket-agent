#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""输入层「结构标记化」：把带变量的危险命令拆成线性模型看得见的组合特征。

动机（红队实测）：
    X=/etc; echo pwned >> $X/passwd     -> 旧模型 0.000
    echo pwned >> /etc/passwd           -> 旧模型 1.000
两段信息（变量指向 /etc、变量被用作写目标）分开看都不显眼，
只有**跨段组合**起来才是明确的破坏。unigram 学不到这种组合，
所以显式产出 t:vchain / t:act / t:skel 这类结构标记。

同时提供 normalize_for_char()：把变量展开、丢掉赋值段后的「规范化命令串」，
让字符视图直接看见 /etc/passwd，而不是 $X/passwd。

Java 侧 StructTokens.java 逐行对应；改动必须跑 Parity 对拍。
"""
import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from feat import (split_segments, tokens, unquote, norm_path, pclass,
                  expand, subst_literal, VERBS, SHELLS, BACKTICK)

ASSIGN = re.compile(r"^(?:export\s+)?([A-Za-z_]\w*)=(.+)$")
VARREF = re.compile(r"\$\{(\w+)\}|\$(\w+)")
REDIR = re.compile(r">>?\s*(\S+)")
SUBST = re.compile(r"\$\(([^()]*)\)")
SUBST_LIT = re.compile(r"^(?:echo|printf\s+%s|echo\s+-n)\s+(\S+)$")
PROTECTED_CLASSES = ("root", "rootsub", "share", "device")

PRESET = {"HOME": "/root", "PWD": "/root/workspace",
          "TMPDIR": "/tmp", "USER": "root"}


def cls_of(path, cwd):
    np = norm_path(path, cwd)
    c = pclass(np) or "other"
    # pclass 只把 /root 本身列为 sys:/root；/root 下的 .ssh/.bashrc 才是要害
    if c == "other" and np and np.startswith("/root/") \
            and not np.startswith("/root/workspace"):
        return "rootsub"
    return c


def is_protected(c):
    return c == "root" or str(c).startswith("sys:") or c in PROTECTED_CLASSES


def collect_vars(segs):
    varz = dict(PRESET)
    vcls = {}
    names = []
    for seg in segs:
        am = ASSIGN.match(seg.strip())
        if not am:
            continue
        name = am.group(1)
        val = unquote(expand(am.group(2).strip(), varz))
        lit = subst_literal(val)
        varz[name] = val
        vcls[name] = cls_of(lit or val, "/root/workspace")
        names.append(name)
    return varz, vcls, names


def _expanded_seg(seg, varz):
    lit = subst_literal(seg)
    return expand(lit if lit else seg, varz)


def normalize_for_char(cmd):
    """规范化命令串：丢赋值段、还原命令替换、展开变量，给字符视图用。"""
    segs, _ = split_segments(cmd)
    varz, _, _ = collect_vars(segs)
    kept = []
    for seg in segs:
        if not seg or ASSIGN.match(seg.strip()):
            continue
        kept.append(_expanded_seg(seg, varz).strip())
    return " ; ".join(k for k in kept if k) or cmd


def _var_classes(raw_tokens, vcls):
    out = []
    for t in raw_tokens:
        for m in VARREF.finditer(t):
            name = m.group(1) or m.group(2)
            if name in vcls:
                out.append(vcls[name])
    return out


def _flags(toks, expanded):
    flags = []
    joined = " ".join(toks[1:])
    for x in toks[1:]:
        if x.startswith("-") and not x.startswith("--") and len(x) > 1:
            if "r" in x or "R" in x:
                flags.append("rec")
            if "f" in x:
                flags.append("force")
    if "--recursive" in joined:
        flags.append("rec")
    if "--force" in joined:
        flags.append("force")
    if ">" in expanded:
        flags.append("write")
    return "+".join(sorted(set(flags))) or "plain"


def struct_tokens(cmd):
    """结构标记列表（特征键，顺序无关）。"""
    segs, _ = split_segments(cmd)
    varz, vcls, var_names = collect_vars(segs)
    out = []
    if var_names:
        out.append("t:envprefix")
    for name in var_names:
        out.append("t:vdef:" + vcls[name])
        val = varz.get(name, "")
        head = unquote(val).split(" ")[0] if val else ""
        if head in VERBS or val.startswith(("./", "/.")):
            out.append("t:vdef_cmd")

    seq = []
    cwd = "/root/workspace"
    for seg in segs:
        if not seg or ASSIGN.match(seg.strip()):
            continue
        raw = tokens(seg)
        expanded = _expanded_seg(seg, varz)
        toks = tokens(expanded)
        if not toks:
            continue
        if toks[0] == "cd" and len(toks) > 1:
            np = norm_path(toks[1], cwd)
            if np:
                cwd = np
            continue

        act = unquote(toks[0]).split("/")[-1] or "unknown"
        # 跨段组合：变量指向的类别 × 使用它的动作
        for vc in _var_classes(raw, vcls):
            out.append("t:vuse:" + vc)
            out.append("t:vchain:" + vc + ":" + act)
            if is_protected(vc):
                out.append("t:vchain:protected:" + act)

        flagtag = _flags(toks, expanded)
        # 只把「看起来是路径」的 token 当目标，避免把普通词噪声化
        classes = []
        for t in toks[1:]:
            if t.startswith("-") or "/" not in t or "://" in t:
                continue
            if t in ("/dev/null", "/dev/zero") or t.startswith("/dev/std"):
                continue
            c = cls_of(t, cwd)
            classes.append(c)
            out.append("t:act:" + act + ":" + c)
            if "*" in t or "?" in t:
                out.append("t:glob:" + c)
            if is_protected(c):
                out.append("t:depth:" + str(min(len([x for x in t.split("/") if x]), 5)))
        if not classes:
            classes = ["none"]
        out.append("t:act:" + act)
        out.append("t:skel:" + act + ":" + flagtag + ":" + classes[0])
        seq.append(classes[0])

        rm = REDIR.search(expanded)
        if rm and rm.group(1) not in ("/dev/null", "/dev/zero", "&1", "&2") \
                and not rm.group(1).startswith("/dev/std"):
            rc = cls_of(rm.group(1), cwd)
            out.append("t:redir:" + rc)
            if is_protected(rc):
                out.append("t:redir:protected")

        for sm in SUBST.finditer(seg):
            mm = SUBST_LIT.match(sm.group(1).strip())
            if mm:
                out.append("t:subst:" + cls_of(mm.group(1), cwd))
            else:
                out.append("t:subst:unresolved")
        if BACKTICK in seg and "$(" not in seg:
            out.append("t:subst:unresolved")
        if act == "ln":
            out.append("t:symlink:" + classes[-1])
        if act in ("eval", "source") or (act in SHELLS and len(toks) > 1 and toks[1] == "-c"):
            out.append("t:eval")

    for a, b in zip(seq, seq[1:]):
        out.append("t:seq:" + a + ":" + b)
    return out


if __name__ == "__main__":
    for c in sys.argv[1:]:
        print(c)
        print("  norm:", normalize_for_char(c))
        print("  toks:", " ".join(sorted(set(struct_tokens(c)))))
