# Theon — RISC-V emulator for Minecraft 1.21.11 (Fabric)

A hardware-accelerated RV64IMACU emulator for Minecraft 1.21.11. The whole emulator core is plain
Java with no Minecraft types, so it can be driven headlessly; the mod layer wraps it in a block, a
block entity and a network protocol.

**The emulated machine is called Theon.** The Fabric mod keeps the id `riscv-emulator`, because
that is a Minecraft-facing identifier that worlds, resource packs and config directories already
depend on. What the guest sees — the SBI base extension's implementation name, and the device tree's
`model` and `compatible` — says Theon, and lives in one place,
[`Theon.java`](src/main/java/com/emulator/riscv/Theon.java).

**Read [STATUS.md](STATUS.md) before using this.** Parts of the client-facing rendering layer are
not finished and the test suite is not fully green. The exact boundaries are documented there
rather than hidden.

---

## What is implemented

| Layer | Class | State |
|---|---|---|
| Hart | `com.emulator.riscv.cpu.RiscVCpu` | RV64IMACU + privileged v1.11 (M/S/U, traps, PMP) |
| CSRs | `com.emulator.riscv.cpu.CsrFile` | aliased supervisor view, WARL masks, live counters |
| MMU | `com.emulator.riscv.mmu.MMU` | Sv39 + Sv48, set-associative TLB, exact `sfence.vma` |
| Memory | `com.emulator.riscv.bus.Memory` | off-heap arena, device table, framebuffer carve-out |
| Bus | `com.emulator.riscv.bus.Bus` | separate `*Phy` and `*Virt` accessors, DMA helpers |
| Devices | `com.emulator.riscv.devices.*` | CLINT, PLIC, NS16550A, RTC, SiFive test finisher |
| VirtIO | `VirtioMmioTransport` + models | virtio-mmio **v2**, split virtqueues: blk, framebuffer, input, rng |
| Firmware | `com.emulator.riscv.firmware.*` | SBI (legacy + v1.0/v2.0 ext IDs) and a DTB v17 generator |
| Engine | `com.emulator.riscv.RiscVEngine` | machine assembly, throttling, lifecycle |
| Mod | `com.emulator.block.*`, `com.emulator.network.*` | block, block entity, throttled framebuffer protocol |
| Renderer / screen | — | **not finished**, see STATUS.md |

### Memory map

Mirrors QEMU's `virt` board, because that is the platform RISC-V Linux is tested against.

```
0x0010_0000  SiFive test finisher
0x0101_0000  RTC
0x0200_0000  CLINT          (msip, mtimecmp, mtime)
0x0C00_0000  PLIC
0x1000_0000  NS16550A       IRQ 10
0x1000_1000  virtio-blk     IRQ 1
0x1000_2000  virtio-framebuffer  IRQ 2
0x1000_3000  virtio-input   IRQ 3
0x1000_4000  virtio-rng     IRQ 4
0x8000_0000  RAM, framebuffer carved from the top
```

### Memory layout

Guest RAM is a flat off-heap arena with no Java objects on the access path. Two implementations:

* `UnsafePhysicalMemory` — one contiguous native block via `sun.misc.Unsafe`. No 2 GiB ceiling, so
  the default 2 GiB and the 8 GiB maximum both fit in a single allocation.
* `ChunkedPhysicalMemory` — little-endian direct `ByteBuffer`s, used when `Unsafe` is unavailable or
  disabled with `unsafe_memory: false`.

### Execution model

`runBlock(budget)` retires instructions in one loop, polling for interrupts every 64 and folding the
retired count into `instret` so a guest polling the counter sees it move. `RiscVEngine.runForNanos`
turns a wall-clock slice into an instruction budget from `cpu_frequency_target_mhz`, so the emulated
machine runs at a chosen rate regardless of how fast the host is.

The interpreter is one flat `switch` over the 7-bit opcode with immediates extracted by masking.
`fetch()` masks to 32 bits: reading a double word is the cheapest way to fetch an aligned
instruction, but leaving the upper half in place lets `funct7` fields and the `SYSTEM` immediate pick
up bits from the neighbouring instruction.

---

## Loading a Linux guest

### 1. Kernel

Put an uncompressed RISC-V `Image` in the machine directory:

```
run/riscv_machines/Image
```

Anything built for `riscv64` with `CONFIG_TEXT_BASE` near `0x80200000` works, because that is where
the engine loads it. A distribution kernel built for `virt` is the closest match.

Download one, for example:

```sh
# Debian's generic riscv64 kernel, which is built for the virt board
wget https://deb.debian.org/debian/pool/main/l/linux/linux-image-6.12-riscv64_6.12.*-1_riscv64.deb
dpkg-deb -x linux-image-6.12-riscv64_*.deb extracted
cp extracted/boot/vmlinuz-* riscv_machines/Image
```

An `initrd` is optional; if present it is placed after the kernel and announced through
`/chosen/linux,initrd-start`. **A guest with no initrd and no root filesystem will panic**, so
either supply an initramfs or set `bootargs` to one that boots without mounting a root disk.

### 2. Root filesystem

`riscv_machines/rootfs.img` is a **raw disk image** consumed by virtio-blk. The emulator implements
the block device, not a filesystem, so there is no `ext4` driver inside the mod: put a filesystem
into the image yourself, or boot from an initrd.

```sh
# 4 GiB sparse image containing ext4
truncate -s 4G riscv_machines/rootfs.img
mkfs.ext4 riscv_machines/rootfs.img
sudo mkdir -p /mnt/rv
sudo mount riscv_machines/rootfs.img /mnt/rv
# debootstrap --arch=riscv64 --variant=minbase Debian bookworm /mnt/rv
sudo umount /mnt/rv
```

Then point `bootargs` at it:

```json
"bootargs": "console=ttyS0,115200 earlycon root=/dev/vda rw rootwait"
```

`/dev/vda` is the first virtio-blk slot. `earlycon` is optional but makes kernel output appear
before the 8250 driver is up, which is where you want to look when something goes wrong.

If the image is missing the mod creates a sparse one at first start and logs a warning rather than
refusing to boot, so the machine is still usable for console-only work.

### 3. Kernel and userspace requirements

The guest must have:

* `CONFIG_RISCV_SBI` — the engine implements the SBI in M-mode; there is no OpenSBI binary.
* `CONFIG_SERIAL_8250` / `CONFIG_SERIAL_8250_DW` for the console.
* `CONFIG_VIRTIO`, `CONFIG_VIRTIO_MMIO`, `CONFIG_VIRTIO_BLK`, `CONFIG_VIRTIO_FRAMEBUFFER`,
  `CONFIG_VIRTIO_INPUT`, `CONFIG_VIRTIO_RNG` for the devices.

---

## Configuration

`config/riscv_emulator.json`, written on first run with these defaults:

| Key | Default | Meaning |
|---|---|---|
| `default_ram_mb` | 2048 | RAM per computer |
| `max_ram_mb` | 8192 | operator ceiling for `default_ram_mb` |
| `screen_width` / `screen_height` | 1920 / 1080 | framebuffer size |
| `cpu_frequency_target_mhz` | 200 | instruction throttle |
| `framebuffer_fps_limit` | 30 | cap on framebuffer sends per second |
| `execute_block_size` | 10000 | instructions between interrupt polls |
| `tlb_sets` / `tlb_ways` | 512 / 4 | translation cache geometry |
| `enable_sv48` | true | advertise Sv48 alongside Sv39 |
| `unsafe_memory` | true | flat arena; `false` selects chunked buffers |
| `bootargs` | `console=ttyS0,115200 earlycon root=/dev/vda rw` | `/chosen/bootargs` |
| `compress_framebuffer_packets` | true | Deflate framebuffer payloads |
| `sync_only_when_open` | true | stream only to players with the screen open |

Missing keys are filled from defaults, so a truncated file is repaired rather than rejected.

---

## Framebuffer transport

A full 1080p frame is 8 MiB of raw pixels. Three things keep it off the wire:

1. **Damage tracking.** The framebuffer lives in guest RAM but its window is routed through
   `FramebufferDevice`, which buckets writes into 64×64 pixel tiles and coalesces them into one
   rectangle. A one-entry cache of the last touched tile keeps sequential writes division-free.
2. **Rate limiting.** At most `framebuffer_fps_limit` rectangles per second are sent.
3. **Compression.** The rectangle is Deflated at `BEST_SPEED`; a desktop frame compresses by roughly
   an order of magnitude because most of it is flat colour. If compression does not help, the raw
   bytes are sent instead.

Gating on `sync_only_when_open` means walking past a machine costs nothing.

---

## Building

```sh
./gradlew build -x test        # jar -> build/libs/riscv-emulator-1.0.0.jar
./gradlew test                 # headless CPU suite
```

Requires JDK 21. Fabric Loom 1.14 needs Gradle 8.11+; this was built with Gradle 9.8.1.

---

## Running the emulator headlessly

```java
RiscVEngine.MachineConfig config = new RiscVEngine.MachineConfig(
        512, 640, 480, 100, 30, 10000, 512, 4, true, true,
        "console=ttyS0,115200",
        Path.of("riscv_machines/Image"),
        Path.of("riscv_machines/rootfs.img"),
        null);

try (RiscVEngine engine = new RiscVEngine(config)) {
    while (!engine.halted()) {
        engine.runForNanos(8_000_000L);   // 8 ms slice
        Long damage = engine.framebuffer().consumeDamageRect();
        if (damage != null) {
            System.out.println("damaged rect = 0x" + Long.toHexString(damage));
        }
    }
    System.out.println(engine.uart().drainTransmitted());   // kernel console
}
```