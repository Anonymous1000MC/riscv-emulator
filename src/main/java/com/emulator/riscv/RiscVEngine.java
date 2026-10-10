package com.emulator.riscv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.emulator.riscv.bus.Bus;
import com.emulator.riscv.bus.Memory;
import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.RiscVCpu;
import com.emulator.riscv.cpu.TrapException;
import com.emulator.riscv.devices.ClintDevice;
import com.emulator.riscv.devices.DiskImage;
import com.emulator.riscv.devices.FramebufferDevice;
import com.emulator.riscv.devices.PlicDevice;
import com.emulator.riscv.devices.RtcDevice;
import com.emulator.riscv.devices.SiFiveTestDevice;
import com.emulator.riscv.devices.Uart16550Device;
import com.emulator.riscv.devices.VirtioBlockDevice;
import com.emulator.riscv.devices.VirtioInputDevice;
import com.emulator.riscv.devices.VirtioMmioTransport;
import com.emulator.riscv.devices.VirtioRngDevice;
import com.emulator.riscv.firmware.DeviceTreeBuilder;
import com.emulator.riscv.firmware.SbiHandler;
import com.emulator.riscv.mmu.MMU;

/**
 * One complete virtual machine: hart, address space, devices and platform firmware.
 *
 * <p>Construction is a single deterministic sequence - allocate RAM, map devices, build the device
 * tree, load the kernel, program the architectural state a firmware would leave behind - so a
 * machine either comes up completely or throws with nothing half-initialised.
 *
 * <p>The M-mode half of the platform is implemented in Java (see {@link SbiHandler}) rather than
 * shipped as an OpenSBI binary, which means "booting" is a matter of programming PMP, the trap
 * delegation registers and the counter enables, then starting the hart in S-mode at the kernel
 * entry with {@code a0} = hart id and {@code a1} = device tree address.
 *
 * <p>This class has no Minecraft types, so the whole emulator can be exercised headlessly.
 */
public final class RiscVEngine implements AutoCloseable {

    /**
     * Kernel load address; matches the convention {@code virt} uses so images are interchangeable.
     *
     * <p>The two megabytes below it are where OpenSBI lives on real hardware. This emulator
     * implements the firmware in Java and occupies no memory, but a kernel built against
     * {@code virt} still links its text for {@code 0x80200000}, so loading it lower is wrong: doing so
     * was measured to turn the relocation target into {@code 0xffffffff80001000}, a 32 bit address
     * sign extended into 64 bits, which is further from a boot than loading it here.
     */
    public static final long KERNEL_LOAD_ADDRESS = 0x8020_0000L;
    /** Upper bound for an initrd placed immediately after the kernel image. */
    public static final long INITRD_LIMIT_ADDRESS = 0x8400_0000L;
    private static final long STACK_SIZE = 0x0010_0000L;
    private static final long TIMEBASE_FREQUENCY = 10_000_000L;
    private static final long CLOCK_FREQUENCY = 2_000_000_000L;
    /** PLIC window; must stop at the UART base, which is the next device on the map. */
    private static final long PLIC_WINDOW_SIZE = MemoryMap.UART_BASE - MemoryMap.PLIC_BASE;

    /** Everything a single machine needs to know about itself. */
    public record MachineConfig(int ramMb, int screenWidth, int screenHeight, int cpuFrequencyMhz,
                                int framebufferFpsLimit, int executeBlockSize, int tlbSets,
                                int tlbWays, boolean enableSv48, boolean preferUnsafeMemory,
                                String bootargs, Path kernelImage, Path rootfsImage,
                                Path initrdImage) {

        public long ramBytes() {
            return (long) ramMb * 1024L * 1024L;
        }

        public long targetInstructionsPerSecond() {
            return (long) cpuFrequencyMhz * 1_000_000L;
        }

        public MachineConfig withRootfs(Path newRootfs) {
            return new MachineConfig(ramMb, screenWidth, screenHeight, cpuFrequencyMhz,
                    framebufferFpsLimit, executeBlockSize, tlbSets, tlbWays, enableSv48,
                    preferUnsafeMemory, bootargs, kernelImage, newRootfs, initrdImage);
        }
    }

    private final MachineConfig config;
    private final Memory memory;
    private final Bus bus;
    private final CsrFile csr;
    private final MMU mmu;
    private final RiscVCpu cpu;

    private final ClintDevice clint;
    private final PlicDevice plic;
    private final Uart16550Device uart;
    private final RtcDevice rtc;
    private final SiFiveTestDevice testFinisher;
    private final FramebufferDevice framebuffer;
    private final VirtioBlockDevice blockDevice;
    private final VirtioInputDevice inputDevice;
    private final VirtioRngDevice rngDevice;
    private final SbiHandler sbi;
    private final DiskImage diskImage;

    private volatile boolean halted;
    private volatile String haltReason;

    private long retired;
    private long lastIpsSampleNanos;
    private long lastIpsSampleRetired;
    private volatile double ips;
    private volatile int viewers;

    public RiscVEngine(MachineConfig config) throws IOException {
        this.config = config;
        long ramBytes = config.ramBytes();

        long fbBytes = alignUp((long) config.screenWidth() * config.screenHeight() * 4, 4096);
        if (fbBytes >= ramBytes) {
            throw new IOException("framebuffer of " + (fbBytes >> 20) + " MiB does not fit in "
                    + (ramBytes >> 20) + " MiB of RAM");
        }
        long fbBase = MemoryMap.framebufferBase(MemoryMap.RAM_BASE, ramBytes, fbBytes);

        this.memory = Memory.allocateRam(MemoryMap.RAM_BASE, ramBytes, config.preferUnsafeMemory());
        this.csr = new CsrFile();
        this.mmu = new MMU(memory, csr, config.tlbSets(), config.tlbWays());
        this.bus = new Bus(memory, mmu);
        this.cpu = new RiscVCpu(memory, mmu, csr);

        this.clint = new ClintDevice(csr, TIMEBASE_FREQUENCY);
        this.plic = new PlicDevice(csr, 1);
        this.uart = new Uart16550Device(plic, MemoryMap.UART_IRQ);
        this.rtc = new RtcDevice(plic, MemoryMap.RTC_IRQ);
        this.testFinisher = new SiFiveTestDevice();
        this.framebuffer = new FramebufferDevice(memory.arena(), fbBase, MemoryMap.RAM_BASE,
                config.screenWidth(), config.screenHeight(), plic, MemoryMap.VIRTIO_IRQ_BASE + 1);

        this.diskImage = openDisk(config.rootfsImage(), ramBytes / 2);
        this.blockDevice = new VirtioBlockDevice(bus, diskImage, true);
        this.inputDevice = new VirtioInputDevice(bus);
        this.rngDevice = new VirtioRngDevice(bus);

        Runnable shutdown = () -> stop("guest requested shutdown");
        Runnable reboot = () -> stop("guest requested reboot");
        this.sbi = new SbiHandler(TIMEBASE_FREQUENCY, shutdown, reboot);
        this.sbi.setTimeSource(clint::time);
        this.testFinisher.setShutdownHandler(shutdown);

        mapDevices();

        csr.setTimeSource(clint::time);
        csr.setSatpListener(mmu::flushAll);
        cpu.setSupervisor(this::serviceSupervisorCall);

        framebuffer.clear();
        bootKernel(ramBytes);
    }

    private static DiskImage openDisk(Path path, long defaultBytes) throws IOException {
        if (path == null) {
            throw new IOException("no root filesystem image configured");
        }
        if (Files.isRegularFile(path)) {
            return DiskImage.open(path);
        }
        // A missing image still yields a usable machine: the guest simply has no root filesystem
        // to mount. Creating it sparse is friendlier than refusing to start.
        return DiskImage.openOrCreate(path, Math.max(defaultBytes, 1L << 30));
    }

    private void mapDevices() {
        memory.map(MemoryMap.SIFIVE_TEST, MemoryMap.SIFIVE_TEST_SIZE, testFinisher);
        memory.map(MemoryMap.CLINT_BASE, MemoryMap.CLINT_SIZE, clint);
        memory.map(MemoryMap.RTC_BASE, MemoryMap.RTC_SIZE, rtc);
        // 64 MiB, which is the region QEMU's virt gives the PLIC and ends exactly at the UART
        // base. A larger window reaches past 0x10000000 and silently swallows the console and
        // every virtio-mmio transport, because deviceAt returns the first matching window.
        memory.map(MemoryMap.PLIC_BASE, PLIC_WINDOW_SIZE, plic);
        memory.map(MemoryMap.UART_BASE, MemoryMap.UART_SIZE, uart);

        memory.map(MemoryMap.VIRTIO_BLK_BASE, MemoryMap.VIRTIO_STRIDE,
                new VirtioMmioTransport(bus, blockDevice, MemoryMap.VIRTIO_BLK_BASE, plic,
                        MemoryMap.VIRTIO_IRQ_BASE));
        memory.map(MemoryMap.VIRTIO_FB_BASE, MemoryMap.VIRTIO_STRIDE,
                new VirtioMmioTransport(bus, framebuffer, MemoryMap.VIRTIO_FB_BASE, plic,
                        MemoryMap.VIRTIO_IRQ_BASE + 1));
        memory.map(MemoryMap.VIRTIO_INPUT_BASE, MemoryMap.VIRTIO_STRIDE,
                new VirtioMmioTransport(bus, inputDevice, MemoryMap.VIRTIO_INPUT_BASE, plic,
                        MemoryMap.VIRTIO_IRQ_BASE + 2));
        memory.map(MemoryMap.VIRTIO_RNG_BASE, MemoryMap.VIRTIO_STRIDE,
                new VirtioMmioTransport(bus, rngDevice, MemoryMap.VIRTIO_RNG_BASE, plic,
                        MemoryMap.VIRTIO_IRQ_BASE + 3));

        memory.mapFramebuffer(framebuffer, framebuffer.physicalBase(), framebuffer.sizeBytes());
        memory.freeze();
    }

    // ---- firmware ---------------------------------------------------------------------------------

    private void bootKernel(long ramBytes) throws IOException {
        byte[] kernel = Files.readAllBytes(config.kernelImage());
        memory.loadImage(kernel, 0, kernel.length, KERNEL_LOAD_ADDRESS);

        long initrdStart = 0;
        long initrdEnd = 0;
        Path initrdPath = config.initrdImage();
        if (initrdPath != null && Files.isRegularFile(initrdPath)) {
            byte[] initrd = Files.readAllBytes(initrdPath);
            initrdStart = alignUp(KERNEL_LOAD_ADDRESS + kernel.length, 4096);
            initrdEnd = initrdStart + initrd.length;
            if (initrdEnd > INITRD_LIMIT_ADDRESS) {
                throw new IOException("initrd of " + initrd.length
                        + " bytes does not fit below " + Long.toHexString(INITRD_LIMIT_ADDRESS));
            }
            memory.loadImage(initrd, 0, initrd.length, initrdStart);
        }

        byte[] dtb = new DeviceTreeBuilder(MemoryMap.RAM_BASE, ramBytes,
                framebuffer.physicalBase(), framebuffer.sizeBytes(),
                TIMEBASE_FREQUENCY, CLOCK_FREQUENCY, 1, config.bootargs(), config.enableSv48())
                .initrd(initrdStart, initrdEnd)
                .build();

        // The two megabytes below the kernel: that is where the device tree goes, and leaving it
        // there means the kernel's .bss cannot grow over it once setup_vm runs. Clamping it upwards
        // instead would collide with LinuxBootRunner's trap stub, so it is clamped downwards only.
        long dtbAddress = alignDown(Math.min(KERNEL_LOAD_ADDRESS - 0x0020_0000L,
                MemoryMap.RAM_BASE + ramBytes - 0x0020_0000L), 4096);
        memory.loadImage(dtb, 0, dtb.length, dtbAddress);

        long stackPointer = MemoryMap.RAM_BASE + ramBytes - STACK_SIZE;
        long entryOffset = kernelEntryOffset(kernel);
        cpu.reset(KERNEL_LOAD_ADDRESS + entryOffset, stackPointer, 1);

        // Physical memory protection. A hart with no PMP entries configured would let M-mode
        // through but deny every S-mode access, so a single locked top-of-range entry is installed
        // before the kernel's first instruction retires.
        csr.setPmpAddr(0, -1L);
        csr.setPmpCfg(0, 0x8F);

        // Let user and supervisor mode read the hardware counters without an ecall.
        csr.rawWrite(CsrFile.MCOUNTEREN, 0x7L);
        // Hand the floating point unit over enabled. mstatus.FS lives in the M-mode half, so
        // supervisor code cannot turn the FPU on for itself: an S-mode kernel that touches an f
        // register with FS still at Off takes an illegal instruction on the very first one, which
        // is exactly what OpenSBI arranges the firmware handoff to avoid. Initial rather than Dirty
        // is the correct starting state because no f register has been written yet.
        long status = csr.mstatus() | (1L << CsrFile.MSTATUS_FS_SHIFT);
        csr.rawWrite(CsrFile.MSTATUS, status);
        // Delegate the traps S-mode is expected to handle itself, matching what OpenSBI programs
        // on a virt machine (medeleg = 0x0f4b509, mideleg = 0x1666). The important one is bit 12,
        // instruction page fault: relocate_enable_mmu installs the trampoline page directory and
        // then relies on the *fetch* of the next instruction faulting, so that it can vector to
        // S-mode at the virtual address it stashed in stvec and come back up relocated. With
        // nothing delegated that fetch traps to M-mode, the kernel never relocates, and it dies at
        // the first instruction of the second stage. The SBI calls still reach the supervisor
        // hook, because an ecall from S-mode (bit 9) is delegated only in the sense that it lands
        // where the handler puts it, and this engine services it directly.
        csr.setMedeleg(0x0F4B_509L);
        csr.setMideleg(0x0000_1666L);
        csr.rawWrite(CsrFile.MTVEC, 0L);

        cpu.reg(10, 0L);         // a0 = hart id
        cpu.reg(11, dtbAddress); // a1 = device tree
    }

    private final long[] ecallN = new long[1];
    private final long[] clintArmed = {Long.MIN_VALUE};

    private boolean serviceSupervisorCall(long cause, long epc, long[] regs, CsrFile csrs) {
        if (System.getenv("SBI_ECALL") != null && ecallN[0] < 20) {
            System.out.printf("  ECALL#%d cause=%d a7=0x%x a6=0x%x a0=0x%x a1=0x%x a2=0x%x epc=0x%x%n",
                    ecallN[0], cause, regs[17], regs[16], regs[10], regs[11], regs[12], epc);
            ecallN[0]++;
        }
        sbi.call(regs, bus);
        csrs.setMepc(epc + 4);
        // SBI set_timer must arm the CLINT, not just record a deadline in the handler: the
        // compare register is what the device compares mtime against. Leaving the two separate
        // left mtimecmp at zero, so every mtime was "past due" and the timer interrupt fired
        // continuously without ever settling the guest.
        long armed = sbi.pendingTimer();
        if (armed != clintArmed[0]) {
            clintArmed[0] = armed;
            clint.setMtimecmp(0, armed);
            // set_timer clears the pending timer interrupt, same as OpenSBI on a hart without
            // the Sstc extension: Linux's timer handler re-arms through this call and relies on
            // it to drop STIP, so without it the interrupt is taken again immediately.
            csrs.clearMipBits(1L << 5);
            lastMtip[0] = false;
        }
        if (System.getenv("SBI_ECALL") != null && ecallN[0] <= 20) {
            System.out.printf("        -> mepc=0x%x a0=0x%x a1=0x%x%n",
                    csrs.mepc(), regs[10], regs[11]);
        }
        return true;
    }

    // ---- execution ---------------------------------------------------------------------------------

    /**
     * Executes enough instructions to fill the time slice at the configured target frequency.
     *
     * @param sliceNanos wall-clock budget for this call
     * @return the number of instructions retired
     */
    public long runForNanos(long sliceNanos) {
        if (halted) {
            return 0;
        }
        long budget = config.targetInstructionsPerSecond() * sliceNanos / 1_000_000_000L;
        if (budget <= 0) {
            tickDevices();
            return 0;
        }
        int block = Math.max(256, config.executeBlockSize());
        long executed = 0;
        long start = System.nanoTime();
        while (executed < budget && !halted) {
            int chunk = (int) Math.min(block, budget - executed);
            try {
                long step = cpu.runBlock(chunk);
                executed += step;
                if (step == 0) {
                    break;
                }
            } catch (TrapException trap) {
                cpu.enterTrap(trap.cause, trap.value);
                executed++;
            }
            // Never blow a slice wide open just because instructions are cheap: yield so the host
            // thread stays responsive even when the guest is in a tight loop.
            if ((executed & 0xFFFF) == 0 && System.nanoTime() - start > sliceNanos) {
                break;
            }
        }
        tickDevices();
        retired += executed;
        sampleIps();
        return executed;
    }

    private void tickDevices() {
        clint.tick();
        uart.flush();
        framebuffer.tick();
        bridgeTimerToSupervisor();
    }

    /**
     * Presents the CLINT timer interrupt to S-mode as {@code sip.STIP}.
     *
     * <p>Linux programs the timer through {@code mtimecmp} over SBI, which makes the CLINT raise
     * {@code mip.MTIP}. MTIP is a machine-mode pending bit and is not among the bits {@code mideleg}
     * forwards, so it would be raised in M-mode - where this platform has no guest trap vector, and
     * {@code MIE} is clear because the kernel runs with interrupts delegated. The interrupt would
     * then never be delivered, and a guest waiting on a timer spins for ever: that is the hang in
     * {@code do_raw_spin_lock} once the boot reaches {@code memmap_init_range}.
     *
     * <p>OpenSBI's machine layer performs the same translation, raising {@code sip.STIP} once the
     * compare register has been reached so that the delegated S-mode timer interrupt fires. Only
     * forward it when M-mode cannot consume it, so an M-mode guest that keeps {@code MIE} set still
     * sees the real MTIP.
     */
    private final boolean[] lastMtip = new boolean[1];

    private void bridgeTimerToSupervisor() {
        long mip = csr.rawRead(CsrFile.MIP);
        boolean mtip = (mip & (1L << 7)) != 0;
        boolean mie = (csr.rawRead(CsrFile.MSTATUS) & (1L << CsrFile.MSTATUS_MIE)) != 0;
        boolean delegatedToSupervisor = ((csr.mideleg() >>> 5) & 1) != 0;
        if (mie || !delegatedToSupervisor) {
            lastMtip[0] = mtip;
            return;
        }
        // Rising edge only. Recomputing STIP from MTIP on every visit meant it stayed set after
        // the interrupt was taken, so the handler was re-entered on the first instruction after
        // mret - a livelock with the PC pinned just past the SBI ecall.
        if (mtip && !lastMtip[0]) {
            csr.setMipBits(1L << 5);
        }
        lastMtip[0] = mtip;
    }

    private void sampleIps() {
        long now = System.nanoTime();
        long delta = now - lastIpsSampleNanos;
        if (delta < 500_000_000L) {
            return;
        }
        if (lastIpsSampleNanos != 0) {
            ips = (retired - lastIpsSampleRetired) / (delta / 1e9);
        }
        lastIpsSampleNanos = now;
        lastIpsSampleRetired = retired;
    }

    // ---- accessors ---------------------------------------------------------------------------------

    public FramebufferDevice framebuffer() {
        return framebuffer;
    }

    public Uart16550Device uart() {
        return uart;
    }

    public VirtioInputDevice input() {
        return inputDevice;
    }

    public VirtioBlockDevice blockDevice() {
        return blockDevice;
    }

    public DiskImage diskImage() {
        return diskImage;
    }

    public CsrFile csr() {
        return csr;
    }

    public RiscVCpu cpu() {
        return cpu;
    }

    public Bus bus() {
        return bus;
    }

    public Memory memory() {
        return memory;
    }

    public MMU mmu() {
        return mmu;
    }

    public SbiHandler sbi() {
        return sbi;
    }

    public MachineConfig config() {
        return config;
    }

    public long retiredInstructions() {
        return retired;
    }

    public double instructionsPerSecond() {
        return ips;
    }

    public boolean halted() {
        return halted;
    }

    public String haltReason() {
        return haltReason;
    }

    public void stop(String reason) {
        this.halted = true;
        this.haltReason = reason;
    }

    public void addViewer() {
        viewers++;
    }

    public void removeViewer() {
        if (viewers > 0) {
            viewers--;
        }
    }

    public int viewers() {
        return viewers;
    }

    /** Uploads a reusable buffer sized to the framebuffer; avoids per-frame allocation. */
    public ByteBuffer allocateUploadBuffer() {
        return ByteBuffer.allocateDirect((int) Math.min(framebuffer.sizeBytes(),
                Integer.MAX_VALUE - 8)).order(ByteOrder.nativeOrder());
    }

    /**
     * Where execution has to start inside a loaded kernel image.
     *
     * <p>A bare {@code Image} begins with code and the entry is offset zero. A modern RISC-V kernel
     * built with the EFI stub is instead a PE/COFF executable: offset zero is an {@code MZ} DOS
     * header, and the {@code AddressOfEntryPoint} in its optional header is the <em>EFI</em> stub,
     * which expects an EFI system table rather than a device tree. Entering there makes the kernel
     * probe {@code a1} for an EFI signature, fail the comparison and spin forever, which looks
     * exactly like a hang.
     *
     * <p>The S-mode entry is the first instruction of {@code .text}, which for these images is
     * {@code head.S} - it computes the relocation delta, installs {@code stvec} and unpacks the hart
     * id. For a PE image the section's raw pointer is used, since RISC-V Linux images lay the
     * sections out with the file offset equal to the virtual address.
     */
    private static int kernelEntryOffset(byte[] image) {
        if (image.length < 4 || image[0] != 'M' || image[1] != 'Z') {
            return 0;
        }
        int pe = littleEndianInt(image, 0x3C);
        if (pe < 0 || pe + 24 > image.length
                || image[pe] != 'P' || image[pe + 1] != 'E') {
            return 0;
        }
        int sections = littleEndianShort(image, pe + 6);
        int optionalSize = littleEndianShort(image, pe + 20);
        int table = pe + 24 + optionalSize;
        for (int i = 0; i < sections; i++) {
            int record = table + i * 40;
            if (record + 40 > image.length) {
                break;
            }
            String name = new String(image, record, 8, StandardCharsets.ISO_8859_1)
                    .replace("\0", "").trim();
            if (name.equals(".text")) {
                // The PE/COFF AddressOfEntryPoint points at the EFI stub, which is not how
                // SBI firmware enters a Linux kernel: OpenSBI jumps to the image base and the
                // MZ stub there ("c.li s4,-13" then "j") chains into .text. Verified against
                // QEMU -d in_asm, which enters at 0x80200000 before reaching .text at
                // 0x80201000. The RISC-V image header agrees: text_offset at 0xb8 is zero.
                return 0;
            }
        }
        return 0;
    }

    private static int littleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    private static int littleEndianShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    private static long alignDown(long value, long alignment) {
        return value & ~(alignment - 1);
    }

    @Override
    public void close() {
        halted = true;
        try {
            if (diskImage != null) {
                diskImage.flush();
            }
        } catch (IOException ignored) {
            // Tearing down; a failed flush changes nothing.
        }
        try {
            if (diskImage != null) {
                diskImage.close();
            }
        } catch (IOException ignored) {
            // Same.
        }
        memory.close();
    }
}