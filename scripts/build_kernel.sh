#!/usr/bin/env bash
#
# Builds a Linux kernel restricted to exactly the extensions this emulator can decode.
#
# The emulator implements RV64IMACU. Distribution riscv64 kernels enable the V (vector) extension by
# default, so they execute instructions the interpreter rejects as illegal within the first few
# hundred instructions of boot. The fix is to compile a kernel that never emits them, which is what
# this script does.
#
# Only `Image` is built. That is enough to boot and is much smaller and faster than `make all`,
# because it skips every loadable module.
#
# Requirements on the host:
#   * a riscv64 cross toolchain:        $CROSS_COMPILE-gcc (defaults to riscv64-linux-gnu-)
#   * flex, bison, bc, libssl-dev, libelf-dev, dwarves (optional), rsync, cpio
#
# Usage:
#   scripts/build_kernel.sh                 # download, configure and build
#   scripts/build_kernel.sh --keep          # keep the source and object tree afterwards
#   scripts/build_kernel.sh --clean         # remove any existing tree first
#   KERNEL_VERSION=6.12.10 scripts/build_kernel.sh
#
# On success the kernel is copied to src/test/resources/payloads/Image so LinuxBootRunner can boot
# it directly.

set -euo pipefail

KERNEL_VERSION="${KERNEL_VERSION:-6.12}"
KERNEL_URL="${KERNEL_URL:-https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-${KERNEL_VERSION}.tar.xz}"
CROSS_COMPILE="${CROSS_COMPILE:-riscv64-linux-gnu-}"
JOBS="${JOBS:-$(nproc)}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="${BUILD_ROOT:-${TMPDIR:-/tmp}/riscv-emulator-kernel-build}"
SRC_DIR="${BUILD_ROOT}/linux-${KERNEL_VERSION}"
TARBALL="${BUILD_ROOT}/linux-${KERNEL_VERSION}.tar.xz"
OUTPUT="${REPO_ROOT}/src/test/resources/payloads/Image"

KEEP=0
for arg in "$@"; do
    case "$arg" in
        --keep) KEEP=1 ;;
        --clean) rm -rf "${BUILD_ROOT}"; mkdir -p "${BUILD_ROOT}" ;;
        *) echo "unknown option $arg" >&2; exit 2 ;;
    esac
done

mkdir -p "${BUILD_ROOT}" "$(dirname "${OUTPUT}")"

say() { printf '\n=== %s\n' "$*"; }

say "host prerequisites"
for tool in "${CROSS_COMPILE}gcc" make bc flex bison cpio rsync xz; do
    command -v "${tool}" >/dev/null 2>&1 || {
        echo "missing required tool: ${tool}" >&2
        exit 1
    }
done
echo "cross compiler: $("${CROSS_COMPILE}gcc" --version | head -1)"
echo "parallel jobs : ${JOBS}"
echo "build root    : ${BUILD_ROOT}"

say "free space before"
df -h "${BUILD_ROOT}" | tail -1

# ---------------------------------------------------------------------------------------------
# Source
# ---------------------------------------------------------------------------------------------
if [[ ! -d "${SRC_DIR}" ]]; then
    if [[ ! -f "${TARBALL}" ]]; then
        say "downloading linux-${KERNEL_VERSION}"
        wget -q --show-progress -O "${TARBALL}" "${KERNEL_URL}"
    fi
    say "extracting"
    # The tarball is deleted immediately: it is dead weight while the 1.4 GB tree exists.
    tar -xf "${TARBALL}" -C "${BUILD_ROOT}"
    rm -f "${TARBALL}"
fi

cd "${SRC_DIR}"

# ---------------------------------------------------------------------------------------------
# Configuration
#
# Start from the architecture defconfig, then force the ISA to match the emulator. olddefconfig is
# run afterwards so the Kconfig dependencies get a chance to re-enable anything that was turned off
# because it depended on a now-disabled symbol.
# ---------------------------------------------------------------------------------------------
if [[ ! -f .config ]]; then
    say "generating defconfig"
    make ARCH=riscv CROSS_COMPILE="${CROSS_COMPILE}" defconfig
fi

say "applying the RV64IMACU contract"

config() {
    ./scripts/config --file .config "$@"
}

config --enable  CONFIG_ARCH_RV64I
config --enable  CONFIG_RISCV_ISA_C
config --enable  CONFIG_RISCV_ISA_M
config --disable CONFIG_RISCV_ISA_V
config --disable CONFIG_FPU

config --enable  CONFIG_SERIAL_8250
config --enable  CONFIG_SERIAL_8250_CONSOLE
config --enable  CONFIG_VIRTIO_MENU
config --enable  CONFIG_VIRTIO_MMIO
config --enable  CONFIG_VIRTIO_BLK

# The device tree the emulator hands over describes an ns16550a and virtio-mmio devices, so the
# corresponding platform drivers and the SBI layer have to be present.
config --enable  CONFIG_SERIAL_8250_DF
config --enable  CONFIG_SERIAL_8250_16550A_VARIANTS
config --enable  CONFIG_RISCV_SBI
config --enable  CONFIG_OF
config --enable  CONFIG_OF_RISCV

# Keep the build small and the boot path simple: no modules to unpack, no initrd to relocate.
config --disable CONFIG_MODULES
config --disable CONFIG_BLK_DEV_INITRD
config --enable  CONFIG_BLK_DEV_INITRD

make ARCH=riscv CROSS_COMPILE="${CROSS_COMPILE}" olddefconfig

say "verifying the contract survived olddefconfig"

# This is the step that matters, and it is deliberately not "trust the commands above". A Kconfig
# "select" can silently turn a disabled option back on, and the result is a kernel that faults on
# illegal vector instructions at boot.
#
# Two traps make a naive check lie:
#   * Kconfig writes a disabled option as "# CONFIG_X is not set", never as "CONFIG_X=n", so
#     grepping for the latter reports every disabled option as missing.
#   * Not every requested symbol exists in every kernel version. CONFIG_RISCV_ISA_M disappeared once
#     M became mandatory for rv64, and CONFIG_RISCV_ISA_F is unconditional on any kernel with an
#     FPU, so asking for either is a no-op rather than an error.
#
# scripts/config --state is used instead of grepping, because it reads the generated .config the
# same way the build will and reports y / n / m / undef.

failed=0

# Does this symbol exist in this kernel's Kconfig at all?
symbol_exists() {
    grep -rqE "^config[[:space:]]+$1\$" --include='Kconfig*' . && return 0 || return 1
}

check_enable() {
    local symbol="$1" state
    state="$(./scripts/config --state "${symbol}")"
    if [[ "${state}" == "y" ]]; then
        printf '  %-32s y
' "${symbol}"
    else
        printf '  %-32s WANT y, GOT %s
' "${symbol}" "${state:-undef}"
        failed=1
    fi
}

check_disable() {
    local symbol="$1" state
    state="$(./scripts/config --state "${symbol}")"
    # "undef" means the symbol is not reachable under this configuration, which for a wanted-off
    # option is as good as an explicit n: the feature cannot be built in.
    if [[ "${state}" == "n" || "${state}" == "undef" ]]; then
        printf '  %-32s off (%s)\n' "${symbol}" "${state}"
    else
        printf '  %-32s WANT off, GOT %s\n' "${symbol}" "${state}"
        failed=1
    fi
}

check_exists_enabled() {
    local symbol="$1" note="$2" state
    if ! symbol_exists "${symbol#CONFIG_}"; then
        printf '  %-32s not a Kconfig symbol here: %s\n' "${symbol}" "${note}"
        return 0
    fi
    check_enable "${symbol}"
}

# The emulator implements exactly RV64IMACU.
check_exists_enabled CONFIG_ARCH_RV64I    ""
check_exists_enabled CONFIG_RISCV_ISA_M   "M is mandatory for rv64 and always built in"
check_enable       CONFIG_RISCV_ISA_C
check_disable      CONFIG_RISCV_ISA_V
check_disable      CONFIG_FPU

# The device tree the emulator hands over describes an ns16550a and virtio-mmio devices.
check_enable CONFIG_SERIAL_8250
check_enable CONFIG_SERIAL_8250_CONSOLE
check_enable CONFIG_VIRTIO_MENU
check_enable CONFIG_VIRTIO_MMIO
check_enable CONFIG_VIRTIO_BLK
check_enable CONFIG_RISCV_SBI

if (( failed )); then
    echo
    echo "configuration does not match the emulator's ISA contract; refusing to build" >&2
    exit 1
fi

say "kernel config summary"
grep -E "^CONFIG_(RISCV_ISA_|FPU|ARCH_RV64|SERIAL_8250(_CONSOLE)?|VIRTIO_(MENU|MMIO|BLK)|RISCV_SBI)=" .config | sort

# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------
say "free space before build"
df -h "${BUILD_ROOT}" | tail -1

say "building Image with ${JOBS} jobs (this is the slow part)"
make ARCH=riscv CROSS_COMPILE="${CROSS_COMPILE}" -j"${JOBS}" Image

IMAGE="${SRC_DIR}/arch/riscv/boot/Image"
[[ -f "${IMAGE}" ]] || { echo "no Image produced at ${IMAGE}" >&2; exit 1; }

say "built kernel"
ls -la "${IMAGE}"
file "${IMAGE}"

cp "${IMAGE}" "${OUTPUT}"
say "copied to ${OUTPUT}"

# ---------------------------------------------------------------------------------------------
# No second objcopy pass.
#
# arch/riscv/boot/Image is already a flat binary produced by objcopy during the kernel build, and
# it already carries the PE/COFF header at offset 0. Running objcopy -O binary over it is a
# different operation: it drops .text from image+0x1000 to image+0, shifting every symbol by
# 0x1000 and breaking relocation. Verified against real firmware - the stripped image fails to
# produce a single line of output under qemu-system-riscv64 + OpenSBI, while the unstripped Image
# boots to "Linux version" and on to userspace. Boot the kernel's own Image unmodified.
# ---------------------------------------------------------------------------------------------

if (( KEEP == 0 )); then
    say "cleaning build tree (pass --keep to retain it)"
    cd /
    rm -rf "${BUILD_ROOT}"
fi

say "free space after"
df -h "$(dirname "${OUTPUT}")" | tail -1