package com.emulator.riscv.mmu;

import com.emulator.riscv.bus.Memory;
import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.TrapException;

import java.util.Arrays;

/**
 * Sv39/Sv48 address translation with a set-associative translation cache.
 *
 * <p>The page-table walk itself is deliberately plain: three or four dependent loads through
 * {@link Memory#read64(long)}. What makes it fast in practice is the cache in front of it. Each
 * entry stores the leaf PTE verbatim, so a later permission check on a hit sees exactly the bits
 * the walk would have produced rather than a lossy summary of them.
 *
 * <p>Invalidation is exact: {@code sfence.vma} with a zero address and ASID retires everything,
 * and with a non-zero address or ASID only matching entries are dropped.
 */
public final class MMU {

    public static final int ACCESS_READ = 1;
    public static final int ACCESS_WRITE = 2;
    public static final int ACCESS_EXEC = 4;

    private static final int PAGE_SHIFT = 12;
    private static final long PAGE_MASK = 0xFFFL;

    private static final int SATP_MODE_SHIFT = 60;
    private static final long SATP_ASID_MASK = 0xFFFF_0000_0000_0000L;
    private static final int SATP_ASID_SHIFT = 44;
    /** satp holds PPN in bits 43:0; the root address is that field shifted left by 12. */
    private static final long SATP_PPN_MASK = 0x0000_0FFF_FFFF_FFFFL;

    public static final int MODE_BARE = 0;
    public static final int MODE_SV39 = 8;
    public static final int MODE_SV48 = 9;

    /** Privilege level at which satp translation is bypassed unless MPRV is set. */
    public static final int PRIV_M = 3;

    private static final long PTE_R = 1L << 1;
    private static final long PTE_W = 1L << 2;
    private static final long PTE_X = 1L << 3;
    private static final long PTE_U = 1L << 4;
    private static final long PTE_G = 1L << 5;
    private static final long PTE_A = 1L << 6;
    private static final long PTE_D = 1L << 7;

    /** Reserved in every defined PTE encoding, so it is free to carry the global-page flag. */
    private static final long GLOBAL_MARKER = 1L << 63;

    private final Memory memory;
    private final CsrFile csr;

    private final long[] tags;
    private final int[] asids;
    private final long[] ptes;
    private final long[] stamps;
    private final byte[] valid;

    private final int sets;
    private final int ways;
    private final int setMask;

    private long clock;
    private long translations;
    private long tlbHits;

    public MMU(Memory memory, CsrFile csr, int sets, int ways) {
        this.memory = memory;
        this.csr = csr;
        this.sets = Math.max(1, sets);
        this.ways = Math.max(1, ways);
        this.setMask = this.sets - 1;
        int entries = this.sets * this.ways;
        this.tags = new long[entries];
        this.asids = new int[entries];
        this.ptes = new long[entries];
        this.stamps = new long[entries];
        this.valid = new byte[entries];
    }

    public int activeMode() {
        return (int) ((csr.satp() >>> SATP_MODE_SHIFT) & 0xFL);
    }

    public CsrFile csrFile() {
        return csr;
    }

    public Memory memory() {
        return memory;
    }

    public boolean isBare() {
        return activeMode() == MODE_BARE;
    }

    public boolean translationEnabled() {
        return activeMode() != MODE_BARE;
    }

    public long translationCount() {
        return translations;
    }

    public double hitRate() {
        return translations == 0 ? 0.0 : (double) tlbHits / translations;
    }

    /**
     * Translates a virtual address to a physical address.
     *
     * @param vaddr     virtual address; the caller has already verified it is canonical
     * @param access    one of {@link #ACCESS_READ}, {@link #ACCESS_WRITE}, {@link #ACCESS_EXEC}
     * @param privilege current privilege level (0 = U, 1 = S, 3 = M)
     * @param sum       value of {@code mstatus.SUM}
     * @param mxr       value of {@code mstatus.MXR}
     * @throws TrapException carrying the matching page-fault cause when translation fails
     */
    public long translate(long vaddr, int access, int privilege, boolean sum, boolean mxr) {
        int mode = activeMode();
        if (mode == MODE_BARE) {
            return vaddr;
        }
        // Machine mode does not use translation unless mstatus.MPRV is set, and MPRV never
        // applies to instruction fetch (RISC-V Privileged, "Translation Detail").
        boolean mprv = (csr.rawRead(CsrFile.MSTATUS) & (1L << CsrFile.MSTATUS_MPRV)) != 0;
        if (privilege == PRIV_M && (access == MMU.ACCESS_EXEC || !mprv)) {
            return vaddr;
        }
        int faultCause = faultCause(access);
        int asid = (int) ((csr.satp() & SATP_ASID_MASK) >>> SATP_ASID_SHIFT);
        int levels = mode == MODE_SV48 ? 4 : 3;

        long vpn = vaddr >>> PAGE_SHIFT;
        long tag = vpn << 1;
        int base = ((int) (vpn & setMask)) * ways;

        for (int i = 0; i < ways; i++) {
            int idx = base + i;
            if (valid[idx] == 0 || (tags[idx] & ~1L) != tag) {
                continue;
            }
            boolean global = (tags[idx] & 1L) != 0;
            if (!global && asids[idx] != asid) {
                // Same VPN, different address space: keep scanning, a global entry may follow.
                continue;
            }
            long entry = ptes[idx];
            stamps[idx] = ++clock;
            translations++;
            if (!permits(entry, access, privilege, sum, mxr)) {
                throw new TrapException(faultCause, vaddr);
            }
            tlbHits++;
            return compose(entry, vaddr);
        }

        long raw = walk(vaddr, levels, faultCause);
        boolean global = (raw & GLOBAL_MARKER) != 0;
        long pte = global ? raw & ~GLOBAL_MARKER : raw;
        fill((int) (vpn & setMask), tag, asid, pte, global);
        translations++;
        if (!permits(pte, access, privilege, sum, mxr)) {
            throw new TrapException(faultCause, vaddr);
        }
        return compose(pte, vaddr);
    }

    /** Builds a physical address from a leaf PTE, which keeps the PPN in bits 10 and above. */
    private static long compose(long pte, long vaddr) {
        return (pte >>> 10 << PAGE_SHIFT) | (vaddr & PAGE_MASK);
    }

    private static int faultCause(int access) {
        return switch (access) {
            case ACCESS_EXEC -> TrapException.INSTRUCTION_PAGE_FAULT;
            case ACCESS_WRITE -> TrapException.STORE_PAGE_FAULT;
            default -> TrapException.LOAD_PAGE_FAULT;
        };
    }

    /**
     * Walks the page table, setting accessed and dirty in place. That mirrors real hardware and
     * avoids pushing every first touch through the kernel's fault fixup path.
     */
    private long walk(long vaddr, int levels, int faultCause) {
        long base = (csr.satp() & SATP_PPN_MASK) << PAGE_SHIFT;
        long a = vaddr >>> PAGE_SHIFT;

        for (int i = levels - 1; i >= 0; i--) {
            long index = (a >>> (i * 9)) & 0x1FFL;
            long pteAddr = base + (index << 3);
            long pte = memory.read64(pteAddr);

            long kind = pte & 3L;
            if (kind == 1L) {
                // A pointer replaces the base table, not the virtual address. The VPN stays put so
                // the remaining levels are indexed from the same VA; overwriting "a" with the
                // child PPN (and truncating it to 12 bits) made every level below the first
                // pointer index from garbage, so Sv39/Sv48 paging could not translate anything.
                base = ((pte >>> 10) & 0xFFFF_FFFF_FFFFL) << PAGE_SHIFT;
                continue;
            }
            if (kind != 3L) {
                throw new TrapException(faultCause, vaddr);
            }

            // W=1, X=1, R=0 is reserved.
            if ((pte & (PTE_W | PTE_X)) == (PTE_W | PTE_X) && (pte & PTE_R) == 0) {
                throw new TrapException(faultCause, vaddr);
            }

            if ((pte & PTE_A) == 0 || (pte & PTE_D) == 0) {
                long updated = pte | PTE_A;
                if ((pte & PTE_W) != 0) {
                    updated |= PTE_D;
                }
                memory.write64(pteAddr, updated);
                pte = updated;
            }

            if (i > 0) {
                // Leaf above level zero is a superpage. A misaligned superpage is re-walked from
                // the next level down after zeroing the overlapping PPN bits, as the spec requires.
                long lowMask = (1L << (i * 9)) - 1;
                if (((pte >>> 10) & lowMask) != 0) {
                    long alignedPpn = (pte >>> 10) & ~lowMask;
                    long innerVaddr = (alignedPpn << PAGE_SHIFT) | (vaddr & PAGE_MASK);
                    return walk(innerVaddr, i, faultCause);
                }
                // A superpage ignores the virtual address bits between the page offset and this
                // level, so those bits have to be carried into the PPN here. compose() only ORs in
                // the 12 bit page offset, because that is all a 4KiB leaf needs; without this the
                // cached entry composes to the wrong physical address. Linux's setup_vm() maps the
                // kernel image with 2MiB superpages, so this is the normal case, not a corner: it
                // made the fetch after "csrw satp" land 0x6000 bytes early and execute garbage,
                // which showed up as a load fault at 0x81000 rather than as anything recognizable.
                pte = (pte & ~(lowMask << 10)) | (((vaddr >>> PAGE_SHIFT) & lowMask) << 10);
            }

            return (pte & PTE_G) != 0 ? pte | GLOBAL_MARKER : pte;
        }
        throw new TrapException(faultCause, vaddr);
    }

    private void fill(int set, long tag, int asid, long pte, boolean global) {
        int base = set * ways;
        int victim = -1;
        long oldest = Long.MAX_VALUE;

        for (int i = 0; i < ways; i++) {
            int idx = base + i;
            if (valid[idx] == 0) {
                victim = idx;
                break;
            }
            if ((tags[idx] & ~1L) == tag) {
                victim = idx;
                break;
            }
            if (stamps[idx] < oldest) {
                oldest = stamps[idx];
                victim = idx;
            }
        }
        tags[victim] = tag | (global ? 1L : 0L);
        asids[victim] = asid;
        ptes[victim] = pte;
        stamps[victim] = ++clock;
        valid[victim] = 1;
    }

    private static boolean permits(long pte, int access, int privilege, boolean sum, boolean mxr) {
        boolean userPage = (pte & PTE_U) != 0;
        if (privilege == 0) {
            if (!userPage) return false;
        } else if (privilege == 1 && userPage && !sum) {
            return false;
        }
        return switch (access) {
            case ACCESS_EXEC -> (pte & PTE_X) != 0;
            case ACCESS_WRITE -> (pte & PTE_W) != 0;
            default -> (pte & PTE_R) != 0 || (mxr && (pte & PTE_X) != 0);
        };
    }

    // ---- invalidation ----------------------------------------------------------------------

    /** Retires every cached translation. Used on {@code satp} writes and on reset. */
    public void flushAll() {
        Arrays.fill(valid, (byte) 0);
        Arrays.fill(stamps, 0L);
        clock = 0;
    }

    /**
     * Implements {@code sfence.vma}.
     *
     * @param vaddr virtual address to fence, or zero for the whole address space
     * @param asid  address space identifier to fence, or zero for all of them
     */
    public void sfence(long vaddr, long asid) {
        if (vaddr == 0 && asid == 0) {
            flushAll();
            return;
        }
        long vpnMask = vaddr == 0 ? -1L : (vaddr >>> PAGE_SHIFT);
        int asidMask = asid == 0 ? -1 : (int) asid;
        for (int i = 0; i < valid.length; i++) {
            if (valid[i] == 0) continue;
            boolean global = (tags[i] & 1L) != 0;
            if (!global && asidMask != -1 && asids[i] != asidMask) continue;
            if (vpnMask != -1L && ((tags[i] >>> 1) & vpnMask) != vpnMask) continue;
            valid[i] = 0;
        }
    }

    public void reset() {
        flushAll();
    }
}