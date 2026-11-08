#!/usr/bin/env python3
"""最小 xz/unxz：-d 解压（python 自带 lzma），支持 -c 输出到 stdout。"""
import sys, lzma, os

def main():
    a = sys.argv[1:]
    to_stdout = "-c" in a or "--stdout" in a or os.path.basename(sys.argv[0]) in ("xzcat", "unxz")
    files = [x for x in a if not x.startswith("-")]
    if not files:
        data = lzma.decompress(sys.stdin.buffer.read())
        sys.stdout.buffer.write(data)
        return
    for f in files:
        with open(f, "rb") as fh:
            out = lzma.decompress(fh.read())
        if to_stdout:
            sys.stdout.buffer.write(out)
        else:
            dst = f[:-3] if f.endswith(".xz") else f + ".out"
            open(dst, "wb").write(out)
            os.remove(f)

if __name__ == "__main__":
    main()
