#!/usr/bin/env bash
#
# Builds a bootable root filesystem disk for LinuxBootRunner.
#
# The guest mounts this through virtio-blk, so the image has to be a real block
# device: a partition table the kernel can parse, with a filesystem inside it.
#
#   scripts/make_rootfs.sh                 # Alpine minirootfs -> /tmp/alpine-disk.img
#   scripts/make_rootfs.sh <out.img>       # somewhere else
#
# Requirements: mkfs.ext4, parted, dd, curl, tar.

set -euo pipefail

OUT="${1:-/tmp/alpine-disk.img}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ALPINE_VERSION="${ALPINE_VERSION:-3.24.2}"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/riscv64/alpine-minirootfs-${ALPINE_VERSION}-riscv64.tar.gz"

echo "fetching Alpine ${ALPINE_VERSION} minirootfs"
curl -sfL --retry 3 -o "$WORK/alpine.tar.gz" "$ALPINE_URL"

echo "unpacking"
mkdir -p "$WORK/root"
tar xzf "$WORK/alpine.tar.gz" -C "$WORK/root"

# The filesystem itself. mkfs.ext4 writes a protective MBR when it sees what it
# takes to be a whole disk, which leaves the kernel with a partition covering the
# entire image and nothing to mount; keeping the filesystem in its own file and
# dropping it at a partition offset avoids that entirely.
# Alpine's minirootfs ships an inittab that hands control to /sbin/openrc, which the
# minirootfs does not contain, so init exits with "can't run '/sbin/openrc'". Replacing
# it with a busybox inittab that mounts the pseudo filesystems and respawns a shell on
# the serial console is what makes the image reach a prompt unattended.
if true; then
    cat > "$WORK/root/etc/inittab" <<'INITTAB'
::sysinit:/bin/mount -t proc proc /proc
::sysinit:/bin/mount -t sysfs sysfs /sys
::sysinit:/bin/mount -t tmpfs tmpfs /tmp
::sysinit:/bin/mount -o remount,rw /
ttyS0::respawn:/sbin/getty -L 115200 ttyS0 vt100
::ctrlaltdel:/sbin/reboot
::shutdown:/bin/umount -a -r
INITTAB
fi

echo "building filesystem"
truncate -s 1000M "$WORK/fs.img"
mkfs.ext4 -q -F -d "$WORK/root" -L alpine-root "$WORK/fs.img"

echo "building partitioned disk -> $OUT"
rm -f "$OUT"
truncate -s 1100M "$OUT"
parted -s "$OUT" mklabel gpt mkpart primary ext4 1MiB 100% >/dev/null
dd if="$WORK/fs.img" of="$OUT" bs=1MiB seek=1 conv=notrunc status=none

echo
echo "done: $OUT"
echo "boot with:"
echo "  gradle build --no-daemon"
echo "  java -cp build/classes/java/main com.emulator.riscv.LinuxBootRunner \\"
echo "    --kernel=src/test/resources/payloads/Image --rootfs=$OUT --ram=1024 \\"
echo "    --bootargs='console=ttyS0,115200 earlycon root=/dev/vda1 rw' \\"
echo "    --halt-on='VFS: Unable to mount root fs'"