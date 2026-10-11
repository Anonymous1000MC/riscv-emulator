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
    /**
     * Offset of the first context's registers within the PLIC window, not an absolute address.
     *
     * <p>Every other PLIC constant here is an offset too, and the context registers are reached
     * by comparing an address already made relative to {@link #PLIC_BASE}. This one used to hold
     * the absolute address {@code 0x0200_0000}, which no relative offset within the window can
     * ever reach: the largest is {@code 0x400000}. Every access the hart made to its own threshold,
     * enable and claim registers therefore fell through and was dropped, so no source was ever
     * enabled and the PLIC signalled nothing. The guest's devices completed their work and raised
     * an interrupt that went nowhere, and the kernel blocked forever waiting for it.
     */
    public static final long PLIC_CONTEXT_BASE = 0x0020_0000L;
    /**
     * Bytes per context in the threshold/claim region.
     *
     * <p>These regions are indexed by context, not by hart: a hart owns a machine context and a
     * supervisor context, and they are consecutive.
     */
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
    /**
     * Number of virtio-mmio transports the machine actually instantiates, and therefore the
     * number of {@code virtio_mmio} nodes the device tree may advertise.
     *
     * <p>These have to agree. Advertising a slot that nothing is mapped behind sends the kernel
     * into a load access fault when it probes that address, and an access fault is not delegated
     * to S-mode, so it escalates to M-mode and stops the boot outright.
     *
     * <p>Keep in step with the transports registered in {@code RiscVEngine.mapDevices()}.
     */
    public static final int VIRTIO_SLOT_COUNT = 4;

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