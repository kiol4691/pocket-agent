#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""用全部样本重训并把模型写进 assets。"""
import json, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_feat, feat

def main():
    samples = sys.argv[1]
    out = sys.argv[2]
    rows = [json.loads(l) for l in open(samples) if l.strip()]
    feats = [feat.features(r["cmd"]) for r in rows]
    vocab = sorted({k for f in feats for k in f})
    idx = {k: i for i, k in enumerate(vocab)}
    X = [[(idx[k], v) for k, v in f.items()] for f in feats]
    Y = [r["label"] for r in rows]

    npos = sum(Y); nneg = len(Y) - npos
    cw = [len(Y) / (2.0 * max(npos, 1)), len(Y) / (2.0 * max(nneg, 1))]
    w = [0.0] * len(vocab); b = 0.0
    lr0 = 0.5
    order = list(range(len(Y)))
    import random
    rnd = random.Random(3)
    for ep in range(400):
        rnd.shuffle(order)
        lr = lr0 / (1 + 0.01 * ep)
        for i in order:
            z = b + sum(w[j] * v for j, v in X[i])
            p = train_feat.sigmoid(z)
            g = (p - Y[i]) * cw[Y[i]]
            b -= lr * g
            for j, v in X[i]:
                w[j] -= lr * (g * v + train_feat.L2 * w[j])

    model = {
        "version": "0.2.0",
        "features": vocab,
        "weights": [round(x, 5) for x in w],
        "intercept": round(b, 5),
        "threshold": 0.9,
    }
    json.dump(model, open(out, "w"), ensure_ascii=False)
    print("model -> %s  %d dims  %d bytes" % (out, len(vocab), os.path.getsize(out)))

if __name__ == "__main__":
    main()
