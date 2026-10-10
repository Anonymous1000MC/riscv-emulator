# Status

Honest accounting of what is finished, what is not, and what is verified.

The emulated machine is **Theon**. The mod keeps the id `riscv-emulator`; the guest-visible name lives
in `com.emulator.riscv.Theon` and is what the SBI base extension and the device tree report.

## Builds

`./gradlew clean build` succeeds and produces `build/libs/riscv-emulator-1.0.0.jar`.

`./gradlew test` **passes**: 107 tests, 0 failures, 0 errors.

## Finished and compiling

* RV64IMACU interpreter with the compressed extension and the privileged architecture.
* CSR file with the aliased supervisor view, WARL masks, live `cycle`/`instret`, PMP.
* Sv39/Sv48 MMU with a set-associative TLB, hardware A/D update, misaligned superpage handling and
  exact `sfence.vma` invalidation.
* Off-heap memory: flat `sun.misc.Unsafe` arena plus a chunked direct-buffer fallback.
* Bus with separate physical and virtual accessors, plus DMA helpers for the device models.
* Devices: CLINT, PLIC, NS16550A, RTC, SiFive test finisher.
* VirtIO-MMIO **version 2** transport with split virtqueues, and models for block, framebuffer,
  keyboard/mouse input and entropy.
* M-mode platform in Java: the SBI (both the legacy call convention and the v1.0/v2.0 extension
  IDs) and a flattened device tree generator that mirrors QEMU's `virt` board node for node.
* `RiscVEngine`: machine assembly, PMP and delegation setup, frequency throttling, lifecycle.
* Minecraft layer: block with facing/powered/booting/crashed state, block entity with a dedicated
  worker and machine parking across chunk unloads, throttled and compressed framebuffer protocol,
  config file.

## Not finished

**The client renderer and the terminal screen.** Minecraft 1.21.11 replaced the client render API
outright:

* `BlockEntityRenderer<T>` is now `BlockEntityRenderer<T, S extends BlockEntityRenderState>` with
  `createRenderState()` / `updateRenderState()` / `render(S, MatrixStack, OrderedRenderCommandQueue,
  CameraRenderState)`. There is no `VertexConsumerProvider` parameter any more.
* Textures are `GpuTexture` / `GpuTextureView` objects. There are no GL texture ids and the
  `GlStateManager._texSubImage2D` upload path is gone.
* `RenderLayer.of(...)` is package-private and the old `RenderLayer.getEntityCutoutNoCull(int)` is
  gone; custom geometry goes through `RenderCommandQueue.submitCustom(MatrixStack, RenderLayer,
  Custom)`.
* `Screen` no longer has `charTyped`, `mouseMoved`, `mouseClicked` or `mouseScrolled` overrides —
  only `keyPressed(KeyInput)` survives on the base class.

Drafts of these files are kept in `reference/client-pending/` but they do **not** compile against
1.21.11 and are not part of the build. The client source set has been removed from `build.gradle`
and the client entrypoint removed from `fabric.mod.json` so the project builds cleanly.

Consequences: the machine runs, and the framebuffer protocol carries correct pixels, but there is
no on-block display and no full-screen view yet. Input capture is therefore not wired up either.

**Framebuffer display for a guest.** Even once the renderer exists, the guest needs
`CONFIG_VIRTIO_FRAMEBUFFER` and a userspace that draws through the framebuffer. A desktop
environment would need software rendering (Mesa llvmpipe); a `virtio-gpu` path with real 3D was not
attempted.

## Test results

The CPU suite (`src/test/java/com/emulator/riscv/RiscVCpuTest.java`, 80 tests) was written to find
decoder bugs, and it did: it exposed **17 genuine emulator defects**, all fixed. The last four came
from differential tracing against QEMU rather than from the suite, and each has a regression test
that is mutation checked.

Emulator bugs found and fixed:

1. `LUI` and `AUIPC` did not advance the program counter.
2. J-type and B-type immediate decode placed bit 31 in the wrong position.
3. `mulhu` was missing one of its two sign-correction terms.
4. `fetch()` returned a 64-bit word, so `funct7` fields and the `SYSTEM` immediate read bits out of
   the *neighbouring* instruction.
5. Shift-immediate legality check rejected `SRAI` and then `SLLI` for the wrong bit mask.
6. `ECALL` and `EBREAK` advanced `pc` before trapping, so `mepc` recorded the wrong instruction.
7. `CSRRSI`/`CSRRWI`/`CSRRCI` read the operand from `x[rs1]` instead of the immediate field, and
   `CSRRS`/`CSRRC` wrote even when `rs1 == 0`.
8. `CsrFile.write` did not fold supervisor aliases, so writes to `stvec`, `sscratch`, `scause` and
   `stval` landed in a slot `read()` never consulted.
9. The direct `setPmpAddr`/`setPmpCfg` setters did not bump the generation counter, so the CPU's
   cached PMP summary could go stale — and `RiscVEngine` uses exactly those setters.
10. `AMOADD` and `AMOSWAP` were swapped in the decoder.
11. Unsigned loads (`LBU`, `LHU`, `LWU`) did not zero-extend, because `Memory.read8/read16` return
    sign-extended primitives.
12. `SRET` was rejected from S-mode; only U-mode should be barred.
13. `C.ADDI16SP` and `C.LUI` decoded the wrong immediate bits and truncated to a 6-bit sign
    extension; `C.EBREAK`/`C.JR`/`C.JALR` were not told apart.

### The last round of failures

The 19 remaining failures turned out to be four real emulator bugs plus a batch of test-authoring
errors. The emulator bugs were:

14. **S-type store immediate dropped its low half.** `executeStore` computed the displacement as
    `sext(inst[31:25], 12)`, never adding `inst[11:7]`. Every store whose low displacement was
    non-zero landed `imm[4:0]` bytes too low, which is what made `lhu`/`lh`/`lw` read back zeros
    and stopped `misalignedStoreTraps` from ever trapping.
15. **Quadrant 0 swapped its operands.** Every instruction in quadrant 0 is a CL format, so the
    destination is `inst[4:2]` and the source base is `inst[9:7]`. The CPU decoded them the other
    way round for the whole quadrant, so `c.lw`/`c.ld` loaded from the destination register and
    `c.sw`/`c.sd` stored to it, and `c.addi4spn` read a field that is not its destination. Registers
    are now decoded per instruction, since `C.ADDI4SPN` has no source base at all.
16. **`CSRRS`/`CSRRC` wrote even when `rs1` was `x0`.** The guard read `!immediate || rs1 != 0`, and
    since the register forms have `immediate == false` they always wrote. `csrrs rd, INSTRET, x0`
    is the normal way to read the retired-instruction counter, and writing to `INSTRET` is illegal,
    so the very first instruction of `countersAdvance` trapped. The spec is clear that `rs1 == x0`
    performs no write, and the guard is now simply `rs1 != 0` for all six forms.
17. **The test assembler encoded AMO `funct5` in `funct7`.** Bits 31:27 hold a five bit `funct5`,
    but the helpers passed it to `r()`, which places a seven bit `funct7` at bits 31:25. Every
    AMO decoded as `amoadd` from the wrong source, `lr.d` behaved as an `amoadd` from `x0`, and
    `sc.d` behaved as an `amoadd` (the trace showed memory `5 + 9 = 14` and `x9 = 5` instead of a
    status code). The AMO helpers now shift `funct5` left by two. Two more assembler bugs were
    found alongside it: `c.jr` set bit 12, which selects `c.mv` instead, and `c.swsp` put a
    hardcoded `2` in `inst[8:7]` — the `uimm[7:6]` field, since CSS format implies `sp` from the
    opcode — which silently added 128 to every displacement.

The test-authoring errors were all in the same families:

* **Immediates that do not fit.** `addi` has a twelve bit signed immediate and `c.li` a six bit one,
  but the tests wrote `0x1234`, `4321`, `99`, `88` and `42` into them. `addi x6, x0, 4321` encodes
  `225`; `c.li x6, 42` encodes `-22`. Both are silent truncations, which is why the failures showed
  plausible-but-wrong values rather than an encoding error. Values are now built with `lui`+`addi`
  or kept inside range.
* **Branch displacements counted from the wrong place,** and compressed branches named registers
  outside `x8..x15`, where the three bit prime field wraps onto a different register.
* **Programs that ran off the end.** Several tests ran a fixed number of steps past the last
  instruction and then executed zeros, which raises an illegal instruction and restarts the program
  at `mtvec` — erasing the registers they were about to assert on. Trap tests now use a `runUntil`
  helper that stops at the handler, and the loop tests park on a `jal x0, 0` self loop.
* **Assertions on intermediate values** after later instructions had already overwritten them, and
  on registers the program never wrote (which still held the reset stack pointer).

## New coverage: SBI and device tree

`SbiDtbTest` (7 tests) covers the two pieces of firmware glue that previously had none. It builds the
whole machine by hand — `Memory`, `MMU`, `Bus`, `RiscVCpu`, `SbiHandler` — rather than going through
`RiscVEngine`, because `RiscVEngine` insists on a kernel image on disk.

The device tree half **parses the FDT binary back into a node tree** instead of searching the bytes
for a string. A substring match cannot tell a `reg` of `0x10000000` from one of `0x100000000`, and it
passes happily when a property lands in the wrong node. The parser walks the token stream and decodes
cells with the widths the node inherits from its ancestors, which is what exposed the `reg` decoder:
properties store one 32-bit cell per element, not one 64-bit value per element.

It checks the header (`0xd00dfeed`, version 17, and that the three block offsets tile the blob without
overlap), the memory node against 2048 MiB at `0x80000000`, and the CLINT/PLIC/UART `reg` values
against the QEMU `virt` addresses written out literally (`0x02000000`, `0x0c000000`, `0x10000000`)
*and* against `MemoryMap`, so a change to either side has to be justified in both.

The SBI half runs real S-mode code that issues `ecall`, because the interesting part is the trap
path around the handler rather than the handler itself: `set timer`, `putchar` and `shutdown` all
have to reach M-mode, be consumed by the supervisor hook, and `mret` back into S-mode on the
following instruction. It also checks the inverse — with `medeleg` bit 9 set the ecall must be taken
in S-mode and the M-mode hook must never see it — and drives `BASE_GET_IMPL_NAME`, which writes
through `Bus.dmaWrite`, to prove the bus is genuinely wired rather than merely constructed.

Two things that surfaced while writing it and are worth remembering:

* **`lui` sign-extends.** A pointer at `0x80003000` has bit 31 set, so `lui` alone leaves the register
  holding a *negative* address and the SBI's write faults. Pointer materialisation needs the four
  instruction `lui`/`addi`/`slli 32`/`srli 32` form to force zero extension.
* **`a6` and `a7` are `x16` and `x17`.** Setting `x6`/`x7` instead compiles and runs, but every call
  silently becomes `set_scratch` because `a7` is zero and `a6` is zero. Nothing traps; the SBI just
  quietly answers the wrong question.

The tests were checked against deliberate regressions: moving the CLINT `reg`, halving the advertised
RAM, corrupting the FDT magic, and dropping the shutdown action each fail exactly the test that claims
to cover them.

## Headless boot runner

`LinuxBootRunner` boots a guest on `RiscVEngine` with the console on `System.out`, halts on a marker
string, and reports traps. It is a `main` class:

```
java -cp build/classes/java/main com.emulator.riscv.LinuxBootRunner \
     --kernel=Image --rootfs=rootfs.img [--ram=MB] [--halt-on='Linux version']
```

Two emulator bugs surfaced the moment it was run against real code:

15. **The PLIC window was 96 MiB and swallowed the console.** `mapDevices` mapped the PLIC from
    `0x0c000000` with a size of `0x6000000`, reaching `0x12000000`. `Memory.deviceAt` returns the
    *first* window containing an address, and the PLIC was registered first, so every UART access
    from `0x10000000` and every virtio-mmio access from `0x10001000` was answered by
    `PlicDevice.read` instead. Both returned zero. **No console output and no disk were possible at
    all** — a Linux boot could never have worked, and nothing in the previous test suite noticed
    because none of it touched a device. QEMU's `virt` gives the PLIC 64 MiB, which ends exactly at
    the UART base; the window is now derived as `UART_BASE - PLIC_BASE` so it cannot drift again.
16. **`Memory.map` accepted overlapping windows.** The above was silent because nothing checked.
    `map` already rejected overlap with RAM, so it now rejects overlap with any previously mapped
    window too, naming both. Verified by mapping a colliding window and confirming the rejection.

**Fault reporting needed a design decision.** The interpreter converts every trap internally and the
emulator has no logging framework and no error callbacks, so a fault can only be observed by
sampling `mcause`/`mtval`. Sampling alone reports the *wrong* address: with `mtvec` at zero the hart
traps to address zero and faults again, overwriting the original cause within the same slice. The
runner therefore writes a single `j .` into RAM and points `mtvec` at it, so a faulting guest
*parks* and its first fault stays readable. That is also the right behaviour on its own — a trap to
machine mode is unrecoverable for a Linux boot, so stopping with the state intact beats collecting
thousands of repeats.

### The real kernel: the EFI stub was the entry point

`src/test/resources/payloads/Image` is a genuine Linux 6.12 RV64 kernel built by
`scripts/build_kernel.sh`. Booting it found that the image is a **PE/COFF executable**: offset zero is
an `MZ` DOS header, the PE header is at `0x40`, and `AddressOfEntryPoint` (`0x124d120`) points at the
**EFI stub**, not the kernel. That stub wants an EFI system table where `a1` holds the device tree, so
it probed `a1`, compared eight bytes against `0x5453595320494249`, failed, and spun forever. Because
that value is not the DTB magic, the comparison could never succeed no matter what was placed there.

**Corrected against QEMU: the entry is the MZ stub at the image base, not `.text`.** A control boot of
the same image under `qemu-system-riscv64 -M virt` reaches `Linux version 6.12.112` and runs to
`VFS: Unable to mount root fs`. Its `-d in_asm` trace enters at `0x80200000` - the DOS stub, which is
`c.li s4,-13` followed by `j 0x802010d0` - and only then reaches `.text` at `0x80201000`. The RISC-V
image header agrees: `text_offset` at `0xb8` is zero. So `kernelEntryOffset` now returns `0`, because
that is where SBI firmware jumps, and the PE `AddressOfEntryPoint` is simply not consulted. Entering
`.text + 0x1000` made the kernel execute its own PE header.

`objcopy -O binary` should be dropped from `scripts/build_kernel.sh`. `arch/riscv/boot/Image` is
already a flat binary produced by objcopy, and stripping it again shifts `.text` from image+`0x1000`
to image+`0`, which breaks relocation. The stripped `Image.raw` does not boot under QEMU either; the
unstripped `Image` boots all the way to userspace. Load the kernel's own `Image` unmodified.

**Relocating the kernel to the DRAM base was tried and disproven.** Moving `KERNEL_LOAD_ADDRESS` to
`0x80000000`, on the theory that `head.S` links against physical zero, turned the relocation target
into `0xffffffff80001000` - a 32 bit address sign extended into 64 bits - which is *further* from a boot
than the `0x7fe01000` it replaced, and it broke two runner tests. The address was reverted to
`0x80200000`, which is what a `virt` kernel links for. So the `0x200000` in the old fault is not a
missing firmware reservation.

**Historical note, since superseded.** Stripping was originally read as "did not fix it, but proved
the diagnosis": with the PE image the kernel faulted at `0x7fe00000` and with `Image.raw` at
`0x7fe01000`, a shift of exactly the `0x1000` the header occupied. That reading was wrong. The QEMU
control run showed the stripped image produces no kernel output at all on real firmware, so the
strip does not merely fail to help - it is itself the fault, and the arithmetic below was chasing a
self-inflicted offset. `Image.raw` has been removed from `scripts/build_kernel.sh`. The observation
about `RAM_BASE - 0x200000` was that the
`0x1000` is a contributing term rather than the cause. Whatever `head.S` is adding to `ra` is
dominated by something worth about `RAM_BASE - 0x200000`, and that has not been identified. The
leading candidates remain the image's link address against `KERNEL_LOAD_ADDRESS = 0x80200000`, and
the kernel reading a handover structure it did not find. It still prints nothing: `head.S` derives a
relocation delta from a table in its own image, computes `ra = 0x7fe00000` - two megabytes below
`RAM_BASE` - and returns to it, faulting. That is a link-address mismatch, not a decoding problem:
candidates are the image's link address against `KERNEL_LOAD_ADDRESS = 0x80200000`, and the `0x1000` of
PE header ahead of `.text` shifting every symbol. Until the delta is right nothing can reach `printk`.

Three further defects were fixed along the way:

* **The compressed floating point forms stored integer registers.** `c.ldsp`/`c.sdsp` in quadrant 2
  were executing `store(..., x[rs2])`. In RV64 those encodings are exclusively floating point, so a
  test had been written against the bug.
* **`load()` returned zero for any unsigned 64-bit access.** `1L << (size * 8)` is `1L << 0` in Java
  because the shift count is modulo 64, so the "and with 2^64-1" mask collapsed to zero.
* **`LinuxBootRunner` reported the wrong program counter on a fault**, printing the trap handler
  rather than `mepc`, which sent the investigation at the stub instead of the faulting instruction.

With F and D implemented, `MISA_VALUE` is `(2 << 62) | 0xA112D` (RV64IMAFDCU) and the device tree
advertises `riscv,isa = "rv64gc"`. `mstatus.FS` is enabled at handoff because it lives in the M-mode
half and supervisor code cannot enable the FPU for itself.

### Four emulator bugs found by differential tracing against QEMU

Comparing my instruction trace with QEMU's on the same image named each of these; none of them was
visible from the failing address alone. Four regression tests were added (`98` tests total, all
passing), and each was mutation checked: reverting the fix makes the new test fail.

* **The whole supervisor CSR block had invented addresses.** The S-mode block was modelled as a
  contiguous `0x100..0x106` range, so `sie` was `0x101`, `stvec` `0x102`, `sscratch` `0x103`,
  `scause` `0x104`, `stval` `0x105` and `sip` `0x106` - and `0x140..0x144` were unclaimed. Correct
  values are `sie 0x104`, `stvec 0x105`, `scounteren 0x106`, `sscratch 0x140`, `sepc 0x141`,
  `scause 0x142`, `stval 0x143`, `sip 0x144`. Linux enters `secondary_start_sbi` by clearing `sie`
  then `sip` as its first two instructions; `csrw 0x144` hit an unimplemented CSR, raised
  illegal-instruction, and because `stvec` was still `0` the hart vectored to address 0 and stalled
  silently. The existing tests missed this because they used the `CsrFile` constants symbolically,
  so a wrong constant was self-consistent.
* **`C.SDSP` was decoded as a floating point store.** Quadrant 2 funct3 `111` is the integer
  `c.sdsp`; funct3 `101` is the FP `c.fsdsp`. `c.sdsp` was calling `requireFp` and writing `f[rs2]`.
  `head.S` runs `csrc sstatus, SR_FS` immediately to detect FP misuse, so FP is off and every
  `c.sdsp` trapped. The hart parked in `.Lsecondary_park` at `0x802010b4`, 228 397 instructions in.
* **`CSRWI`/`CSRRSI`/`CSRRCI` skipped the write when the operand was zero.** These encode their
  operand in `zimm[4:0]`, not in `rs1`, but the `rs1 != 0` read-only guard was applied to them too.
  Linux's `csrrwi a4, satp, 0` - which restores bare mode after a satp probe - therefore wrote
  nothing, left paging enabled, and trapped on the next instruction fetch. Only `CSRRS`/`CSRRC`,
  where `rs1 == x0` really does mean "read only", may keep the guard.
* **`satp` was not WARL.** `csr[SATP] = value` accepted any mode, so `set_satp_mode` could latch
  Sv57, which the MMU would then reinterpret as a three level Sv39 walk with a 57 bit root pointer.
  Unsupported modes are now rejected and the previous value kept; `rootMask` for Sv39 and Sv48 also
  now covers the same 44 bits instead of relying on a leftover `(SATP_MODE_SHIFT < 44 ? 0 : 0L)`
  no-op.

Progress along the trace: 20 000 instructions faulting at `0x7fe00000` -> 240 000+ instructions, with
`satp` reaching Sv48 and the kernel deep inside the SBI handoff.

### Device tree divergence, ruled out by control experiment

Differencing the PC sequence against QEMU (`-d exec` on both sides) put the first divergence at
`0x81627bfa`, where QEMU executes `beq a0, s2, 60` and branches because `a0 == s2`, while the emulator
decoded the same instruction identically and fell through. A register *value* difference, not a decode
bug.

Before hunting backwards through the callee at `0x81627a14`, the cheaper hypothesis was tested: that
the two device trees simply differ. QEMU's tree was dumped with
`qemu-system-riscv64 -machine virt,dumpdtb=x.dtb` and injected into the emulator at the same address
(`0x80000000`, the address the engine already passes in `a1`). It matters:

| Device tree | Instructions before parking |
|---|---|
| emulator's own | 228 397 |
| QEMU's, injected | 324 755 |

and the emulator now reaches `0x81627c36`, the exact PC it previously could not. So the divergence at
`0x81627bfa` was the device tree, not the emulator.

**This is not a fix, though, and it should not be copied.** QEMU's tree says
`mmu-type = "riscv,sv57"`, `riscv,isa-base = "rv64i"` and a long `riscv,isa-extensions` list, because
QEMU implements Sv57. This emulator implements Sv39 and Sv48 only, so its own tree correctly says
`riscv,sv48`. Injecting `sv57` makes the kernel size its address space for a paging mode the MMU
cannot walk; that it advances further is a change of code path, not a correction. Any long term
comparison against QEMU has to accept that the two MMUs genuinely differ here.

The emulator's tree is also thinner than QEMU's in ways that matter for fidelity - QEMU describes
`platform-bus`, syscon poweroff/reboot, a proper `/chosen`, and richer ISA strings. Closing that gap
is the honest way to reduce DTB-driven divergence.

### Sv39/Sv48 paging was completely broken - three bugs in one function

Differencing against QEMU kept pointing at the fetch immediately after `csrw satp`. Three separate
defects sat in `MMU.walk`, and none of them had any test coverage, because there was no paging test
at all:

* **The root PPN was masked from the wrong bits of `satp`.** `satp` holds PPN in bits 43:0 and the
  root address is that field shifted left 12, but the mask was `0x00000FFFFFF000` - bits 43:12 -
  and the result was used directly as a physical address. The low 12 bits of the PPN were silently
  discarded. For the kernel's `satp = 0x9000000000081605` the walker looked for its root table at
  physical `0x81000` instead of `0x81605000`.
* **A pointer PTE overwrote the virtual address instead of the base table.** The descent was
  `a = (pte >>> 10) & 0xFFF`, which truncates the child PPN to 12 bits and throws away the VA, so
  every level below the first pointer indexed from nonsense. `walk` now keeps a separate base.
* **A superpage leaf did not carry the virtual address into the PPN.** `compose()` ORs in only the
  12 bit page offset, which is right for a 4KiB leaf and wrong for anything larger, so every address
  inside a 2MiB mapping resolved to that superpage's first page. Linux's `setup_vm()` maps the
  kernel image with 2MiB superpages, so this was the normal case: it made the fetch after `csrw satp`
  land `0x6000` bytes early, execute whatever was there, and surface as a *load* fault at `0x81000`
  rather than as anything recognisable.

The first two meant no paging translation had ever succeeded in this emulator. The visible symptom
compounded it: the walk's internal PTE read at physical `0x81000` was unmapped, and that
`TrapException` escaped instead of being converted, so the guest saw `mcause = 5` (load access
fault) naming a *physical* address rather than a page fault naming the virtual one. That mismatch is
why the whole thing looked like an MMIO probe rather than paging.

Both satp writes now behave: `csrw satp` at `0x814065bc` installs Sv48, and `csrrwi a4, satp, 0` at
`0x814065c0` correctly restores bare mode, so the WARL check at `0x814065c8` passes instead of
parking. The guest now runs for 15 000 traced instructions past that point and reaches kernel code
using kernel virtual addresses.

`MmuTest` (6 tests) pins all of it, and each of the three fixes was mutation checked.

### Two firmware bugs, both of the same kind as the others

* **Every SBI call dispatched on `a0` instead of `a6`.** The v0.2 calling convention puts the
  extension in `a7` and the *function* in `a6`; `a0`-`a5` are arguments. `base()`, `time()` and
  `debugConsole()` all switched on `regs[10]`. Because the kernel zeroes `a0` before the ecall, that
  made **every** BASE call match `BASE_PROBE`, so the kernel read `-2` out of
  `GET_SPEC_VERSION`, concluded the firmware was useless, and stopped talking to it. Two existing
  tests encoded the mistake - they set the function id in `a0` while their comments said `a6` -
  which is the same failure mode as the CSR block: a test written against the constant cannot catch
  the constant being wrong. `sbiBaseDispatchesOnA6NotA0` now covers it and is mutation checked.
* **A guest-supplied pointer that was not addressable killed the hart.** `Bus.dmaWrite` fell through
  to per-byte `memory.write8`, which throws `STORE_ACCESS_FAULT` on an unmapped address, and that
  exception escaped the SBI handler. A guest asking the firmware to write somewhere impossible is
  the *guest's* error; real firmware returns an error code. `dmaWrite` now returns a boolean and
  `BASE_GET_IMPL_NAME` reports `SBI_ERR_INVALID_ADDRESS`.

### The kernel now runs relocated, and what still stops it

Four more bugs, all found the same way - by diffing against QEMU and then against `vmlinux`
symbols from a `--keep` rebuild:

* **`C.SUB`, `C.XOR`, `C.OR` and `C.AND` were sign extending their result from 32 bits.** These are
  the full 64-bit quadrant 1 operations; only `C.SUBW`/`C.ADDW` sign extend. Linux's
  `create_kernel_page_table` computes its physical base with `c.sub` as
  `phys_addr - virt_addr + va`, and truncating that turned
  `0x80200000 - 0xffffffff80000000` into `0x200000` instead of `0x0000000100200000`. The bad value
  was then handed to `create_pud_mapping` as a *physical* address, so the kernel built page tables
  pointing at `0xffffffff82171000` and died on the first dereference. `C.ADDW` was also being computed
  as `C.XOR`, because the code never distinguished the `100011` (64-bit) and `100111` (word) funct6.
  Note the quadrant 1 operand layout is the reverse of quadrant 0: `rd'/rs1'` is bits 9:7 and `rs2'`
  is bits 4:2. The emulator already had that right; only the sign extension was wrong.
* **`medeleg`'s write mask was `0xBEEF5`, which has bit 12 clear.** Bit 12 is instruction page fault.
  The correct implemented-bit mask is `0xBBFF` (bits 0-9, 11, 12, 13, 15). This is what
  `relocate_enable_mmu` lives on: it installs the trampoline page directory and *relies* on the next
  instruction fetch faulting, so it can vector to S-mode at the virtual address it stashed in
  `stvec` and come back up relocated. With bit 12 masked out that fetch trapped to M-mode, where
  `mtvec` pointed at the runner's stub, and the kernel never relocated at all.
* **`medeleg`/`mideleg` were left at zero.** They are now set to what OpenSBI programs on a `virt`
  machine (`0x0f4b509` and `0x1666`).

**Effect: the kernel now runs relocated.** It reaches `relocate_enable_mmu`, takes the deliberate
instruction page fault, vectors to S-mode, comes up on `swapper_pg_dir`, and executes at
`0xffffffff80...`. The runner reports **0 machine faults, 11 S-mode page faults (the trampoline, as
expected), 7.9 billion instructions retired at ~53 MIPS**, and `kernel_map` is correct in memory
(`virt_addr = 0xffffffff80000000`, `phys_addr = 0x80200000`, `satp_mode = 0x9000000000000000`).

### What still stops it

It spins forever in `lib/vsprintf.c`'s `number()` - specifically the padding loop
`while (--field_width >= 0)`. With a large negative `field_width` that runs about four billion
iterations counting without storing, which is exactly the 7.9 billion instructions observed.

The kernel is formatting real banners at that point - `"Machine model: %s"`, `"%s (DT)"`,
`"SBI specification v%lu.%lu detected"` - all present and intact in memory, so the strings are fine
and `%s` handling works. What is wrong is the argument: `number()` is entered with
`num = 0xffff0a00ffffff09` (garbage) and derives a field width of `0xfffffffe`, which is what
`get_size()` produces when it is seeded with `-1` and reads a digit: `-1 * 10 + 8 = -2`. So the
format specifier is being misparsed.

One concrete observation to chase: every one of these printk format strings has a `0x01` byte
immediately before it in memory, and the `fmt` pointer reaching `vsnprintf` points **at that prefix
byte** rather than at the first character. riscv encodes printk flags in a leading byte, so the
caller is expected to skip one byte before formatting. Whether that skip is happening and my harness
is reading the pre-skip pointer, or the skip genuinely did not execute, is the open question.

### Conformance testing: what was taken from Sedna

[Sedna](https://github.com/fnuecke/sedna) is the closest comparable project - a pure-Java RISC-V
emulator that boots Linux - and its most valuable idea by far is that it runs the **official
`riscv/riscv-tests` suite** with the compiled binaries checked in. That is directly relevant here:
every spec violation found today (six wrong CSR addresses, `C.SDSP` decoded as an FP store, `CSRWI`
skipping a zero immediate, three page-table walk bugs, the `a6`/`a0` mix-up) would have been caught
by conformance tests rather than by tracing a fault address. This emulator had no paging test at
all before today, which is why a page-table walk that could never succeed went unnoticed.

Adopting it is not free right now: `riscv-tests` has been restructured and its `env/` tree (which
provided `riscv_test.h`, the linker script and the `tohost` handshake) has been removed, leaving
only the instruction macros, and its successor `riscv-arch-test` generates assembly from coverpoint
YAML through a Python toolchain. Sedna's cheap path - check in prebuilt binaries from an older tree -
is the one worth following.

Also worth adopting later: generated decoder with a "checked-in file is stale" test, JMH benchmarks,
and machine state serialisation, which would make any boot failure reproducible from a snapshot.

### The remaining divergence

The kernel next faults at `pc = 0x81405c9a`, which QEMU executes as `ld a5, 0(s1)`, with
`s1 = 0xffffffff82171ff0` here. The kernel is running at *physical* `0x81405c9a` (paging is off -
`satp` is bare at this point) while dereferencing a *kernel virtual* pointer, so the emulator traps
where QEMU does not. `0xffffffff82171ff0` is the low 32 bits `0x82171ff0` sign extended, which
points at the 32-bit sign extension family: `addw`/`sllw`/`srliw` results, or `lwu` wrongly sign
extending. `load()` itself is correct (`lwu` routes to the unsigned path), so the producer is
elsewhere.

Both device trees end in the same place. The kernel writes `satp = 0x9000000000081605` (Sv48, root PPN
`0x81605`) at `0x814065bc` and the *fetch of the very next instruction* at `0x814065c0` fails, because
the root page table's VPN[2] entry is zero, so `stvec` (deliberately set to `.Lsecondary_park` just
before) is taken and the guest parks for good.

`head.S` parks there on purpose: it installs the park loop as `stvec`, then writes `satp`, relying on
the following instruction still fetching through the *old* translations, and only then issues
`sfence.vma`. The privileged spec allows stale translations to survive a `satp` write until that
fence. This emulator's MMU walks afresh on every access and has no TLB to keep stale entries in, so
there is nothing to preserve and the fetch translates through the not-yet-populated table. Disabling
the `mmu::flushAll` satp listener changes nothing here, for exactly that reason - the code was reverted
rather than left in place as a change with no demonstrated benefit.

### Not covered

**A Linux kernel still does not reach `printk`.** The kernel image builds and its entry point is
correct, but the relocation delta is wrong, so it faults in the first few thousand instructions. Every
"the boot works" claim here is therefore about the runner's plumbing, not about a working Linux. To make the runner testable anyway, `src/test/resources/riscvboot`
holds two real RV64 payloads built with `riscv64-linux-gnu-gcc` and linked flat at `0x80200000`:
`smoke.bin` prints the hart id, the device tree pointer and the virtio-mmio `MagicValue` and then
emits a prompt, and `fault.bin` deliberately reads an unmapped address. Six tests drive them.

The emulator was therefore never asked to: decompress a kernel, walk page tables under `satp`, parse
the device tree it had just been handed, run `initcall`s, mount a filesystem over virtio-blk, or
service a timer interrupt. Those remain unexercised.

Also still untested: the MMU, the device models, and the virtio transports. A page walk and a
virtqueue round trip would each be worth adding.

Two things to know before trusting the runner on a real kernel: `RiscVEngine.openDisk` creates a
sparse image of at least 1 GiB for a missing rootfs, and `MachineConfig.ramMb` is an `int`, so the
2048 MiB default is real but has not been exercised here — this machine had under 1 GiB free, so
the tests run at 128 MiB.

## Known gaps

* No `sfence.vma` fast path — invalidation scans the whole cache. Correct, but not fast.
* Multi-hart is not implemented; the hart count is hard-wired to one and SBI HSM calls for other
  harts report "not supported".
* LR/SC reservations are address based and single-harted, so an SC always succeeds after an LR.
* The MMU advertises Sv48 but does not emit `riscv,isa-svcount` in the device tree; the kernel
  chooses, and both modes are implemented.
* `FramebufferTexture` damage rectangles are 64×64 tiles coalesced into one bounding box, so a
  scattered update uploads more than strictly necessary.
* No block model or item model JSON, no lang file, no icon. The block will render as the default
  cube until assets are added.