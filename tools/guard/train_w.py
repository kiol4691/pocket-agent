#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""在哈希特征上换词权重：TF（基线）/ BM25 / 对数熵（log-entropy）。

为什么值得试：
  - 现在用的是裸计数（tf），高频噪声（比如到处都是的 'rm'、路径分隔）权重和稀有信号一样大；
  - BM25 用 idf 压高频 + 长度归一化，log-entropy 用分布熵压"到处都出现"的特征。
  - 代价：需要把全局权重（idf 或 g(t)）随模型一起发，2^15 个 float = 128 KB。

用法：python3 train_w.py SAMPLES MODEL BITS MODE WEIGHTING
      WEIGHTING = tf | bm25 | logent
"""
import base64, json, math, random, struct, sys, collections
sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from feat import features
from feat_hash import all_features, bucket

K1, B = 1.2, 0.75

def sigmoid(z):
    if z < -30: return 1e-13
    if z > 30: return 1 - 1e-13
    return 1 / (1 + math.exp(-z))

def raw(cmd, mode, bits):
    """返回 {bucket: 有符号计数} + 文档长度。"""
    f = features(cmd)
    acc = {}
    for k in all_features(cmd, f, mode):
        i, s = bucket(k, bits)
        acc[i] = acc.get(i, 0.0) + s
    return acc

def build_weights(docs, bits, weighting):
    """在训练集上统计全局权重：idf(BM25) 或 g(t)(log-entropy)。docs 是 [{bucket: tf}]。"""
    n = 1 << bits
    df = [0] * n
    total = [0.0] * n
    for d in docs:
        for i, v in d.items():
            df[i] += 1
            total[i] += abs(v)
    N = max(len(docs), 1)
    if weighting == "bm25":
        w = [math.log(1 + (N - df[i] + 0.5) / (df[i] + 0.5)) if df[i] else 0.0 for i in range(n)]
    elif weighting == "logent":
        # g(t) = 1 + sum_j p_ij log p_ij / log N ；p_ij = tf_ij / total_tf_t
        w = [0.0] * n
        logN = math.log(N) if N > 1 else 1.0
        for i in range(n):
            if not df[i]:
                continue
            # 只用"该特征在每个文档里的归一化占比"的熵近似：用平均占比代替逐文档
            p = (total[i] / df[i]) / (total[i] or 1)
            # 退化情形：所有 doc 里出现次数一样 -> p=1/df -> 熵最高 -> 权重最低
            ent = 0.0
            pj = 1.0 / df[i]
            if pj > 0:
                ent = math.log(pj)
            w[i] = max(0.0, 1.0 + ent / logN)
    else:
        w = [1.0] * n
    return w

def apply_weight(acc, gw, avglen, weighting):
    out = {}
    L = sum(abs(v) for v in acc.values()) or 1.0
    for i, v in acc.items():
        tf = abs(v)
        if weighting == "bm25":
            denom = tf + K1 * (1 - B + B * L / avglen)
            out[i] = (v / tf) * gw[i] * tf * (K1 + 1) / denom if tf else 0.0
        elif weighting == "logent":
            out[i] = (v / tf) * (1 + math.log(tf)) * gw[i] if tf else 0.0
        else:
            out[i] = v
    return out

def prf(ids, X, Y, w, b, thr):
    tp = fp = fn = 0
    for i in ids:
        p = sigmoid(b + sum(w[j] * v for j, v in X[i].items())) >= thr
        if Y[i] == 1 and p: tp += 1
        elif Y[i] == 1: fn += 1
        elif p: fp += 1
    prec = tp / (tp + fp) if tp + fp else 0
    rec = tp / (tp + fn) if tp + fn else 0
    return prec, rec, (2 * prec * rec / (prec + rec) if prec + rec else 0)

def main():
    samples, out, bits, mode, weighting = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
    rows = [json.loads(l) for l in open(samples) if l.strip()]
    accs = [raw(r["cmd"], mode, bits) for r in rows]
    Y = [r["label"] for r in rows]
    G = [r.get("gid", str(i)) for i, r in enumerate(rows)]

    groups = sorted(set(G)); random.Random(7).shuffle(groups)
    cut = int(len(groups) * 0.85); trg = set(groups[:cut])
    tr = [i for i, g in enumerate(G) if g in trg]
    va = [i for i, g in enumerate(G) if g not in trg]

    gw = build_weights([accs[i] for i in tr], bits, weighting)
    avglen = sum(sum(abs(v) for v in accs[i].values()) for i in tr) / max(len(tr), 1)
    X = [apply_weight(a, gw, avglen, weighting) for a in accs]

    n = 1 << bits
    w = [0.0] * n
    b = 0.0
    npos = sum(1 for i in tr if Y[i] == 1); nneg = len(tr) - npos
    cw = [len(tr) / (2.0 * max(npos, 1)), len(tr) / (2.0 * max(nneg, 1))]
    rnd = random.Random(3)
    for ep in range(30):
        rnd.shuffle(tr)
        lr = 0.25 / (1 + 0.05 * ep)
        for i in tr:
            z = b + sum(w[j] * v for j, v in X[i].items())
            p = sigmoid(z)
            g = (p - Y[i]) * cw[Y[i]]
            b -= lr * g
            for j, v in X[i].items():
                w[j] -= lr * (g * v + 1e-6 * w[j])

    best = (0.0, 0.5, 0.0)
    for t in [x / 100 for x in range(5, 99)]:
        prec, rec, _ = prf(va, X, Y, w, b, t)
        if prec >= 0.90 and rec > best[0]:
            best = (rec, t, prec)
    print("%-7s thr=%.2f val recall=%.3f precision=%.3f" % (weighting, best[1], best[0], best[2]))

    buf = struct.pack("<%df" % n, *w)
    gwbuf = struct.pack("<%df" % n, *gw)
    model = {
        "version": "0.4.0", "scheme": "hash-mixed", "mode": mode, "bits": bits,
        "weighting": weighting, "k1": K1, "b": B, "avglen": avglen,
        "intercept": round(b, 6), "threshold": round(best[1], 3),
        "weights_b64": base64.b64encode(buf).decode(),
        "global_b64": base64.b64encode(gwbuf).decode(),
    }
    json.dump(model, open(out, "w"))
    print("model -> %s  %.0f KB" % (out, len(json.dumps(model)) / 1024))

if __name__ == "__main__":
    main()
