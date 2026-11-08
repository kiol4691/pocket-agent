#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""混合特征 + 特征哈希。

三组特征拼在一起，全部哈希进固定桶：
  - 语义特征（feat.py 那 90 多个：动词、目标类别、管道、重定向）  -> "s:xxx"
  - 字符 n-gram（char_wb 3-5，抓变形写法、拼写变体）              -> "c3:abc"
  - 词级 n-gram（unigram + bigram，抓工具名与搭配）               -> "w1:rm" / "w2:rm -rf"

为什么用哈希而不是词表：
  - 词表要随训练集导出，几十万条字符串，模型文件几百 KB 到几 MB；
  - 哈希把任何特征映射到固定 32k 桶，模型就是一个 float 数组（base64 后 170 KB），
    Java 侧不需要解析词表，算特征时边算边累加；
  - 哈希碰撞在 32k 桶 / ~10 万特征下会有一点噪声，但用"符号哈希"（同一特征固定加/减）
    可以让碰撞互相抵消而不是系统性偏移。
"""
import zlib, collections

ASSIGN_RE = __import__("re").compile(r"^\s*(export\s+)?[A-Za-z_]\w*=")


def operative(cmd):
    """char/word n-gram 只看"干活"的段，不看环境准备段。

    为什么：一条命令里环境准备语句（export PATH=...、X=/etc）会贡献几十个
    字符 n-gram 桶，每个都带一点负权重，合起来足以把 "rm -rf /etc/nginx"
    的明确语义信号压到 0.02。语义特征照旧吃全文，不受影响。
    """
    from feat import split_segments
    segs, _ = split_segments(cmd)
    keep = [s for s in segs if s and not ASSIGN_RE.match(s)]
    return " ; ".join(keep) if keep else cmd


def char_ngrams(cmd, lo=3, hi=5):
    out = []
    c = cmd.lower()
    for word in c.split():
        t = " " + word + " "
        for n in range(lo, hi + 1):
            for i in range(len(t) - n + 1):
                out.append("c%d:%s" % (n, t[i:i + n]))
    return out

def word_ngrams(cmd):
    out = []
    words = cmd.lower().split()
    for w in words:
        out.append("w1:" + w)
    for a, b in zip(words, words[1:]):
        out.append("w2:%s %s" % (a, b))
    return out

def semantic(feats):
    return ["s:" + k for k, v in feats.items() if v]

SPLIT_CHARS = " \t\n/\\-=:;|&()'\"<>*,?[]"

def adaptive_chunks(cmd):
    """H-gram：按**结构边界**切出变长片段，而不是固定 3-5 字符。

    切成什么：空白、/ - = : ; | & 括号 引号 通配 这些位置天然是语义边界，
    切完得到的是 'usr'、'lib'、'rf'、'no'、'preserve'、'root'、'sdcard'、'DCIM' 这种
    长度 1..N 不等的块。相比固定 n-gram，它不会被"跨边界"的片段污染
    （' -r'、'f /' 这种既没意义又占桶）。
    同时给相邻块的 bi-gram，保留组合语义（'rm'+'rf'、'cd'+'/'）。
    """
    parts, cur = [], ""
    for ch in cmd.lower():
        if ch in SPLIT_CHARS:
            if cur:
                parts.append(cur)
                cur = ""
            if ch in "/-":
                parts.append(ch)      # 路径分隔和 flag 前缀本身也是信号
            continue
        cur += ch
    if cur:
        parts.append(cur)
    out = ["h1:" + q for q in parts if q.strip()]
    for a, b in zip(parts, parts[1:]):
        out.append("h2:%s|%s" % (a, b))
    return out

# 语义特征每条只有 1~3 个，字符/词 n-gram 有几十上百个。
# 不加权时，一个无害前缀（export PATH=...）能靠几十个负权重把
# "rm -rf /etc" 这类明确信号压到 0.000 —— 实测过。
SEM_W = int(__import__("os").environ.get("GUARD_SEM_W", "2"))


def all_features(cmd, semantic_feats, mode="union"):
    op = operative(cmd)
    f = semantic(semantic_feats) * SEM_W + word_ngrams(op)
    if mode in ("char", "union"):
        f += char_ngrams(op)
    if mode in ("chunk", "union"):
        f += adaptive_chunks(op)
    return f

def bucket(key, bits, signed=True):
    h = zlib.crc32(key.encode("utf-8")) & 0xFFFFFFFF
    idx = h & ((1 << bits) - 1)
    if not signed:
        return idx, 1.0
    sign = 1.0 if (h >> 31) & 1 else -1.0
    return idx, sign
