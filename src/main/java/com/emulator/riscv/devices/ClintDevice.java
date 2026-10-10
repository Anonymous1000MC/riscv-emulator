package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;

/**
 * SiFive core-local interruptor.
 *
 * <p>Provides the machine software interrupt, the per-hart timer compare register and the
 * free-running 64-bit {@code mtime} counter. Linux treats {@code mtime} as its wall clock, so it
 * is derived from {@link System#nanoTime()} rather than counted by retired instructions: the two
 * have wildly different rates and tying the clock to instruction retirement would make the
 * guest's notion of time depend on how much work it is doing.
 */
public final class ClintDevice implements Device {

    private final CsrFile csr;
    private final long timeFrequency;
    private final long nanoBase;

    private final long[] mtimecmp = new long[1];
    private final int[] msip = new int[1];

    public ClintDevice(CsrFile csr, long timeFrequency) {
        this.csr = csr;
        this.timeFrequency = timeFrequency;
        this.nanoBase = System.nanoTime();
    }

    public long timeFrequency() {
        return timeFrequency;
    }

    /** Current value of {@code mtime}, in units of {@link #timeFrequency()}. */
    public long time() {
        long delta = System.nanoTime() - nanoBase;
        if (delta < 0) {
            delta = 0;
        }
        return (delta / 1_000_000_000L) * timeFrequency
                + (delta % 1_000_000_000L) * timeFrequency / 1_000_000_000L;
    }

    public long mtimecmp(int hart) {
        return mtimecmp[hart];
    }

    @Override
    public int read(int size, long pa) {
        if (size != 8 && size != 4) {
            return 0;
        }
        long rel = pa - MemoryMap.CLINT_BASE;
        if (rel < 0x4000L) {
            return msip[0];
        }
        if (rel < 0xBFF8L) {
            return size == 8 ? (int) mtimecmp[0] : (int) (mtimecmp[0] >>> 32);
        }
        return size == 8 ? (int) time() : (int) (time() >>> 32);
    }

    @Override
    public void write(int size, long pa, int value) {
        if (size != 8 && size != 4) {
            return;
        }
        long rel = pa - MemoryMap.CLINT_BASE;
        if (rel < 0x4000L) {
            msip[0] = value;
            if (value != 0) {
                csr.setMipBits(1L << 3);
            } else {
                csr.clearMipBits(1L << 3);
            }
            return;
        }
        if (rel < 0xBFF8L) {
            // The compare register is 64 bit wide and assembled from two 32 bit accesses.
            long current = mtimecmp[0];
            if ((rel & 4) == 0) {
                mtimecmp[0] = (current & 0xFFFF_FFFF_0000_0000L) | (value & 0xFFFF_FFFFL);
            } else {
                mtimecmp[0] = (current & 0xFFFF_FFFFL)
                        | ((long) value << 32 & 0xFFFF_FFFF_0000_0000L);
            }
        }
    }

    @Override
    public void tick() {
        if (time() >= mtimecmp[0]) {
            csr.setMipBits(1L << 7);
        } else {
            csr.clearMipBits(1L << 7);
        }
    }

    /** Clears the timer interrupt explicitly, as {@code mtimecmp} writes require. */
    public void refreshTimerInterrupt() {
        tick();
    }

    @Override
    public String name() {
        return "clint";
    }
}