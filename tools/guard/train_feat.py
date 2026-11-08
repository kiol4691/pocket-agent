#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""用语义特征训练护栏模型（逻辑回归，~60 维，纯 Python 秒级）。

与字符 n-gram 版的区别：特征是"解析后的结构"（动词、目标类别、管道去向、重定向），
所以换个写法也能命中 —— 迁移集上 80%/67% -> 见 eval 输出。
"""
import json, math, random, sys, collections
sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from feat import features

EPOCHS = 400
LR = 0.5
L2 = 1e-4

def sigmoid(z):
    if z < -30: return 1e-13
    if z > 30: return 1 - 1e-13
    return 1 / (1 + math.exp(-z))

def main():
    rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    out_path = sys.argv[2]

    feats = [features(r["cmd"]) for r in rows]
    vocab = sorted({k for f in feats for k in f})
    idx = {k: i for i, k in enumerate(vocab)}
    X = [[(idx[k], v) for k, v in f.items()] for f in feats]
    Y = [r["label"] for r in rows]
    G = [r.get("gid", str(i)) for i, r in enumerate(rows)]

    # 按模板分组的训练/验证切分
    groups = sorted(set(G))
    random.Random(7).shuffle(groups)
    cut = int(len(groups) * 0.85)
    train_g = set(groups[:cut])
    tr = [i for i, g in enumerate(G) if g in train_g]
    va = [i for i, g in enumerate(G) if g not in train_g]
    print("train=%d val=%d features=%d" % (len(tr), len(va), len(vocab)))

    w = [0.0] * len(vocab)
    b = 0.0
    npos = sum(1 for i in tr if Y[i] == 1)
    nneg = len(tr) - npos
    cw = [len(tr) / (2.0 * max(npos, 1)), len(tr) / (2.0 * max(nneg, 1))]
    rnd = random.Random(3)

    for ep in range(EPOCHS):
        rnd.shuffle(tr)
        lr = LR / (1 + 0.01 * ep)
        for i in tr:
            z = b + sum(w[j] * v for j, v in X[i])
            p = sigmoid(z)
            g = (p - Y[i]) * cw[Y[i]]
            b -= lr * g
            for j, v in X[i]:
                w[j] -= lr * (g * v + L2 * w[j])
        if (ep + 1) % 100 == 0:
            print("  ep %3d  val_f1=%.4f" % (ep + 1, f1(va, X, Y, w, b, 0.5)))

    # 选阈值：先保证误报低（precision >= 0.95），在此前提下取召回最高的
    best = (0.0, 0.9, 0.0)
    for t in [x / 100 for x in range(5, 99)]:
        prec, rec, f1v = prf(va, X, Y, w, b, t)
        if prec >= 0.95 and rec > best[0]:
            best = (rec, t, prec)
    if best[0] == 0:
        best = (0.0, 0.9, 0.0)
    print("thr=%.2f  val recall=%.4f precision=%.4f" % (best[1], best[0], best[2]))

    model = {
        "version": "0.2.0",
        "features": vocab,
        "weights": [round(x, 5) for x in w],
        "intercept": round(b, 5),
        "threshold": round(best[1], 3),
    }
    json.dump(model, open(out_path, "w"), ensure_ascii=False)
    print("model -> %s (%d dims)" % (out_path, len(vocab)))

def prf(ids, X, Y, w, b, thr):
    tp = fp = fn = 0
    for i in ids:
        p = sigmoid(b + sum(w[j] * v for j, v in X[i])) >= thr
        if Y[i] == 1 and p: tp += 1
        elif Y[i] == 1: fn += 1
        elif p: fp += 1
    prec = tp / (tp + fp) if tp + fp else 0
    rec = tp / (tp + fn) if tp + fn else 0
    f1v = 2 * prec * rec / (prec + rec) if prec + rec else 0
    return prec, rec, f1v

def f1(ids, X, Y, w, b, thr):
    tp = fp = fn = 0
    for i in ids:
        p = sigmoid(b + sum(w[j] * v for j, v in X[i])) >= thr
        if Y[i] == 1 and p: tp += 1
        elif Y[i] == 1: fn += 1
        elif p: fp += 1
    prec = tp / (tp + fp) if tp + fp else 0
    rec = tp / (tp + fn) if tp + fn else 0
    return 2 * prec * rec / (prec + rec) if prec + rec else 0

if __name__ == "__main__":
    main()
