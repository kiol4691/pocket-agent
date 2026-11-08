#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""迁移集评估：支持 tf / bm25 / logent 三种词权重。"""
import base64, json, math, struct, sys
sys.path.insert(0, "/home/nio/项目/pocket-agent/tools/guard")
from feat import features
from feat_hash import all_features, bucket
from sets_v2 import DANGER, SAFE

def load(path):
    m = json.load(open(path))
    n = 1 << m["bits"]
    w = list(struct.unpack("<%df" % n, base64.b64decode(m["weights_b64"])))
    gw = list(struct.unpack("<%df" % n, base64.b64decode(m["global_b64"])))
    return m, w, gw

def vec(m, cmd):
    f = features(cmd)
    acc = {}
    for k in all_features(cmd, f, m["mode"]):
        i, s = bucket(k, m["bits"])
        acc[i] = acc.get(i, 0.0) + s
    return acc

def score(m, w, gw, cmd):
    acc = vec(m, cmd)
    L = sum(abs(v) for v in acc.values()) or 1.0
    z = m["intercept"]
    wt = m["weighting"]
    for i, v in acc.items():
        tf = abs(v)
        if tf == 0:
            continue
        if wt == "bm25":
            denom = tf + m["k1"] * (1 - m["b"] + m["b"] * L / m["avglen"])
            x = (v / tf) * gw[i] * tf * (m["k1"] + 1) / denom
        elif wt == "logent":
            x = (v / tf) * (1 + math.log(tf)) * gw[i]
        else:
            x = v
        z += w[i] * x
    return 1 / (1 + math.exp(-max(-30, min(30, z))))

def main():
    for path in sys.argv[1:]:
        m, w, gw = load(path)
        print("== %s  (%s)" % (path.split("/")[-1], m["weighting"]))
        print("   %-6s %-12s %-12s" % ("thr", "DANGER(rec)", "SAFE(spec)"))
        for t in (0.2, 0.3, 0.5, 0.7):
            d = sum(1 for c in DANGER if score(m, w, gw, c) >= t) / len(DANGER)
            s = sum(1 for c in SAFE if score(m, w, gw, c) < t) / len(SAFE)
            print("   %-6.2f %-12.3f %-12.3f" % (t, d, s))

if __name__ == "__main__":
    main()
