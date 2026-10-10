package com.emulator.riscv.bus;

import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.mmu.MMU;

/**
 * Address-space facade shared by the CPU and the device models.
 *
 * <p>Two families of accessors live here on purpose:
 *
 * <ul>
 *   <li>{@code *Phy} entry points take a physical address. Devices use them for DMA, and so does
 *       the boot loader when it plants the firmware and the device tree into RAM.
 *   <li>{@code *Virt} entry points translate first. They are the general-purpose form used by
 *       device code and the firmware.
 * </ul>
 *
 * <p>The interpreter does <em>not</em> route its own loads and stores through these methods. It
 * inlines the same sequence directly against the final {@link MMU} and {@link Memory} fields so
 * that HotSpot can keep the register file in a local across the whole execution block. The two
 * implementations below are the readable reference that the inlined version matches.
 */
public final class Bus {

    private final Memory memory;
    private final MMU mmu;

    public Bus(Memory memory, MMU mmu) {
        this.memory = memory;
        this.mmu = mmu;
    }

    public Memory memory() {
        return memory;
    }

    public MMU mmu() {
        return mmu;
    }

    public CsrFile csr() {
        return mmu.csrFile();
    }

    // ---- physical access -------------------------------------------------------------------

    public long read64Phy(long pa) {
        return memory.read64(pa);
    }

    public int read32Phy(long pa) {
        return memory.read32(pa);
    }

    public short read16Phy(long pa) {
        return memory.read16(pa);
    }

    public byte read8Phy(long pa) {
        return memory.read8(pa);
    }

    public void write64Phy(long pa, long value) {
        memory.write64(pa, value);
    }

    public void write32Phy(long pa, int value) {
        memory.write32(pa, value);
    }

    public void write16Phy(long pa, short value) {
        memory.write16(pa, value);
    }

    public void write8Phy(long pa, byte value) {
        memory.write8(pa, value);
    }

    // ---- virtual access --------------------------------------------------------------------

    public long read64Virt(long vaddr, int privilege, boolean sum, boolean mxr) {
        return memory.read64(mmu.translate(vaddr, MMU.ACCESS_READ, privilege, sum, mxr));
    }

    public int read32Virt(long vaddr, int privilege, boolean sum, boolean mxr) {
        return memory.read32(mmu.translate(vaddr, MMU.ACCESS_READ, privilege, sum, mxr));
    }

    public short read16Virt(long vaddr, int privilege, boolean sum, boolean mxr) {
        return memory.read16(mmu.translate(vaddr, MMU.ACCESS_READ, privilege, sum, mxr));
    }

    public byte read8Virt(long vaddr, int privilege, boolean sum, boolean mxr) {
        return memory.read8(mmu.translate(vaddr, MMU.ACCESS_READ, privilege, sum, mxr));
    }

    public void write64Virt(long vaddr, long value, int privilege, boolean sum, boolean mxr) {
        memory.write64(mmu.translate(vaddr, MMU.ACCESS_WRITE, privilege, sum, mxr), value);
    }

    public void write32Virt(long vaddr, int value, int privilege, boolean sum, boolean mxr) {
        memory.write32(mmu.translate(vaddr, MMU.ACCESS_WRITE, privilege, sum, mxr), value);
    }

    public void write16Virt(long vaddr, short value, int privilege, boolean sum, boolean mxr) {
        memory.write16(mmu.translate(vaddr, MMU.ACCESS_WRITE, privilege, sum, mxr), value);
    }

    public void write8Virt(long vaddr, byte value, int privilege, boolean sum, boolean mxr) {
        memory.write8(mmu.translate(vaddr, MMU.ACCESS_WRITE, privilege, sum, mxr), value);
    }

    // ---- DMA helpers -----------------------------------------------------------------------

    /** Copies guest memory into a Java array, used by virtio device models. */
    public void dmaRead(long pa, byte[] dst, int dstIndex, int length) {
        if (length <= 0) {
            return;
        }
        long offset = pa - memory.ramBase();
        if (offset >= 0 && offset + length <= memory.ramSize()) {
            memory.arena().read(offset, dst, dstIndex, length);
            return;
        }
        for (int i = 0; i < length; i++) {
            dst[dstIndex + i] = memory.read8(pa + i);
        }
    }

    /** Copies a Java array into guest memory. */
    /**
     * Copies bytes into guest memory on behalf of a device or the firmware.
     *
     * @return {@code true} if the whole range was written, {@code false} if any part of it is not
     *     addressable. Reporting failure instead of throwing matters because the callers are all
     *     servicing guest-supplied pointers: a guest that hands the firmware an unwritable address
     *     is entitled to an error code, and a {@link com.emulator.riscv.cpu.TrapException} escaping
     *     here kills the hart outright.
     */
    public boolean dmaWrite(byte[] src, int srcIndex, int length, long pa) {
        if (length <= 0) {
            return true;
        }
        long offset = pa - memory.ramBase();
        if (offset >= 0 && offset + length <= memory.ramSize()) {
            memory.arena().write(offset, src, srcIndex, length);
            return true;
        }
        for (int i = 0; i < length; i++) {
            if (!memory.isWritable(pa + i)) {
                return false;
            }
            memory.write8(pa + i, src[srcIndex + i]);
        }
        return true;
    }

    /** Services device timers and other deferred work. */
    public void tick() {
        memory.tickDevices();
    }
}