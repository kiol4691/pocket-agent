#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""用「语义 + 字符 + 词级」混合特征、哈希到固定桶后训练逻辑回归。"""
import base64, json, math, random, struct, sys
sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from feat import features
from feat_hash import all_features, bucket

BITS = int(sys.argv[3]) if len(sys.argv) > 3 else 15
MODE = sys.argv[4] if len(sys.argv) > 4 else "union"
EPOCHS = 30
LR = 0.25
L2 = 1e-6

def sigmoid(z):
    if z < -30: return 1e-13
    if z > 30: return 1 - 1e-13
    return 1 / (1 + math.exp(-z))

def vectorize(cmd):
    f = features(cmd)
    acc = {}
    for k in all_features(cmd, f, MODE):
        i, s = bucket(k, BITS)
        acc[i] = acc.get(i, 0.0) + s
    return list(acc.items())

def main():
    rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    out = sys.argv[2]
    X = [vectorize(r["cmd"]) for r in rows]
    Y = [r["label"] for r in rows]
    G = [r.get("gid", str(i)) for i, r in enumerate(rows)]

    groups = sorted(set(G)); random.Random(7).shuffle(groups)
    cut = int(len(groups) * 0.85); trg = set(groups[:cut])
    tr = [i for i, g in enumerate(G) if g in trg]
    va = [i for i, g in enumerate(G) if g not in trg]

    n = 1 << BITS
    w = [0.0] * n
    b = 0.0
    npos = sum(1 for i in tr if Y[i] == 1); nneg = len(tr) - npos
    cw = [len(tr) / (2.0 * max(npos, 1)), len(tr) / (2.0 * max(nneg, 1))]
    rnd = random.Random(3)
    for ep in range(EPOCHS):
        rnd.shuffle(tr)
        lr = LR / (1 + 0.05 * ep)
        for i in tr:
            z = b + sum(w[j] * v for j, v in X[i])
            p = sigmoid(z)
            g = (p - Y[i]) * cw[Y[i]]
            b -= lr * g
            for j, v in X[i]:
                w[j] -= lr * (g * v + L2 * w[j])
        print("  ep %2d  val_f1=%.4f" % (ep + 1, prf(va, X, Y, w, b, 0.5)[2]))

    # 阈值：precision >= 0.90 前提下召回最高
    best = (0.0, 0.9, 0.0)
    for t in [x / 100 for x in range(5, 99)]:
        prec, rec, f1v = prf(va, X, Y, w, b, t)
        if prec >= 0.90 and rec > best[0]:
            best = (rec, t, prec)
    print("thr=%.2f  val recall=%.3f precision=%.3f" % (best[1], best[0], best[2]))

    buf = struct.pack("<%df" % n, *w)
    model = {
        "version": "0.3.0",
        "scheme": "hash-mixed",
        "mode": MODE,
        "bits": BITS,
        "intercept": round(b, 6),
        "threshold": round(best[1], 3),
        "weights_b64": base64.b64encode(buf).decode(),
    }
    json.dump(model, open(out, "w"))
    print("model -> %s  %.0f KB" % (out, len(json.dumps(model)) / 1024))

def prf(ids, X, Y, w, b, thr):
    tp = fp = fn = 0
    for i in ids:
        p = sigmoid(b + sum(w[j] * v for j, v in X[i])) >= thr
        if Y[i] == 1 and p: tp += 1
        elif Y[i] == 1: fn += 1
        elif p: fp += 1
    prec = tp / (tp + fp) if tp + fp else 0
    rec = tp / (tp + fn) if tp + fn else 0
    return prec, rec, (2 * prec * rec / (prec + rec) if prec + rec else 0)

if __name__ == "__main__":
    main()
