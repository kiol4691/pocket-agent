#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""迁移测试（语义特征版）：手写的、没参与生成的命令。"""
import json, math, sys
sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from feat import features

sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from sets_v2 import DANGER, SAFE

def load(p):
    m = json.load(open(p))
    return m, {k: i for i, k in enumerate(m["features"])}, m["weights"], m["intercept"], m["threshold"]

def score(m, idx, w, b, cmd):
    f = features(cmd)
    z = b + sum(w[idx[k]] * v for k, v in f.items() if k in idx)
    return 1 / (1 + math.exp(-max(-30, min(30, z))))

def main():
    m, idx, w, b, thr = load(sys.argv[1])
    for name, rows, want in (("DANGER", DANGER, 1), ("SAFE", SAFE, 0)):
        hit = 0; miss = []
        for c in rows:
            s = score(m, idx, w, b, c)
            if (1 if s >= thr else 0) == want: hit += 1
            else: miss.append((round(s, 3), c))
        print("%-6s %d/%d acc=%.3f" % (name, hit, len(rows), hit / len(rows)))
        for s, c in miss[:10]:
            print("    miss %.3f  %s" % (s, c))

if __name__ == "__main__":
    main()
