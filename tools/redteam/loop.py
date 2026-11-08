#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""闭环：候选 → 现有模型打分 → 挑模型判错的 → 沙箱真跑 → 出带真实标签的数据集。

挑选策略是主动学习：模型已经 BLOCK 的不必花沙箱预算，它放过的才有信息量；
另外按比例抽样高分段，用来量护栏的误杀（oracle 说无害却判危险）。

用法：
    python3 loop.py --cand cand.jsonl --budget 300 --out data/rt_v1
"""
import argparse
import json
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(BASE, "tools", "guard"))
sys.path.insert(0, HERE)

from taxonomy import BY_ID                      # noqa: E402
from eval_hash import load as load_model, score as model_score  # noqa: E402

SANDBOX = os.path.join(HERE, "sandbox.py")


def select(scored, budget, rnd, hi_sample=0.15):
    """低分全收，高分按比例抽，避免预算全花在已经拦住的样本上。"""
    low = [r for r in scored if r["score"] < 0.5]
    high = [r for r in scored if r["score"] >= 0.5]
    rnd.shuffle(low)
    rnd.shuffle(high)
    take_hi = int(budget * hi_sample)
    picked = low[: budget - take_hi] + high[:take_hi]
    if len(picked) < budget:
        rest = high[take_hi:]
        picked += rest[: budget - len(picked)]
    return picked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cand", required=True)
    ap.add_argument("--model", default=os.path.join(BASE, "app/src/main/assets/guard-model.json"))
    ap.add_argument("--budget", type=int, default=300)
    ap.add_argument("--timeout", type=float, default=5.0)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--out", required=True, help="输出前缀，会生成 .scored/.reports/.train")
    args = ap.parse_args()

    m, w = load_model(args.model)
    rows = [json.loads(l) for l in open(args.cand) if l.strip()]
    for r in rows:
        r["score"] = round(model_score(m, w, r["cmd"]), 6)
    with open(args.out + ".scored.jsonl", "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # 只有文件系统可观测的机制才值得花执行预算
    runnable = [r for r in rows if BY_ID.get(r["mechanism"], {}).get("oracle") == "fs"]
    skipped = len(rows) - len(runnable)
    picked = select(runnable, min(args.budget, len(runnable)), random.Random(args.seed))

    cases = os.path.join(args.out + ".cases.jsonl")
    with open(cases, "w") as f:
        for i, r in enumerate(picked):
            f.write(json.dumps({"cmd": r["cmd"], "mechanism": r["mechanism"], "cid": i},
                               ensure_ascii=False) + "\n")
    subprocess.run([sys.executable, SANDBOX, "--cases", cases,
                    "--out", args.out + ".reports.jsonl",
                    "--jobs", str(args.jobs), "--timeout", str(args.timeout)], check=True)

    reports = {r["cid"]: r for r in (json.loads(l) for l in open(args.out + ".reports.jsonl"))}
    tp = fp = fn = tn = 0
    per = {}
    unverified = []
    with open(args.out + ".train.jsonl", "w") as f:
        for i, r in enumerate(picked):
            rep = reports.get(i)
            if not rep or rep.get("label", -1) < 0:
                continue
            if not rep.get("usable"):
                unverified.append({"cmd": r["cmd"], "mechanism": r["mechanism"],
                                   "verdict": rep.get("verdict"), "why": rep.get("why", "")[:80]})
                continue
            pred = 1 if r["score"] >= 0.5 else 0
            truth = rep["label"]
            tp += pred == 1 and truth == 1
            fp += pred == 1 and truth == 0
            fn += pred == 0 and truth == 1
            tn += pred == 0 and truth == 0
            d = per.setdefault(r["mechanism"], [0, 0])
            d[0] += truth
            d[1] += 1
            f.write(json.dumps({
                "cmd": r["cmd"], "label": truth, "mechanism": r["mechanism"],
                "sev": rep["sev"], "score": r["score"],
                "deleted": rep.get("deleted", [])[:4],
                "modified": rep.get("modified", [])[:4],
            }, ensure_ascii=False) + "\n")

    with open(args.out + ".unverified.jsonl", "w") as f:
        for r in unverified:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    total = tp + fp + fn + tn
    print("执行 %d 条，可判定 %d 条（跳过 %d 条非 fs 机制，%d 条环境缺失不可判）"
          % (len(picked), total, skipped, len(unverified)))
    print("  模型 vs 沙箱   真危险  真无害")
    print("  判危险        %5d  %5d" % (tp, fp))
    print("  判安全        %5d  %5d" % (fn, tn))
    if total:
        print("  漏判 %d (%.1f%%)  误杀 %d (%.1f%%)"
              % (fn, 100.0 * fn / max(tp + fn, 1), fp, 100.0 * fp / max(fp + tn, 1)))
    print("按机制（真危险/执行）：")
    for k, (d, n) in sorted(per.items(), key=lambda kv: -kv[1][1]):
        print("  %-18s %3d/%-3d" % (k, d, n))


if __name__ == "__main__":
    main()
