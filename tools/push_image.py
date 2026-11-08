#!/usr/bin/env python3
"""把已经 pull 好的镜像推到手机（网络不稳时在本机拉更靠谱）。

用法: tools/push_image.py <本机镜像目录> [--transport N] [--pkg 包名]

设备侧 tar 有三件事做不到，这里预处理：
  1. 0000/0555 的文件和目录（我们不是 root）读不了也写不进 -> 补 0600/0700
  2. toybox tar 拒绝创建指向解包目录之外的绝对符号链接 -> 目标在 rootfs 内就改相对
  3. SELinux 不允许 app 目录建硬链接 -> tar --hard-dereference 展开
"""
import argparse
import os
import subprocess
import sys


def fix_tree(rootfs):
    nperm = nlink = 0
    for d, dirs, files in os.walk(rootfs):
        for name in dirs:
            p = os.path.join(d, name)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            if os.path.islink(p):
                continue
            if (st.st_mode & 0o700) != 0o700:
                os.chmod(p, (st.st_mode & 0o7777) | 0o700)
                nperm += 1
        for name in files:
            p = os.path.join(d, name)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            if os.path.islink(p):
                t = os.readlink(p)
                if t.startswith("/"):
                    inside = os.path.join(rootfs, t.lstrip("/"))
                    if os.path.lexists(inside):
                        os.remove(p)
                        os.symlink(os.path.relpath(t, os.path.dirname(p)), p)
                        nlink += 1
            elif (st.st_mode & 0o400) == 0:
                os.chmod(p, (st.st_mode & 0o7777) | 0o600)
                nperm += 1
    print("  权限修补 %d 处，绝对符号链接改写 %d 处" % (nperm, nlink))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image_dir")
    ap.add_argument("--pkg", default="com.dsh.android.pkr")
    ap.add_argument("--transport", default="")
    a = ap.parse_args()

    src = os.path.abspath(a.image_dir.rstrip("/"))
    name = os.path.basename(src)
    if not os.path.isdir(os.path.join(src, "rootfs")):
        print("[FAIL] %s 下没有 rootfs/" % src)
        sys.exit(1)

    adb = ["adb"] + (["-t", a.transport] if a.transport else [])
    print("[1/3] 准备 %s" % name)
    fix_tree(os.path.join(src, "rootfs"))

    print("[2/3] 打包并推送")
    remote = "mkdir -p files/oci && cd files/oci && gzip -dc | tar -xf -"
    pack = subprocess.Popen(["tar", "--hard-dereference", "-cf", "-",
                             "-C", os.path.dirname(src), name], stdout=subprocess.PIPE)
    gz = subprocess.Popen(["gzip", "-1", "-c"], stdin=pack.stdout, stdout=subprocess.PIPE)
    pack.stdout.close()
    push = subprocess.Popen(adb + ["shell", "run-as " + a.pkg + " sh -c " + repr(remote)],
                            stdin=gz.stdout)
    gz.stdout.close()
    rc = push.wait()
    gz.wait()
    pack.wait()
    if rc != 0:
        print("[FAIL] 推送失败 rc=%d" % rc)
        sys.exit(rc)

    print("[3/3] 校验")
    subprocess.call(adb + ["shell", "run-as %s du -sm files/oci/%s" % (a.pkg, name)])
    print("完成：container {image: \"%s\", command: \"...\"}" % name)


if __name__ == "__main__":
    main()
