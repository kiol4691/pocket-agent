#!/usr/bin/env python3
"""最小 ar：只实现 deb 解包需要的 t / x / p（不依赖 binutils）。"""
import sys, os

def members(data):
    if data[:8] != b"!<arch>\n":
        raise SystemExit("ar: not an archive")
    pos = 8
    while pos + 60 <= len(data):
        h = data[pos:pos + 60]
        size = int((h[48:58].decode() or "0").strip() or "0")
        body = data[pos + 60:pos + 60 + size]
        name = h[0:16].decode("ascii", "replace").strip().rstrip("/")
        yield name, body
        pos += 60 + size + (size % 2)

def main():
    a = sys.argv[1:]
    if not a:
        raise SystemExit("usage: ar {t|x|p} ARCHIVE [member...]")
    cmd, arch = a[0], a[1]
    want = a[2:]
    data = open(arch, "rb").read()
    for name, body in members(data):
        if not name or name in ("/", "//") or (want and name not in want):
            continue
        if cmd == "t":
            print(name)
        elif cmd == "x":
            open(name, "wb").write(body)
        elif cmd == "p":
            sys.stdout.buffer.write(body)
        else:
            raise SystemExit("ar: unsupported op " + cmd)

if __name__ == "__main__":
    main()
