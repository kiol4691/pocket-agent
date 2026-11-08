#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""训练命令护栏模型：char_wb 3-5 gram 的线性分类器（纯 Python，无 numpy/sklearn）。

为什么是这个结构：
  - 与 Kestrel 同款（char n-gram + 线性），体积小、纯查表、Java 侧 80 行就能跑；
  - 训练集是我们自己按模板生成 + 变形出来的，标签确定，没有标注噪声；
  - 评估按**模板分组**切分（同一模板的变形不会同时出现在训练和验证里），
    这样验证集衡量的是"没见过的写法"，不是"记住的模板"。

用法：python3 train.py /tmp/guard-samples.jsonl model.json
"""
import json, math, random, sys, collections, hashlib

NGRAM = (3, 5)
MIN_DF = 2
MAX_FEAT = 60000
EPOCHS = 12
LR = 0.08
L2 = 1e-6

def grams(cmd):
    c = cmd.lower()
    out = collections.Counter()
    for word in c.split():
        t = " " + word + " "
        for n in range(NGRAM[0], NGRAM[1] + 1):
            for i in range(len(t) - n + 1):
                out[t[i:i + n]] += 1
    return out

def sigmoid(z):
    if z < -30: return 1e-13
    if z > 30: return 1 - 1e-13
    return 1 / (1 + math.exp(-z))

def main():
    rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    out_path = sys.argv[2]

    # 按模板分组切分，避免同一模板的变形跨集合
    groups = collections.defaultdict(list)
    for r in rows:
        gid = r.get("gid") or ("id:" + str(len(groups)))
        groups[gid].append(r)
    gids = list(groups)
    random.Random(7).shuffle(gids)
    cut = int(len(gids) * 0.85)
    train_rows = [r for g in gids[:cut] for r in groups[g]]
    val_rows = [r for g in gids[cut:] for r in groups[g]]

    # 词表
    df = collections.Counter()
    for r in train_rows:
        df.update(grams(r["cmd"]).keys())
    vocab = [g for g, c in df.most_common(MAX_FEAT) if c >= MIN_DF]
    index = {g: i for i, g in enumerate(vocab)}
    print("samples train=%d val=%d  vocab=%d" % (len(train_rows), len(val_rows), len(vocab)))

    def vec(cmd):
        return [(index[g], tf) for g, tf in grams(cmd).items() if g in index]

    w = [0.0] * len(vocab)
    b = 0.0
    # 类平衡
    npos = sum(1 for r in train_rows if r["label"] == 1)
    nneg = len(train_rows) - npos
    wpos = len(train_rows) / (2.0 * max(npos, 1))
    wneg = len(train_rows) / (2.0 * max(nneg, 1))

    rnd = random.Random(11)
    order = list(range(len(train_rows)))
    for ep in range(EPOCHS):
        rnd.shuffle(order)
        lr = LR / (1 + 0.3 * ep)
        for i in order:
            r = train_rows[i]
            x = vec(r["cmd"])
            z = b + sum(w[j] * tf for j, tf in x)
            p = sigmoid(z)
            y = r["label"]
            wt = wpos if y == 1 else wneg
            g = (p - y) * wt
            b -= lr * g
            for j, tf in x:
                w[j] -= lr * (g * tf + L2 * w[j])
        # 每轮看一次验证集
        print("  epoch %2d  val_f1=%.4f" % (ep + 1, evaluate(val_rows, lambda c: score(c, index, w, b, vocab)[0])))

    # 选阈值：在验证集上取 F1 最大
    scores = [(score_row(r, index, w, b, vocab), r["label"]) for r in val_rows]
    best = (0, 0.5)
    for t in [x / 100 for x in range(5, 96)]:
        tp = sum(1 for s, y in scores if s >= t and y == 1)
        fp = sum(1 for s, y in scores if s >= t and y == 0)
        fn = sum(1 for s, y in scores if s < t and y == 1)
        prec = tp / (tp + fp) if tp + fp else 0
        rec = tp / (tp + fn) if tp + fn else 0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0
        if f1 > best[0]:
            best = (f1, t)
    print("best val threshold=%.2f f1=%.4f" % (best[1], best[0]))

    model = {
        "version": "0.1.0",
        "analyzer": "char_wb",
        "ngram": list(NGRAM),
        "threshold": round(best[1], 3),
        "intercept": round(b, 6),
        "weights": {g: round(w[i], 5) for g, i in index.items() if abs(w[i]) > 1e-4},
    }
    json.dump(model, open(out_path, "w"), ensure_ascii=False)
    size = len(json.dumps(model, ensure_ascii=False).encode())
    print("model -> %s  %.2f MB  %d weights" % (out_path, size / 1048576.0, len(model["weights"])))

def score(cmd, index, w, b, vocab):
    z = b + sum(w[j] * tf for j, tf in [(index[g], tf) for g, tf in grams(cmd).items() if g in index])
    return sigmoid(z), z

def score_row(r, index, w, b, vocab):
    return score(r["cmd"], index, w, b, vocab)[0]

def evaluate(rows, fn):
    tp = fp = fn_ = 0
    for r in rows:
        p = fn(r["cmd"]) >= 0.5
        if r["label"] == 1 and p: tp += 1
        elif r["label"] == 1: fn_ += 1
        elif p: fp += 1
    prec = tp / (tp + fp) if tp + fp else 0
    rec = tp / (tp + fn_) if tp + fn_ else 0
    return 2 * prec * rec / (prec + rec) if prec + rec else 0

if __name__ == "__main__":
    main()
