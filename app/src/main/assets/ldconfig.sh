#!/bin/sh
# pocketroot: Debian's ldconfig is a static-pie binary (no PT_INTERP), so the
# LD_PRELOAD shim never sees it and cannot translate path arguments. Running it
# as-is would operate on Android's real "/" and fail. Remap -r <guest root> to
# the host rootfs, where the shim's ZNG_ROOT points.
REAL=/usr/sbin/ldconfig
[ -x "$REAL" ] || REAL=/sbin/ldconfig
ROOT="$ZNG_ROOT"
[ -n "$ROOT" ] || exec "$REAL" "$@"
case "$1" in
  -r|--root)
    r="$2"
    [ -n "$r" ] || r=/
    case "$r" in
      /) exec "$REAL" -r "$ROOT" ;;
      *) exec "$REAL" -r "$ROOT$r" ;;
    esac ;;
  '') exec "$REAL" -r "$ROOT" ;;
  *) exec "$REAL" "$@" ;;
esac
