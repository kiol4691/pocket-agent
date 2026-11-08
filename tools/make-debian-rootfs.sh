#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# 生成 assets/debian-rootfs-arm64.{0,1,2}.bin（集成进来的 pocketroot 用的 Debian 13 轻量 rootfs）
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
OUT="$BASE/app/src/main/assets/debian-rootfs-arm64"

for t in pull-registry-rootfs.py add-debs.py add-wheels.py slim-rootfs.py rootfs-identity.py; do
    [ -f "$PK/tools/$t" ] || { echo "缺少 $PK/tools/$t（用 POCKETROOT=<pocketroot 路径> 指定）"; exit 1; }
done

WORK="${WORK:-/tmp/dsh-rootfs}"
rm -rf "$WORK"
mkdir -p "$WORK/rootfs"

echo "== [1/7] 拉取 Debian 13 + Python 3.13 =="
python3 "$PK/tools/pull-registry-rootfs.py" docker.io/library/python:3.13-slim -o "$WORK/base.tar.gz"
tar -xzf "$WORK/base.tar.gz" -C "$WORK/rootfs"

echo "== [2/7] 补装 deb（light profile）=="
python3 "$PK/tools/add-debs.py" "$WORK/rootfs" --profile light

echo "== [3/7] 装 Python 包 =="
python3 "$PK/tools/add-wheels.py" "$WORK/rootfs" --py 3.13 --arch aarch64 \
    --pkgs numpy,requests,openpyxl,python-docx

echo "== [4/7] 瘦身 =="
python3 "$PK/tools/slim-rootfs.py" "$WORK/rootfs"

echo "== [5/7] 标注修改版（Debian 走 mark 模式）=="
python3 "$PK/tools/rootfs-identity.py" "$WORK/rootfs" --mode=mark

echo "== [6/7] 打补丁（seccomp）=="
python3 "$PK/tools/patch-glibc-syscalls.py" "$WORK/rootfs" \
    --manifest="$WORK/patch-manifest.json"

echo "== [7/7] 分 $PARTS 段打 xz =="
# xz 而不是 gzip：同一份 rootfs 从 97 MB 降到 ~52 MB（-47%）。
# 设备上没有 xz 二进制（toybox 的 -J 只是 exec 外部 xz），只能走 Java 解码，
# 而 xz 解码是单线程的、约 15 MB/s —— 一整包 345 MB 要 22 s。
# 所以按子树切成 N 段**各自独立**的 xz，解压端 N 线程并行。
# 真机（6×A55 + 2×A76）实测：1 段 22.3 s、3 段 13.8 s、6 段 10.4 s，
# 首次解包 25.0 → 12.7 s；再加段数只剩争抢（6 段时每线程 6.5 MB/s）。
# 每段都是完整合法的 tar，解包端按时序喂给 toybox 就行，条目数不变，
# 解包耗时和整包一样（2 s 左右）。
# 字典用 4 MiB（preset=4e）而不是 8 MiB（-6e）或 64 MiB（-9e）：
# N 个解码器同时活着，8 MiB × 6 = 48 MiB 堆，4 MiB × 6 = 24 MiB，
# 而体积只差 1.5%（实测 10.19 → 10.35 MB/段）；64 MiB 时体积只再省 1 MB 量级，
# 却要 384 MiB，必 OOM。
#
# 过滤器链带 --arm64（ARM64 BCJ）：把 BL 指令里的相对地址还原成绝对地址再熵编码，
# 整包实测 -5.6%。xz-java 1.10 有 ARM64 BCJ（org.tukaani.xz.simple.ARM64），
# 已对拍：Java 解出的字节数与原始 tar 完全一致。
# 注意 xz 不接受「-6e --arm64」这种写法，必须显式写 --lzma2=preset=Ne。
PARTS=6
rm -f "$BASE/app/src/main/assets/debian-rootfs-arm64.bin"
python3 "$BASE/tools/split-rootfs.py" "$WORK/rootfs" --parts "$PARTS" --out "$WORK"
# 不加 -T0：多线程按块编码要额外 ~1.5 MB（59.9 → 58.3），构建多花两分钟而已。
XZ="--arm64 --lzma2=preset=4e"
for ((i = 0; i < PARTS; i++)); do
    if [ "$i" = 0 ] && [ -s "$WORK/meta.lst" ]; then
        # 被拆开的父目录（usr、usr/lib）不属于任何一段，权限位就会丢，
        # 解包器只能按 umask 建目录（真机上是 700）。把这几条目录条目
        # 拼到第 0 段最前面 —— 同一份 tar 先 -c 再 -r 追加，解包顺序
        # 天然保证父目录先于所有子项出现。
        tar -cf "$WORK/part0.tar" -C "$WORK/rootfs" --no-recursion --null -T "$WORK/meta.lst"
        tar -rf "$WORK/part0.tar" -C "$WORK/rootfs" --hard-dereference --null -T "$WORK/part0.lst"
        XZ_OPT="$XZ" xz -c "$WORK/part0.tar" > "$OUT.0.bin"
        rm -f "$WORK/part0.tar"
    else
        XZ_OPT="$XZ" tar -cJf "$OUT.$i.bin" --hard-dereference -C "$WORK/rootfs" \
            --null -T "$WORK/part$i.lst"
    fi
done

ls -lh "$BASE/app/src/main/assets/"
echo "完成：$OUT.{0..$((PARTS - 1))}.bin"
