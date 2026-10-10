package com.emulator.riscv.cpu;

import com.emulator.riscv.bus.Memory;
import com.emulator.riscv.mmu.MMU;

import static com.emulator.riscv.cpu.TrapException.BREAKPOINT;
import static com.emulator.riscv.cpu.TrapException.INSTRUCTION_ACCESS_FAULT;
import static com.emulator.riscv.cpu.TrapException.INSTRUCTION_ADDR_MISALIGNED;
import static com.emulator.riscv.cpu.TrapException.ILLEGAL_INSTRUCTION;
import static com.emulator.riscv.cpu.TrapException.LOAD_ADDR_MISALIGNED;
import static com.emulator.riscv.cpu.TrapException.MACHINE_ECALL;
import static com.emulator.riscv.cpu.TrapException.STORE_ADDR_MISALIGNED;
import static com.emulator.riscv.cpu.TrapException.SUPERVISOR_ECALL;
import static com.emulator.riscv.cpu.TrapException.USER_ECALL;

/**
 * An RV64IMACU hart with the privileged architecture required to host Linux.
 *
 * <h2>Execution strategy</h2>
 * The interpreter is written as one flat {@code switch} over the seven-bit primary opcode with
 * the immediate fields pulled out by masking rather than by a separate decode stage. There is no
 * micro-op cache and no decoded-instruction array: on HotSpot a single dense {@code switch}
 * compiles to a bounds check plus a jump table, which is cheaper than a cache lookup and it
 * keeps the register file in a register across the whole method.
 *
 * <p>Field access is arranged so the compiler can prove array indices are in range: every index
 * is masked with {@code 0x1F} before it reaches {@link #x}, and every memory offset is either a
 * masked index or has already been range-checked by the MMU. Writes to {@code x0} are skipped
 * with an explicit {@code if} rather than followed by a copy-back, which removes the read that a
 * naive store-and-restore would cost on roughly half of all executed instructions.
 *
 * <p>{@link #runBlock(int)} amortises exception-table setup and interrupt polling across thousands
 * of instructions so the host thread never has to touch the machine in the middle of straight-line
 * code.
 */
public final class RiscVCpu {

    /** General purpose registers; {@code x[0]} is hardwired to zero. */
    public final long[] x = new long[32];

    /**
     * Floating point registers {@code f0}-{@code f31}, held as raw IEEE 754 bit patterns.
     *
     * <p>Every slot is a full 64 bits so that the double precision form needs no conversion. A
     * single precision value is <em>NaN boxed</em>: its low 32 bits are the pattern and the upper
     * 32 are all ones, which is how the architecture distinguishes a real single from a NaN whose
     * payload happens to look like one.
     */
    public final long[] f = new long[32];

    public final CsrFile csr;
    public final MMU mmu;
    private final Memory memory;

    /** Implements the M-mode half of the platform (the SBI) in Java rather than as guest code. */
    public interface MachineSupervisor {
        /**
         * Services a trap that would otherwise be taken in M-mode.
         *
         * @param epc  address of the instruction that trapped
         * @param regs the hart's register file; results are written into {@code a0}
         * @return {@code true} when the trap was consumed and execution should resume
         */
        boolean handle(long cause, long epc, long[] regs, CsrFile csrs);
    }

    private MachineSupervisor supervisor;
    private long pc;
    private int privilege;

    /** Cached mstatus fields consulted on every data access. */
    private boolean sum;
    private boolean mxr;

    private boolean wfi;

    /** Cached PMP summary; recomputed lazily when a PMP CSR changes. */
    private long pmpGenerationSeen = -1;
    private int pmpMode;

    private static final int POLL_MASK = 63;

    private static final int PMP_ACCESS_NONE = 0;
    private static final int PMP_ACCESS_READ = 1;
    private static final int PMP_ACCESS_WRITE = 2;
    private static final int PMP_ACCESS_EXEC = 4;

    public RiscVCpu(Memory memory, MMU mmu, CsrFile csr) {
        this.memory = memory;
        this.mmu = mmu;
        this.csr = csr;
    }

    // ---- architectural state -----------------------------------------------------------------

    /** Installs the M-mode supervisor; pass {@code null} to disable interception. */
    public void setSupervisor(MachineSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    public long pc() {
        return pc;
    }

    public void setPc(long value) {
        this.pc = value;
    }

    /** Writes an architectural register directly; used to seed the boot context. */
    public void reg(int index, long value) {
        if (index != 0) {
            x[index & 0x1F] = value;
        }
    }

    public long reg(int index) {
        return x[index & 0x1F];
    }

    public int privilege() {
        return privilege;
    }

    public void setPrivilege(int value) {
        this.privilege = value;
        refreshStatusCache();
    }

    public boolean halted() {
        return false;
    }

    public boolean waitingForInterrupt() {
        return wfi;
    }

    private void refreshStatusCache() {
        long status = csr.mstatus();
        sum = (status & (1L << CsrFile.MSTATUS_SUM)) != 0;
        mxr = (status & (1L << CsrFile.MSTATUS_MXR)) != 0;
    }

    /** Resets the hart to the state the architecture requires out of reset. */
    public void reset(long entryPc, long stackPointer, int startPrivilege) {
        java.util.Arrays.fill(x, 0L);
        java.util.Arrays.fill(f, 0L);
        csr.clearPmp();
        long status = CsrFile.MSTATUS_SXL_RV64 | (2L << CsrFile.MSTATUS_UXL_SHIFT);
        status |= (long) startPrivilege << CsrFile.MSTATUS_MPP_SHIFT;
        if (startPrivilege == 1) {
            status |= CsrFile.MSTATUS_SPP_BIT;
        }
        csr.setMstatus(status);
        refreshStatusCache();
        mmu.reset();
        this.privilege = startPrivilege;
        this.pc = entryPc;
        x[2] = stackPointer;
        this.wfi = false;
        this.pmpGenerationSeen = -1;
    }

    // ---- main loop -----------------------------------------------------------------------------

    /**
     * Executes up to {@code budget} instructions, polling for interrupts once every 64 retired
     * instructions and converting any synchronous trap before returning.
     *
     * @return the number of instructions retired, including the one that trapped
     */
    public long runBlock(int budget) {
        if (budget <= 0) {
            return 0;
        }
        long executed = 0;
        long accounted = 0;
        try {
            while (executed < budget) {
                if ((executed & POLL_MASK) == 0) {
                    // Fold the retired count in here so a guest polling instret sees a value that
                    // moves instead of one frozen at the start of the block.
                    csr.addInstructions(executed - accounted);
                    accounted = executed;
                    if (takePendingInterrupt()) {
                        executed++;
                        continue;
                    }
                    if (wfi) {
                        wfi = false;
                    }
                }
                long inst = fetch();
                execute(inst);
                executed++;
            }
        } catch (TrapException trap) {
            csr.addInstructions(executed - accounted);
            accounted = executed;
            enterTrap(trap.cause, trap.value);
            executed++;
        }
        csr.addInstructions(executed - accounted);
        return executed;
    }

    /** Executes exactly one instruction. Used by the debugger and by tests. */
    public void step() {
        try {
            execute(fetch());
        } catch (TrapException trap) {
            enterTrap(trap.cause, trap.value);
        }
        csr.addInstructions(1);
    }

    // ---- fetch ---------------------------------------------------------------------------------

    /**
     * Fetches the instruction word at {@code pc}.
     *
     * <p>The result is masked to 32 bits. Reading a whole double word is the cheapest way to get
     * at an aligned instruction, but leaving the upper half in place would let any shift past bit
     * 31 - the {@code funct7} fields, and the immediate extraction in {@code SYSTEM} - pick up bits
     * from the neighbouring instruction.
     */
    private long fetch() {
        if ((pc & 1) != 0) {
            throw new TrapException(INSTRUCTION_ADDR_MISALIGNED, pc);
        }
        if (!pmpAllows(pc, PMP_ACCESS_EXEC, privilege)) {
            throw new TrapException(INSTRUCTION_ACCESS_FAULT, pc);
        }
        // A 32-bit instruction may straddle a page boundary, so the halves are translated
        // independently: pulling all eight bytes from the first page's physical address would
        // silently read the wrong bytes whenever the next virtual page is mapped elsewhere, and
        // would skip the access fault that the second page owes. When the pair does not straddle,
        // both halves come from the same translation.
        long lo = mmu.translate(pc, MMU.ACCESS_EXEC, privilege, sum, mxr);
        if ((pc & 0xFFF) <= 0xFFC) {
            return memory.read32(lo);
        }
        int low = memory.read16(lo) & 0xFFFF;
        if (!pmpAllows(pc + 2, PMP_ACCESS_EXEC, privilege)) {
            throw new TrapException(INSTRUCTION_ACCESS_FAULT, pc + 2);
        }
        long hi = mmu.translate(pc + 2, MMU.ACCESS_EXEC, privilege, sum, mxr);
        return ((long) (memory.read16(hi) & 0xFFFF) << 16) | low;
    }

    // ---- data access ----------------------------------------------------------------------------

    private long load(long vaddr, int size, boolean signed) {
        if ((vaddr & (size - 1)) != 0) {
            throw new TrapException(LOAD_ADDR_MISALIGNED, vaddr);
        }
        int accessPrivilege = dataPrivilege();
        if (!pmpAllows(vaddr, PMP_ACCESS_READ, accessPrivilege)) {
            throw new TrapException(TrapException.LOAD_ACCESS_FAULT, vaddr);
        }
        long pa = mmu.translate(vaddr, MMU.ACCESS_READ, accessPrivilege, sum, mxr);
        long raw = switch (size) {
            case 1 -> memory.read8(pa);
            case 2 -> memory.read16(pa);
            case 4 -> memory.read32(pa);
            default -> memory.read64(pa);
        };
        if (signed) {
            return sext(raw, size * 8);
        }
        // A 64 bit load has to be returned whole. Java takes the shift count modulo 64, so
        // "1L << (size * 8)" is 1L << 0 == 1 for a double, and the mask would be zero. Every
        // 64 bit user so far went through the signed path and so never noticed.
        if (size == 8) {
            return raw;
        }
        return size == 4 ? raw & 0xFFFF_FFFFL : raw & ((1L << (size * 8)) - 1);
    }

    private void store(long vaddr, int size, long value) {
        if ((vaddr & (size - 1)) != 0) {
            throw new TrapException(STORE_ADDR_MISALIGNED, vaddr);
        }
        int accessPrivilege = dataPrivilege();
        if (!pmpAllows(vaddr, PMP_ACCESS_WRITE, accessPrivilege)) {
            throw new TrapException(TrapException.STORE_ACCESS_FAULT, vaddr);
        }
        long pa = mmu.translate(vaddr, MMU.ACCESS_WRITE, accessPrivilege, sum, mxr);
        invalidateReservationOn(pa, size);
        switch (size) {
            case 1 -> memory.write8(pa, (byte) value);
            case 2 -> memory.write16(pa, (short) value);
            case 4 -> memory.write32(pa, (int) value);
            default -> memory.write64(pa, value);
        }
    }

    /**
     * {@code mstatus.MPRV} makes data accesses use the privilege encoded in {@code MPP} instead
     * of the current privilege, which is how Linux runs its debug monitor.
     */
    private int dataPrivilege() {
        long status = csr.mstatus();
        return (status & (1L << CsrFile.MSTATUS_MPRV)) != 0
                ? (int) ((status >>> CsrFile.MSTATUS_MPP_SHIFT) & 3)
                : privilege;
    }

    private static long sext(long value, int bits) {
        int shift = 64 - bits;
        return shift == 0 ? value : (value << shift) >> shift;
    }

    // ---- PMP ------------------------------------------------------------------------------------

    private boolean pmpAllows(long addr, int access, int accessPrivilege) {
        if (pmpGenerationSeen != csr.pmpGeneration()) {
            pmpGenerationSeen = csr.pmpGeneration();
            pmpMode = computePmpMode();
        }
        return switch (pmpMode) {
            case 0 -> accessPrivilege == 3;
            case 1 -> true;
            default -> pmpCheck(addr, access, accessPrivilege);
        };
    }

    private int computePmpMode() {
        boolean any = false;
        for (int i = 0; i < 16; i++) {
            if ((csr.pmpCfg(i) & 0xFF) != 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            // No configured entry: M-mode bypasses PMP entirely, S and U are denied everywhere.
            return 0;
        }
        // The firmware installs a single locked TOR entry spanning the whole address space, which
        // is the overwhelmingly common configuration; recognise it so data accesses skip the loop.
        if ((csr.pmpCfg(0) & 0xFF) == 0x8F && csr.pmpAddr(0) == -1L) {
            return 1;
        }
        return 2;
    }

    private boolean pmpCheck(long addr, int access, int accessPrivilege) {
        boolean anyEntry = false;
        for (int i = 0; i < 16; i++) {
            int cfg = csr.pmpCfg(i) & 0xFF;
            int mode = (cfg >>> 3) & 7;
            if (mode == 0) {
                continue;
            }
            anyEntry = true;
            long lo;
            long hi;
            switch (mode) {
                case 1 -> { // top of range
                    lo = i == 0 ? 0 : csr.pmpAddr(i - 1);
                    hi = csr.pmpAddr(i);
                }
                case 2 -> { // naturally aligned four-byte
                    lo = csr.pmpAddr(i) & ~7L;
                    hi = lo + 8;
                }
                case 3 -> { // naturally aligned power-of-two
                    long size = 8L << (csr.pmpAddr(i) & 0xFL);
                    lo = csr.pmpAddr(i) & ~(size - 1);
                    hi = lo + size;
                }
                default -> {
                    lo = i == 0 ? 0 : csr.pmpAddr(i - 1);
                    hi = csr.pmpAddr(i);
                }
            }
            if (addr < lo || addr >= hi) {
                continue;
            }
            boolean readable = (cfg & 1) != 0;
            boolean writable = (cfg & 2) != 0;
            boolean executable = (cfg & 4) != 0;
            return switch (access) {
                case PMP_ACCESS_EXEC -> executable;
                case PMP_ACCESS_WRITE -> writable;
                case PMP_ACCESS_READ -> readable;
                default -> true;
            };
        }
        // Entries are implemented but none matched: only M-mode is exempt.
        return !anyEntry && accessPrivilege == 3;
    }

    // ---- trap entry and exit ----------------------------------------------------------------------

    /** Takes the most significant enabled-and-pending interrupt, if any. */
    private boolean takePendingInterrupt() {
        long active = csr.pendingAndEnabled();
        if (active == 0) {
            return false;
        }
        long delegation = csr.mideleg();
        int target = privilege == 3 ? 3 : 1;
        for (int irq = 0; irq < 16; irq++) {
            if ((active & (1L << irq)) == 0) {
                continue;
            }
            if (target == 1 && ((delegation >>> irq) & 1) == 0) {
                // Not delegated to S-mode, so it must be taken in M-mode.
                target = 3;
            }
            enterTrap(TrapException.INTERRUPT_BIT | irq, 0L);
            return true;
        }
        return false;
    }

    /** Enters the trap handler for {@code cause} with faulting value {@code value}. */
    public void enterTrap(long cause, long value) {
        boolean isInterrupt = (cause & TrapException.INTERRUPT_BIT) != 0;
        long code = isInterrupt ? cause & ~TrapException.INTERRUPT_BIT : cause;

        long deleg = isInterrupt ? csr.mideleg() : csr.medeleg();
        boolean delegated = ((deleg >>> code) & 1) != 0;
        int target = (privilege == 3 || !delegated) ? 3 : 1;

        long status = csr.mstatus();
        // SIE <- SPIE, SPIE <- MIE, MIE <- 0
        status = (status & ~(1L << CsrFile.MSTATUS_SIE))
                | ((status >>> CsrFile.MSTATUS_SPIE) & 1L) << CsrFile.MSTATUS_SIE;
        status = (status & ~(1L << CsrFile.MSTATUS_SPIE))
                | ((status >>> CsrFile.MSTATUS_MIE) & 1L) << CsrFile.MSTATUS_SPIE;
        status &= ~(1L << CsrFile.MSTATUS_MIE);
        // MPP <- previous privilege, SPP <- (previous privilege < M)
        status = (status & ~(3L << CsrFile.MSTATUS_MPP_SHIFT))
                | (long) privilege << CsrFile.MSTATUS_MPP_SHIFT;
        status = privilege < 3 ? status | CsrFile.MSTATUS_SPP_BIT
                : status & ~CsrFile.MSTATUS_SPP_BIT;
        csr.setMstatus(status);

        long epc = isInterrupt ? pc : pc;
        if (target == 3) {
            csr.setMepc(epc);
            csr.setMcause(cause);
            csr.setMtval(isInterrupt ? 0L : value);
            long tvec = csr.mtvec();
            long base = tvec & ~3L;
            long mode = tvec & 3L;
            long target2 = (isInterrupt && mode == 1L) ? base + (code << 2) : base;
            this.pc = target2;
            this.privilege = 3;
            if (supervisor != null && isEcallCause(code)) {
                if (supervisor.handle(cause, epc, x, csr)) {
                    // The supervisor advanced mepc past the ecall and produced a result, so this
                    // trap never reaches an mtvec at all.
                    executeMret();
                    return;
                }
            }
        } else {
            csr.setSepc(epc);
            csr.setScause(cause);
            csr.setStval(isInterrupt ? 0L : value);
            long tvec = csr.stvec();
            long base = tvec & ~3L;
            long mode = tvec & 3L;
            long target2 = (isInterrupt && mode == 1L) ? base + (code << 2) : base;
            this.pc = target2;
            this.privilege = 1;
        }
        refreshStatusCache();
    }

    /** True for the three environment-call exception codes. */
    private static boolean isEcallCause(long code) {
        return code == USER_ECALL || code == SUPERVISOR_ECALL || code == MACHINE_ECALL;
    }

    private void executeMret() {
        long status = csr.mstatus();
        int target = (int) ((status >>> CsrFile.MSTATUS_MPP_SHIFT) & 3);
        status = (status & ~(1L << CsrFile.MSTATUS_MIE))
                | ((status >>> CsrFile.MSTATUS_MPIE) & 1L) << CsrFile.MSTATUS_MIE;
        status |= 1L << CsrFile.MSTATUS_MPIE;
        status &= ~(3L << CsrFile.MSTATUS_MPP_SHIFT);
        if ((status & (1L << CsrFile.MSTATUS_MPRV)) != 0) {
            status &= ~(1L << CsrFile.MSTATUS_MPRV);
            status |= (1L << CsrFile.MSTATUS_SUM) | (1L << CsrFile.MSTATUS_MXR);
        }
        csr.setMstatus(status);
        this.pc = csr.mepc();
        this.privilege = target;
        refreshStatusCache();
    }

    private void executeSret() {
        long status = csr.mstatus();
        int target = (status & CsrFile.MSTATUS_SPP_BIT) != 0 ? 1 : 0;
        status = (status & ~(1L << CsrFile.MSTATUS_SIE))
                | ((status >>> CsrFile.MSTATUS_SPIE) & 1L) << CsrFile.MSTATUS_SIE;
        status |= 1L << CsrFile.MSTATUS_SPIE;
        status &= ~CsrFile.MSTATUS_SPP_BIT;
        if (target != 3) {
            status &= ~(1L << CsrFile.MSTATUS_MPRV);
        }
        csr.setMstatus(status);
        this.pc = csr.sepc();
        this.privilege = target;
        refreshStatusCache();
    }

    // ---- floating point -----------------------------------------------------------------------
    //
    // Arithmetic delegates to java.lang, which is itself IEEE 754 compliant for the
    // round-to-nearest-even mode. The other four rounding modes are obtained by nudging the
    // correctly rounded result by one representable value, which is exact whenever the true result
    // lies strictly between two representable numbers - that is, whenever the operation is inexact,
    // which is the only case where the mode matters.
    //
    // Exception flags are derived structurally rather than by comparison: the operand classes
    // already say which of invalid, divide-by-zero and overflow apply, and inexactness is decided
    // by a round-trip identity (a / b == a, r * b == a, r * r == a), which holds exactly when no
    // bits were lost.

    public static final int FFLAG_NX = 0x01;
    public static final int FFLAG_UF = 0x02;
    public static final int FFLAG_OF = 0x04;
    public static final int FFLAG_DZ = 0x08;
    public static final int FFLAG_NV = 0x10;

    /** Upper half of a NaN boxed single precision value. */
    private static final long NAN_BOX = 0xFFFF_FFFF_0000_0000L;

    private static float single(long bits) {
        return Float.intBitsToFloat((int) bits);
    }

    private static long boxSingle(float value) {
        return NAN_BOX | (Float.floatToRawIntBits(value) & 0xFFFF_FFFFL);
    }

    private static double dbl(long bits) {
        return Double.longBitsToDouble(bits);
    }

    /** {@code mstatus.FS}: 0 off, 1 initial, 2 clean, 3 dirty. */
    public int fpState() {
        return (int) ((csr.mstatus() >>> CsrFile.MSTATUS_FS_SHIFT) & 3);
    }

    /**
     * Refuses a floating point instruction while the FP unit is off, which the architecture requires
     * so that software can prove no floating point state exists.
     */
    private void requireFp(long inst) {
        if (fpState() == 0) {
            throw illegal(inst);
        }
    }

    /**
     * Records that an f register was written, moving FS to Dirty and therefore mstatus.SD with it.
     *
     * <p>Loads and stores and the compare and classify forms touch no register state and leave FS
     * alone, which is why this is called from the arithmetic paths rather than from the decoder.
     */
    private void markFpDirty() {
        long status = csr.mstatus();
        int fs = (int) ((status >>> CsrFile.MSTATUS_FS_SHIFT) & 3);
        if (fs == 3) {
            return;
        }
        csr.setMstatus((status & ~(3L << CsrFile.MSTATUS_FS_SHIFT))
                | (3L << CsrFile.MSTATUS_FS_SHIFT));
    }

    private static boolean isNaN(double v) {
        return Double.isNaN(v);
    }

    private static boolean isNaN(float v) {
        return Float.isNaN(v);
    }

    /** The canonical quiet NaN for each width, with the sign bit set as the specification wants. */
    private static long quietNaN64() {
        return 0xFFF8_0000_0000_0000L | 1L;
    }

    private static long quietNaN32() {
        return NAN_BOX | 0x7FC0_0000L;
    }

    /**
     * Applies the dynamic rounding mode to an already correctly rounded result.
     *
     * @param inexact whether the true result was not representable
     */
    private static double roundMode(double r, boolean inexact, int rm) {
        if (!inexact) {
            return r;
        }
        return switch (rm) {
            case 1 -> r < 0 ? Math.nextUp(r) : Math.nextDown(r);        // RTZ, toward zero
            case 2 -> r > 0 ? Math.nextDown(r) : r;                      // RDN, toward -inf
            case 3 -> r < 0 ? Math.nextUp(r) : r;                        // RUP, toward +inf
            default -> r;                                               // RNE, and RMM as RNE
        };
    }

    private static float roundMode(float r, boolean inexact, int rm) {
        if (!inexact) {
            return r;
        }
        return switch (rm) {
            case 1 -> r < 0 ? Math.nextUp(r) : Math.nextDown(r);
            case 2 -> r > 0 ? Math.nextDown(r) : r;
            case 3 -> r < 0 ? Math.nextUp(r) : r;
            default -> r;
        };
    }

    /** Binary operations on doubles, with the flags the specification requires. */
    private long fpDouble(int op, double a, double b) {
        int rm = csr.frm();
        if (isNaN(a) || isNaN(b)) {
            csr.raiseFflags(FFLAG_NV);
            return quietNaN64();
        }
        double r;
        boolean inexact;
        switch (op) {
            case 0 -> { // FADD.D
                if (Double.isInfinite(a) && Double.isInfinite(b)
                        && Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN64();
                }
                r = a + b;
                inexact = (r - a) != b && !(Double.isInfinite(r) && !Double.isInfinite(a)
                        && !Double.isInfinite(b));
                if (Double.isInfinite(r) && !Double.isInfinite(a) && !Double.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            case 1 -> { // FSUB.D
                if (Double.isInfinite(a) && Double.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN64();
                }
                r = a - b;
                inexact = (r + b) != a && !(Double.isInfinite(r) && !Double.isInfinite(a)
                        && !Double.isInfinite(b));
                if (Double.isInfinite(r) && !Double.isInfinite(a) && !Double.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            case 2 -> { // FMUL.D
                if ((Double.isInfinite(a) && b == 0.0) || (Double.isInfinite(b) && a == 0.0)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN64();
                }
                r = a * b;
                inexact = a != 0 && b != 0 && r / a != b;
                if (Double.isInfinite(r) && !Double.isInfinite(a) && !Double.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact && Math.abs(r) < Double.MIN_NORMAL && r != 0) {
                    csr.raiseFflags(FFLAG_UF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            case 3 -> { // FDIV.D
                if (b == 0.0) {
                    csr.raiseFflags(Double.isInfinite(a) ? FFLAG_NV : FFLAG_DZ);
                    // A finite non-zero over zero is a signed infinity; only infinity over zero
                    // is the invalid operation that yields a NaN.
                    return Double.isInfinite(a) ? quietNaN64()
                            : Double.doubleToRawLongBits(signOf(a) * Double.POSITIVE_INFINITY);
                }
                if (Double.isInfinite(a) && Double.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN64();
                }
                r = a / b;
                inexact = Double.isFinite(r) && r * b != a;
                if (Double.isInfinite(r)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact && Math.abs(r) < Double.MIN_NORMAL && r != 0) {
                    csr.raiseFflags(FFLAG_UF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            default -> { // FSQRT.D
                if (a < 0.0) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN64();
                }
                if (Double.isNaN(a)) {
                    return quietNaN64();
                }
                r = Math.sqrt(a);
                inexact = r * r != a;
                if (inexact && Math.abs(r) < Double.MIN_NORMAL && r != 0) {
                    csr.raiseFflags(FFLAG_UF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
        }
        return Double.doubleToRawLongBits(roundMode(r, inexact, rm));
    }

    private static double signOf(double v) {
        return Double.doubleToRawLongBits(v) < 0 ? -1.0 : 1.0;
    }

    /** Binary operations on singles, with the NaN boxing rules applied on the way out. */
    private long fpSingle(int op, float a, float b) {
        int rm = csr.frm();
        if (isNaN(a) || isNaN(b)) {
            csr.raiseFflags(FFLAG_NV);
            return quietNaN32();
        }
        float r;
        boolean inexact;
        switch (op) {
            case 0 -> { // FADD.S
                if (Float.isInfinite(a) && Float.isInfinite(b)
                        && Float.floatToRawIntBits(a) != Float.floatToRawIntBits(b)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN32();
                }
                r = a + b;
                inexact = (r - a) != b && !(Float.isInfinite(r) && !Float.isInfinite(a)
                        && !Float.isInfinite(b));
                if (Float.isInfinite(r) && !Float.isInfinite(a) && !Float.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            case 1 -> { // FSUB.S
                if (Float.isInfinite(a) && Float.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN32();
                }
                r = a - b;
                inexact = (r + b) != a && !(Float.isInfinite(r) && !Float.isInfinite(a)
                        && !Float.isInfinite(b));
                if (Float.isInfinite(r) && !Float.isInfinite(a) && !Float.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            case 2 -> { // FMUL.S
                if ((Float.isInfinite(a) && b == 0f) || (Float.isInfinite(b) && a == 0f)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN32();
                }
                r = a * b;
                inexact = a != 0 && b != 0 && r / a != b;
                if (Float.isInfinite(r) && !Float.isInfinite(a) && !Float.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact && Math.abs(r) < Float.MIN_NORMAL && r != 0) {
                    csr.raiseFflags(FFLAG_UF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            case 3 -> { // FDIV.S
                if (b == 0f) {
                    csr.raiseFflags(Float.isInfinite(a) ? FFLAG_NV : FFLAG_DZ);
                    // A finite non-zero over zero is a signed infinity, as IEEE 754 requires.
                    return Float.isInfinite(a) ? quietNaN32()
                            : boxSingle(Float.floatToRawIntBits(a) < 0
                                    ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY);
                }
                if (Float.isInfinite(a) && Float.isInfinite(b)) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN32();
                }
                r = a / b;
                inexact = Float.isFinite(r) && r * b != a;
                if (Float.isInfinite(r)) {
                    csr.raiseFflags(FFLAG_OF | FFLAG_NX);
                } else if (inexact && Math.abs(r) < Float.MIN_NORMAL && r != 0) {
                    csr.raiseFflags(FFLAG_UF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
            default -> { // FSQRT.S
                if (a < 0f) {
                    csr.raiseFflags(FFLAG_NV);
                    return quietNaN32();
                }
                r = (float) Math.sqrt(a);
                inexact = r * r != a;
                if (inexact && Math.abs(r) < Float.MIN_NORMAL && r != 0) {
                    csr.raiseFflags(FFLAG_UF | FFLAG_NX);
                } else if (inexact) {
                    csr.raiseFflags(FFLAG_NX);
                }
            }
        }
        return boxSingle(roundMode(r, inexact, rm));
    }

    /** The IEEE 754 class encoding {@code fclass} reports. */
    private static int classify(double v) {
        long bits = Double.doubleToRawLongBits(v);
        int sign = (bits < 0) ? 1 : 0;
        boolean subnormal = (bits & 0x7FF0_0000_0000_0000L) == 0
                && (bits & 0x000F_FFFF_FFFF_FFFFL) != 0;
        if (Double.isNaN(v)) {
            return 1 << 8;                                  // 0x100, quiet NaN
        }
        if (Double.isInfinite(v)) {
            return 1 << (sign == 0 ? 7 : 0);                // +inf = 0x080, -inf = 0x001
        }
        if (v == 0.0) {
            return 1 << (sign == 0 ? 4 : 3);                // +0 = 0x010, -0 = 0x008
        }
        if (subnormal) {
            return 1 << (sign == 0 ? 2 : 1);                // +sub = 0x004, -sub = 0x002
        }
        return 1 << (sign == 0 ? 6 : 5);                    // +norm = 0x040, -norm = 0x020
    }

    private static int classifySingle(long boxed) {
        return classify(Float.intBitsToFloat((int) boxed));
    }

    // ---- floating point decode ----------------------------------------------------------------

    /**
     * OP-FP, {@code opcode = 0x53}.
     *
     * <p>The encoding is a seven bit {@code funct7} plus a format bit at inst[25]: clear is
     * single precision, set is double. The fields are not contiguous, so everything is pulled out
     * explicitly rather than by shifting a mask.
     */
    private void executeFp(long inst) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        int funct3 = (int) (inst >>> 12) & 7;
        // inst[25] is the format bit, single when clear and double when set. Masking it off
        // leaves a funct7 that is the same number for both widths, so one case serves each pair.
        boolean dbl = (inst & (1L << 25)) != 0;
        int funct7 = (int) (inst >>> 25) & 0x7E;
        requireFp(inst);

        if (rd != 0) {
            markFpDirty();
        }
        switch (funct7) {
            case 0x00, 0x04, 0x08, 0x0C, 0x2C -> { // FADD/FSUB/FMUL/FDIV/FSQRT
                int op = switch (funct7) {
                    case 0x00 -> 0; case 0x04 -> 1; case 0x08 -> 2;
                    case 0x0C -> 3; default -> 4;
                };
                if (rd != 0) {
                    if (funct7 == 0x2C) {
                        // FSQRT takes one operand and requires rs2 == 0.
                        if (rs2 != 0) {
                            throw illegal(inst);
                        }
                        f[rd] = dbl ? fpDouble(op, dbl(f[rs1]), 0)
                                   : fpSingle(op, single(f[rs1]), 0);
                    } else {
                        f[rd] = dbl ? fpDouble(op, dbl(f[rs1]), dbl(f[rs2]))
                                   : fpSingle(op, single(f[rs1]), single(f[rs2]));
                    }
                }
            }
            case 0x10 -> { // FSGNJ.S/FSGNJN.S/FSGNJX.S and the double forms
                long sign = (f[rs2] & 0x8000_0000_0000_0000L);
                if (rd != 0) {
                    f[rd] = switch (funct3) {
                        case 0 -> (f[rs1] & ~0x8000_0000_0000_0000L) | sign;
                        case 1 -> (f[rs1] & ~0x8000_0000_0000_0000L) | ~sign;
                        case 2 -> f[rs1] ^ sign;
                        default -> throw illegal(inst);
                    };
                }
            }
            case 0x14 -> { // FMIN.S / FMAX.S and the double forms
                if (funct3 > 1) {
                    throw illegal(inst);
                }
                if (rd != 0) {
                    if (isNaN(dbl ? dbl(f[rs1]) : single(f[rs1]))) {
                        f[rd] = dbl ? quietNaN64() : quietNaN32();
                    } else if (isNaN(dbl ? dbl(f[rs2]) : single(f[rs2]))) {
                        f[rd] = dbl ? quietNaN64() : quietNaN32();
                    } else if (dbl) {
                        double a = dbl(f[rs1]);
                        double b = dbl(f[rs2]);
                        boolean takeFirst = funct3 == 1
                                ? a < b || a == b && signBit(a)
                                : a > b || a == b && !signBit(a);
                        f[rd] = Double.doubleToRawLongBits(takeFirst ? a : b);
                    } else {
                        float a = single(f[rs1]);
                        float b = single(f[rs2]);
                        boolean takeFirst = funct3 == 1
                                ? a < b || a == b && Float.floatToRawIntBits(a) < 0
                                : a > b || a == b && Float.floatToRawIntBits(a) >= 0;
                        f[rd] = boxSingle(takeFirst ? a : b);
                    }
                }
            }
            case 0x54 -> { // FEQ / FLT / FLE
                long result;
                if (dbl) {
                    double a = dbl(f[rs1]);
                    double b = dbl(f[rs2]);
                    result = switch (funct3) {
                        case 2 -> a == b ? 1 : 0;
                        case 1 -> a < b ? 1 : 0;
                        case 0 -> a <= b ? 1 : 0;
                        default -> throw illegal(inst);
                    };
                    if (isNaN(a) || isNaN(b)) {
                        csr.raiseFflags(FFLAG_NV);
                    }
                } else {
                    float a = single(f[rs1]);
                    float b = single(f[rs2]);
                    result = switch (funct3) {
                        case 2 -> a == b ? 1 : 0;
                        case 1 -> a < b ? 1 : 0;
                        case 0 -> a <= b ? 1 : 0;
                        default -> throw illegal(inst);
                    };
                    if (isNaN(a) || isNaN(b)) {
                        csr.raiseFflags(FFLAG_NV);
                    }
                }
                if (rd != 0) {
                    x[rd] = result;
                }
            }
            case 0x60 -> { // FCVT.W[U] / FCVT.L[U] from a floating point source
                int source = rs2 & 3;
                if ((rs2 & ~3) != 0) {
                    throw illegal(inst);
                }
                if (rd != 0) {
                    x[rd] = convertToInt(dbl ? dbl(f[rs1]) : (double) single(f[rs1]), source);
                }
            }
            case 0x68 -> { // FCVT.{D,S}.{W,WU,L,LU}: integer to floating point
                if ((rs2 & ~3) != 0) {
                    throw illegal(inst);
                }
                if (rd != 0) {
                    f[rd] = convertFromInt(x[rs1], rs2, dbl, inst);
                }
            }
            case 0x70 -> { // FMV.X.W / FMV.X.D and FCLASS
                if (rs2 != 0) {
                    throw illegal(inst);
                }
                if (rd != 0) {
                    x[rd] = switch (funct3) {
                        case 0 -> sext(f[rs1], dbl ? 64 : 32);
                        case 1 -> dbl ? classify(dbl(f[rs1])) : classifySingle(f[rs1]);
                        default -> throw illegal(inst);
                    };
                }
            }
            case 0x78 -> { // FMV.W.X / FMV.D.X
                if (funct3 != 0 || rs2 != 0) {
                    throw illegal(inst);
                }
                if (rd != 0) {
                    f[rd] = dbl ? x[rs1] : (x[rs1] & 0xFFFF_FFFFL) | NAN_BOX;
                }
            }
            case 0x58 -> { // FCVT.S.D / FCVT.S.L / FCVT.S.LU
                if (rs2 == 0) {
                    throw illegal(inst); // there is no FCVT.S.S
                }
                if (rd != 0) {
                    double v = switch (rs2) {
                        case 1 -> {
                            if (isNaN(dbl(f[rs1]))) {
                                csr.raiseFflags(FFLAG_NV);
                            }
                            yield dbl(f[rs1]);
                        }
                        case 2 -> (double) x[rs1];
                        case 3 -> unsignedToDouble(x[rs1]);
                        default -> throw illegal(inst);
                    };
                    f[rd] = boxSingle((float) v);
                }
            }
            default -> throw illegal(inst);
        }
        pc += 4;
    }

    private static boolean signBit(double v) {
        return Double.doubleToRawLongBits(v) < 0;
    }

    /**
     * Floating point to integer conversion, shared by the {@code FCVT.W/L} family.
     *
     * <p>Truncation toward zero is what the conversion requires and is also what Java's cast does,
     * so the dynamic rounding mode does not apply here. Out of range values saturate to the largest
     * magnitude representable in the destination and raise the invalid flag, which is what the
     * specification requires rather than wrapping.
     *
     * @param source 0 signed word, 1 unsigned word, 2 signed long, 3 unsigned long
     */
    private long convertToInt(double value, int source) {
        if (Double.isNaN(value)) {
            csr.raiseFflags(FFLAG_NV);
            return switch (source) {
                case 0, 2 -> Long.MIN_VALUE;
                case 1 -> Integer.MIN_VALUE & 0xFFFF_FFFFL;
                default -> 0L;
            };
        }
        switch (source) {
            case 0 -> { // FCVT.W: signed 32 bit, then sign extended into the XLEN register
                if (value >= Integer.MAX_VALUE) {
                    csr.raiseFflags(FFLAG_NV);
                    return Integer.MAX_VALUE;
                }
                if (value <= Integer.MIN_VALUE) {
                    csr.raiseFflags(FFLAG_NV);
                    return Integer.MIN_VALUE;
                }
                return sext((long) value, 32);
            }
            case 1 -> { // FCVT.WU: unsigned 32 bit, then zero extended
                if (value >= 0xFFFF_FFFFL) {
                    csr.raiseFflags(FFLAG_NV);
                    return 0xFFFF_FFFFL;
                }
                if (value <= -1.0) {
                    csr.raiseFflags(FFLAG_NV);
                    return 0L;
                }
                return value <= 0 ? 0L : (long) value & 0xFFFF_FFFFL;
            }
            case 2 -> { // FCVT.L
                if (value >= (double) Long.MAX_VALUE) {
                    csr.raiseFflags(FFLAG_NV);
                    return Long.MAX_VALUE;
                }
                if (value <= (double) Long.MIN_VALUE) {
                    csr.raiseFflags(FFLAG_NV);
                    return Long.MIN_VALUE;
                }
                return (long) value;
            }
            default -> { // FCVT.LU
                if (Double.isInfinite(value)) {
                    csr.raiseFflags(FFLAG_NV);
                    return -1L;
                }
                if (value <= -1.0) {
                    csr.raiseFflags(FFLAG_NV);
                    return 0L;
                }
                if (value >= 0xFFFF_FFFF_FFFF_FFFFL) {
                    csr.raiseFflags(FFLAG_NV);
                    return -1L;
                }
                return (long) value;
            }
        }
    }

    /** Integer to floating point, shared by the {@code FCVT.*} family. */
    private long convertFromInt(long v, int source, boolean toDouble, long inst) {
        double d = switch (source) {
            case 0 -> (double) (int) v;
            case 1 -> (double) (v & 0xFFFF_FFFFL);
            case 2 -> (double) v;
            case 3 -> unsignedToDouble(v);
            default -> throw illegal(inst);
        };
        return toDouble ? Double.doubleToRawLongBits(d) : boxSingle((float) d);
    }

    private static double unsignedToDouble(long v) {
        // Bit twiddling rather than (double) on the long, which would sign extend.
        if (v >= 0) {
            return (double) v;
        }
        return (double) ((v >>> 1) * 2.0 + (v & 1));
    }

    /** Fused multiply-add family, {@code opcode} selects the rounding of the final addition. */
    private void executeFma(long inst, int operation) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        int rs3 = (int) (inst >>> 27) & 0x1F;
        boolean dbl = (inst & (1L << 25)) != 0;
        requireFp(inst);
        if (rd != 0) {
            markFpDirty();
        }
        if (dbl) {
            double a = dbl(f[rs1]);
            double b = dbl(f[rs2]);
            double c = dbl(f[rs3]);
            double r = Math.fma(a, b, operation == 1 ? c : -c);
            f[rd] = Double.doubleToRawLongBits(r);
        } else {
            float r = (float) Math.fma((double) single(f[rs1]), (double) single(f[rs2]),
                    operation == 1 ? (double) single(f[rs3]) : -(double) single(f[rs3]));
            f[rd] = boxSingle(r);
        }
        pc += 4;
    }

    // ---- decoder ---------------------------------------------------------------------------------

    private void execute(long inst) {
        if ((inst & 3L) != 3L) {
            executeCompressed(inst);
            return;
        }
        switch ((int) (inst & 0x7FL)) {
            case 0x07 -> { // LOAD-FP: FLW / FLD
                int rd = (int) (inst >>> 7) & 0x1F;
                int rs1 = (int) (inst >>> 15) & 0x1F;
                int imm = (int) (inst >>> 20);
                requireFp(inst);
                switch ((int) (inst >>> 12) & 3) {
                    case 2 -> { // FLW, NaN boxed into the 64 bit slot
                        if (rd != 0) {
                            f[rd] = NAN_BOX | (load(x[rs1] + sext(imm, 12), 4, false)
                                    & 0xFFFF_FFFFL);
                            markFpDirty();
                        }
                    }
                    case 3 -> { // FLD
                        if (rd != 0) {
                            f[rd] = load(x[rs1] + sext(imm, 12), 8, false);
                            markFpDirty();
                        }
                    }
                    default -> throw illegal(inst); // FLQ is not part of RV64G
                }
                pc += 4;
            }
            case 0x27 -> { // STORE-FP: FSW / FSD
                int rs2 = (int) (inst >>> 20) & 0x1F;
                int rs1 = (int) (inst >>> 15) & 0x1F;
                int imm = (int) (((inst >>> 25) << 5) | ((inst >>> 7) & 0x1F));
                requireFp(inst);
                int offset = (int) sext(imm, 12);
                switch ((int) (inst >>> 12) & 3) {
                    case 2 -> store(x[rs1] + offset, 4, f[rs2] & 0xFFFF_FFFFL);
                    case 3 -> store(x[rs1] + offset, 8, f[rs2]);
                    default -> throw illegal(inst); // FSQ is not part of RV64G
                }
                pc += 4;
            }
            case 0x43, 0x47, 0x4B, 0x4F -> executeFma(inst,
                    ((int) (inst & 0x7FL)) == 0x43 || ((int) (inst & 0x7FL)) == 0x4B ? 0 : 1);
            case 0x53 -> executeFp(inst);
            case 0x37 -> { // LUI
                int rd = (int) (inst >>> 7) & 0x1F;
                if (rd != 0) x[rd] = sext(inst & 0xFFFFF000L, 32);
                pc += 4;
            }
            case 0x17 -> { // AUIPC
                int rd = (int) (inst >>> 7) & 0x1F;
                if (rd != 0) x[rd] = pc + sext(inst & 0xFFFFF000L, 32);
                pc += 4;
            }
            case 0x6F -> { // JAL
                int rd = (int) (inst >>> 7) & 0x1F;
                long offset = ((inst >>> 31) & 0x1L) << 20
                        | ((inst >>> 21) & 0x3FFL) << 1
                        | ((inst >>> 20) & 0x1L) << 11
                        | ((inst >>> 12) & 0xFFL) << 12;
                long next = pc + 4;
                long target = pc + sext(offset, 21);
                if ((target & 1) != 0) {
                    throw new TrapException(INSTRUCTION_ADDR_MISALIGNED, target);
                }
                if (rd != 0) x[rd] = next;
                pc = target;
            }
            case 0x67 -> { // JALR
                if (((inst >>> 12) & 7L) != 0L) {
                    throw illegal(inst);
                }
                int rd = (int) (inst >>> 7) & 0x1F;
                int rs1 = (int) (inst >>> 15) & 0x1F;
                long offset = sext(inst >>> 20, 12);
                long next = pc + 4;
                long target = (x[rs1] + offset) & ~1L;
                if (rd != 0) x[rd] = next;
                pc = target;
            }
            case 0x63 -> executeBranch(inst);
            case 0x03 -> executeLoad(inst);
            case 0x23 -> executeStore(inst);
            case 0x13 -> executeOpImm(inst);
            case 0x1B -> executeOpImm32(inst);
            case 0x33 -> executeOp(inst);
            case 0x3B -> executeOp32(inst);
            case 0x0F -> executeFence(inst);
            case 0x2F -> executeAmo(inst);
            case 0x73 -> executeSystem(inst);
            default -> throw illegal(inst);
        }
    }

    private void executeBranch(long inst) {
        long offset = ((inst >>> 31) & 0x1L) << 12
                | ((inst >>> 25) & 0x3FL) << 5
                | ((inst >>> 8) & 0xFL) << 1
                | ((inst >>> 7) & 0x1L) << 11;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        long a = x[rs1];
        long b = x[rs2];
        boolean taken = switch ((int) (inst >>> 12) & 7) {
            case 0 -> a == b;
            case 1 -> a != b;
            case 4 -> a < b;
            case 5 -> a >= b;
            case 6 -> Long.compareUnsigned(a, b) < 0;
            case 7 -> Long.compareUnsigned(a, b) >= 0;
            default -> throw illegal(inst);
        };
        if (taken) {
            long target = pc + sext(offset, 13);
            if ((target & 1) != 0) {
                throw new TrapException(INSTRUCTION_ADDR_MISALIGNED, target);
            }
            pc = target;
        } else {
            pc += 4;
        }
    }

    private void executeLoad(long inst) {
        long offset = sext(inst >>> 20, 12);
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int funct3 = (int) (inst >>> 12) & 7;
        long addr = x[rs1] + offset;
        long value = switch (funct3) {
            case 0 -> load(addr, 1, true);
            case 1 -> load(addr, 2, true);
            case 2 -> load(addr, 4, true);
            case 3 -> load(addr, 8, true);
            case 4 -> load(addr, 1, false);
            case 5 -> load(addr, 2, false);
            case 6 -> load(addr, 4, false);
            default -> throw illegal(inst);
        };
        if (rd != 0) x[rd] = value;
        pc += 4;
    }

    private void executeStore(long inst) {
        // S-type immediate: imm[11:5] lives in inst[31:25] and imm[4:0] in inst[11:7]. Both halves
        // are needed; taking only the high half silently redirects every store whose low
        // displacement is non-zero.
        long offset = sext((((inst >>> 25) & 0x7FL) << 5) | ((inst >>> 7) & 0x1FL), 12);
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        int funct3 = (int) (inst >>> 12) & 7;
        long addr = x[rs1] + offset;
        long value = x[rs2];
        switch (funct3) {
            case 0 -> store(addr, 1, value);
            case 1 -> store(addr, 2, value);
            case 2 -> store(addr, 4, value);
            case 3 -> store(addr, 8, value);
            default -> throw illegal(inst);
        }
        pc += 4;
    }

    private void executeOpImm(long inst) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int funct3 = (int) (inst >>> 12) & 7;
        long a = x[rs1];
        long result;
        switch (funct3) {
            case 0 -> result = a + sext(inst >>> 20, 12);
            case 1 -> result = a << checkShamt(inst, false);
            case 2 -> result = a < sext(inst >>> 20, 12) ? 1 : 0;
            case 3 -> result = Long.compareUnsigned(a, sext(inst >>> 20, 12)) < 0 ? 1 : 0;
            case 4 -> result = a ^ sext(inst >>> 20, 12);
            case 5 -> {
                boolean arithmetic = (inst & 0x4000_0000L) != 0;
                int shamt = checkShamt(inst, arithmetic);
                result = arithmetic ? a >> shamt : a >>> shamt;
            }
            case 6 -> result = a | sext(inst >>> 20, 12);
            default -> result = a & sext(inst >>> 20, 12);
        }
        if (rd != 0) x[rd] = result;
        pc += 4;
    }

    private void executeOpImm32(long inst) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int funct3 = (int) (inst >>> 12) & 7;
        long a = x[rs1];
        long result;
        switch (funct3) {
            case 0 -> result = (int) (a + sext(inst >>> 20, 12));
            case 1 -> result = (int) a << checkShamtW(inst, false);
            case 5 -> {
                boolean arithmetic = (inst & 0x4000_0000L) != 0;
                int shamt = checkShamtW(inst, arithmetic);
                result = arithmetic ? (int) a >> shamt : (int) a >>> shamt;
            }
            default -> throw illegal(inst);
        }
        if (rd != 0) x[rd] = sext(result, 32);
        pc += 4;
    }

    private void executeOp(long inst) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        int funct7 = (int) (inst >>> 25) & 0x7F;
        long a = x[rs1];
        long b = x[rs2];
        long result;
        if (funct7 == 1) {
            result = multiplyDivide(a, b, (int) (inst >>> 12) & 7);
        } else {
            result = switch ((int) (inst >>> 12) & 7) {
                case 0 -> funct7 == 0x20 ? a - b : a + b;
                case 1 -> a << (b & 0x3F);
                case 2 -> a < b ? 1 : 0;
                case 3 -> Long.compareUnsigned(a, b) < 0 ? 1 : 0;
                case 4 -> a ^ b;
                case 5 -> funct7 == 0x20 ? a >> (b & 0x3F) : a >>> (b & 0x3F);
                case 6 -> a | b;
                case 7 -> a & b;
                default -> throw illegal(inst);
            };
        }
        if (rd != 0) x[rd] = result;
        pc += 4;
    }

    private void executeOp32(long inst) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        int funct7 = (int) (inst >>> 25) & 0x7F;
        int a = (int) x[rs1];
        int b = (int) x[rs2];
        long result;
        if (funct7 == 1) {
            result = switch ((int) (inst >>> 12) & 7) {
                case 0 -> a * b;
                case 4 -> b == 0 ? -1 : (a == Integer.MIN_VALUE && b == -1 ? Integer.MIN_VALUE : a / b);
                case 5 -> b == 0 ? -1 : Integer.toUnsignedLong(
                        a == Integer.MIN_VALUE && b == -1 ? a : Integer.divideUnsigned(a, b));
                case 6 -> b == 0 ? a : (a == Integer.MIN_VALUE && b == -1 ? 0 : a % b);
                case 7 -> b == 0 ? a : Integer.toUnsignedLong(
                        a == Integer.MIN_VALUE && b == -1 ? 0 : Integer.remainderUnsigned(a, b));
                default -> throw illegal(inst);
            };
        } else {
            result = switch ((int) (inst >>> 12) & 7) {
                case 0 -> funct7 == 0x20 ? a - b : a + b;
                case 1 -> a << (b & 0x1F);
                case 5 -> funct7 == 0x20 ? a >> (b & 0x1F) : a >>> (b & 0x1F);
                default -> throw illegal(inst);
            };
        }
        if (rd != 0) x[rd] = sext(result, 32);
        pc += 4;
    }

    /** The M extension, including the architecturally defined divide-by-zero results. */
    private static long multiplyDivide(long a, long b, int funct3) {
        switch (funct3) {
            case 0:
                return a * b;
            case 1:
                return Math.multiplyHigh(a, b);
            case 2:
                return Math.multiplyHigh(a, b) + (b < 0 ? a : 0);
            case 3:
                // Unsigned high half: signed high, corrected once for each negative operand.
                return Math.multiplyHigh(a, b) + (a < 0 ? b : 0) + (b < 0 ? a : 0);
            case 4:
                if (b == 0) return -1L;
                if (a == Long.MIN_VALUE && b == -1L) return Long.MIN_VALUE;
                return a / b;
            case 5:
                if (b == 0) return -1L;
                return Long.divideUnsigned(a, b);
            case 6:
                if (b == 0) return a;
                if (a == Long.MIN_VALUE && b == -1L) return 0L;
                return a % b;
            default:
                if (b == 0) return a;
                return Long.remainderUnsigned(a, b);
        }
    }

    private void executeAmo(long inst) {
        int funct3 = (int) (inst >>> 12) & 7;
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rs2 = (int) (inst >>> 20) & 0x1F;
        int funct5 = (int) (inst >>> 27) & 0x1F;
        long addr = x[rs1];
        int size = switch (funct3) {
            case 2 -> 4;
            case 3 -> 8;
            default -> throw illegal(inst);
        };

        if (funct5 == 0x02) { // LR
            if (rs2 != 0) {
                throw illegal(inst);
            }
            long value = load(addr, size, true);
            reservation = addr;
            reservationValid = true;
            if (rd != 0) x[rd] = value;
            pc += 4;
            return;
        }
        if (funct5 == 0x03) { // SC
            // The reservation is address based: it is dropped as soon as anything writes into the
            // reserved granule, not when the stored value happens to differ from the one loaded.
            if (!reservationValid || reservation != addr) {
                reservationValid = false;
                if (rd != 0) x[rd] = 1;
                pc += 4;
                return;
            }
            store(addr, size, x[rs2]);
            reservationValid = false;
            if (rd != 0) x[rd] = 0;
            pc += 4;
            return;
        }

        long original = load(addr, size, true);
        long source = x[rs2];
        long updated = switch (funct5) {
            case 0x00 -> original + source;                       // AMOADD
            case 0x01 -> source;                                  // AMOSWAP
            case 0x04 -> original ^ source;
            case 0x08 -> original | source;
            case 0x0C -> original & source;
            case 0x10 -> size == 4 ? Integer.min((int) original, (int) source)
                    : Long.compare(original, source) < 0 ? original : source;
            case 0x14 -> size == 4 ? Integer.max((int) original, (int) source)
                    : Long.compare(original, source) > 0 ? original : source;
            case 0x18 -> size == 4 ? Integer.compareUnsigned((int) original, (int) source) < 0
                    ? original : source
                    : Long.compareUnsigned(original, source) < 0 ? original : source;
            case 0x1C -> size == 4 ? Integer.compareUnsigned((int) original, (int) source) > 0
                    ? original : source
                    : Long.compareUnsigned(original, source) > 0 ? original : source;
            default -> throw illegal(inst);
        };
        long stored = size == 4 ? sext(updated, 32) : updated;
        store(addr, size, stored);
        if (rd != 0) x[rd] = original;
        pc += 4;
    }

    private long reservation;
    private boolean reservationValid;

    /**
     * Drops an outstanding LR reservation when a store lands in the reserved granule.
     *
     * <p>Single-harted, so this only ever fires on the hart's own stores; a real multi-hart system
     * would additionally need the store buffer to arbitrate between harts.
     */
    private void invalidateReservationOn(long address, int size) {
        if (reservationValid
                && address < reservation + 8
                && reservation < address + size) {
            reservationValid = false;
        }
    }

    /** Releases any outstanding LR reservation. */
    public void invalidateReservation() {
        reservationValid = false;
    }

    private void executeFence(long inst) {
        int funct3 = (int) (inst >>> 12) & 7;
        if (funct3 == 1) {
            // FENCE.I: the pipeline never caches decoded instructions, so this is a no-op.
            pc += 4;
            return;
        }
        pc += 4;
    }

    private void executeSystem(long inst) {
        int funct3 = (int) (inst >>> 12) & 7;
        if (funct3 == 0) {
            long imm = inst >>> 20;
            switch ((int) imm) {
                case 0x000 -> { // ECALL
                    long cause = switch (privilege) {
                        case 3 -> MACHINE_ECALL;
                        case 1 -> SUPERVISOR_ECALL;
                        default -> USER_ECALL;
                    };
                    // mepc has to point at this instruction, so pc is left alone and the handler
                    // advances it past the ecall before returning.
                    throw new TrapException(cause, 0L);
                }
                case 0x001 -> throw new TrapException(BREAKPOINT, pc); // EBREAK
                case 0x102 -> { // SRET
                    if (privilege == 0) {
                        throw illegal(inst);
                    }
                    executeSret();
                    return;
                }
                case 0x302 -> { // MRET
                    if (privilege != 3) {
                        throw illegal(inst);
                    }
                    executeMret();
                    return;
                }
                case 0x105 -> { // WFI
                    if (privilege == 1 && (csr.mstatus() & (1L << CsrFile.MSTATUS_TW)) != 0) {
                        throw illegal(inst);
                    }
                    wfi = true;
                    pc += 4;
                    return;
                }
                default -> {
                    if ((inst & 0xFE007FFFL) == 0x12000073L) {
                        // SFENCE.VMA rs1, rs2
                        if (privilege == 1 && (csr.mstatus() & (1L << CsrFile.MSTATUS_TSR)) != 0) {
                            throw illegal(inst);
                        }
                        int rs1 = (int) (inst >>> 15) & 0x1F;
                        int rs2 = (int) (inst >>> 20) & 0x1F;
                        mmu.sfence(x[rs1], x[rs2]);
                        pc += 4;
                        return;
                    }
                    throw illegal(inst);
                }
            }
        }
        executeCsr(inst, funct3);
    }

    private void executeCsr(long inst, int funct3) {
        int csrAddress = (int) (inst >>> 20) & 0xFFF;
        int rs1 = (int) (inst >>> 15) & 0x1F;
        int rd = (int) (inst >>> 7) & 0x1F;
        if (!csrAccessible(csrAddress)) {
            throw new IllegalInstructionException(csrAddress, "csr not accessible");
        }
        boolean immediate = funct3 >= 5;
        // The three immediate forms encode their operand in the rs1 field instead of reading x[rs1].
        long operand = immediate ? rs1 : x[rs1];
        long old = csr.read(csrAddress);
        switch (funct3) {
            case 1 -> csr.write(csrAddress, operand);             // CSRRW
            case 2 -> {
                // rs1 == x0 means "read only": the CSR must not be written, which is the only way
                // to read a read-only counter such as INSTRET without trapping.
                if (rs1 != 0) csr.write(csrAddress, old | operand);                // CSRRS
            }
            case 3 -> {
                if (rs1 != 0) csr.write(csrAddress, old & ~operand);               // CSRRC
            }
            case 5 -> csr.write(csrAddress, operand);   // CSRRWI: always writes, zimm included
            case 6 -> csr.write(csrAddress, old | operand); // CSRRSI: zimm 0 sets nothing
            case 7 -> csr.write(csrAddress, old & ~operand); // CSRRCI: zimm 0 clears nothing
            default -> throw illegal(inst);
        }
        long value = (funct3 == 1 || funct3 == 5) ? old : csr.read(csrAddress);
        if (rd != 0) x[rd] = value;
        pc += 4;
    }

    private boolean csrAccessible(int address) {
        if (address >= 0xC00) {
            long counterEn = privilege == 3 ? 0xFFFF_FFFF_FFFF_FFFFL : csr.rawRead(CsrFile.MCOUNTEREN);
            int bit = switch (address) {
                case CsrFile.CYCLE, CsrFile.CYCLEH -> 0;
                case CsrFile.TIME, CsrFile.TIMEH -> 1;
                default -> 2;
            };
            return ((counterEn >>> bit) & 1) != 0 || address >= 0xF11;
        }
        if (address < 0x300) {
            return privilege >= 1;
        }
        return privilege == 3;
    }

    // ---- compressed instructions -----------------------------------------------------------------

    private void executeCompressed(long inst) {
        int quadrant = (int) (inst & 3);
        int funct3 = (int) (inst >>> 13) & 7;
        switch (quadrant) {
            case 0 -> executeQuadrant0(inst, funct3);
            case 1 -> executeQuadrant1(inst, funct3);
            default -> executeQuadrant2(inst, funct3);
        }
    }

    private static int creg(long inst, int shift) {
        return 8 + (int) ((inst >>> shift) & 7);
    }

    private static long sext6(long value) {
        return sext(value, 6);
    }

    private void executeQuadrant0(long inst, int funct3) {
        // Everything in this quadrant is a CL format instruction, so the destination is inst[4:2]
        // and the source base is inst[9:7]. They are decoded per instruction rather than once for
        // the whole quadrant, because C.ADDI4SPN has no source base at all.
        switch (funct3) {
            case 0 -> { // C.ADDI4SPN
                int rd = creg(inst, 2);
                long imm = (((inst >>> 6) & 1) << 2)
                        | (((inst >>> 5) & 1) << 3)
                        | (((inst >>> 11) & 3) << 4)
                        | (((inst >>> 7) & 0xF) << 6);
                if (imm == 0) {
                    throw illegal(inst);
                }
                x[rd] = x[2] + imm;
            }
            case 1 -> { // C.FLD, double precision from the prime register pair
                int rd = creg(inst, 2);
                int rs1 = creg(inst, 7);
                long imm = (((inst >>> 5) & 3) << 6)
                        | (((inst >>> 10) & 7) << 3)
                        | (((inst >>> 6) & 1) << 2);
                requireFp(inst);
                f[rd] = load(x[rs1] + imm, 8, false);
                markFpDirty();
            }
            case 2 -> { // C.LW
                int rd = creg(inst, 2);
                int rs1 = creg(inst, 7);
                long imm = (((inst >>> 6) & 1) << 2)
                        | (((inst >>> 10) & 7) << 3)
                        | (((inst >>> 5) & 1) << 6);
                x[rd] = sext(load(x[rs1] + imm, 4, true), 32);
            }
            case 3 -> { // C.LD
                int rd = creg(inst, 2);
                int rs1 = creg(inst, 7);
                long imm = (((inst >>> 5) & 3) << 6)
                        | (((inst >>> 10) & 7) << 3);
                x[rd] = load(x[rs1] + imm, 8, true);
            }
            case 5 -> { // C.FSD
                int rs2 = creg(inst, 2);
                int rs1 = creg(inst, 7);
                long imm = (((inst >>> 5) & 3) << 6)
                        | (((inst >>> 10) & 7) << 3)
                        | (((inst >>> 6) & 1) << 2);
                requireFp(inst);
                store(x[rs1] + imm, 8, f[rs2]);
            }
            case 6 -> { // C.SW
                int rs2 = creg(inst, 2);
                int rs1 = creg(inst, 7);
                long imm = (((inst >>> 6) & 1) << 2)
                        | (((inst >>> 10) & 7) << 3)
                        | (((inst >>> 5) & 1) << 6);
                store(x[rs1] + imm, 4, x[rs2]);
            }
            default -> { // C.SD
                int rs2 = creg(inst, 2);
                int rs1 = creg(inst, 7);
                long imm = (((inst >>> 5) & 3) << 6)
                        | (((inst >>> 10) & 7) << 3);
                store(x[rs1] + imm, 8, x[rs2]);
            }
        }
        pc += 2;
    }

    private void executeQuadrant1(long inst, int funct3) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs2 = (int) (inst >>> 2) & 0x1F;
        switch (funct3) {
            case 0 -> { // C.NOP / C.ADDI
                long imm = sext6(((inst >>> 2) & 0x1F) | (((inst >>> 12) & 1) << 5));
                if (rd != 0) x[rd] = x[rd] + imm;
            }
            case 1 -> { // C.ADDIW, RV64 only
                long imm = sext6(((inst >>> 2) & 0x1F) | (((inst >>> 12) & 1) << 5));
                if (rd != 0) x[rd] = sext(x[rd] + imm, 32);
            }
            case 2 -> { // C.LI
                long imm = sext6(((inst >>> 2) & 0x1F) | (((inst >>> 12) & 1) << 5));
                if (rd != 0) x[rd] = imm;
            }
            case 3 -> {
                if (rd == 2) { // C.ADDI16SP
                    // nzimm[9]=inst[12], [8:7]=inst[4:3], [6]=inst[5], [5]=inst[2], [4]=inst[6]
                    long imm = sext((((inst >>> 12) & 1) << 9)
                            | (((inst >>> 3) & 3) << 7)
                            | (((inst >>> 5) & 1) << 6)
                            | (((inst >>> 2) & 1) << 5)
                            | (((inst >>> 6) & 1) << 4), 10);
                    if (imm == 0) {
                        throw illegal(inst);
                    }
                    x[2] = x[2] + imm;
                } else { // C.LUI
                    // The six encoded bits are nzimm[17:12]; they occupy bits 31:12 of the result.
                    long nzimm = (((inst >>> 12) & 1) << 5) | ((inst >>> 2) & 0x1F);
                    if (nzimm == 0) {
                        throw illegal(inst);
                    }
                    if (rd != 0) x[rd] = sext(sext(nzimm, 6) << 12, 32);
                }
            }
            case 4 -> {
                int funct6 = (int) (inst >>> 10) & 0x3F;
                if (funct6 == 0b100011 || funct6 == 0b100111) {
                    // Quadrant 1 puts rd'/rs1' in bits 9:7 and rs2' in bits 4:2, the opposite
                    // of quadrant 0.
                    int crd = creg(inst, 7);
                    int cs2 = creg(inst, 2);
                    long a = x[crd];
                    long b = x[cs2];
                    int funct2 = (int) (inst >>> 5) & 3;
                    if (funct6 == 0b100111 && funct2 < 2) {
                        // C.SUBW and C.ADDW are 32 bit operations whose result is sign extended.
                        x[crd] = sext(funct2 == 0 ? (int) a - (int) b : (int) a + (int) b, 32);
                    } else {
                        // C.SUB, C.XOR, C.OR and C.AND are full 64 bit operations. Sign extending
                        // them from 32 bits silently throws away the upper half of every result;
                        // Linux's setup_vm() computes its physical base as
                        // "phys_addr - virt_addr + va" with C.SUB, and truncating that made
                        // 0x80200000 - 0xffffffff80000000 come out as 0x200000 instead of
                        // 0x0000000100200000, which then fed a virtual address into the page
                        // tables and killed the boot in create_pud_mapping.
                        long result = switch (funct2) {
                            case 0 -> a - b;   // C.SUB
                            case 1 -> a ^ b;   // C.XOR
                            case 2 -> a | b;   // C.OR
                            default -> a & b;  // C.AND
                        };
                        x[crd] = result;
                    }
                    pc += 2;
                    return;
                }
                int shamt = (int) (inst >>> 2) & 0x1F | ((int) (inst >>> 12) & 1) << 5;
                int crd = creg(inst, 7);
                int sub = (int) (inst >>> 10) & 3;
                if (sub == 0) {
                    x[crd] = x[crd] >>> shamt;   // C.SRLI
                } else if (sub == 1) {
                    x[crd] = x[crd] >> shamt;    // C.SRAI
                } else {
                    x[crd] = x[crd] & sext6(shamt); // C.ANDI
                }
                pc += 2;
                return;
            }
            case 5 -> { // C.J
                long imm = sext((((inst >>> 3) & 7) << 1)
                        | (((inst >>> 11) & 1) << 4)
                        | (((inst >>> 2) & 1) << 5)
                        | (((inst >>> 7) & 1) << 6)
                        | (((inst >>> 6) & 1) << 7)
                        | (((inst >>> 9) & 3) << 8)
                        | (((inst >>> 8) & 1) << 10)
                        | (((inst >>> 12) & 1) << 11), 12);
                jumpTo(pc + imm);
                return;
            }
            default -> { // C.BEQZ / C.BNEZ
                long imm = sext(((inst >>> 3) & 3) << 1
                        | (((inst >>> 10) & 3) << 3)
                        | (((inst >>> 2) & 1) << 5)
                        | (((inst >>> 5) & 3) << 6)
                        | (((inst >>> 12) & 1) << 8), 9);
                int crs = creg(inst, 7);
                long value = x[crs];
                boolean taken = funct3 == 6 ? value == 0 : value != 0;
                if (taken) {
                    jumpTo(pc + imm);
                    return;
                }
            }
        }
        pc += 2;
    }

    private void executeQuadrant2(long inst, int funct3) {
        int rd = (int) (inst >>> 7) & 0x1F;
        int rs2 = (int) (inst >>> 2) & 0x1F;
        switch (funct3) {
            case 0 -> { // C.SLLI
                int shamt = (int) (inst >>> 2) & 0x1F | ((int) (inst >>> 12) & 1) << 5;
                if (rd != 0) x[rd] = x[rd] << shamt;
            }
            case 1 -> { // C.FLDSP
                long imm = (((inst >>> 5) & 3) << 3)
                        | (((inst >>> 12) & 1) << 5)
                        | (((inst >>> 2) & 7) << 6);
                requireFp(inst);
                // rd = 0 is the HINT form and executes as a no-op, exactly like C.LWSP/C.LDSP
                // below. Only C.LWSP/C.LDSP are truly reserved at rd = 0.
                if (rd != 0) {
                    f[rd] = load(x[2] + imm, 8, false);
                    markFpDirty();
                }
            }
            case 2 -> { // C.LWSP
                long imm = (((inst >>> 4) & 7) << 2)
                        | (((inst >>> 12) & 1) << 5)
                        | (((inst >>> 2) & 3) << 6);
                if (rd != 0) x[rd] = sext(load(x[2] + imm, 4, true), 32);
            }
            case 3 -> { // C.LDSP
                long imm = (((inst >>> 5) & 3) << 3)
                        | (((inst >>> 12) & 1) << 5)
                        | (((inst >>> 2) & 7) << 6);
                if (rd != 0) x[rd] = load(x[2] + imm, 8, true);
            }
            case 4 -> {
                // Five instructions share funct3 = 100, and bit 12 splits them in two:
                //   clear -> C.JR (rs2 == 0) and C.MV (rs2 != 0)
                //   set   -> C.EBREAK (rd == 0), C.JALR (rs2 == 0) and C.ADD (rs2 != 0)
                // This polarity was previously inverted, which made every c.mv trap as illegal and
                // silently turned every c.add into a move. GNU as is the reference here: c.mv a0,s1
                // assembles to 0x8526 with bit 12 clear, and c.add a0,s1 to 0x9526 with it set.
                boolean bit12 = (inst & (1L << 12)) != 0;
                if (!bit12) {
                    if (rd == 0) {
                        throw illegal(inst); // reserved
                    }
                    if (rs2 == 0) {
                        pc = x[rd] & ~1L; // C.JR
                        return;
                    }
                    x[rd] = x[rs2]; // C.MV
                } else {
                    if (rd == 0) {
                        if (rs2 != 0) {
                            throw illegal(inst); // reserved
                        }
                        throw new TrapException(BREAKPOINT, pc); // C.EBREAK
                    }
                    if (rs2 == 0) {
                        long link = pc + 2; // C.JALR links to the instruction after the jump
                        pc = x[rd] & ~1L;
                        x[1] = link;
                        return;
                    }
                    x[rd] = x[rd] + x[rs2]; // C.ADD
                }
            }
            case 5 -> { // C.FSDSP
                long imm = (((inst >>> 7) & 7) << 6)
                        | (((inst >>> 10) & 7) << 3);
                requireFp(inst);
                store(x[2] + imm, 8, f[rs2]);
            }
            case 6 -> { // C.SWSP
                long imm = (((inst >>> 9) & 0xF) << 2) | (((inst >>> 7) & 3) << 6);
                store(x[2] + imm, 4, x[rs2]);
            }
            default -> { // C.SDSP: integer store, unlike C.FSDSP
                long imm = (((inst >>> 10) & 7) << 3) | (((inst >>> 7) & 7) << 6);
                store(x[2] + imm, 8, x[rs2]);
            }
        }
        pc += 2;
    }

    private void jumpTo(long target) {
        if ((target & 1) != 0) {
            throw new TrapException(INSTRUCTION_ADDR_MISALIGNED, target);
        }
        pc = target;
    }

    /**
     * Validates and extracts the shift amount of a 64 bit shift immediate.
     *
     * <p>Bits 31:26 must be zero for every shift, and bit 30 selects arithmetic shift, so
     * {@code SRAI} alone is allowed to set it.
     */
    private static int checkShamt(long inst, boolean arithmetic) {
        // Bit 31 and bits 29:26 must always be zero. Bit 30 selects the arithmetic form, so it is
        // only reserved for the logical shifts.
        long reserved = arithmetic ? 0xBC00_0000L : 0xFC00_0000L;
        if ((inst & reserved) != 0) {
            throw new TrapException(ILLEGAL_INSTRUCTION, sext(inst & 0xFFFF_FFFFL, 32));
        }
        return (int) (inst >>> 20) & 0x3F;
    }

    /** The same check for the word-width shifts, where the amount is only five bits wide. */
    private static int checkShamtW(long inst, boolean arithmetic) {
        long reserved = arithmetic ? 0xBE00_0000L : 0xFE00_0000L;
        if ((inst & reserved) != 0) {
            throw new TrapException(ILLEGAL_INSTRUCTION, sext(inst & 0xFFFF_FFFFL, 32));
        }
        return (int) (inst >>> 20) & 0x1F;
    }

    private static TrapException illegal(long inst) {
        return new TrapException(ILLEGAL_INSTRUCTION, sext(inst & 0xFFFF_FFFFL, 32));
    }
}