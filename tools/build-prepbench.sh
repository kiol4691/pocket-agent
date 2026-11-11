#!/usr/bin/env bash
# 编译 prep_bench.c -> app/src/main/jniLibs/arm64-v8a/libprepbench.so
#
# 不需要 NDK / CMake：用 zroot-ng 自带的 zig 交叉编译成 freestanding .so，
# 与 libzng.so 同样的做法（readelf -d 无 NEEDED，Android 可直接 dlopen）。
# 唯一外部依赖是本机 JDK 的 jni.h（JAVA_HOME 指向任意 JDK 17+）。
set -euo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="${ZIG:-/home/nio/项目/zroot-ng/tools/zig-linux-x86_64-0.13.0/zig}"
JDK="${JAVA_HOME:-/home/nio/jdk17}"
OUT="$BASE/app/src/main/jniLibs/arm64-v8a/libprepbench.so"

"$ZIG" cc -target aarch64-linux-musl \
    -shared -nostdlib -ffreestanding -fPIC -O2 \
    -fno-math-errno -fvisibility=default \
    -I"$BASE/app/src/main/jni/stub" \
    -I"$JDK/include" -I"$JDK/include/linux" \
    -Wl,-soname,libprepbench.so \
    -o "$OUT" "$BASE/app/src/main/jni/prep_bench.c"

echo "built $OUT"
file "$OUT"
echo "NEEDED (应为空):"; readelf -d "$OUT" | grep NEEDED || true
echo "JNI symbols:"; nm -D "$OUT" | grep Java_
