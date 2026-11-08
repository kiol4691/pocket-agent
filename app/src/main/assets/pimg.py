#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""
pimg —— 在 pocketroot 里管 Docker/OCI 镜像（无 daemon、无 root）

    pimg pull <repo>[:tag] [--registry HOST] [--name NAME]
    pimg ls
    pimg info <name>
    pimg patch <name>
    pimg rm <name>
    pimg run <name> [-- argv...]

镜像落在 IMAGES/<name>/{rootfs,manifest.json}；pull 完会**自动打 glibc 补丁**
（libc 的 5 个 seccomp 站点 + resolv.conf 路径），打不上就整条 pull 失败并把
目录删掉 —— 宁可失败，也不留一个启动就 SIGSYS 的镜像。

环境：
    PIMG_REGISTRY       默认 docker.m.daocloud.io（国内可达）
    PIMG_IMAGES         客体内的镜像目录，默认 /images
    PIMG_HOST_IMAGES    同一目录的宿主路径（pimg run 用，由 App 注入）
    PIMG_PATCHER        补丁脚本路径，默认 /usr/local/lib/pimg/patch-glibc.py
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time

try:
    import requests
except ImportError:
    print("[FAIL] 需要 python3-requests（本 rootfs 自带）")
    sys.exit(1)

REGISTRY = os.environ.get("PIMG_REGISTRY", "docker.m.daocloud.io")
IMAGES = os.environ.get("PIMG_IMAGES", "/images")
HOST_IMAGES = os.environ.get("PIMG_HOST_IMAGES", "")
PATCHER = os.environ.get("PIMG_PATCHER", "/usr/local/lib/pimg/patch-glibc.py")
ARCH = "arm64"
ACCEPT = ",".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])


def die(msg):
    print("[FAIL] " + msg)
    sys.exit(1)


def split_ref(ref):
    if ":" in ref.rsplit("/", 1)[-1]:
        repo, tag = ref.rsplit(":", 1)
    else:
        repo, tag = ref, "latest"
    if "/" not in repo:
        repo = "library/" + repo
    return repo, tag


def get_token(reg, repo, tag):
    """先匿名探一次，从 401 的 WWW-Authenticate 里拿 realm（不同 registry 不一样）。"""
    url = "https://%s/v2/%s/manifests/%s" % (reg, repo, tag)
    r = requests.get(url, headers={"Accept": ACCEPT}, timeout=30)
    if r.status_code == 200:
        return None
    if r.status_code != 401:
        die("拉 manifest 失败：HTTP %d %s" % (r.status_code, r.text[:200]))
    hdr = r.headers.get("WWW-Authenticate", "")
    realm = service = scope = None
    for part in hdr.replace("Bearer ", "").split(","):
        k, _, v = part.strip().partition("=")
        v = v.strip('"')
        if k == "realm":
            realm = v
        elif k == "service":
            service = v
        elif k == "scope":
            scope = v
    if not realm:
        return None
    params = {}
    if service:
        params["service"] = service
    params["scope"] = scope or ("repository:%s:pull" % repo)
    t = requests.get(realm, params=params, timeout=30)
    if t.status_code != 200:
        die("取 token 失败：HTTP %d" % t.status_code)
    return t.json().get("token")


def pick_manifest(reg, repo, tag, tok):
    url = "https://%s/v2/%s/manifests/%s" % (reg, repo, tag)
    h = {"Accept": ACCEPT}
    if tok:
        h["Authorization"] = "Bearer " + tok
    r = requests.get(url, headers=h, timeout=30)
    if r.status_code != 200:
        die("拉 manifest 失败：HTTP %d %s" % (r.status_code, r.text[:200]))
    m = r.json()
    digest = r.headers.get("Docker-Content-Digest", "")
    if m.get("manifests"):
        entry = None
        for x in m["manifests"]:
            pf = x.get("platform") or {}
            if pf.get("os") == "linux" and pf.get("architecture") == ARCH:
                entry = x
                break
        if not entry:
            die("这个镜像没有 linux/%s 变体（可用：%s）" % (
                ARCH, ", ".join(sorted({(x.get("platform") or {}).get("architecture", "?")
                                        for x in m["manifests"]}))))
        r2 = requests.get("https://%s/v2/%s/manifests/%s" % (reg, repo, entry["digest"]),
                          headers=h, timeout=30)
        if r2.status_code != 200:
            die("拉 arm64 manifest 失败：HTTP %d" % r2.status_code)
        return r2.json(), entry["digest"]
    return m, digest


def download(url, headers, dst, want_sha=None):
    """下载一层，失败重试 3 次。

    真机现象：130 MB 的层下到 128 MB 时 `ChunkedEncodingError: IncompleteRead`
    （手机网络抖动/连接被掐），不重试的话整个 pull 白费。
    """
    last = None
    for attempt in range(3):
        try:
            return _download_once(url, headers, dst, want_sha)
        except Exception as e:          # noqa: BLE001 —— 网络错误种类太多
            last = e
            print("      下载中断（第 %d 次）：%s，重试…" % (attempt + 1, type(e).__name__))
            time.sleep(2 + attempt * 3)
    die("下载失败：%r" % (last,))


def _download_once(url, headers, dst, want_sha=None):
    r = requests.get(url, headers=headers, stream=True, timeout=120)
    if r.status_code != 200:
        die("下载失败：HTTP %d %s" % (r.status_code, url))
    h = hashlib.sha256()
    n = 0
    with open(dst, "wb") as f:
        for chunk in r.iter_content(1 << 20):
            if not chunk:
                continue
            f.write(chunk)
            h.update(chunk)
            n += len(chunk)
    if want_sha:
        got = h.hexdigest()
        if got != want_sha.split(":")[-1]:
            die("层校验失败：%s != %s" % (got, want_sha))
    return n


def safe_join(root, name):
    name = name.lstrip("/")
    p = os.path.abspath(os.path.join(root, name))
    if p != root and not p.startswith(root + os.sep):
        return None
    return p


def _ensure_writable(d):
    """把目录对 owner 补上 w。

    Android 上我们是普通 uid：镜像里 `0555 root:root` 的目录对我们就是只读，
    下一层再往里写就 EACCES（真机现象：
      PermissionError: [Errno 13] ... /etc/pki/ca-trust/extracted/pem/directory-hash/002c0b4f.0
    docker 里是 root，所以从来不会暴露这个问题）。
    """
    try:
        import stat as _stat
        st = os.stat(d)
        if _stat.S_ISDIR(st.st_mode) and not (st.st_mode & 0o200):
            os.chmod(d, (st.st_mode & 0o7777) | 0o700)
    except OSError:
        pass


def _rmtree(p):
    """删目录时先补写权限：只读目录里的文件普通 uid 删不掉。"""
    def onerr(func, path, exc):
        try:
            os.chmod(os.path.dirname(path), 0o700)
            os.chmod(path, 0o700)
        except OSError:
            pass
        try:
            func(path)
        except OSError:
            pass
    shutil.rmtree(p, onerror=onerr)

def apply_layer(tar_path, root):
    """按 OCI 规则落层：.wh.<name> 删除、.wh..wh..opq 清空目录。"""
    removed = 0
    written = 0
    with tarfile.open(tar_path, "r:*") as tf:
        for m in tf:
            name = m.name
            base = os.path.basename(name.rstrip("/"))
            parent = os.path.dirname(name.rstrip("/"))
            if base == ".wh..wh..opq":
                d = safe_join(root, parent)
                if d and os.path.isdir(d):
                    _ensure_writable(d)
                    for e in os.listdir(d):
                        p = os.path.join(d, e)
                        _rmtree(p) if os.path.isdir(p) and not os.path.islink(p) else os.remove(p)
                    removed += 1
                continue
            if base.startswith(".wh."):
                p = safe_join(root, os.path.join(parent, base[4:]))
                if p and (os.path.lexists(p)):
                    _ensure_writable(os.path.dirname(p))
                    if os.path.isdir(p) and not os.path.islink(p):
                        _rmtree(p)
                    else:
                        os.remove(p)
                    removed += 1
                continue

            p = safe_join(root, name)
            if p is None:
                continue
            if m.isdir():
                os.makedirs(p, exist_ok=True)
                # 补 owner 写权限：后面还有层要往这个目录里写
                os.chmod(p, (m.mode & 0o7777) | 0o700)
            elif m.issym():
                os.makedirs(os.path.dirname(p), exist_ok=True)
                _ensure_writable(os.path.dirname(p))
                if os.path.lexists(p):
                    os.remove(p)
                os.symlink(m.linkname, p)
            elif m.islnk():
                src = safe_join(root, m.linkname)
                if src and os.path.exists(src):
                    os.makedirs(os.path.dirname(p), exist_ok=True)
                    _ensure_writable(os.path.dirname(p))
                    if os.path.lexists(p):
                        os.remove(p)
                    try:
                        os.link(src, p)
                    except OSError:
                        # Android 的 SELinux 不允许 app 数据目录里建硬链接，退化成复制
                        shutil.copyfile(src, p)
                        os.chmod(p, m.mode & 0o7777)
            elif m.isfile():
                os.makedirs(os.path.dirname(p), exist_ok=True)
                _ensure_writable(os.path.dirname(p))
                # 先删掉旧条目：镜像里 /etc/gshadow 这类文件是 0000，
                # 普通 uid 连 open(...,"wb") 都会 EACCES（docker 是 root）。
                if os.path.lexists(p):
                    if os.path.isdir(p) and not os.path.islink(p):
                        _rmtree(p)
                    else:
                        try:
                            os.remove(p)
                        except OSError:
                            os.chmod(p, 0o600)
                            os.remove(p)
                with tf.extractfile(m) as fsrc, open(p, "wb") as fdst:
                    shutil.copyfileobj(fsrc, fdst, 1 << 20)
                os.chmod(p, m.mode & 0o7777)
                written += 1
    return written, removed


def patch_rootfs(rootfs):
    """在当前解释器里直接跑补丁脚本。

    不用 subprocess：客体里 posix_spawn("/usr/local/bin/python3") 会被 shim 的
    execve 钩子翻译，实测抛 FileNotFoundError（真机验证过），而补丁脚本本来就是
    纯 stdlib，import 进来跑最稳。
    """
    if not os.path.isfile(PATCHER):
        return {"ok": False, "why": "patcher 缺失: " + PATCHER}
    import contextlib
    import importlib.util
    buf = io.StringIO()
    spec = importlib.util.spec_from_file_location("pimg_patcher", PATCHER)
    mod = importlib.util.module_from_spec(spec)
    old_argv = sys.argv
    try:
        spec.loader.exec_module(mod)
        sys.argv = [PATCHER, rootfs, "--relaxed"]
        with contextlib.redirect_stdout(buf):
            rc = mod.main()
    except SystemExit as e:
        rc = e.code if isinstance(e.code, int) else 1
    except Exception as e:
        return {"ok": False, "rc": 1, "log": "patch 异常: %r" % (e,)}
    finally:
        sys.argv = old_argv
    return {"ok": rc == 0, "rc": rc, "log": buf.getvalue()[-1500:]}


def cmd_pull(a):
    repo, tag = split_ref(a.ref)
    name = a.name or (repo.replace("library/", "") + ":" + tag).replace("/", "_").replace(":", "_")
    dest = os.path.join(IMAGES, name)
    if os.path.exists(dest):
        die("镜像已存在：%s（先 pimg rm %s）" % (dest, name))
    reg = a.registry or REGISTRY
    os.makedirs(IMAGES, exist_ok=True)
    print("[1/5] 解析 %s:%s @ %s" % (repo, tag, reg))
    tok = get_token(reg, repo, tag)
    man, digest = pick_manifest(reg, repo, tag, tok)
    layers = man.get("layers") or []
    if not layers:
        die("manifest 里没有 layers")
    total = sum(l.get("size", 0) for l in layers)
    print("      arm64 manifest %s，%d 层，压缩 %.1f MB" % (digest[:19], len(layers), total / 1e6))

    # 临时目录必须建在 IMAGES 下：/tmp 与镜像盘常常不是同一个文件系统，
    # 最后 os.rename 会 EXDEV（真机/本机都踩过：Invalid cross-device link）。
    os.makedirs(IMAGES, exist_ok=True)
    staging = os.path.join(IMAGES, ".staging")
    os.makedirs(staging, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="pimg-", dir=staging)
    work = os.path.join(tmp, "rootfs")
    os.makedirs(work)
    h = {"Authorization": "Bearer " + tok} if tok else {}
    try:
        for i, l in enumerate(layers):
            f = os.path.join(tmp, "l%d.tgz" % i)
            print("[2/5] 下载层 %d/%d（%.1f MB）" % (i + 1, len(layers), l.get("size", 0) / 1e6))
            download("https://%s/v2/%s/blobs/%s" % (reg, repo, l["digest"]), h, f, l["digest"])
            w, r = apply_layer(f, work)
            print("      落盘 %d 个文件，whiteout %d 处" % (w, r))
            os.remove(f)

        print("[3/5] 打 seccomp 补丁")
        rep = patch_rootfs(work)
        if not rep["ok"]:
            print(rep.get("log", ""))
            die("补丁失败 —— 这个镜像的 libc 版本不认识，已中止（目录不会留下）")
        print("      补丁完成")

        print("[4/5] 写 manifest")
        meta = {
            "name": name, "repo": repo, "tag": tag, "registry": reg,
            "digest": digest, "arch": "linux/" + ARCH,
            "layers": len(layers), "compressed_bytes": total,
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        with open(os.path.join(tmp, "manifest.json"), "w") as f:
            json.dump(meta, f, indent=2, ensure_ascii=False)
        os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
        try:
            os.rename(tmp, dest)
        except OSError as e:
            # 保底：跨设备时退化为拷贝（大镜像会慢，但总比整个 pull 白费强）
            if e.errno != 18:
                raise
            shutil.move(tmp, dest)
        print("[5/5] 完成：%s（%s）" % (name, dest))
        print("      运行：pimg run %s -- /bin/sh -c 'cat /etc/os-release'" % name)
    except Exception:
        _rmtree(tmp)   # 用带 chmod 的版本：镜像里常有 0000/0555 的目录，普通 rmtree 删不掉
        raise


def load_meta(name):
    p = os.path.join(IMAGES, name, "manifest.json")
    if not os.path.isfile(p):
        die("没有这个镜像：%s" % name)
    with open(p) as f:
        return json.load(f), os.path.join(IMAGES, name, "rootfs")


def cmd_ls(_a):
    if not os.path.isdir(IMAGES):
        print("(还没有镜像)")
        return
    rows = []
    for n in sorted(os.listdir(IMAGES)):
        p = os.path.join(IMAGES, n, "manifest.json")
        if os.path.isfile(p):
            with open(p) as f:
                m = json.load(f)
            rows.append((n, m.get("repo", "?"), m.get("tag", "?"), m.get("created_at", "")))
    if not rows:
        print("(还没有镜像)")
        return
    for r in rows:
        print("%-28s %-24s %-10s %s" % r)


def cmd_info(a):
    m, root = load_meta(a.name)
    print(json.dumps(m, indent=2, ensure_ascii=False))
    print("rootfs:", root, "(%.1f MB)" % (sum(
        os.path.getsize(os.path.join(d, f))
        for d, _, fs in os.walk(root) for f in fs) / 1e6))


def cmd_patch(a):
    m, root = load_meta(a.name)
    rep = patch_rootfs(root)
    print(rep.get("log", ""))
    sys.exit(0 if rep["ok"] else 1)


def cmd_rm(a):
    d = os.path.join(IMAGES, a.name)
    if not os.path.isdir(d):
        die("没有这个镜像：%s" % a.name)
    shutil.rmtree(d)
    print("已删除", a.name)


def cmd_run(a):
    """在镜像里执行命令（= docker run 的最小版）。

    关键点：**不要 stat 宿主路径**。pimg 自己就跑在 shim 里，宿主路径在它眼里是
    不存在的；只要把「客体的 ldso 路径」交给 execve（shim 会按 /images 绑定翻译成
    宿主路径），再把 ZNG_ROOT 设成**宿主**路径字符串（纯字符串，不 stat）给子进程，
    子进程就会以镜像为根重新初始化一遍 shim。
    """
    m, root = load_meta(a.name)
    guest_root = root                      # /images/<name>/rootfs（客体视图）
    host_root = root
    if HOST_IMAGES and IMAGES and root.startswith(IMAGES):
        host_root = HOST_IMAGES + root[len(IMAGES):]
    argv = list(a.argv or [])
    if argv and argv[0] == "--":
        argv = argv[1:]
    if not argv:
        argv = ["/bin/sh"]

    ldso = None
    for cand in ("lib/ld-linux-aarch64.so.1", "lib/ld-musl-aarch64.so.1",
                 "usr/lib/ld-linux-aarch64.so.1"):
        p = os.path.join(guest_root, cand)
        if os.path.exists(p):                  # 客体路径，shim 能解析
            ldso = p
            break
    if ldso is None:
        die("镜像里找不到动态加载器：%s" % guest_root)

    env = dict(os.environ)
    env["ZNG_ROOT"] = host_root
    env["PIMG_IMAGES"] = IMAGES
    env["HOME"] = "/root"
    # 镜像自己可能没有 /root/workspace，绑定保留（不存在时 shim 会忽略）
    os.execve(ldso, [ldso] + argv, env)
def main():
    ap = argparse.ArgumentParser(prog="pimg", add_help=True)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("pull", help="拉取并打补丁")
    p.add_argument("ref")
    p.add_argument("--registry")
    p.add_argument("--name")
    p.set_defaults(fn=cmd_pull)

    p = sub.add_parser("ls")
    p.set_defaults(fn=cmd_ls)

    p = sub.add_parser("info")
    p.add_argument("name")
    p.set_defaults(fn=cmd_info)

    p = sub.add_parser("patch")
    p.add_argument("name")
    p.set_defaults(fn=cmd_patch)

    p = sub.add_parser("rm")
    p.add_argument("name")
    p.set_defaults(fn=cmd_rm)

    p = sub.add_parser("run")
    p.add_argument("name")
    p.add_argument("argv", nargs=argparse.REMAINDER)
    p.set_defaults(fn=cmd_run)

    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
