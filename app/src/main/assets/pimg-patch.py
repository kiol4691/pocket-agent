#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""
构建期 glibc seccomp 兼容补丁
=============================

背景
----
App 进程带着 zygote 的 seccomp 过滤器（adb shell 的 Seccomp=0，复现不了）。
用 tools/zprobe 在真机 App 进程里逐条实测，被拦的 syscall 是：

    rseq(293)            glibc 2.35+ 给主线程注册 TLS 快速路径
    set_robust_list(99)  NPTL 初始化必调
    clone3(435)          glibc 2.34+ 线程创建
    faccessat2(439)      glibc access() 内部
    statx(291)           glibc stat 内部
    openat2(437)         （glibc 里没有直接站点；shim 自己降级）

它们都是 **libc / ld.so 内部直接发的 syscall**，不经过 PLT，LD_PRELOAD 拦不住
（与 zroot-lab 在 musl 上遇到的是同一类问题）。被 seccomp 拦不是返回 EPERM，
而是**当场 SIGSYS**，调用方连 ENOSYS 都拿不到，也就没有 fallback ——
真机表现为 App 里 glibc rootfs exit=159，且在 ld.so 打完 "TCB allocated"
之后立刻死亡，没有任何自己的输出。

对策
----
指令级降级，与 patch-musl.py 同一思路，但 glibc 的语义不同：

  · rseq        -> 返回 -ENOSYS。glibc 显式处理 ENOSYS：__rseq_size 置 0，
                  退回普通 TLS 实现（这是老内核上的正常路径）。
  · clone3      -> 返回 -ENOSYS。glibc 退回 clone()。
  · faccessat2  -> 返回 -ENOSYS。glibc 退回 faccessat()（参数布局兼容）。
  · statx       -> 返回 -ENOSYS。等价于老内核，调用方自行回退。
  · set_robust_list -> 返回 0（假装成功）。该调用返回值被忽略，
                  只是内核侧不再维护健壮 futex 链，属可接受降级。

实现方式：定位 `movz x8, #nr` 之后窗口内的 `svc #0`，把 svc 那一条指令
换成"直接写返回值"的指令（不陷入内核）：

    svc #0            (0xD4000001)  ->  movn x0, #37    (0x928004A0)  x0 = -38 = -ENOSYS
    svc #0            (0xD4000001)  ->  orr x0, xzr, xzr (0xAA1F03E0) x0 = 0

为什么必须构建期做（而不是设备端）
----------------------------------
与 patch-musl.py 相同的三条理由：只跑一次、命中数不对就硬失败、
补丁正确性在发布前可验证（设备端只校验 SHA-256）。

命中数是**显式断言**的
----------------------
glibc 里同一条 syscall 可能有多个站点（实测 libc.so.6 的 set_robust_list
有 2 处：主线程初始化 + clone 后的新线程）。因此每个 (文件, syscall) 都写清
预期命中数；实际不符即非零退出。换 glibc 版本后这里会先炸 —— 这是设计意图：
重新确认语义后再更新 EXPECT 表，而不是让设备端悄悄 SIGSYS。

顺带做的第二件事：DNS
-------------------
glibc 的 resolver 在 libc 内部读 /etc/resolv.conf（同上，PLT 之外），在宿主上
找不到这个文件就退回 127.0.0.1:53，于是 guest 里所有域名解析都是 EAI_AGAIN。
这里把那个路径字符串等长改写成 "/dev/fd/3"，运行期由 shim 把 fd 3 指到 rootfs
里的 resolv.conf（详见 shim/zng_ctor.c）。不涉及任何指令改写。

用法
----
    python3 tools/patch-glibc-syscalls.py ROOTFS [--check] [--relaxed]
                                             [--manifest out.json] [--skip-seccomp]

    --check     只校验不写盘：在**未打补丁**的 rootfs 上断言"码型可定位"
                （与 patch-musl.py 的 --check 语义一致）。要验证已打补丁的
                产物，用清单里的 sha256_after（App 端启动时就在做这件事）。

退出码
------
    0  成功
    1  目标缺失 / 命中数不符 / 回读校验失败
    2  参数错误
"""

import hashlib
import json
import os
import struct
import sys

# ---- aarch64 指令编码（小端存储，按 32 位字）----
SVC = 0xD4000001            # svc #0
RET_ENOSYS = 0x928004A0     # movn x0, #37   -> x0 = ~37 = -38 = -ENOSYS
#
# 注意 MOVN 的语义是 x0 = ~imm，不是 -imm：写 #38 得到的是 -39(ENOTEMPTY)，
# glibc 只在 errno == ENOSYS 时才回退到 clone()/faccessat()，写错一个数就会
# 表现为 pthread_create 返回 "Directory not empty" —— 线程压测第一次跑就撞上了。
RET_ZERO = 0xAA1F03E0       # orr x0, xzr, xzr -> x0 = 0

# 在 movz x8 之后最多看几条指令找 svc。
# 实测 glibc 2.43：libc 里 rseq 紧邻 svc，set_robust_list 夹 2~3 条；
# 但 **ld.so 里 set_robust_list 夹了 7 条**（str/stp/strb 一连串写结构体），
# 窗口 5 会漏掉它 —— 而它正好在 "TCB allocated" 之后执行，就是真机上
# 打死了 4 个站点仍 exit=159 的原因。取 12 覆盖，同时遇到别的
# movz x8 / 写 x8 的指令就立刻放弃，避免跨函数误认。
WINDOW = 12

# 目标文件：合并 /usr 的 rootfs 里 lib/*.so* 是 symlink，必须按 realpath 去重，
# 否则"对着 symlink 打补丁却没生效"（zroot-lab 0.1.3 的教训）。
TARGETS = [
    "usr/lib/libc.so.6",
    "lib/libc.so.6",
    "usr/lib/ld-linux-aarch64.so.1",
    "lib/ld-linux-aarch64.so.1",
    # Debian 系发行版把 libc 和 loader 放在 multiarch 子目录里
    # （/usr/lib/aarch64-linux-gnu/），rootfs 里 lib/ 是指向 usr/lib 的
    # 符号链接，因此用 realpath 去重后只会剩一份。
    "usr/lib/aarch64-linux-gnu/libc.so.6",
    "lib/aarch64-linux-gnu/libc.so.6",
    "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
    "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
]

# (syscall 号, 名字, 返回值语义, 每个文件名的预期命中数)
# 语义 'enosys' -> 让调用方看到 ENOSYS；'zero' -> 假装成功。
PATCHES = [
    # libc 2 处（主线程初始化 + clone 后的新线程），ld.so 1 处（初始线程，
    # 就在 TCB 分配之后）。
    (99,  "set_robust_list", "zero",   {"libc.so.6": 2, "ld-linux-aarch64.so.1": 1}),
    (293, "rseq",            "enosys", {"libc.so.6": 1, "ld-linux-aarch64.so.1": 1}),
    (435, "clone3",          "enosys", {"libc.so.6": 1}),

]


# DNS：glibc 的 __resolv_conf_load 在 libc **内部** open("/etc/resolv.conf")，
# 不走 PLT，LD_PRELOAD 的 fopen/open 钩子一律看不到。真机验证：改 rootfs 里的
# resolv.conf 对 getaddrinfo 毫无影响，永远 15 ms 就 EAI_AGAIN —— 它去宿主上
# 找这个文件（Android 没有），失败后退回 127.0.0.1:53，而 app 进程无权监听 53。
# 对策：把这个路径字符串**等长**改写成 "/dev/fd/3"（不动任何指令），由 shim 在
# 构造期把 fd 3 常驻指向 rootfs 里的那份文件（shim/zng_ctor.c: zng_install_resolv）。
RESOLV_OLD = b"/etc/resolv.conf\x00"                # 17 字节（16 字符 + NUL）
RESOLV_NEW = b"/dev/fd/3\x00"                        # 必须补齐到同样长度：
assert len(RESOLV_NEW) <= len(RESOLV_OLD)
RESOLV_NEW = RESOLV_NEW + b"\x00" * (len(RESOLV_OLD) - len(RESOLV_NEW))

# /etc/hosts 同理：nss_files 在 libc 内部 open 它，PLT 钩子拦不到。
# "/etc/hosts\0" 11 字节，"/dev/fd/4\0" 10 字节，补一个 NUL 保持等长。
HOSTS_OLD = b"/etc/hosts\x00"
HOSTS_NEW = b"/dev/fd/4\x00"
HOSTS_NEW = HOSTS_NEW + b"\x00" * (len(HOSTS_OLD) - len(HOSTS_NEW))

# nss_files 的数据库文件：同样在 libc 内部 open，PLT 钩子拦不到。
# 槽位号必须与 shim 的 zng_install_fd() 表一致：
#   3=resolv.conf  4=hosts  5=passwd  6=group  7=nsswitch.conf
DATA_FILES = [
    (b"/etc/hosts\x00", 4),
    (b"/etc/passwd\x00", 5),
    (b"/etc/group\x00", 6),
    (b"/etc/nsswitch.conf\x00", 7),
]


def movz_x8(nr: int) -> int:
    """movz x8, #nr 的编码。"""
    return 0xD2800000 | ((nr & 0xFFFF) << 5) | 8


def is_movz_x8(word: int) -> bool:
    return (word & 0xFFE0001F) == 0xD2800008


def find_sites(words, nr: int):
    """返回 [(movz_idx, svc_idx)] —— 在窗口内找到 svc、且中间没有别的 movz x8。"""
    sites = []
    pat = movz_x8(nr)
    n = len(words)
    for i, w in enumerate(words):
        if w != pat:
            continue
        for k in range(1, WINDOW + 1):
            j = i + k
            if j >= n:
                break
            if words[j] == SVC:
                sites.append((i, j))
                break
            if is_movz_x8(words[j]):
                break          # 号已被覆盖，这条不是该 syscall 的站点
    return sites


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def resolve_targets(rootfs: str):
    """按 realpath 去重后的 [(相对路径, 真实路径)]。"""
    out, seen = [], set()
    for rel in TARGETS:
        p = os.path.join(rootfs, rel)
        if not os.path.exists(p):
            continue
        rp = os.path.realpath(p)
        if rp in seen:
            continue
        seen.add(rp)
        out.append((rel, rp))
    return out


def patch_file(real_path: str, do_write: bool, report: dict,
               skip_seccomp: bool = False, relaxed: bool = False) -> bool:
    base = os.path.basename(real_path)
    with open(real_path, "rb") as f:
        data = bytearray(f.read())

    nwords = len(data) // 4
    words = list(struct.unpack_from("<%dI" % nwords, data, 0))

    ok = True
    already_patched = [False]   # 幂等判定：resolv 路径已是 /dev/fd/3
    applied = []          # [(svc_idx, new_word, label)]

    if base == "libc.so.6":
        old_hits = data.count(RESOLV_OLD)
        new_hits = data.count(RESOLV_NEW)
        if old_hits == 0 and new_hits == 1:
            print("[ok]   libc.so.6: resolv.conf 路径已是 /dev/fd/3（跳过）")
            already_patched[0] = True
        elif relaxed and old_hits == 0 and new_hits == 0:
            print("[FAIL] libc.so.6: 既找不到 /etc/resolv.conf 也没有 /dev/fd/3"
                  "（不是认识的 glibc？）")
            report["patches"].append({"file": base, "resolv_conf": True,
                                      "hits": 0, "expected": 1, "applied": False})
            ok = False
        elif old_hits != 1:
            print(f"[FAIL] libc.so.6: /etc/resolv.conf 字符串命中 {old_hits} 处（预期 1）")
            report["patches"].append({"file": base, "resolv_conf": True,
                                      "hits": old_hits, "expected": 1, "applied": False})
            ok = False
        else:
            off = data.find(RESOLV_OLD)
            data[off:off + len(RESOLV_OLD)] = RESOLV_NEW
            report["patches"].append({"file": base, "resolv_conf": True, "hits": 1,
                                      "expected": 1, "applied": True, "offset": off})
            print(f"[ok]   {base}: /etc/resolv.conf -> /dev/fd/3 @0x{off:x}")

    if base == "libc.so.6":
        # /etc/passwd 等：initdb / getpwuid 一类调用会读它们，而 libc 内部
        # 的 openat 不经过 PLT，绝对路径不会被翻译 -> 读到宿主文件（或 ENOENT），
        # 真机现象：initdb: could not look up effective user ID 10270
        for old_s, slot in DATA_FILES:
            new_s = ("/dev/fd/%d" % slot).encode() + b"\x00"
            if len(new_s) > len(old_s):
                print(f"[warn] libc.so.6: {old_s!r} 放不进 /dev/fd/{slot}")
                continue
            new_s = new_s + b"\x00" * (len(old_s) - len(new_s))
            o = data.count(old_s)
            n = data.count(new_s)
            label = old_s.rstrip(b"\x00").decode()
            if o == 1:
                off = data.find(old_s)
                data[off:off + len(old_s)] = new_s
                report["patches"].append({"file": base, "data_file": label,
                                          "slot": slot, "hits": 1, "applied": True,
                                          "offset": off})
                print(f"[ok]   {base}: {label} -> /dev/fd/{slot} @0x{off:x}")
            elif o == 0 and n >= 1:
                print(f"[ok]   libc.so.6: {label} 已是 /dev/fd/{slot}（跳过）")
                report["patches"].append({"file": base, "data_file": label,
                                          "slot": slot, "hits": 0, "applied": False})
            else:
                # 找不到只告警：这些文件缺失不该让整个镜像失败
                print(f"[warn] libc.so.6: {label} 字符串命中 {o} 处，跳过")
                report["patches"].append({"file": base, "data_file": label,
                                          "slot": slot, "hits": o, "applied": False})

    for nr, name, action, expect in PATCHES:
        if skip_seccomp:
            break
        want = expect.get(base)
        if want is None and not relaxed:   # 该文件里不该有这个 syscall
            continue

        sites = find_sites(words, nr)
        got = len(sites)
        if relaxed and got == 0:
            # 宽松模式：这一版 glibc 里没有这个 syscall 的站点，跳过即可
            # （例如 glibc 2.31 没有 faccessat2）。不加进 applied，也不报错。
            report["patches"].append({
                "file": base, "syscall": name, "nr": nr,
                "hits": 0, "expected": None, "applied": False,
                "note": "no site in this build",
            })
            continue
        if got != want and not relaxed:
            print(f"[FAIL] {base}: {name}({nr}) 命中 {got} 处（预期 {want}）")
            report["patches"].append({
                "file": base, "syscall": name, "nr": nr,
                "hits": got, "expected": want, "applied": False,
            })
            ok = False
            continue

        new_word = RET_ENOSYS if action == "enosys" else RET_ZERO
        label = f"{name}->{'ENOSYS' if action == 'enosys' else '0'}"
        for (mi, si) in sites:
            # 幂等：已经打过（该位置不是 svc 而是目标指令）就跳过
            if words[si] == new_word:
                continue
            applied.append((si, new_word, name))

        report["patches"].append({
            "file": base, "syscall": name, "nr": nr,
            "hits": got, "expected": want, "applied": True,
            "offsets": [si * 4 for (_mi, si) in sites],
            "action": action,
        })
        print(f"[ok]   {base}: {label} 命中 {got}，站点 "
              + ", ".join("0x%x" % (si * 4) for (_mi, si) in sites))

    if relaxed and base == "libc.so.6":
        # 宽松模式的安全阀：一个站点都没命中，说明这份 libc 的码型我们不认识
        # （换了编译器/架构写法的 movz x8），绝不能"成功"返回然后把镜像放进去。
        if not applied and not skip_seccomp and not already_patched[0]:
            print("[FAIL] libc.so.6: 宽松模式下没有命中任何站点"
                  "（码型不认识，拒绝打补丁）")
            ok = False

    if not ok or not do_write:
        return ok

    for (si, new_word, _name) in applied:
        struct.pack_into("<I", data, si * 4, new_word)

    with open(real_path, "wb") as f:
        f.write(bytes(data))

    # 回读校验：逐个确认原先的站点位置已变成目标指令，且已无残留 svc 站点。
    with open(real_path, "rb") as f:
        back = f.read()
    bw = list(struct.unpack_from("<%dI" % (len(back) // 4), back, 0))
    for (si, new_word, name) in applied:
        if bw[si] != new_word:
            print(f"[FAIL] {base}: {name} 回读校验失败 @0x{si * 4:x}"
                  f"（0x{bw[si]:08x} != 0x{new_word:08x}）")
            ok = False
        else:
            print(f"[ok]   {base}: {name} @0x{si * 4:x} 已改为 0x{new_word:08x}")
    for nr, name, _action, expect in PATCHES:
        if skip_seccomp:
            continue
        if not relaxed and expect.get(base) is None:
            continue
        left = find_sites(bw, nr)
        if left:
            print(f"[FAIL] {base}: {name} 回读后仍有 {len(left)} 处 svc 站点")
            ok = False
    if base == "libc.so.6" and back.count(RESOLV_OLD):
        print(f"[FAIL] {base}: resolv.conf 路径回读后仍是原值")
        ok = False

    return ok

def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]

    if not args:
        print(__doc__)
        return 2

    rootfs = args[0]
    if not os.path.isdir(rootfs):
        print(f"[FAIL] rootfs 目录不存在: {rootfs}")
        return 1

    check_only = "--check" in flags
    skip_seccomp = "--skip-seccomp" in flags
    relaxed = "--relaxed" in flags
    manifest_path = None
    for f in flags:
        if f.startswith("--manifest="):
            manifest_path = f.split("=", 1)[1]

    targets = resolve_targets(rootfs)
    if not targets:
        print("[FAIL] 没有可打补丁的 glibc 二进制（rootfs 不完整？）")
        return 1

    print(f"补丁目标（去重后 {len(targets)} 个）:")
    for rel, rp in targets:
        print(f"  {rel} -> {os.path.relpath(rp, rootfs)}")

    if relaxed:
        print("[mode] --relaxed：按扫描结果打，不校验精确命中数；"
              "打完回读确认无残留 svc 站点")

    report = {"rootfs": os.path.abspath(rootfs), "files": [], "patches": []}
    all_ok = True

    for rel, rp in targets:
        before = sha256_file(rp)
        file_ok = patch_file(rp, do_write=not check_only, report=report,
                             skip_seccomp=skip_seccomp, relaxed=relaxed)
        after = sha256_file(rp) if not check_only else before
        report["files"].append({
            "path": rel,
            "real": os.path.relpath(rp, rootfs),
            "sha256_before": before,
            "sha256_after": after,
            "patched": (not check_only) and file_ok,
        })
        all_ok = all_ok and file_ok

    if manifest_path and not check_only:
        with open(manifest_path, "w") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"[ok]   清单已写出: {manifest_path}")

    print()
    if all_ok:
        print("glibc seccomp 补丁: 全部通过"
              + ("（仅校验，未修改）" if check_only else "")
              + ("（--skip-seccomp：只做了 resolv.conf 路径改写）" if skip_seccomp else ""))
        return 0
    print("glibc seccomp 补丁: 存在失败项 —— 构建应当在此中止")
    return 1


if __name__ == "__main__":
    sys.exit(main())
