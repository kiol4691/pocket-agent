#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# 生成 assets/debian-rootfs-arm64.bin（集成进来的 pocketroot 用的 Debian 13 轻量 rootfs）
#
# 为什么用脚本而不是把 99 MB 的 tar 提交进仓库：
#   GitHub 单文件硬上限是 100 MB，这个包 98.4 MB —— 提交上去等于永远贴着红线走，
#   而且每次改一遍 rootfs 历史里就多一份。所以仓库只存生成脚本，产物本地生成。
#
# 依赖：pocketroot 仓库（提供 pull-registry-rootfs.py / add-debs.py / add-wheels.py /
#       slim-rootfs.py / rootfs-identity.py）。默认从 ../zroot-ng 找，可用 POCKETROOT 覆盖。
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
PK="${POCKETROOT:-$BASE/../zroot-ng}"
OUT="$BASE/app/src/main/assets/debian-rootfs-arm64.bin"

for t in pull-registry-rootfs.py add-debs.py add-wheels.py slim-rootfs.py rootfs-identity.py; do
    [ -f "$PK/tools/$t" ] || { echo "缺少 $PK/tools/$t（用 POCKETROOT=<pocketroot 路径> 指定）"; exit 1; }
done

WORK="${WORK:-/tmp/dsh-rootfs}"
rm -rf "$WORK"
mkdir -p "$WORK/rootfs"

echo "== [1/6] 拉取 Debian 13 + Python 3.13 =="
python3 "$PK/tools/pull-registry-rootfs.py" docker.io/library/python:3.13-slim -o "$WORK/base.tar.gz"
tar -xzf "$WORK/base.tar.gz" -C "$WORK/rootfs"

echo "== [2/6] 补装 deb（light profile）=="
python3 "$PK/tools/add-debs.py" "$WORK/rootfs" --profile light

echo "== [3/6] 装 Python 包 =="
python3 "$PK/tools/add-wheels.py" "$WORK/rootfs" --py 3.13 --arch aarch64 \
    --pkgs numpy,requests,openpyxl,python-docx

echo "== [4/6] 瘦身 =="
python3 "$PK/tools/slim-rootfs.py" "$WORK/rootfs"

echo "== [5/6] 标注修改版（Debian 走 mark 模式）=="
python3 "$PK/tools/rootfs-identity.py" "$WORK/rootfs" --mode=mark

echo "== [6/6] 打补丁（seccomp）并打包 =="
python3 "$PK/tools/patch-glibc-syscalls.py" "$WORK/rootfs" \
    --manifest="$WORK/patch-manifest.json"
tar -czf "$OUT" --hard-dereference -C "$WORK/rootfs" .

ls -lh "$OUT"
echo "完成：$OUT"
