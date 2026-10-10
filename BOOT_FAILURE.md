# Boot failure: kernel hangs in `vsnprintf`

This is the current blocker. It is written for someone (human or AI) picking the bug up cold.

## What happens

Booting the unstripped Linux 6.12.112 `Image` under `LinuxBootRunner`:

```
java -cp build/classes/java/main com.emulator.riscv.LinuxBootRunner \
  --kernel=src/test/resources/payloads/Image \
  --rootfs=/path/to/rootfs.img \
  --ram=1024 --bootargs='console=ttyS0,115200 earlycon' \
  --halt-on='Linux version' --timeout-ms=150000
```

The guest does a great deal of things correctly and then stops:

* OpenSBI runs and the kernel is entered at the right address.
* The kernel parses the device tree, sets up traps, and relocates itself.
* `relocate_enable_mmu` is reached; the expected trampoline instruction page fault is taken, the
  kernel vectors to S-mode, and execution continues at `0xffffffff80...`.
* 0 machine-mode faults, 11 S-mode page faults, ~7.9 billion instructions at ~53 MIPS.
* **No console output at all.** `Linux version` is never reached.

## Where it is stuck

A PC histogram over 200M instructions shows ~100% of time in two 3-instruction loops, both inside
`number()` in `lib/vsprintf.c` (`number` spans `0xffffffff80fd908a..0xffffffff80fd94a4`):

| PC | share | instruction |
|---|---|---|
| `0xffffffff80fd92d4` | 17.07% | `bgeu a5,a4,...` |
| `0xffffffff80fd92dc` | 17.07% | `addi a1,a1,1` |
| `0xffffffff80fd92de` | 17.07% | `bne a5,a2,...` |
| `0xffffffff80fd91f0` | 15.72% | `bgeu a5,a4,...` |
| `0xffffffff80fd91f8` | 15.72% | `addi a5,a5,1` |
| `0xffffffff80fd91fa` | 15.72% | `bne a5,a2,...` |

These are the field-width padding loops. They should execute a handful of times; instead they run
~2 billion times because the width is enormous.

## The exact symptom

> **Correction.** An earlier revision of this file claimed the `printf_spec` arriving in `a3` was
> corrupt (`base=0`, `field_width=0xffffff`). **That was wrong** — it came from reading
> `field_width` as an unsigned 24-bit field and mis-placing `base`. The spec is in fact correct.
> Do not chase a corrupt spec.

`struct printf_spec` is passed **by value** to `number()` and is a single packed 64-bit word
(`__packed`, `sizeof == 8`):

```c
struct printf_spec {
	unsigned int type:8;          /* bits  0-7  */
	signed int   field_width:24;  /* bits  8-31 -- SIGNED */
	unsigned int flags:8;         /* bits 32-39 */
	unsigned int base:8;          /* bits 40-47 */
	signed int   precision:16;    /* bits 48-63 -- SIGNED */
} __packed;
```

On entry to `number()` for the format `"SBI specification v%lu.%lu detected\n"`:

```
a2 = 0x0000000000000000                              num
a3 = 0xffff0a00ffffff09                              struct printf_spec
```

Decoding `a3`, with the two signed fields interpreted as signed:

| field | bits | decoded | required for `%lu` |
|---|---|---|---|
| `type` | 0-7 | 9 | `FORMAT_TYPE_ULONG` |
| `field_width` | 8-31 | `0xffffff` = **-1** | -1 (no width) ✓ |
| `flags` | 32-39 | 0 | 0 ✓ |
| `base` | 40-47 | **10** | 10 ✓ |
| `precision` | 48-63 | `0xffff` = **-1** | -1 ✓ |

**Every field is exactly what a plain `%lu` should produce.** `0xffffff` in a *signed* 24-bit
field is -1, not 16777215; that single sign-extension mistake is what produced the earlier bogus
diagnosis.

`num = 0` is also legitimate: if the SBI BASE extension does not implement `get_spec_version`,
Linux falls back to legacy SBI v0.1 and prints major version 0.

## Hand-checkable expectation

For `number(0, spec{field_width=-1, precision=-1, base=10})` the kernel should compute:

* `i = 1` (the single digit `'0'`)
* `precision = max(i, precision) = 1`
* `field_width -= precision` → -2, stored in the 24-bit field as `0xfffffe`
* `while (--field_width >= 0)` sees -3 and does **not** run

The loop does run, for ~2 billion iterations. So the fault is in **executing `number()`'s own
instructions** — some instruction in the `0xffffffff80fd908a..0x80fd92e4` range computes a value
that differs from the above, and the first such instruction is the bug.

## The important negative result

**The format string parsing is correct.** At the moment of the failure:

* `fmt` = `0xffffffff81994970`, which maps to `"."` — exactly one character past the first `%lu` in
  the string. The parser has consumed `"SBI specification v%lu"` and stopped precisely on the `.`
  before the second `%lu`. That is right.

Every format string in `.rodata` is preceded by a 2-byte `01 36` tag. That is the RISC-V
`printk` level prefix, and `fmt` correctly points *past* it. It is not corruption.

## Ruled out

These were checked directly and are **correct**:

* `BEQ/BNE/BLT/BGE/BLTU/BGEU` — signed vs unsigned selection is right. (An apparent inversion here
  turned out to be a broken test harness: `0xfffffffeL` in Java is positive `4294967294`, since
  Java hex literals do not sign-extend. Verified all six with `c.reg(10, -2L)`.)
* `number`/`vsnprintf`/`special_hex_number` symbol boundaries in `System.map`; the hot loops really
  are inside `number`.
* The MMU translation of `fmt`, the stack, and the string bytes. Reading through
  `MMU.translate(va, ACCESS_READ, 1, false, false)` gives sane text; reading at a hand-computed
  physical address does not, which is a trap — do not trust hand-rolled VA→PA here.
* `ADDIW`, `ADDW`, `SUBW`, `SLLW`, `SRLW`, `SRAW`, `DIVW`, `REMW`, `DIVUW`, `MULW` — all correct.

## Fixed along the way

1. **`SRAIW`/`SRLIW` shifted the full 64-bit register** instead of the low 32 bits, so a negative
   32-bit operand with a dirty upper half shifted incorrectly. Fixed in `RiscVCpu.executeOpImm32`
   by casting to `int` before the shift.
   Test: `RiscVCpuTest.wordWidthShiftsIgnoreTheUpperHalf`.

2. **`checkShamt` under-checked the `SRAI` reserved bits.** The mask was `0xB8000000`, leaving bit
   26 unchecked; it must be `0xBC000000` (bit 31 plus bits 29:26). This only made the emulator
   *too lenient* — it accepted reserved encodings — so it is not this bug.
   Test: `RiscVCpuTest.arithmeticShiftImmediateRejectsBit26`.

3. **`fetch()` mishandled instructions that straddle a page boundary.** It translated only the
   first page and read 8 bytes from that physical address, so a 32-bit instruction at page offset
   `0xffe` took its second halfword from the *physically* adjacent page rather than the next
   *virtual* page, and never took the access fault the second page owes. Now each halfword is
   translated independently. Harmless inside the kernel's contiguous linear mapping, but it would
   break user space and kernel modules — which matters for the goal of booting arbitrary distros.
   Test: `RiscVCpuTest.instructionStraddlingAPageBoundaryIsFetchedCorrectly`.

Fixing (3) also exposed that `RiscVCpuTest.compressedEbreakTraps` asserted `mcause == 2`
(illegal instruction). `C.EBREAK` raises a **breakpoint** exception (`mcause == 3`), exactly like
the uncompressed `EBREAK` — which the adjacent `ebreakTraps` test already asserted correctly. The
test had been passing only because the old `fetch()` handed it the wrong instruction word; the
expectation is now corrected to 3.

## Where to look next

Ordered by suspicion:

1. **Compressed (RVC) instruction decode.** The kernel is dense with `c.*` and the failing loop is
   itself full of them (`c.slli`/`c.srli` zero-extension, `c.add`, `c.mv`). A subtle decode error
   here produces exactly this shape: the kernel runs, but a register holds a wrong value.
2. **FP↔integer moves** (`fmv.d.x`, `fmv.x.d`, `fld`, `fsd`, `fmv.w.x`). A bad FP store corrupting
   the register save area would garble the `va_list` without any obvious fault.
3. **CSR access** in the `printk`/`vsnprintf` path (`csrr`, `csrrs`, `csrrw`).
4. **Stack alignment / calling convention** for the by-value 8-byte struct and for `va_list`.

## Recommended: stop hunting these one at a time

Roughly ten decoder bugs have now been found by hand-tracing, and this one still has not surfaced
from inspection. That is the argument for checking in prebuilt conformance binaries and running
them as a batch: `rv64ui-p`, `rv64um-p`, `rv64uc-p` and `rv64si-p` from `riscv/riscv-tests` would
sweep the remaining instruction-level gaps in a single pass and give a definitive answer about which
encodings are wrong, instead of one CPU state at a time.

This is also what generalises. The stated goal is to boot **any** distro and kernel without
faults, not one 6.12.112 image. Per-instruction conformance is the foundation for that; the current
approach - infer a defect from one guest's behaviour - cannot scale to it.

## Reproducing the observation

`LinuxBootRunner` plus a throwaway harness is enough; no debugger is installed and there is no
`libexeclog.so`, so value-level QEMU tracing was not available.

Useful techniques that worked:

* Read guest memory through `engine.mmu().translate(...)`, never via a hand-computed PA.
* Dump a **global** PC histogram over a large step budget. Targeting a single remembered PC led to a
  false conclusion that the loop was straight-line code; the histogram showed the two real loops.
* Trace the emulator's own `pc` sequence around the loop entry and print registers per instruction.
  That is what identified `a2` never being written.
* Decode the kernel `Image` by mapping VA→file offset as `va - 0xffffffff80000000`, then
  `off = pa - 0x80200000`. Getting this wrong silently produces plausible-looking garbage.
