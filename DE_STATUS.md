# Desktop environment status

Where the graphical guest got to, and what is known about the bug that stops it.

## What works

- `scripts/make_de_rootfs.sh` builds a graphical Alpine image end to end. Verified: produces a
  ~800 MiB GPT/ext4 disk with `xorg-server`, `xf86-video-fbdev` and `xinit` installed.
- riscv64 packages install on an x86 host via `qemu-riscv64-static` and Alpine's `apk.static`.
  The dynamic apk does not work under qemu-user; only the static build does.
- `Xorg -version` runs in the guest, so the binary, its libraries and the loader are all fine.
- `/dev/fb0` appears at every boot as a real character device (major 29) provided by the
  emulator's virtio-gpu device. `make_de_rootfs.sh` waits for it and then execs X against it.

## The bug

Xorg dies during display initialisation with a machine-mode trap that is not delegated to
supervisor mode, so the kernel never prints an oops about it.

A 96-deep instruction trace ring captured the last instructions before the fault:

```
pc=7fff824a4ade  insn=7cf150ef   jal  x1, ...
pc=7fff824baaac  insn=e2050653   opcode 0x53 (OP-FP), funct7 0x71 -> FMADD.D
pc=7fff824baab0  insn=002027f3   csrrs x15, 0x002 -> illegal instruction
```

CSR `0x002` does not exist in the current specification; it was `ustatus` long ago. No real
code reads it. Xorg is executing its own code **two bytes out of step**, having landed in the
middle of a different instruction.

The likely cause is a compressed-instruction decode or length bug. Xorg's userspace is dense
with 16-bit RVC encodings; the kernel boot path is not. That is why booting works perfectly
and only Xorg falls over.

## Next step

An automated desync detector was written and does detect the condition, but its control-flow
filter only understands 32-bit instructions and so reports compressed branches as desyncs.
Make it RVC-aware, then:

1. Run it over a full Xorg boot of the image `make_de_rootfs.sh` produces.
2. Fix the first genuine desync. That names the bad instruction directly instead of guessing.

## Reproducing

```bash
bash scripts/make_de_rootfs.sh /tmp/de.img
javac -cp build/classes/java/main -d /tmp/isa tools/de-diagnosis/Plain.java
java -cp build/classes/java/main:/tmp/isa Plain \
    src/test/resources/payloads/Image \
    'console=ttyS0,115200 earlycon root=/dev/vda1 rw' \
    /tmp/de.img 150
```

`Plain.java` deliberately installs no machine-level trap stub, so the guest's own behaviour
shows through. `Trap.java` is the earlier variant that parked faults in a self-loop; it was
abandoned because timer interrupts overwrite `mepc` before it can be read.

## Traps hit on the way here

Worth knowing so they are not rediscovered:

- Alpine's minirootfs tarball has **no top-level directory**. It unpacks straight into the
  target, so an intermediate directory left empty yields an image with no base system, no
  busybox and no `/sbin/init`.
- The image must contain `/dev`. Without it the kernel's devtmpfs mount fails with `ENOENT`
  and init never gets a console.
- `apk` exits non-zero whenever a package trigger fails, and under qemu-user the busybox and
  fontconfig triggers always fail because they call `unshare`. Under `set -e` that aborts the
  build silently, so the image is never assembled and it looks like a hang.
- `awk` is not reliably present on the host that runs the build script.