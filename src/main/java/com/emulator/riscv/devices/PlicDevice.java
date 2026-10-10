package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;

/**
 * SiFive platform-level interrupt controller.
 *
 * <p>Register layout follows the published PLIC specification, which is also what Linux's
 * {@code sifive,plic-1.0.0} driver expects:
 *
 * <pre>
 *   +0x000000  priority, four bytes per source
 *   +0x001000  pending bitmask
 *   +0x200000 + hart * 0x1000
 *               +0x0000  threshold
 *               +0x0004  claim (read) / complete (write)
 *               +0x2000  per-hart enable bitmask
 *   +0x2000000 gateway (MSI), not modelled: the guest runs with GIC/APLIC off
 * </pre>
 *
 * <p>Sources are level triggered. A source that has been claimed stays masked out of the pending
 * set until the hart writes it back to the claim register, which is what stops a hart from
 * receiving the same interrupt twice before it has finished handling it.
 */
public final class PlicDevice implements Device, InterruptSource {

    public static final int MAX_SOURCES = 96;
    private static final int MAX_HARTS = 4;
    private static final int PENDING_WORDS = (MAX_SOURCES + 31) / 32;

    private final CsrFile csr;

    private final int[] priority = new int[MAX_SOURCES];
    private final boolean[] asserted = new boolean[MAX_SOURCES];
    private final boolean[] claimed = new boolean[MAX_SOURCES];
    private final int[] pendingWord = new int[PENDING_WORDS];

    private final int[][] enable = new int[MAX_HARTS][PENDING_WORDS];
    private final int[] threshold = new int[MAX_HARTS];
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
            update(0);
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
            update(0);
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

    /** Recomputes whether hart zero should see a supervisor external interrupt. */
    private void update(int hart) {
        int[] en = enable[hart];
        for (int i = 0; i < PENDING_WORDS; i++) {
            if ((pendingWord[i] & en[i]) != 0 && threshold[hart] == 0) {
                csr.setMipBits(1L << 9);
                return;
            }
        }
        csr.clearMipBits(1L << 9);
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
        if (rel >= MemoryMap.PLIC_CONTEXT_BASE) {
            int hart = (int) ((rel - MemoryMap.PLIC_CONTEXT_BASE) / MemoryMap.PLIC_CONTEXT_STRIDE);
            if (hart >= harts) {
                return 0;
            }
            long offset = (rel - MemoryMap.PLIC_CONTEXT_BASE)
                    % MemoryMap.PLIC_CONTEXT_STRIDE;
            if (offset == MemoryMap.PLIC_CONTEXT_THRESHOLD) {
                return threshold[hart];
            }
            if (offset == MemoryMap.PLIC_CONTEXT_CLAIM) {
                return claim(hart);
            }
            if (offset >= MemoryMap.PLIC_ENABLE_BASE && offset < MemoryMap.PLIC_ENABLE_BASE + 0x80) {
                return enable[hart][(int) ((offset - MemoryMap.PLIC_ENABLE_BASE) >>> 2)];
            }
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
        if (rel >= MemoryMap.PLIC_CONTEXT_BASE) {
            int hart = (int) ((rel - MemoryMap.PLIC_CONTEXT_BASE) / MemoryMap.PLIC_CONTEXT_STRIDE);
            if (hart >= harts) {
                return;
            }
            long offset = (rel - MemoryMap.PLIC_CONTEXT_BASE) % MemoryMap.PLIC_CONTEXT_STRIDE;
            if (offset == MemoryMap.PLIC_CONTEXT_THRESHOLD) {
                threshold[hart] = value;
                update(hart);
                return;
            }
            if (offset == MemoryMap.PLIC_CONTEXT_CLAIM) {
                complete(value);
                return;
            }
            if (offset >= MemoryMap.PLIC_ENABLE_BASE && offset < MemoryMap.PLIC_ENABLE_BASE + 0x80) {
                enable[hart][(int) ((offset - MemoryMap.PLIC_ENABLE_BASE) >>> 2)] = value;
                update(hart);
            }
        }
    }

    /** Returns the highest-priority enabled source above the hart's threshold, or zero. */
    private int claim(int hart) {
        int[] en = enable[hart];
        int limit = threshold[hart];
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
            update(hart);
            return source;
        }
        update(hart);
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
        update(0);
    }

    @Override
    public String name() {
        return "plic";
    }
}