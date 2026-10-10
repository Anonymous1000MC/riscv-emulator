package com.emulator.riscv.bus;

/**
 * Physical address layout of the virtual machine.
 *
 * <p>The map mirrors the {@code virt} machine that RISC-V Linux is normally tested against so
 * that upstream device trees and bootloaders work unmodified.
 */
public final class MemoryMap {

    private MemoryMap() {
    }

    // ---- devices ---------------------------------------------------------------------------
    /** SiFive test finisher; the legacy QEMU address kept so old images still halt cleanly. */
    public static final long SIFIVE_TEST = 0x0010_0000L;
    public static final long SIFIVE_TEST_SIZE = 0x1000;

    /** Core-local interruptor (timer + software interrupts). */
    public static final long CLINT_BASE = 0x0200_0000L;
    public static final long CLINT_SIZE = 0x0001_0000L;
    public static final long CLINT_MSIP = CLINT_BASE + 0x0000;
    public static final long CLINT_MTIMECMP_HART0 = CLINT_BASE + 0x4000;
    public static final long CLINT_MTIMECMP_LO = CLINT_BASE + 0x4000;
    public static final long CLINT_MTIMECMP_HI = CLINT_BASE + 0x4004;
    public static final long CLINT_MTIME = CLINT_BASE + 0xBFF8;
    public static final long CLINT_MTIME_LO = CLINT_BASE + 0xBFF8;
    public static final long CLINT_MTIME_HI = CLINT_BASE + 0xBFFC;

    /** Platform-level interrupt controller. */
    public static final long PLIC_BASE = 0x0C00_0000L;
    public static final long PLIC_PRIORITY_BASE = 0x0000_0000L;
    public static final long PLIC_PENDING_BASE = 0x0000_1000L;
    public static final long PLIC_ENABLE_BASE = 0x0000_2000L;
    public static final long PLIC_ENABLE_STRIDE = 0x80;
    public static final long PLIC_CONTEXT_BASE = 0x0200_0000L;
    public static final long PLIC_CONTEXT_STRIDE = 0x1000;
    public static final long PLIC_CONTEXT_THRESHOLD = 0x00;
    public static final long PLIC_CONTEXT_CLAIM = 0x04;

    /** NS16550A console. */
    public static final long UART_BASE = 0x1000_0000L;
    public static final long UART_SIZE = 0x100;
    public static final int UART_IRQ = 10;

    /** RTC (Goldfish style, as exposed by the {@code virt} machine). */
    public static final long RTC_BASE = 0x0101_0000L;
    public static final long RTC_SIZE = 0x1000;
    public static final int RTC_IRQ = 11;

    /** VirtIO-MMIO transport. Each device occupies one 4 KiB slot. */
    public static final long VIRTIO_BASE = 0x1000_1000L;
    public static final long VIRTIO_STRIDE = 0x1000;
    public static final int VIRTIO_IRQ_BASE = 1;
    public static final int VIRTIO_SLOT_COUNT = 8;

    /** Block device slot. */
    public static final long VIRTIO_BLK_BASE = VIRTIO_BASE;
    /** Framebuffer / display slot. */
    public static final long VIRTIO_FB_BASE = VIRTIO_BASE + VIRTIO_STRIDE;
    /** Keyboard and mouse slot. */
    public static final long VIRTIO_INPUT_BASE = VIRTIO_BASE + 2 * VIRTIO_STRIDE;
    /** Entropy slot. */
    public static final long VIRTIO_RNG_BASE = VIRTIO_BASE + 3 * VIRTIO_STRIDE;
    /** Optional second block device used for swap or a scratch overlay. */
    public static final long VIRTIO_BLK2_BASE = VIRTIO_BASE + 4 * VIRTIO_STRIDE;

    // ---- main memory ----------------------------------------------------------------------
    public static final long RAM_BASE = 0x8000_0000L;

    /** Highest physical address the machine decodes; RV64 physical addresses are 56 bits wide. */
    public static final long PHYS_ADDR_MASK = 0x00FF_FFFF_FFFF_FFFFL;

    /**
     * The framebuffer lives inside the main RAM arena so that virtio-framebuffer DMA from the
     * guest is an ordinary physical write. It is carved out of the top of RAM and is routed
     * through {@link com.emulator.riscv.devices.FramebufferDevice} so dirty tiles can be
     * tracked without adding work to the ordinary RAM path.
     */
    public static long framebufferBase(long ramBase, long ramSize, long fbSize) {
        long end = ramBase + ramSize;
        long base = (end - fbSize) & ~0xFFFL;
        return base;
    }

    /** Total bytes covered by the decoded physical address space. */
    public static long addressSpaceSize() {
        return PHYS_ADDR_MASK + 1;
    }
}