package com.emulator.riscv.firmware;

import com.emulator.riscv.Theon;
import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;

/**
 * Builds the flattened device tree that describes this machine to the guest.
 *
 * <p>The node layout deliberately matches QEMU's {@code virt} board, node for node, so that an
 * unmodified RISC-V kernel finds the interrupt controller, the console and the virtio transports
 * exactly where it expects them. Address cells are two everywhere in the root and {@code /soc},
 * matching the real board.
 */
public final class DeviceTreeBuilder {

    private final long ramBase;
    private final long ramSize;
    private final long framebufferBase;
    private final long framebufferSize;
    private final long timebaseFrequency;
    private final long clockFrequency;
    private final int hartCount;
    private final String bootargs;
    private final boolean sv39;

    /** Set when an initrd is supplied; the kernel is told about it through {@code /chosen}. */
    private long initrdStart;
    private long initrdEnd;

    public DeviceTreeBuilder(long ramBase, long ramSize, long framebufferBase, long framebufferSize,
                             long timebaseFrequency, long clockFrequency, int hartCount,
                             String bootargs, boolean sv39) {
        this.ramBase = ramBase;
        this.ramSize = ramSize;
        this.framebufferBase = framebufferBase;
        this.framebufferSize = framebufferSize;
        this.timebaseFrequency = timebaseFrequency;
        this.clockFrequency = clockFrequency;
        this.hartCount = Math.max(1, hartCount);
        this.bootargs = bootargs == null ? "" : bootargs;
        this.sv39 = sv39;
    }

    /** Declares an initrd so the kernel mounts it before touching the root filesystem. */
    public DeviceTreeBuilder initrd(long start, long end) {
        this.initrdStart = start;
        this.initrdEnd = end;
        return this;
    }

    public byte[] build() {
        DeviceTree dt = new DeviceTree();

        int phandleRoot = dt.newPhandle();
        int[] phandleCpuIntc = new int[hartCount];
        int[] phandleCpu = new int[hartCount];
        for (int i = 0; i < hartCount; i++) {
            phandleCpuIntc[i] = dt.newPhandle();
            phandleCpu[i] = dt.newPhandle();
        }
        int phandleClicIntc = dt.newPhandle();
        int phandlePlic = dt.newPhandle();
        int phandleUart = dt.newPhandle();
        int phandleFramebuffer = dt.newPhandle();
        int phandleInitrd = dt.newPhandle();

        // ---- root ---------------------------------------------------------------------------
        dt.beginNode("");
        dt.propertyU32("#address-cells", 2);
        dt.propertyU32("#size-cells", 2);
        dt.propertyStrings("compatible", Theon.COMPATIBLE, Theon.COMPATIBLE_PLATFORM);
        dt.propertyString("model", Theon.MODEL);
        dt.propertyU32("phandle", phandleRoot);

        // ---- chosen -------------------------------------------------------------------------
        dt.beginNode("chosen");
        if (bootargs.isEmpty()) {
            dt.propertyString("bootargs", "console=ttyS0,115200 earlycon");
        } else {
            dt.propertyString("bootargs", bootargs);
        }
        dt.propertyString("stdout-path", "/soc/serial@" + Long.toHexString(MemoryMap.UART_BASE));
        dt.endNode();

        // ---- aliases ------------------------------------------------------------------------
        dt.beginNode("aliases");
        dt.propertyU32("serial0", phandleUart);
        dt.endNode();

        // ---- memory -------------------------------------------------------------------------
        dt.beginNode("memory@" + Long.toHexString(ramBase));
        dt.propertyString("device_type", "memory");
        dt.propertyReg(ramBase, ramSize, 2, 2);
        dt.endNode();

        // ---- reserved memory ----------------------------------------------------------------
        dt.beginNode("reserved-memory");
        dt.propertyU32("#address-cells", 2);
        dt.propertyU32("#size-cells", 2);
        dt.propertyU32("#alloc-ranges-size", 0);

        // The framebuffer is ordinary RAM as far as virtio is concerned, but nothing else should
        // be allowed to hand it to the page allocator.
        dt.beginNode("framebuffer@" + Long.toHexString(framebufferBase));
        dt.propertyString("compatible", "reserved-memory");
        dt.propertyReg(framebufferBase, framebufferSize, 2, 2);
        dt.propertyBoolean("no-map", true);
        dt.propertyU32("phandle", phandleFramebuffer);
        dt.endNode();

        if (initrdEnd > initrdStart) {
            dt.reserve(initrdStart, initrdEnd - initrdStart);
            dt.beginNode("initrd@" + Long.toHexString(initrdStart));
            dt.propertyString("compatible", "reserved-memory");
            dt.propertyReg(initrdStart, initrdEnd - initrdStart, 2, 2);
            dt.propertyU32("phandle", phandleInitrd);
            dt.endNode();
        }
        dt.endNode();

        // ---- cpus ---------------------------------------------------------------------------
        dt.beginNode("cpus");
        dt.propertyU32("#address-cells", 1);
        dt.propertyU32("#size-cells", 0);
        dt.propertyU32("timebase-frequency", timebaseFrequency);

        dt.beginNode("cpu-map");
        dt.beginNode("socket0");
        for (int i = 0; i < hartCount; i++) {
            dt.beginNode("core" + i);
            dt.propertyU32("cpu", phandleCpu[i]);
            dt.endNode();
        }
        dt.endNode();
        dt.endNode();

        for (int i = 0; i < hartCount; i++) {
            dt.beginNode("cpu@" + i);
            dt.propertyString("device_type", "cpu");
            dt.propertyString("compatible", "riscv");
            dt.propertyString("status", "okay");
            dt.propertyU32("reg", i);
            dt.propertyU32("clock-frequency", clockFrequency);
            dt.propertyString("mmu-type", sv39 ? "riscv,sv39" : "riscv,sv48");
            // The kernel derives its compiled-in ISA expectations from this string, so it has to
            // name exactly what the interpreter decodes. Claiming vector here would make Linux emit
            // instructions this core rejects as illegal; floating point is now genuinely decoded,
            // so it is claimed.
            dt.propertyString("riscv,isa", "rv64gc");
            dt.propertyString("riscv,isa-base", "rv64imafdc");
            dt.propertyStrings("riscv,isa-extensions",
                    "s", "u", "zicsr", "zifencei", "zihintpause", "zihintntl", "zihintnopval");
            dt.propertyU32("riscv,t", CsrFile.MISA_VALUE);
            dt.propertyU32("phandle", phandleCpu[i]);

            dt.beginNode("interrupt-controller");
            dt.propertyString("compatible", "riscv,cpu-intc");
            dt.propertyEmpty("interrupt-controller");
            dt.propertyU32("#interrupt-cells", 1);
            dt.propertyU32("phandle", phandleCpuIntc[i]);
            dt.endNode();
            dt.endNode();
        }
        dt.endNode();

        // ---- soc ----------------------------------------------------------------------------
        dt.beginNode("soc");
        dt.propertyU32("#address-cells", 2);
        dt.propertyU32("#size-cells", 2);
        dt.propertyEmpty("ranges");

        // Test finisher, so poweroff and reboot work.
        dt.beginNode("test@" + Long.toHexString(MemoryMap.SIFIVE_TEST));
        dt.propertyString("compatible", "sifive,test,finisher");
        dt.propertyReg(MemoryMap.SIFIVE_TEST, MemoryMap.SIFIVE_TEST_SIZE, 2, 2);
        dt.endNode();

        // Goldfish-style RTC.
        dt.beginNode("rtc@" + Long.toHexString(MemoryMap.RTC_BASE));
        dt.propertyString("compatible", "goldfish-rtc");
        dt.propertyReg(MemoryMap.RTC_BASE, MemoryMap.RTC_SIZE, 2, 2);
        dt.propertyU32("interrupt-parent", phandlePlic);
        dt.propertyU32("interrupts", MemoryMap.RTC_IRQ);
        dt.endNode();

        // Core local interruptor: machine software interrupt is interrupt 3 on the CPU intc.
        dt.beginNode("interrupt-controller@" + Long.toHexString(MemoryMap.CLINT_BASE));
        dt.propertyStrings("compatible", "sifive,clint0.9", "sifive,clint1.0");
        dt.propertyReg(MemoryMap.CLINT_BASE, MemoryMap.CLINT_SIZE, 2, 2);
        dt.propertyEmpty("interrupt-controller");
        dt.propertyU32("#interrupt-cells", 1);
        int[] extended = new int[hartCount * 2];
        for (int i = 0; i < hartCount; i++) {
            extended[i * 2] = phandleCpuIntc[i];
            extended[i * 2 + 1] = 3;
        }
        dt.property("interrupts-extended", extended);
        dt.propertyU32("phandle", phandleClicIntc);
        dt.endNode();

        // Platform level interrupt controller, two interrupt cells: source then hart context.
        dt.beginNode("interrupt-controller@" + Long.toHexString(MemoryMap.PLIC_BASE));
        dt.propertyStrings("compatible", "sifive,plic-1.0.0", "riscv,plic0");
        dt.propertyReg(MemoryMap.PLIC_BASE,
                MemoryMap.PLIC_CONTEXT_BASE + (long) hartCount * MemoryMap.PLIC_CONTEXT_STRIDE,
                2, 2);
        dt.propertyEmpty("interrupt-controller");
        dt.propertyU32("#interrupt-cells", 1);
        dt.propertyU32("#address-cells", 0);
        int[] plicTargets = new int[hartCount * 2];
        for (int i = 0; i < hartCount; i++) {
            plicTargets[i * 2] = phandleCpuIntc[i];
            plicTargets[i * 2 + 1] = 7 + i;
        }
        dt.property("interrupts-extended", plicTargets);
        dt.propertyU32("riscv,ndev", 96);
        dt.propertyU32("phandle", phandlePlic);
        dt.endNode();

        // NS16550A console.
        dt.beginNode("serial@" + Long.toHexString(MemoryMap.UART_BASE));
        dt.propertyString("compatible", "ns16550a");
        dt.propertyReg(MemoryMap.UART_BASE, MemoryMap.UART_SIZE, 2, 2);
        dt.propertyU32("clock-frequency", 24_000_000);
        dt.propertyU32("interrupt-parent", phandlePlic);
        dt.propertyU32("interrupts", MemoryMap.UART_IRQ);
        dt.propertyU32("phandle", phandleUart);
        dt.endNode();

        // Virtio-mmio transports, one node per 4 KiB slot.
        for (int slot = 0; slot < MemoryMap.VIRTIO_SLOT_COUNT; slot++) {
            long address = MemoryMap.VIRTIO_BASE + (long) slot * MemoryMap.VIRTIO_STRIDE;
            dt.beginNode("virtio_mmio@" + Long.toHexString(address));
            dt.propertyString("compatible", "virtio,mmio");
            dt.propertyReg(address, MemoryMap.VIRTIO_STRIDE, 2, 2);
            dt.propertyU32("interrupt-parent", phandlePlic);
            dt.propertyU32("interrupts", MemoryMap.VIRTIO_IRQ_BASE + slot);
            if (slot == 1) {
                // The framebuffer device owns a slice of RAM; tell the kernel where it is so the
                // reserved-memory node above and the device agree.
                dt.propertyU32("memory-region", phandleFramebuffer);
            }
            dt.endNode();
        }

        dt.endNode(); // /soc
        dt.endNode(); // /

        return dt.build();
    }
}