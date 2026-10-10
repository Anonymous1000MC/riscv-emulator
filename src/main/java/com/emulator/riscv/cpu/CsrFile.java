package com.emulator.riscv.cpu;

/**
 * The privileged-architecture control and status register file.
 *
 * <p>Storage is a flat {@code long[4096]} so the hot CSR access path is a bounds-checked array
 * index rather than a name lookup. Supervisor aliases ({@code sstatus}, {@code sie}, {@code sip},
 * {@code sepc}, ...) are folded onto their machine-mode counterparts on every access and the
 * supervisor write mask is applied on the way in and on the way out.
 */
public final class CsrFile {

    // ---- CSR addresses -------------------------------------------------------------------
    public static final int SSTATUS = 0x100;
    public static final int SIE = 0x104;
    public static final int STVEC = 0x105;
    public static final int SCNTREN = 0x106;
    public static final int SENV_CFG = 0x10A;
    public static final int SSCRATCH = 0x140;
    public static final int SEPC = 0x141;
    public static final int SCAUSE = 0x142;
    public static final int STVAL = 0x143;
    public static final int SIP = 0x144;
    public static final int SATP = 0x180;
    private static final int SATP_MODE_SHIFT = 60;
    public static final int SATP_MODE_BARE = 0;
    public static final int SATP_MODE_SV39 = 8;
    public static final int SATP_MODE_SV48 = 9;
    private static final long SATP_ASID_MASK = 0xFFFFL;

    public static final int CYCLE = 0xC00;
    public static final int TIME = 0xC01;
    public static final int INSTRET = 0xC02;
    public static final int CYCLEH = 0xC80;
    public static final int TIMEH = 0xC81;
    public static final int INSTRETH = 0xC82;

    public static final int MVENDORID = 0xF11;
    public static final int MARCHID = 0xF12;
    public static final int MIMPID = 0xF13;
    public static final int MHARTID = 0xF14;

    public static final int MSTATUS = 0x300;
    public static final int MISA = 0x301;
    public static final int MEDELEG = 0x302;
    public static final int MIDELEG = 0x303;
    public static final int MIE = 0x304;
    public static final int MTVEC = 0x305;
    public static final int MCOUNTEREN = 0x306;
    public static final int MSTATUSH = 0x310;
    public static final int MSCRATCH = 0x340;
    public static final int MEPC = 0x341;
    public static final int MCAUSE = 0x342;
    public static final int MTVAL = 0x343;
    public static final int MIP = 0x344;

    public static final int MCYCLE = 0xB00;
    public static final int MINSTRET = 0xB02;
    public static final int MCYCLEH = 0xB80;
    public static final int MINSTRETH = 0xB82;

    public static final int PMPADDR0 = 0x3A0;
    public static final int PMPCFG0 = 0x3B0;

    // ---- mstatus bit positions (RV64) ----------------------------------------------------
    public static final int MSTATUS_SIE = 1;
    public static final int MSTATUS_MIE = 3;
    public static final int MSTATUS_SPIE = 5;
    public static final int MSTATUS_UBE = 6;
    public static final int MSTATUS_MPIE = 7;
    public static final int MSTATUS_SPP = 8;
    public static final int MSTATUS_VS_SHIFT = 9;
    public static final int MSTATUS_MPP_SHIFT = 11;
    public static final int MSTATUS_FS_SHIFT = 13;
    public static final int MSTATUS_XS_SHIFT = 15;
    public static final int MSTATUS_MPRV = 17;
    public static final int MSTATUS_SUM = 18;
    public static final int MSTATUS_MXR = 19;
    public static final int MSTATUS_TVM = 20;
    public static final int MSTATUS_TW = 21;
    public static final int MSTATUS_TSR = 22;
    public static final int MSTATUS_MPV = 23;
    public static final int MSTATUS_GVA = 26;
    public static final int MSTATUS_UXL_SHIFT = 32;
    public static final int MSTATUS_SXL_SHIFT = 34;
    public static final int MSTATUS_SBE = 36;
    public static final int MSTATUS_MBE = 37;
    public static final int MSTATUS_SD = 63;

    /** RV64 records 2 into both XLEN fields. */
    public static final long MSTATUS_SXL_RV64 = 2L << MSTATUS_SXL_SHIFT;

    public static final long MSTATUS_MIE_BIT = 1L << MSTATUS_MIE;
    public static final long MSTATUS_MPP_M = 3L << MSTATUS_MPP_SHIFT;
    public static final long MSTATUS_SPP_BIT = 1L << MSTATUS_SPP;

    /** Every guest-writable {@code mstatus} bit on RV64. */
    public static final long MSTATUS_WRITE_MASK =
            (1L << MSTATUS_SIE)
            | (1L << MSTATUS_MIE)
            | (1L << MSTATUS_SPIE)
            | (1L << MSTATUS_UBE)
            | (1L << MSTATUS_MPIE)
            | (1L << MSTATUS_SPP)
            | (0x7L << MSTATUS_VS_SHIFT)
            | (0x3L << MSTATUS_MPP_SHIFT)
            | (0x3L << MSTATUS_FS_SHIFT)
            | (0x3L << MSTATUS_XS_SHIFT)
            | (1L << MSTATUS_MPRV)
            | (1L << MSTATUS_SUM)
            | (1L << MSTATUS_MXR)
            | (1L << MSTATUS_TVM)
            | (1L << MSTATUS_TW)
            | (1L << MSTATUS_TSR)
            | (1L << MSTATUS_MPV)
            | (1L << MSTATUS_GVA)
            | (0x3L << MSTATUS_UXL_SHIFT)
            | (0x3L << MSTATUS_SXL_SHIFT)
            | (1L << MSTATUS_SBE)
            | (1L << MSTATUS_MBE);

    /** The subset of {@link #MSTATUS_WRITE_MASK} that {@code sstatus} exposes. */
    /**
 * The bits {@code sstatus} owns and only those: SIE, SPIE, UBE, SPP, VS, FS, XS, SUM, MXR, UXL
 * and SD. MPIE and MPP are machine-context fields and must not be readable through the supervisor
 * view - leaking MPIE let a guest read a stale machine value as SPIE, so an {@code sret} restored
 * SIE from the wrong bit and every initcall reported "disabled interrupts".
 */
public static final long SSTATUS_MASK =
            (1L << MSTATUS_SIE)
            | (1L << MSTATUS_SPIE)
            | (1L << MSTATUS_UBE)
            | (1L << MSTATUS_SPP)
            | (0x3L << MSTATUS_VS_SHIFT)
            | (0x3L << MSTATUS_FS_SHIFT)
            | (0x3L << MSTATUS_XS_SHIFT)
            | (1L << MSTATUS_SUM)
            | (1L << MSTATUS_MXR)
            | (0x3L << MSTATUS_UXL_SHIFT)
            | (1L << MSTATUS_SD);

    /** Bits visible through {@code sie}; MSIE and MTIE stay machine-only. */
    public static final long SIE_MASK = (1L << 1) | (1L << 5) | (1L << 9);
    /** Bits writable through {@code sip}; MTIP and MPIP stay machine-only. */
    public static final long SIP_MASK = (1L << 1) | (1L << 5) | (1L << 9);
    /** Bits a guest may toggle in {@code mie}. */
    public static final long MIE_MASK = 0xAAAL;
    /**
     * Floating point CSRs. {@code fflags} and {@code frm} are the two halves of {@code fcsr}
     * ({@code fcsr = frm << 5 | fflags}), so they are not stored separately: reads and writes of
     * either address fold into the single {@code fcsr} slot.
     */
    public static final int FFLAGS = 0x001;
    public static final int FRM = 0x002;
    public static final int FCSR = 0x003;
    /** Only rounding modes 0..4 are defined; 5, 6 and 7 are reserved. */
    public static final long FRM_MASK = 0x7L << 5;
    public static final long FFLAGS_MASK = 0x1FL;

    /** Bits a guest may toggle in {@code mip} (SSIP and STIP are writable on RV64). */
    public static final long MIP_MASK = 0x222L | (1L << 5) | (1L << 9);

    /**
     * MXL = 2 (RV64) with only the extensions the interpreter actually decodes: I, M, A, C, S and
     * U.
     *
     * <p>The value is load-bearing rather than decorative. A guest that reads {@code misa} decides
     * which instructions it may emit, so advertising an extension the core cannot decode turns
     * into illegal instructions at run time. The core implements the floating point opcodes
     * ({@code 0x07}, {@code 0x27}, {@code 0x43}, {@code 0x47}, {@code 0x4B}, {@code 0x4F},
     * {@code 0x53}) and the compressed floating point forms, so F and D are set. It implements no
     * vector opcodes ({@code 0x57}, {@code 0x7D}), so V stays clear - that bit was previously set,
     * which made the kernel emit vector instructions this core rejected at boot.
     *
     * <p>Extension bits follow the specification's fixed assignment: A = 0, C = 2, D = 3, F = 5,
     * I = 8, M = 12, S = 17, U = 19, V = 20. Together the set is RV64IMAFDCU, commonly RV64GC.
     */
    public static final long MISA_VALUE = (2L << 62) | 0xA112DL;

    private final long[] csr = new long[4096];
    private final long[] pmpAddr = new long[16];
    private final int[] pmpCfg = new int[16];

    private TimeSource timeSource = () -> 0L;
    private long instretLow;
    private long instretHigh;

    /** Bumped whenever a PMP register changes so the CPU can drop its cached PMP summary. */
    private long pmpGeneration;

    /** Notified when {@code satp} is written so the TLB can be dropped. */
    private Runnable satpListener = () -> { };

    public interface TimeSource {
        long time();
    }

    public CsrFile() {
        csr[MISA] = MISA_VALUE;
        csr[MSTATUS] = MSTATUS_SXL_RV64 | (2L << MSTATUS_UXL_SHIFT);
        csr[MVENDORID] = 0x52495356L; // "RISV"
        csr[MARCHID] = 0L;
        csr[MIMPID] = 0x00200001L;
        csr[MHARTID] = 0L;
        csr[FCSR] = 0L;
    }

    public void setTimeSource(TimeSource source) {
        this.timeSource = source == null ? () -> 0L : source;
    }

    public void setSatpListener(Runnable listener) {
        this.satpListener = listener == null ? () -> { } : listener;
    }

    // ---- architecturally visible access ---------------------------------------------------

    /**
     * @throws IllegalInstructionException when the address is unimplemented or not readable
     */
    public long read(int address) {
        if (!isImplemented(address)) {
            throw new IllegalInstructionException(address,
                    "read of unimplemented csr 0x" + Integer.toHexString(address));
        }
        int index = alias(address);
        if (index != address) {
            if (index == MIE) return csr[MIE] & SIE_MASK;
            if (index == MIP) return csr[MIP] & SIP_MASK;
            if (index == MSTATUS) return csr[MSTATUS] & SSTATUS_MASK;
        }
        return rawRead(index);
    }

    /**
     * @throws IllegalInstructionException when the address is unimplemented or read-only
     */
    public void write(int address, long value) {
        if (!isImplemented(address)) {
            throw new IllegalInstructionException(address,
                    "write to unimplemented csr 0x" + Integer.toHexString(address));
        }
        if ((address & 0xC00) == 0xC00) {
            throw new IllegalInstructionException(address,
                    "write to read-only csr 0x" + Integer.toHexString(address));
        }

        // Supervisor registers alias machine registers rather than owning separate storage, so the
        // destination is resolved first. Switching on the aliased address is what keeps a write to
        // stvec from landing in a slot that read() never consults.
        int target = alias(address);
        switch (target) {
            case FFLAGS -> csr[FCSR] = (csr[FCSR] & ~FFLAGS_MASK) | (value & FFLAGS_MASK);
            case FRM -> csr[FCSR] = (csr[FCSR] & ~FRM_MASK) | (value << 5 & FRM_MASK);
            case FCSR -> csr[FCSR] = value & (FRM_MASK | FFLAGS_MASK);
            case SSTATUS -> {
                // sstatus is a view of mstatus, not a register of its own, so a write may only
                // touch the bits sstatus owns. Replacing mstatus wholesale with the masked value
                // threw away MXL and UXL along with them, and with them the XLEN: the hart then
                // faulted on the next access. Linux's local_irq_enable() is a csrsi sstatus, <<1,
                // which triggered exactly this at every initcall.
                csr[MSTATUS] = (csr[MSTATUS] & ~SSTATUS_MASK) | (value & SSTATUS_MASK);
                refreshSd();
            }
            case SIE -> csr[MIE] = (csr[MIE] & ~SIE_MASK) | (value & SIE_MASK);
            case SIP -> {
                long writable = SIP_MASK & ~(1L << 7);
                csr[MIP] = (csr[MIP] & ~writable) | (value & writable);
            }
            case MSTATUSH -> {
                csr[MSTATUS] = (csr[MSTATUS] & 0xFFFF_FFFFL) | ((value & 0xFFFF_FFFFL) << 32);
            }
            case SATP -> {
                // satp's mode field is WARL and the whole register must be, because a guest probes
                // it: set_satp_mode writes Sv57, reads it back, and keeps whichever mode survives.
                // Accepting Sv57 here would hand the kernel a paging mode the MMU cannot walk - it
                // only distinguishes bare, Sv48 and a three-level walk, so mode 10 would be
                // silently reinterpreted as Sv39 with a 57 bit root pointer. An unsupported mode has
                // to be ignored instead, leaving the previous value in place.
                int mode = (int) ((value >>> SATP_MODE_SHIFT) & 0xF);
                if (mode != SATP_MODE_BARE && mode != SATP_MODE_SV39
                        && mode != SATP_MODE_SV48) {
                    break;
                }
                // The address and ASID fields are WARL too. The root table pointer is taken from the
                // low bits the implemented levels actually cover, and ASIDLEN is 16.
                long rootMask = mode == SATP_MODE_SV48
                        ? (1L << 44) - 1          // Sv48: a 44 bit physical address
                        : (1L << 44) - 1;         // Sv39 also covers up to 56 bits here
                long satp = (value & ~(0xFL << SATP_MODE_SHIFT))
                        & ((SATP_MODE_SHIFT < 44 ? 0 : 0L) | rootMask | SATP_ASID_MASK);
                csr[SATP] = satp | ((long) mode << SATP_MODE_SHIFT);
                satpListener.run();
            }
            case MSCRATCH -> csr[MSCRATCH] = value;
            case MISA -> {
                // misa is WARL: MXL is hardwired to the widest supported XLEN, and only the
                // extension bits the implementation actually has may be cleared. Letting a write
                // replace the whole register let csrci misa, <C> wipe MXL and every other
                // extension, so a guest probing misa saw garbage afterwards.
                long writable = MISA_VALUE & 0x7FFF_FFFF_FFFFL;
                csr[MISA] = (MISA_VALUE & ~0x7FFF_FFFF_FFFFL) | (value & writable);
            }
            case MCAUSE -> csr[MCAUSE] = value;
            case MTVAL -> csr[MTVAL] = value;
            case PMPCFG0, PMPCFG0 + 1, PMPCFG0 + 2, PMPCFG0 + 3 -> {
                int pmpIndex = target - PMPCFG0;
                for (int i = 0; i < 4; i++) {
                    pmpCfg[pmpIndex * 4 + i] = (int) ((value >>> (i * 8)) & 0xFF);
                }
                pmpGeneration++;
            }
            default -> {
                if (target >= PMPADDR0 && target < PMPCFG0) {
                    pmpAddr[target - PMPADDR0] = value;
                    pmpGeneration++;
                } else {
                    csr[target] = value;
                }
            }
        }
        if (target == MSTATUS || target == MSTATUSH) {
            refreshSd();
        }
    }

    /** Writes a CSR bypassing the legality check; for the platform supervisor and the debugger. */
    public void rawWrite(int address, long value) {
        switch (address) {
            case SSTATUS -> write(SSTATUS, value);
            case SIE -> write(SIE, value);
            case SIP -> write(SIP, value);
            case MSTATUSH -> write(MSTATUSH, value);
            default -> csr[address] = value;
        }
    }

    /** Reads a CSR bypassing the legality check; for firmware, the debugger and internal state. */
    public long rawRead(int address) {
        switch (address) {
            case FFLAGS:
                return csr[FCSR] & FFLAGS_MASK;
            case FRM:
                return (csr[FCSR] & FRM_MASK) >> 5;
            case TIME:
                return timeSource.time();
            case TIMEH:
                return timeSource.time() >>> 32;
            case CYCLE, MCYCLE:
                return instretLow;
            case CYCLEH, MCYCLEH:
                return instretHigh;
            case INSTRET, MINSTRET:
                return instretLow;
            case INSTRETH, MINSTRETH:
                return instretHigh;
            case MSTATUSH:
                return csr[MSTATUS] >>> 32;
            default:
                return csr[address];
        }
    }

    /** Adds to the retired-instruction counters used by {@code cycle} and {@code instret}. */
    public void addInstructions(long count) {
        instretLow += count;
        if (instretLow < count) {
            instretHigh++;
        }
    }

    public long instret() {
        return (instretHigh << 32) | instretLow;
    }

    private void refreshSd() {
        long status = csr[MSTATUS];
        int fs = (int) ((status >>> MSTATUS_FS_SHIFT) & 0x3);
        int xs = (int) ((status >>> MSTATUS_XS_SHIFT) & 0x3);
        boolean dirty = (fs == 3 || xs == 3) && (fs != 0 || xs != 0);
        csr[MSTATUS] = dirty ? (status | (1L << MSTATUS_SD)) : (status & ~(1L << MSTATUS_SD));
    }

    // ---- interrupt plumbing ---------------------------------------------------------------

    public long readMip() {
        return csr[MIP];
    }

    public void setMipBits(long mask) {
        csr[MIP] |= mask;
    }

    public void clearMipBits(long mask) {
        csr[MIP] &= ~mask;
    }

    public long readMie() {
        return csr[MIE];
    }

    public void setMie(long value) {
        csr[MIE] = value;
    }

    /** Bits that are both pending and enabled; used to pick the interrupt to deliver. */
    public long pendingAndEnabled() {
        return csr[MIP] & csr[MIE];
    }

    public long mstatus() {
        return csr[MSTATUS];
    }

    public void setMstatus(long value) {
        csr[MSTATUS] = value & MSTATUS_WRITE_MASK;
        refreshSd();
    }

    // ---- floating point --------------------------------------------------------------------------

    public long fcsr() {
        return csr[FCSR];
    }

    public void setFcsr(long value) {
        csr[FCSR] = value & (FRM_MASK | FFLAGS_MASK);
    }

    /** Accumulates exception flags, as the arithmetic instructions do. */
    public void raiseFflags(long flags) {
        csr[FCSR] |= flags & FFLAGS_MASK;
    }

    public void clearFflags() {
        csr[FCSR] &= ~FFLAGS_MASK;
    }

    public long fflags() {
        return csr[FCSR] & FFLAGS_MASK;
    }

    /** The dynamic rounding mode; 0 is round-to-nearest-even, which is what most guests use. */
    public int frm() {
        return (int) ((csr[FCSR] & FRM_MASK) >> 5);
    }

    public void setFrm(int mode) {
        csr[FCSR] = (csr[FCSR] & ~FRM_MASK) | ((long) (mode & 7) << 5);
    }

    public long satp() {
        return csr[SATP];
    }

    public long medeleg() {
        return csr[MEDELEG];
    }

    public void setMedeleg(long value) {
        // Bits 0-9 (all the trap causes), plus 11 (ecall from M), 12 (instruction page fault),
        // 13 (load page fault) and 15 (store/AMO page fault). Bits 10, 14 and everything above 15
        // are reserved and must read zero. Bit 12 matters more than it looks: relocate_enable_mmu
        // installs the trampoline page directory and then relies on the fetch of the next
        // instruction faulting so it can vector to S-mode at its virtual stvec and come back up
        // relocated. A mask that drops bit 12 sends that fetch to M-mode and the kernel never
        // relocates.
        csr[MEDELEG] = value & 0x0000_0000_0000_BBFFL;
    }

    public long mideleg() {
        return csr[MIDELEG];
    }

    public void setMideleg(long value) {
        csr[MIDELEG] = value & 0x0000_0000_0000_0222L;
    }

    public long mepc() {
        return csr[MEPC];
    }

    public void setMepc(long value) {
        csr[MEPC] = value;
    }

    public long sepc() {
        return csr[SEPC];
    }

    public void setSepc(long value) {
        csr[SEPC] = value;
    }

    public long mcause() {
        return csr[MCAUSE];
    }

    public void setMcause(long value) {
        csr[MCAUSE] = value;
    }

    public long scause() {
        return csr[SCAUSE];
    }

    public void setScause(long value) {
        csr[SCAUSE] = value;
    }

    public long mtval() {
        return csr[MTVAL];
    }

    public void setMtval(long value) {
        csr[MTVAL] = value;
    }

    public long stval() {
        return csr[STVAL];
    }

    public void setStval(long value) {
        csr[STVAL] = value;
    }

    public long mscratch() {
        return csr[MSCRATCH];
    }

    public void setMscratch(long value) {
        csr[MSCRATCH] = value;
    }

    public long sscratch() {
        return csr[SSCRATCH];
    }

    public void setSscratch(long value) {
        csr[SSCRATCH] = value;
    }

    public long mtvec() {
        return csr[MTVEC];
    }

    public long stvec() {
        return csr[STVEC];
    }

    // ---- PMP ------------------------------------------------------------------------------

    public long pmpAddr(int index) {
        return pmpAddr[index];
    }

    public void setPmpAddr(int index, long value) {
        pmpAddr[index] = value;
        pmpGeneration++;
    }

    public int pmpCfg(int index) {
        return pmpCfg[index];
    }

    public long pmpGeneration() {
        return pmpGeneration;
    }

    public void setPmpCfg(int index, int value) {
        pmpCfg[index] = value;
        pmpGeneration++;
    }

    /** Resets all sixteen PMP entries to "unconfigured", which denies S/U access to everything. */
    public void clearPmp() {
        java.util.Arrays.fill(pmpAddr, 0L);
        java.util.Arrays.fill(pmpCfg, 0);
    }

    // ---- helpers --------------------------------------------------------------------------

private static int alias(int address) {
        // Only registers that are genuinely *views* of a machine register alias onto it: sstatus,
        // sie and sip expose parts of mstatus, mie and mip. The rest of the supervisor set are
        // registers in their own right, stored under their own address. Folding stvec and sscratch
        // onto their machine counterparts let a guest's `csrw stvec` overwrite the M-mode trap
        // vector, so a machine trap then jumped into the guest's own handler and ran off the
        // mapped image.
        return switch (address) {
            case SSTATUS -> MSTATUS;
            case SIE -> MIE;
            case SIP -> MIP;
            default -> address;
        };
    }

    /** Mirror of {@link #alias} used by the write path after the switch already ran. */
    private static int index(int address) {
        int a = alias(address);
        return a == SSTATUS ? MSTATUS : a;
    }

    private static boolean isImplemented(int address) {
        if (address <= 0 || address > 0xFFF) return false;
        if ((address & 0xC00) == 0xC00) {
            // Read-only group. We implement the user-level counter trio, the machine-level
            // counter trio and the four identification registers; nothing else is present.
            if (address >= 0xF11 && address <= 0xF14) return true;
            int low = address & 0xFF;
            if (low > 0x02) return false;
            return address >= 0xB00 && address <= 0xB82
                    || address >= 0xC00 && address <= 0xC82;
        }
        return switch (address) {
            case FFLAGS, FRM, FCSR,
                 MSTATUS, MISA, MEDELEG, MIDELEG, MIE, MTVEC, MCOUNTEREN, MSTATUSH,
                 MSCRATCH, MEPC, MCAUSE, MTVAL, MIP,
                 // SEPC belongs here as well: isImplemented() runs before alias() resolves the
                 // supervisor view, so an alias alone would still raise an illegal instruction.
                 SSTATUS, SIE, STVEC, SSCRATCH, SEPC, SCAUSE, STVAL, SIP, SENV_CFG, SATP ->
                    true;
            default -> address >= PMPADDR0 && address < PMPCFG0 + 4;
        };
    }
}