package com.emulator.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emulator.riscv.bus.Memory;
import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.TrapException;
import com.emulator.riscv.mmu.MMU;
import org.junit.jupiter.api.Test;

/**
 * Page table walks.
 *
 * <p>These cases exist because Sv39/Sv48 translation had no coverage at all, and three real bugs
 * were sitting in {@link MMU#walk}: the root PPN was masked from the wrong bits of {@code satp}, a
 * pointer PTE overwrote the virtual address instead of the base table, and a superpage leaf did not
 * carry the virtual address's superpage bits into the PPN. Together they meant no paging
 * translation could ever succeed, which is invisible until a Linux kernel switches {@code satp} on.
 */
class MmuTest {

    private static final long RAM_BASE = 0x8000_0000L;
    private static final long RAM_BYTES = 256L * 1024 * 1024;

    private static final long R = 1L << 1;
    private static final long W = 1L << 2;
    private static final long X = 1L << 3;
    private static final long A = 1L << 6;
    private static final long D = 1L << 7;

    /** satp holds the root PPN in bits 43:0, so the root table lives at {@code ppn << 12}. */
    private static long satp(int mode, long rootPpn) {
        return ((long) mode << 60) | (rootPpn & 0xF_FFFF_FFFFL);
    }

    private static long pointer(long ppn) {
        return (ppn << 10) | 1L;
    }

    private static long leaf(long ppn, long flags) {
        return (ppn << 10) | flags | 1L;
    }

    private final Memory memory = Memory.allocateRam(RAM_BASE, RAM_BYTES, true);
    private final CsrFile csr = new CsrFile();
    private final MMU mmu = new MMU(memory, csr, 64, 4);

    private long translate(long va) {
        return mmu.translate(va, MMU.ACCESS_READ, 1, false, false);
    }

    @Test
    void sv48WalksAPointerChainToA4KiBLeaf() {
        // root -> l2 -> l1 -> l0. A pointer must replace the table being read, never the virtual
        // address, or every level below the first pointer indexes from the wrong VPN.
        long root = 0x8_0100L, l2 = 0x8_0200L, l1 = 0x8_0300L, l0 = 0x8_0400L;
        memory.write64(root << 12, pointer(l2));
        memory.write64(l2 << 12, pointer(l1));
        memory.write64(l1 << 12, pointer(l0));
        memory.write64(l0 << 12, leaf(0x8_5000L, R | W | A | D));

        csr.rawWrite(CsrFile.SATP, satp(9, root));
        long va = 0x0000_0000_0000_0000L;
        assertEquals(0x8_5000L << 12, translate(va), "four level walk to a 4KiB leaf");

        // A different page in the same tables must translate independently.
        memory.write64(l0 << 12 | (1 * 8), leaf(0x8_6000L, R | W | A | D));
        assertEquals(0x8_6000L << 12, translate(0x1000), "sibling page under the same l0 table");
    }

    @Test
    void sv39WalksAPointerChainToA4KiBLeaf() {
        long root = 0x8_0100L, l1 = 0x8_0300L, l0 = 0x8_0400L;
        memory.write64(root << 12, pointer(l1));
        memory.write64(l1 << 12, pointer(l0));
        memory.write64(l0 << 12, leaf(0x8_5000L, R | W | A | D));

        csr.rawWrite(CsrFile.SATP, satp(8, root));
        assertEquals(0x8_5000L << 12, translate(0L), "three level walk to a 4KiB leaf");
    }

    @Test
    void rootPpnUsesAllOfBits43To0() {
        // satp's PPN field is bits 43:0 and the root address is that field shifted left by 12.
        // Masking bits 43:12 instead throws away the low 12 bits of the PPN, which silently
        // relocated every root table and made the walk read unmapped memory.
        long root = 0x8_1605L;
        memory.write64(root << 12, leaf(0x8_5000L, R | W | A | D));
        csr.rawWrite(CsrFile.SATP, satp(9, root));
        assertEquals(0x8_5000L << 12, translate(0L),
                "root PPN 0x81605 must place the root table at 0x81605000");
    }

    @Test
    void superpageCarriesTheVirtualAddressIntoThePpn() {
        // A 2MiB leaf ignores the virtual address between the page offset and its level, so those
        // bits have to be carried into the PPN. compose() only ORs in the 12 bit page offset, so
        // without this every address inside the superpage resolved to the superpage's first page.
        long root = 0x8_0100L, l2 = 0x8_0200L, l1 = 0x8_0300L;
        memory.write64(root << 12, pointer(l2));
        memory.write64(l2 << 12 | (2 * 8), pointer(l1));   // these addresses sit under vpn2 = 2
        // 2MiB superpage at aligned ppn 0x81400, i.e. covering 0x81400000-0x815fffff.
        memory.write64(l1 << 12 | (10 * 8), leaf(0x8_1400L, R | W | X | A | D));

        csr.rawWrite(CsrFile.SATP, satp(9, root));
        for (long va : new long[] {0x8140_0000L, 0x8140_05c0L, 0x8141_2340L}) {
            assertEquals(va, translate(va), "superpage is a VA==PA mapping");
        }
        assertThrows(TrapException.class, () -> translate(0x8160_0000L),
                "just past the superpage must fault");
    }

    @Test
    void unmappedAddressReportsAPageFaultNotALoadFault() {
        // A failed walk must surface as a page fault naming the virtual address. Letting the
        // internal PTE read escape reports a load fault naming a physical address instead, which is
        // what made this look like a device access problem rather than a paging one.
        long root = 0x8_0100L;
        memory.write64(root << 12, pointer(0x8_0200L));
        memory.write64(0x8_0200L << 12, pointer(0x8_0300L));
        memory.write64(0x8_0300L << 12, pointer(0x8_0400L));
        memory.write64(0x8_0400L << 12, 0L);   // invalid leaf

        csr.rawWrite(CsrFile.SATP, satp(9, root));
        TrapException fault = assertThrows(TrapException.class, () -> translate(0x2000L));
        assertEquals(TrapException.LOAD_PAGE_FAULT, fault.cause,
                "an unmapped virtual address is a page fault");
        assertTrue(fault.value == 0x2000L, "the fault names the virtual address, got " + fault.value);
    }

    @Test
    void bareModeBypassesTranslation() {
        csr.rawWrite(CsrFile.SATP, satp(0, 0));
        assertEquals(0x8123_4567L, translate(0x8123_4567L), "bare mode passes the address through");
    }
}