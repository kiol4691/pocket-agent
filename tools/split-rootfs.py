#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""把 rootfs 按体积切成 N 组互不重叠的路径，每组打成一份独立 tar。

为什么按子树切、而不是按字节切：
  字节切出来的第 2、3 段不是合法的 tar（从文件中间开始），解包端必须先拼回
  350 MB 的整包再解一次，多一轮写+读。按子树切，每段都是**完整合法的 tar**，
  xz 解码是单线程的，3 段并行解码就是接近 3 倍的解压速度，解包端按段顺序
  解即可（总计的条目数不变，解包耗时和整包一样）。

切分的两个约束：
  1. 覆盖完整 —— 每个顶层条目必须落在某一组里；
  2. 互不重叠 —— 一旦决定拆开某个目录，就只把子条目分给各组，父目录本身
     不再单独入包（否则两组会同时创建同一个目录，解包时序不同会打架）。

父目录不单独入包意味着它的权限/属主由解包器按默认 0755 root:root 创建，
所以只对 0755 的目录做下钻；/root（0700）、/tmp（1777）这类保持整块。
"""
import argparse
import os
import stat
import sys

MIB = 1 << 20


def scan(path):
    """返回 {绝对路径: 递归体积}；符号链接只算链接本身，不跟随。"""
    sizes = {}
    order = []

    def walk(p):
        st = os.lstat(p)
        if stat.S_ISDIR(st.st_mode):
            total = 0
            with os.scandir(p) as it:
                for e in it:
                    total += walk(e.path)
            total += st.st_size
        else:
            total = st.st_size
        sizes[p] = total
        order.append(p)
        return total

    walk(path)
    return sizes


def collect(rel, root, sizes, budget, depth, out, meta):
    """把 rel 拆成若干"整块"放进 out（相对路径列表）；被拆开的父目录记进 meta。"""
    path = os.path.join(root, rel) if rel else root
    st = os.lstat(path)
    size = sizes[path]
    if stat.S_ISDIR(st.st_mode) and size > budget and depth < 8 and (st.st_mode & 0o7777) == 0o755:
        try:
            children = sorted(os.listdir(path))
        except OSError:
            children = []
        if len(children) > 1:
            if rel:
                meta.append(rel)
            for c in children:
                collect(os.path.join(rel, c) if rel else c, root, sizes, budget, depth + 1, out, meta)
            return
    if rel:
        out.append((rel, size))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rootfs")
    ap.add_argument("--parts", type=int, default=3)
    ap.add_argument("--out", required=True, help="输出目录，写 part0.lst … partN.lst")
    ap.add_argument("--prefix", default="part")
    args = ap.parse_args()

    root = os.path.abspath(args.rootfs)
    if not os.path.isdir(root):
        sys.exit("不是目录: " + root)

    sizes = scan(root)
    total = sizes[root]
    budget = total // args.parts

    blocks = []
    meta = []
    for name in sorted(os.listdir(root)):
        collect(name, root, sizes, budget, 1, blocks, meta)
    if not blocks:
        sys.exit("空的 rootfs")

    # 贪心：大的先放，每次塞进当前最轻的一组。
    bins = [[] for _ in range(args.parts)]
    load = [0] * args.parts
    for rel, size in sorted(blocks, key=lambda b: -b[1]):
        i = load.index(min(load))
        bins[i].append(rel)
        load[i] += size

    os.makedirs(args.out, exist_ok=True)
    for i, paths in enumerate(bins):
        if not paths:
            sys.exit("第 %d 组为空，减少分段数" % i)
        dst = os.path.join(args.out, "%s%d.lst" % (args.prefix, i))
        with open(dst, "wb") as f:
            for p in paths:
                f.write(p.encode() + b"\0")
        print("  part%d: %3d 个路径, %6.1f MiB" % (i, len(paths), load[i] / MIB))

    # 被拆开的父目录单独列一份：它们的权限位不在任何一段里，打包脚本会把这份
    # 列表作为一个只有目录条目的 tar 拼到第 0 段前面（每项 ~10 字节）。
    if meta:
        dst = os.path.join(args.out, "meta.lst")
        with open(dst, "wb") as f:
            for m in meta:
                f.write(m.encode() + b"\0")

    print("  合计 %.1f MiB / %d 块，%d 个父目录写进 meta.lst"
          % (total / MIB, len(blocks), len(meta)))


if __name__ == "__main__":
    main()
