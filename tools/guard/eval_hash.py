#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""迁移集评估（哈希混合特征版）。"""
import base64, json, math, struct, sys
sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from feat import features
from feat_hash import all_features, bucket
from sets_v2 import DANGER, SAFE

def load(path):
    m = json.load(open(path))
    if str(m.get("scheme", "")).startswith("multiview"):
        import mv
        return mv.load(path)
    raw = base64.b64decode(m["weights_b64"])
    n = len(raw) // 4
    return m, list(struct.unpack("<%df" % n, raw))

def vector(cmd, bits, mode="union"):
    f = features(cmd)
    acc = {}
    for k in all_features(cmd, f, mode):
        i, s = bucket(k, bits)
        acc[i] = acc.get(i, 0.0) + s
    return acc

def score(m, w, cmd):
    if str(m.get("scheme", "")).startswith("multiview"):
        import mv
        return mv.score(m, w, cmd)
    v = vector(cmd, m["bits"], m.get("mode", "union"))
    z = m["intercept"] + sum(w[j] * x for j, x in v.items())
    return 1 / (1 + math.exp(-max(-30, min(30, z))))

def main():
    m, w = load(sys.argv[1])
    print("%-6s %-12s %-12s" % ("thr", "DANGER(rec)", "SAFE(spec)"))
    for t in (0.1, 0.2, 0.3, 0.5, 0.7, 0.9):
        d = sum(1 for c in DANGER if score(m, w, c) >= t) / len(DANGER)
        s = sum(1 for c in SAFE if score(m, w, c) < t) / len(SAFE)
        print("%-6.2f %-12.3f %-12.3f" % (t, d, s))
    # 详细看漏判
    t = 0.5
    for name, rows, want in (("DANGER", DANGER, 1), ("SAFE", SAFE, 0)):
        miss = [(round(score(m, w, c), 3), c) for c in rows
                if (1 if score(m, w, c) >= t else 0) != want]
        print("%s miss @0.5: %d" % (name, len(miss)))
        for s, c in miss[:8]:
            print("   %.3f  %s" % (s, c[:90]))

if __name__ == "__main__":
    main()
