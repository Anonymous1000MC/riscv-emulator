package com.emulator.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emulator.riscv.bus.Memory;
import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.RiscVCpu;
import com.emulator.riscv.mmu.MMU;
import com.emulator.riscv.bus.MemoryMap;

/** Shared fixture: a bare machine with no device tree and no firmware, for decoder-level tests. */
public final class TestMachine {

    public static final long RAM_BASE = MemoryMap.RAM_BASE;
    public static final long RAM_BYTES = 16L * 1024 * 1024;
    public static final long CODE = RAM_BASE + 0x1000;
    public static final long DATA = RAM_BASE + 0x40000;

    public final Memory memory;
    public final CsrFile csr;
    public final MMU mmu;
    public final RiscVCpu cpu;

    public TestMachine() {
        this.memory = Memory.allocateRam(RAM_BASE, RAM_BYTES, true);
        this.csr = new CsrFile();
        this.mmu = new MMU(memory, csr, 64, 4);
        this.cpu = new RiscVCpu(memory, mmu, csr);
        csr.setSatpListener(mmu::flushAll);
        cpu.reset(CODE, DATA + 0x10000, 1);
        // A single locked TOR entry spanning the address space, installed after reset because
        // reset clears PMP. This is exactly what the platform firmware programs before handing
        // over to S-mode; without it a hart with no PMP entries would let M-mode through and
        // deny every S-mode access.
        csr.setPmpAddr(0, -1L);
        csr.setPmpCfg(0, 0x8F);
    }

    /** Runs until the instruction pointer leaves the given window, or the budget runs out. */
    public long runUntil(long stopPc, int budget) {
        long total = 0;
        while (total < budget && cpu.pc() != stopPc) {
            cpu.runBlock(Math.min(64, budget - (int) total));
            total += 1;
        }
        return total;
    }

    public void load(int... words) {
        Asm.emit(memory, CODE, words);
    }

    public void loadAt(long address, int... words) {
        Asm.emit(memory, address, words);
    }

    public void loadCompressed(long address, int... halves) {
        Asm.emit16(memory, address, halves);
    }

    public long reg(int index) {
        return cpu.reg(index);
    }

    public void close() {
        memory.close();
    }

    public static void assertRegister(long expected, long actual, String what) {
        assertEquals(expected, actual, what);
    }

    public static void assertNonZero(long value, String what) {
        assertNotEquals(0L, value, what);
        assertTrue(value != 0, what);
    }
}