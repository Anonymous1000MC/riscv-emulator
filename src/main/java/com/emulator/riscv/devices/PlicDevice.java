package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;

/**
 * SiFive platform-level interrupt controller.
 *
 * <p>Register layout follows Linux's {@code sifive,plic-1.0.0} driver, which is what decides
 * where a guest actually writes:
 *
 * <pre>
 *   +0x000000  priority, four bytes per source
 *   +0x001000  pending bitmask
 *   +0x002000 + context * 0x80
 *               enable bitmask for that context
 *   +0x200000 + context * 0x1000
 *               +0x0000  threshold
 *               +0x0004  claim (read) / complete (write)
 * </pre>
 *
 * <p>The important subtlety is that both regions are indexed by <em>context</em>, not by hart.
 * A hart owns two contexts - a machine one and a supervisor one - and they are consecutive, so
 * on a single-hart machine context 0 is machine and context 1 is supervisor. The enable bitmaps
 * are <em>not</em> inside the context block; they sit in their own region at 0x2000, eighty
 * bytes apart. Addressing them as if they were part of the context block silently drops every
 * enable write, and since no source is ever enabled the controller signals nothing at all.
 *
 * <p>Sources are level triggered. A source that has been claimed stays masked out of the pending
 * set until the hart writes it back to the claim register, which is what stops a hart from
 * receiving the same interrupt twice before it has finished handling it.
 */
public final class PlicDevice implements Device, InterruptSource {

    public static final int MAX_SOURCES = 96;
    private static final int MAX_HARTS = 4;
    private static final int MAX_CONTEXTS = MAX_HARTS * 2;
    private static final int PENDING_WORDS = (MAX_SOURCES + 31) / 32;

    /** Bytes of enable bitmap per context. Two words covers the 96 sources. */
    private static final int ENABLE_STRIDE = 0x80;

    /** Supervisor external interrupt, {@code mip.SEIP}. */
    private static final int MIP_SEIP = 9;

    private final CsrFile csr;

    private final int[] priority = new int[MAX_SOURCES];
    private final boolean[] asserted = new boolean[MAX_SOURCES];
    private final boolean[] claimed = new boolean[MAX_SOURCES];
    private final int[] pendingWord = new int[PENDING_WORDS];

    private final int[][] enable = new int[MAX_CONTEXTS][PENDING_WORDS];
    private final int[] threshold = new int[MAX_CONTEXTS];
    private final int harts;

    public PlicDevice(CsrFile csr, int hartCount) {
        this.csr = csr;
        this.harts = Math.max(1, Math.min(hartCount, MAX_HARTS));
    }

    @Override
    public void assertSource(int source) {
        if (source <= 0 || source >= MAX_SOURCES || asserted[source]) {
            return;
        }
        asserted[source] = true;
        if (!claimed[source]) {
            setPending(source, true);
            updateAll();
        }
    }

    @Override
    public void deassertSource(int source) {
        if (source <= 0 || source >= MAX_SOURCES) {
            return;
        }
        asserted[source] = false;
        if (!claimed[source]) {
            setPending(source, false);
            updateAll();
        }
    }

    private void setPending(int source, boolean value) {
        int word = source >>> 5;
        int bit = 1 << (source & 31);
        if (value) {
            pendingWord[word] |= bit;
        } else {
            pendingWord[word] &= ~bit;
        }
    }

    private boolean isPending(int source) {
        return (pendingWord[source >>> 5] & (1 << (source & 31))) != 0;
    }

    /** The supervisor context of hart zero, which is where this single-hart guest runs. */
    private int supervisorContext() {
        return 1;
    }

    /** Recomputes the external interrupt bits for every context that exists on this machine. */
    private boolean anyPending;

    private void updateAll() {
        anyPending = false;
        for (int context = 0; context < harts * 2; context++) {
            update(context);
        }
        apply();
    }

    /**
     * Recomputes the external interrupt line for one context.
     *
     * <p>Odd contexts are supervisor contexts and drive {@code SEIP}; even ones are machine
     * contexts and drive {@code MEIP}. Both are level signals that stay asserted while any
     * enabled source is pending above the context threshold.
     */
    private void update(int context) {
        int[] en = enable[context];
        boolean any = false;
        for (int i = 0; i < PENDING_WORDS; i++) {
            if ((pendingWord[i] & en[i]) != 0 && threshold[context] == 0) {
                any = true;
                break;
            }
        }
        if (any) {
            anyPending = true;
        }
    }

    /**
     * Applies the result of {@link #update} to the CSRs.
     *
     * <p>Signalled as a supervisor external interrupt regardless of which context was enabled.
     * Linux's PLIC driver wires only the machine external interrupt and deliberately disables the
     * supervisor enable bits, leaving forwarding to the machine-mode firmware underneath. This
     * guest has no such firmware - it boots straight into S-mode - so the signal has to be
     * presented as {@code SEIP} to reach it. Raising {@code MEIP} instead would be worse than
     * useless: it is not delegated, so the hart would trap to machine mode, where
     * {@code mtvec} is a parking loop.
     */
    private void apply() {
        if (anyPending) {
            csr.setMipBits(1L << MIP_SEIP);
        } else {
            csr.clearMipBits(1L << MIP_SEIP);
        }
    }

    @Override
    public int read(int size, long pa) {
        long rel = pa - MemoryMap.PLIC_BASE;
        if (rel < 0x1000L) {
            int id = (int) (rel >>> 2);
            return id < MAX_SOURCES ? priority[id] : 0;
        }
        if (rel < 0x2000L) {
            int word = (int) (rel >>> 2);
            return word < PENDING_WORDS ? pendingWord[word] : 0;
        }
        // Enable bitmaps, 0x80 apart, indexed by context.
        if (rel < MemoryMap.PLIC_CONTEXT_BASE) {
            int context = (int) ((rel - MemoryMap.PLIC_ENABLE_BASE) / ENABLE_STRIDE);
            if (context < 0 || context >= harts * 2) {
                return 0;
            }
            int word = (int) (((rel - MemoryMap.PLIC_ENABLE_BASE) % ENABLE_STRIDE) >>> 2);
            return word < PENDING_WORDS ? enable[context][word] : 0;
        }
        // Threshold and claim, one page per context.
        int context = (int) ((rel - MemoryMap.PLIC_CONTEXT_BASE) / MemoryMap.PLIC_CONTEXT_STRIDE);
        if (context >= harts * 2) {
            return 0;
        }
        long offset = (rel - MemoryMap.PLIC_CONTEXT_BASE) % MemoryMap.PLIC_CONTEXT_STRIDE;
        if (offset == MemoryMap.PLIC_CONTEXT_THRESHOLD) {
            return threshold[context];
        }
        if (offset == MemoryMap.PLIC_CONTEXT_CLAIM) {
            return claim(context);
        }
        return 0;
    }

    @Override
    public void write(int size, long pa, int value) {
        long rel = pa - MemoryMap.PLIC_BASE;
        if (rel < 0x1000L) {
            int id = (int) (rel >>> 2);
            if (id < MAX_SOURCES) {
                priority[id] = value;
            }
            return;
        }
        // Enable bitmaps, 0x80 apart, indexed by context.
        if (rel < MemoryMap.PLIC_CONTEXT_BASE) {
            int context = (int) ((rel - MemoryMap.PLIC_ENABLE_BASE) / ENABLE_STRIDE);
            if (context < 0 || context >= harts * 2) {
                return;
            }
            int word = (int) (((rel - MemoryMap.PLIC_ENABLE_BASE) % ENABLE_STRIDE) >>> 2);
            if (word < PENDING_WORDS) {
                enable[context][word] = value;
                updateAll();
            }
            return;
        }
        // Threshold and claim, one page per context.
        int context = (int) ((rel - MemoryMap.PLIC_CONTEXT_BASE) / MemoryMap.PLIC_CONTEXT_STRIDE);
        if (context >= harts * 2) {
            return;
        }
        long offset = (rel - MemoryMap.PLIC_CONTEXT_BASE) % MemoryMap.PLIC_CONTEXT_STRIDE;
        if (offset == MemoryMap.PLIC_CONTEXT_THRESHOLD) {
            threshold[context] = value;
            updateAll();
        } else if (offset == MemoryMap.PLIC_CONTEXT_CLAIM) {
            complete(value);
        }
    }

    /** Returns the highest-priority enabled source above the context's threshold, or zero. */
    private int claim(int context) {
        int[] en = enable[context];
        int limit = threshold[context];
        for (int source = 1; source < MAX_SOURCES; source++) {
            if (!isPending(source)) {
                continue;
            }
            if ((en[source >>> 5] & (1 << (source & 31))) == 0) {
                continue;
            }
            if (limit > 0 && priority[source] < limit) {
                continue;
            }
            setPending(source, false);
            claimed[source] = true;
            updateAll();
            return source;
        }
        updateAll();
        return 0;
    }

    private void complete(int source) {
        if (source <= 0 || source >= MAX_SOURCES) {
            return;
        }
        claimed[source] = false;
        if (asserted[source]) {
            setPending(source, true);
        }
        updateAll();
    }

    @Override
    public String name() {
        return "plic";
    }
}