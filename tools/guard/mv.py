#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""多视图线性模型：三个独立哈希空间 + 线性融合。

为什么分段而不是拼一个大向量：
  之前所有特征共用一个 2^15 桶，字符 n-gram 有几十上百个、语义只有几个，
  一个无害前缀就能把明确信号压到 0.02。分段后每个视图有自己的权重空间，
  训练时独立归一化，融合系数单独可调，谁也淹不了谁。

视图：
  struct (2^15) : 语义特征 s: + 结构标记 t:   —— 含跨段组合 vchain
  char   (2^14) : 规范化命令串的字符 n-gram     —— 变量已展开
  word   (2^13) : 规范化命令串的词 n-gram
  总计 57344 float32 -> 229376 B -> base64 305835 B ≈ 299 KB
"""
import base64
import json
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from feat import features
from feat_hash import bucket, char_ngrams, word_ngrams, operative, SEM_W, all_features
from struct_tokens import struct_tokens, normalize_for_char

VIEWS = [
    {"name": "struct", "bits": 15, "offset": 0},
    {"name": "char", "bits": 14, "offset": 1 << 15},
    {"name": "word", "bits": 13, "offset": (1 << 15) + (1 << 14)},
]
TOTAL = sum(1 << v["bits"] for v in VIEWS)


def view_keys(cmd, f, name, sem_w=SEM_W):
    if name == "struct":
        # 完整继承 v8 的特征空间（语义 + operative 的 char/word），再叠加结构标记。
        # 这样旧判别力不会因为加了新视图而退化。
        base = all_features(cmd, f, "char")
        return base + ["t:" + k for k in struct_tokens(cmd)]
    text = normalize_for_char(cmd)
    if name == "char":
        return char_ngrams(text)
    return word_ngrams(text)


def vector(cmd, m):
    f = features(cmd)
    acc = {}
    for v in m["views"]:
        bits, off = v["bits"], v["offset"]
        for k in view_keys(cmd, f, v["name"], m.get("sem_w", SEM_W)):
            i, s = bucket(k, bits)
            j = i + off
            acc[j] = acc.get(j, 0.0) + s
    return acc


def load(path):
    m = json.load(open(path))
    raw = base64.b64decode(m["weights_b64"])
    n = len(raw) // 4
    return m, list(struct.unpack("<%df" % n, raw))


def sigmoid(z):
    return 1 / (1 + math.exp(-max(-30, min(30, z))))


def raw_score(m, w, vec):
    z = m["intercept"]
    for v in m["views"]:
        lo, hi = v["offset"], v["offset"] + (1 << v["bits"])
        a = v.get("alpha", 1.0)
        z += a * sum(w[j] * x for j, x in vec.items() if lo <= j < hi)
    return z


def score(m, w, cmd):
    return sigmoid(raw_score(m, w, vector(cmd, m)))


def dump(path, m, w, threshold=0.5):
    buf = struct.pack("<%df" % len(w), *w)
    out = {
        "version": "0.4.0",
        "scheme": "multiview-char",
        "mode": "char",
        "bits": 15,                 # 兼容旧加载器（仅在没有 views 时使用）
        "sem_w": SEM_W,
        "views": m["views"],
        "intercept": round(m["intercept"], 6),
        "threshold": threshold,
        "weights_b64": base64.b64encode(buf).decode(),
    }
    json.dump(out, open(path, "w"))
    return os.path.getsize(path)
