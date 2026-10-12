# Builds a graphical Alpine image for Theon.
#
# See DE_STATUS.md for what works, what does not, and why. Two things about this script are worth
# knowing before changing it, because both fail silently rather than loudly:
#
#   - apk exits non-zero whenever a package trigger fails, and under qemu-user the busybox and
#     fontconfig triggers always fail because they call unshare. Under set -e that aborts the
#     build before the image is assembled, which looks exactly like a hang.
#   - awk is not present on every host this runs on. The apk version is parsed with sed.
#
# Requirements: mkfs.ext4, parted, dd, curl, tar, and qemu-riscv64-static.

set -euo pipefail

OUT="${1:-/tmp/theon-desktop.img}"
WORK="${WORK:-${TMPDIR:-/tmp}/theon-de-build}"

ALPINE_VERSION="${ALPINE_VERSION:-3.24.2}"
ALPINE_VERSION_SERIES="${ALPINE_VERSION%.*}"
REPO_BASE="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION_SERIES}"
MINIROOTFS_URL="${REPO_BASE}/releases/riscv64/alpine-minirootfs-${ALPINE_VERSION}-riscv64.tar.gz"

# The framebuffer is 1024x768: it is the first mode in xorg.conf and matches the resolution the
# renderer is sized for. Change both together.
FB_WIDTH="${FB_WIDTH:-1024}"
FB_HEIGHT="${FB_HEIGHT:-768}"

QEMU="${QEMU:-qemu-riscv64-static}"
command -v "$QEMU" >/dev/null || { echo "need $QEMU on PATH" >&2; exit 1; }

mkdir -p "$WORK"
ROOT="$WORK/root"

say() { printf '\n=== %s\n' "$*"; }

say "fetching Alpine ${ALPINE_VERSION} minirootfs"
curl -sfL --retry 3 -o "$WORK/mini.tar.gz" "$MINIROOTFS_URL"
rm -rf "$ROOT"
mkdir -p "$ROOT"
# The tarball has no top-level directory, so it unpacks straight into the target. Unpacking it
# into an intermediate directory that is then copied yields an image with no base system at all.
tar xzf "$WORK/mini.tar.gz" -C "$ROOT"

say "fetching apk-tools-static (runs under qemu-user to install riscv64 packages)"
# Alpine's apk in the minirootfs is dynamically linked and its musl build trips qemu-user's
# relocation support. The static build has no loader and no shared libraries to relocate.
# The index is fetched to a file and parsed from there: piping it straight through tar loses the
# answer, because the parser that finds the version exits early and takes the upstream pipe down.
curl -sfL --retry 3 -o "$WORK/APKINDEX.tar.gz" "${REPO_BASE}/main/riscv64/APKINDEX.tar.gz"
tar xzf "$WORK/APKINDEX.tar.gz" -C "$WORK" APKINDEX
# sed rather than awk: awk is not reliably present on every host this runs on.
APK_VERSION="$(grep -A1 '^P:apk-tools-static$' "$WORK/APKINDEX" \
    | sed -n 's/^V://p' | head -1 | tr -d '\r')"
[ -n "$APK_VERSION" ] || { echo "could not determine apk-tools-static version" >&2; exit 1; }
curl -sfL --retry 3 -o "$WORK/apk-static.apk" \
    "${REPO_BASE}/main/riscv64/apk-tools-static-${APK_VERSION}.apk"
mkdir -p "$WORK/apk"
tar xzf "$WORK/apk-static.apk" -C "$WORK/apk"
APK="$WORK/apk/sbin/apk.static"

say "installing Xorg, the fbdev driver and xinit"
# shellcheck disable=SC2086
# Those trigger failures are the qemu ones described at the top of this file. Whether the
# install worked is checked directly instead of trusting the exit status.
"$QEMU" "$APK" --root "$ROOT" --arch riscv64 --usermode --allow-untrusted \
    --repository "${REPO_BASE}/main" \
    --repository "${REPO_BASE}/community" \
    add xorg-server xf86-video-fbdev xinit || true

for required in "$ROOT/usr/bin/Xorg" "$ROOT/usr/lib/xorg/modules/drivers/fbdev_drv.so"; do
    if [ ! -e "$required" ]; then
        echo "expected $required after install; the package install did not complete" >&2
        exit 1
    fi
done

say "laying out the guest"
# /dev must exist before the kernel mounts devtmpfs on it, and a missing mountpoint makes that
# mount fail with ENOENT, which leaves init with no console and a shell fallback.
mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/tmp"
chmod 1777 "$ROOT/tmp"
mkdir -p "$ROOT/etc/X11"

cat > "$ROOT/etc/X11/xorg.conf" <<EOF
Section "Device"
    Identifier "fbdev0"
    Driver     "fbdev"
    Option     "fbdev" "/dev/fb0"
EndSection
Section "Screen"
    Identifier "Screen0"
    Device     "fbdev0"
    DefaultDepth 24
    DefaultModes "${FB_WIDTH}x${FB_HEIGHT}"
    SubSection "Display"
        Depth   24
        Modes   "${FB_WIDTH}x${FB_HEIGHT}"
    EndSubSection
EndSection
Section "ServerLayout"
    Identifier "Layout0"
    Screen     "Screen0"
EndSection
EOF

cat > "$ROOT/usr/bin/theon-start" <<'EOF'
#!/bin/sh
# Starts the X server on the framebuffer once the DRM device has published it.
L=/dev/ttyS0
i=0
while [ ! -e /dev/fb0 ] && [ $i -lt 90 ]; do sleep 1; i=$((i+1)); done
echo "theon: /dev/fb0 ready, starting X" > $L
# Output goes to the console, not a file: the boot runner treats console silence as a wedge, and
# a server that logs to a file looks exactly like a hang.
exec /usr/bin/X :0 -config /etc/X11/xorg.conf -nolisten tcp > $L 2>&1
EOF
chmod +x "$ROOT/usr/bin/theon-start"

# busybox init runs the sysinit actions in order and blocks on each. X is exec'd from one, so it
# never returns and the respawn shell is only reached if X exits.
cat > "$ROOT/etc/inittab" <<'EOF'
::sysinit:/bin/mount -t proc proc /proc
::sysinit:/bin/mount -t sysfs sysfs /sys
::sysinit:/bin/mount -o remount,rw /
::sysinit:/usr/bin/theon-start
::respawn:/bin/sh
::ctrlaltdel:/sbin/reboot
::shutdown:/bin/umount -a -r
EOF

say "building filesystem"
SIZE_MB="${SIZE_MB:-700}"
truncate -s "${SIZE_MB}M" "$WORK/fs.img"
mkfs.ext4 -q -F -d "$ROOT" -L theon-desktop "$WORK/fs.img"

say "building partitioned disk -> $OUT"
rm -f "$OUT"
truncate -s "$((SIZE_MB + 100))M" "$OUT"
parted -s "$OUT" mklabel gpt mkpart primary ext4 1MiB 100% >/dev/null
dd if="$WORK/fs.img" of="$OUT" bs=1MiB seek=1 conv=notrunc status=none

echo
echo "done: $OUT"
echo "boot with: console=ttyS0,115200 earlycon root=/dev/vda1 rw"