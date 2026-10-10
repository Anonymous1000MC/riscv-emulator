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

`struct printf_spec` is passed **by value** to `number()` and is a single packed 64-bit word
(`__packed`, `sizeof == 8`):

```c
struct printf_spec {
	unsigned int type:8;
	signed int   field_width:24;
	unsigned int flags:8;
	unsigned int base:8;
	signed int   precision:16;
} __packed;
```

On entry to `number()` for the format `"SBI specification v%lu.%lu detected\n"`:

```
a2 = 0x0000000000000000                              num
a3 = 0xffff0a00ffffff09                              struct printf_spec
```

Decoding `a3` by the field order above:

| field | decoded | required for `%lu` |
|---|---|---|
| `type` | 9 | `FORMAT_TYPE_UL_LONG` |
| `field_width` | `0x00ffffff` (16777215) | `-1` (no width) |
| `flags` | 0 | 0 |
| `base` | **0** | **10** |
| `precision` | `-1` | `-1` |

`base = 0` and `field_width = 0xffffff` are wrong. `field_width` is the direct cause of the hang:
`number()` computes a pad count from it, the count is zero-extended to 64 bits, and the loop bound
becomes `buf + 0xffffff` — so the cursor walks toward a bogus `0xffffffff82e0...` target.

## The important negative result

**The format string parsing is correct.** At the moment of the failure:

* `fmt` = `0xffffffff81994970`, which maps to `"."` — exactly one character past the first `%lu` in
  the string. The parser has consumed `"SBI specification v%lu"` and stopped precisely on the `.`
  before the second `%lu`. That is right.

Every format string in `.rodata` is preceded by a 2-byte `01 36` tag. That is the RISC-V
`printk` level prefix, and `fmt` correctly points *past* it. It is not corruption.

So the parser walked the string correctly and still handed `number()` a corrupt spec. The
corrupted 64-bit word is **computed by kernel code**, not by the emulator — the emulator only has
to have executed `vsnprintf`'s instructions correctly. Which means some earlier instruction was
mis-executed, and that instruction is the bug.

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

`SRAIW`/`SRLIW` were shifting the full 64-bit register instead of the low 32 bits, so a negative
32-bit operand with a dirty upper half shifted incorrectly. Fixed in `RiscVCpu.executeOpImm32` by
casting to `int` before the shift. Regression test:
`RiscVCpuTest.wordWidthShiftsIgnoreTheUpperHalf`.

## Where to look next

Ordered by suspicion:

1. **Compressed (RVC) instruction decode.** The kernel is dense with `c.*` and the failing loop is
   itself full of them (`c.slli`/`c.srli` zero-extension, `c.add`, `c.mv`). A subtle decode error
   here produces exactly this shape: the kernel runs, but a register holds a wrong value.
2. **FP↔integer moves** (`fmv.d.x`, `fmv.x.d`, `fld`, `fsd`, `fmv.w.x`). A bad FP store corrupting
   the register save area would garble the `va_list` without any obvious fault.
3. **CSR access** in the `printk`/`vsnprintf` path (`csrr`, `csrrs`, `csrrw`).
4. **Stack alignment / calling convention** for the by-value 8-byte struct and for `va_list`.

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
