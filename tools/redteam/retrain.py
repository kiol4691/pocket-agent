#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""把红队数据并进训练集重训，并做三方对照。

基线只有 23.5k 条模板样本，红队一次只补几百条 —— 直接拼进去权重几乎不动，
所以同一组样本重复 K 次（gid 相同，保证重采样不会跨折泄漏）。

用法：
    python3 retrain.py --rt data/rt_v1.train.jsonl --repeat 20 --out artifacts/guard-hash-15-rt.json
"""
import argparse
import json
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(os.path.dirname(HERE))
GUARD = os.path.join(BASE, "tools", "guard")
sys.path.insert(0, GUARD)

from eval_hash import load as load_model, score as model_score  # noqa: E402
from sets_v2 import DANGER, SAFE                                # noqa: E402


def prf(rows, m, w, thr=0.5):
    tp = fp = fn = tn = 0
    for r in rows:
        p = model_score(m, w, r["cmd"]) >= thr
        y = r["label"]
        tp += p and y == 1
        fp += p and y == 0
        fn += (not p) and y == 1
        tn += (not p) and y == 0
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    return prec, rec, fp, tn, fn


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rt", required=True)
    ap.add_argument("--base", default=os.path.join(GUARD, "gen.py"))
    ap.add_argument("--repeat", type=int, default=20)
    ap.add_argument("--dev", type=float, default=0.3)
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    rt = [json.loads(l) for l in open(args.rt) if l.strip()]
    for r in rt:
        r.setdefault("gid", "rt/%d" % len(r["cmd"]))
    rnd = random.Random(args.seed)
    rnd.shuffle(rt)
    cut = int(len(rt) * (1 - args.dev))
    train, dev = rt[:cut], rt[cut:]

    base_jsonl = os.path.join(os.path.dirname(args.out), "base.jsonl")
    subprocess.run([sys.executable, args.base, base_jsonl], check=True,
                   stdout=subprocess.DEVNULL)

    merged = os.path.join(os.path.dirname(args.out), "merged.jsonl")
    n = 0
    with open(merged, "w") as f:
        for line in open(base_jsonl):
            f.write(line)
            n += 1
        for _ in range(args.repeat):
            for r in train:
                f.write(json.dumps({"cmd": r["cmd"], "label": r["label"],
                                    "gid": r["gid"], "cat": "redteam/" + r["mechanism"]},
                                   ensure_ascii=False) + "\n")
                n += 1
    print("merged %d 行（基线 + 红队 %d×%d）" % (n, len(train), args.repeat))

    subprocess.run([sys.executable, os.path.join(GUARD, "train_hash.py"),
                    merged, args.out, "15", "union"], check=True,
                   stdout=subprocess.DEVNULL)

    old_m, old_w = load_model(os.path.join(BASE, "app/src/main/assets/guard-model.json"))
    new_m, new_w = load_model(args.out)
    devrows = [{"cmd": r["cmd"], "label": r["label"]} for r in dev]
    for name, rows in (("红队留出", devrows),
                       ("迁移集", [{"cmd": c, "label": 1} for c in DANGER] +
                                  [{"cmd": c, "label": 0} for c in SAFE])):
        for tag, m, w in (("旧", old_m, old_w), ("新", new_m, new_w)):
            prec, rec, fp, tn, fn = prf(rows, m, w)
            print("  %-6s %s  召回 %.3f  误杀 %d/%d  漏判 %d"
                  % (name, tag, rec, fp, fp + tn, fn))
        print()


if __name__ == "__main__":
    main()
