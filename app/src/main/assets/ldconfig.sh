#!/bin/sh
# pocketroot: Debian's /usr/sbin/ldconfig is a static-pie binary (no PT_INTERP).
# It loads NO LD_PRELOAD shim, therefore has none of the SIGSYS emulation
# installed by the shim's constructor; Android's zygote seccomp kills it
# (SIGSYS, "Bad system call", exit 159) on the first blocked syscall. That is
# what leaves libc-bin in half-configured state during every apt install.
#
# The old wrapper remapped "-r /" to ZNG_ROOT but still exec'd this static
# binary at the end, and it only lived in /usr/local/bin -- dpkg runs postinsts
# with PATH=/usr/sbin:/usr/bin:/sbin:/bin, so it was never even reached.
# Changing PATH does not help either: the process still dies inside ldconfig.
#
# So the only workable fix is to NOT run it. The Debian image already ships a
# valid /etc/ld.so.cache, and the shim supplies its own library search dirs
# (zng_libpath), so skipping the cache rebuild is harmless: newly installed
# packages land in the standard /usr/lib/aarch64-linux-gnu paths.
#
# GuestTools.installCompat installs this script at all three PATH hit points:
#   /usr/local/bin/ldconfig, /usr/sbin/ldconfig, /sbin/ldconfig
exit 0
