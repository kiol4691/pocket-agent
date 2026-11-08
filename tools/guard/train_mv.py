#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""训练多视图线性模型：三视图各自 LR，再在验证集上做线性融合系数搜索。"""
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mv
from feat import features
from feat_hash import bucket
from struct_tokens import struct_tokens, normalize_for_char

EPOCHS = 30
LR = 0.25
L2 = 1e-6


def sigmoid(z):
    if z < -30: return 1e-13
    if z > 30: return 1 - 1e-13
    return 1 / (1 + math.exp(-z))


def view_vector(cmd, v, sem_w):
    """返回该视图**局部**桶索引 -> 值。"""
    f = features(cmd)
    acc = {}
    for k in mv.view_keys(cmd, f, v["name"], sem_w):
        i, s = bucket(k, v["bits"])
        acc[i] = acc.get(i, 0.0) + s
    return acc


def train_view(X, Y, tr, nbits):
    n = 1 << nbits
    w = [0.0] * n
    b = 0.0
    npos = sum(1 for i in tr if Y[i] == 1)
    nneg = len(tr) - npos
    cw = [len(tr) / (2.0 * max(npos, 1)), len(tr) / (2.0 * max(nneg, 1))]
    rnd = random.Random(3)
    for ep in range(EPOCHS):
        rnd.shuffle(tr)
        lr = LR / (1 + 0.05 * ep)
        for i in tr:
            z = b + sum(w[j] * val for j, val in X[i].items())
            g = (sigmoid(z) - Y[i]) * cw[Y[i]]
            b -= lr * g
            for j, val in X[i].items():
                w[j] -= lr * (g * val + L2 * w[j])
    return w, b


def main():
    src, out = sys.argv[1], sys.argv[2]
    sem_w = int(sys.argv[3]) if len(sys.argv) > 3 else mv.SEM_W
    rows = [json.loads(l) for l in open(src) if l.strip()]
    Y = [r["label"] for r in rows]
    G = [r.get("gid", str(i)) for i, r in enumerate(rows)]
    groups = sorted(set(G))
    random.Random(7).shuffle(groups)
    cut = int(len(groups) * 0.85)
    trg = set(groups[:cut])
    tr = [i for i, g in enumerate(G) if g in trg]
    va = [i for i, g in enumerate(G) if g not in trg]
    print("rows=%d train=%d val=%d" % (len(rows), len(tr), len(va)))

    views = [dict(v) for v in mv.VIEWS]
    W = [0.0] * mv.TOTAL
    Z = {}
    for v in views:
        print("  train view", v["name"], "...", flush=True)
        X = [view_vector(r["cmd"], v, sem_w) for r in rows]
        w, b = train_view(X, Y, tr, v["bits"])
        for j, val in enumerate(w):
            W[v["offset"] + j] = val
        Z[v["name"]] = [sum(w[j] * val for j, val in X[i].items()) for i in va]
        v["b"] = b

    Yv = [Y[i] for i in va]
    best = (-1, None)
    for aA in (0.5, 1.0, 1.5, 2.0):
        for aB in (0.25, 0.5, 1.0, 1.5, 2.0):
            for aC in (0.25, 0.5, 1.0):
                zs = [aA * x + aB * y + aC * z
                      for x, y, z in zip(Z["struct"], Z["char"], Z["word"])]
                for b0 in [x * 1.0 for x in range(-30, 31)]:
                    tp = fp = fn = 0
                    for z, y in zip(zs, Yv):
                        p = (z + b0) >= 0
                        tp += p and y == 1
                        fp += p and y == 0
                        fn += (not p) and y == 1
                    prec = tp / (tp + fp) if tp + fp else 0
                    rec = tp / (tp + fn) if tp + fn else 0
                    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0
                    utility = f1
                    if utility > best[0]:
                        best = (utility, (aA, aB, aC, b0, prec, rec))

    aA, aB, aC, b0, prec, rec = best[1]
    for v, a in zip(views, (aA, aB, aC)):
        v["alpha"] = a
    print("fusion alpha=(%.2f, %.2f, %.2f) b0=%.2f  val prec=%.3f rec=%.3f"
          % (aA, aB, aC, b0, prec, rec))

    m = {"views": views, "intercept": b0}
    size = mv.dump(out, m, W, threshold=0.5)
    print("model -> %s  %d bytes (%.0f KB)" % (out, size, size / 1024))


if __name__ == "__main__":
    main()
