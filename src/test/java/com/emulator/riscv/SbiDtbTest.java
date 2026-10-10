package com.emulator.riscv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.emulator.riscv.bus.Bus;
import com.emulator.riscv.bus.Memory;
import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.RiscVCpu;
import com.emulator.riscv.firmware.DeviceTreeBuilder;
import com.emulator.riscv.firmware.SbiHandler;
import com.emulator.riscv.mmu.MMU;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the two pieces of firmware glue that nothing else exercises: the device tree generator and
 * the SBI handler.
 *
 * <p>The device tree is checked by parsing the binary back into a node tree rather than by searching
 * the bytes for a node name, because a substring match cannot tell a {@code reg} property of
 * {@code 0x10000000} apart from one of {@code 0x100000000}, and it silently passes when a property
 * lands in the wrong node.
 *
 * <p>The SBI calls are driven by real S-mode code issuing {@code ecall}, because the interesting
 * part is not the handler but the trap path around it: an {@code ecall} from S-mode has to reach
 * M-mode, the supervisor hook has to consume it, and {@code mret} has to put the hart back in
 * S-mode on the following instruction.
 */
class SbiDtbTest {

    // ---- machine geometry ------------------------------------------------------------------------

    private static final long RAM_BASE = MemoryMap.RAM_BASE;
    private static final long RAM_BYTES = 16L * 1024 * 1024;
    private static final long CODE = RAM_BASE + 0x1000;
    private static final long STACK_TOP = CODE + 0x80000L;

    private static final long TIMEBASE_FREQUENCY = 10_000_000L;
    private static final long CLOCK_FREQUENCY = 2_000_000_000L;

    /** The address space the device tree advertises; the blob itself needs no backing store. */
    private static final long ADVERTISED_RAM_BYTES = 2048L * 1024 * 1024;

    private static final long FRAMEBUFFER_BYTES = 8L * 1024 * 1024;

    // QEMU's virt machine, the layout this platform claims to be compatible with. These are written
    // out literally rather than read from MemoryMap so that a change to the map is caught here
    // instead of being silently blessed.
    private static final long QEMU_VIRT_CLINT_BASE = 0x0200_0000L;
    private static final long QEMU_VIRT_PLIC_BASE = 0x0C00_0000L;
    private static final long QEMU_VIRT_UART_BASE = 0x1000_0000L;
    private static final long QEMU_VIRT_UART_IRQ = 10;

    // ---- legacy SBI function ids ----------------------------------------------------------------
    //
    // SbiHandler keeps its function constants private, and a7 == 0 selects the legacy interface in
    // which the function travels in a6. These are the spec's values.

    // a0 is x10, but a6 and a7 are x16 and x17, which is easy to get wrong when writing the
    // payload with raw register numbers.
    private static final int A0 = 10;
    private static final int A6 = 16;
    private static final int A7 = 17;

    private static final int LEGACY_CONSOLE_PUTCHAR = 1;
    private static final int LEGACY_SET_TIMER = 5;
    private static final int LEGACY_SHUTDOWN = 8;

    private static final int EXT_BASE = 0x10;
    private static final int BASE_GET_IMPL_NAME = 4;

    private static final int PRIV_S = 1;
    private static final int PRIV_M = 3;

    // ---- fixture --------------------------------------------------------------------------------

    private Memory memory;
    private CsrFile csr;
    private MMU mmu;
    private Bus bus;
    private RiscVCpu cpu;
    private SbiHandler sbi;

    private volatile boolean shutdownRequested;
    private volatile boolean rebootRequested;
    private long now;

    @BeforeEach
    void setUp() {
        memory = Memory.allocateRam(RAM_BASE, RAM_BYTES, true);
        csr = new CsrFile();
        mmu = new MMU(memory, csr, 64, 4);
        bus = new Bus(memory, mmu);
        cpu = new RiscVCpu(memory, mmu, csr);
        csr.setSatpListener(mmu::flushAll);

        shutdownRequested = false;
        rebootRequested = false;
        now = 1_000L;

        cpu.reset(CODE, STACK_TOP, PRIV_S);
        // reset() clears PMP, and a hart with no entries configured would let M-mode through while
        // denying every S-mode access, so the single locked top-of-range entry goes in afterwards.
        csr.setPmpAddr(0, -1L);
        csr.setPmpCfg(0, 0x8F);

        sbi = new SbiHandler(TIMEBASE_FREQUENCY, () -> shutdownRequested = true,
                () -> rebootRequested = true);
        // The time source is held in a static field, so it has to be reset explicitly or it leaks
        // into every later handler in the JVM.
        sbi.setTimeSource(() -> now);

        cpu.setSupervisor((cause, epc, regs, csrs) -> {
            sbi.call(regs, bus);
            // Consuming the trap means advancing mepc past the ecall, because returning true makes
            // the hart execute mret and resume at whatever mepc now holds.
            csrs.setMepc(epc + 4);
            return true;
        });

        // This mirrors RiscVEngine: nothing is delegated, so every trap from S-mode lands in M-mode
        // and the SBI hook gets first refusal on the environment calls.
        csr.setMedeleg(0L);
        csr.setMideleg(0L);
        csr.rawWrite(CsrFile.MTVEC, 0L);
    }

    @AfterEach
    void tearDown() {
        sbi.setTimeSource(null);
        memory.close();
    }

    // ---- device tree --------------------------------------------------------------------------

    @Test
    void dtbHeaderIsWellFormed() {
        Fdt fdt = Fdt.parse(buildDtb());

        assertEquals(0xD00DFEEDL, fdt.magic, "FDT header magic");
        assertEquals(17, fdt.version, "FDT version");
        assertEquals(16, fdt.lastCompVersion, "FDT last compatible version");
        assertTrue(fdt.lastCompVersion <= fdt.version, "the compatible version cannot exceed the version");
        assertEquals(fdt.blob.length, fdt.totalSize, "totalsize covers the whole blob");
        assertEquals(40, fdt.offMemRsvmap, "the reservation map sits directly after the header");
        assertEquals(40 + 16, fdt.offStruct,
                "one reservation plus the all-zero terminator precede the structure block");
        assertTrue(fdt.offStruct + fdt.sizeStruct <= fdt.offStrings, "the structure block precedes the strings");
        assertEquals(fdt.totalSize, fdt.offStrings + fdt.sizeStrings,
                "the strings block runs to the end of the blob");
        assertNotNull(fdt.node(""), "the root node is present");
        assertEquals(2, fdt.node("").u32("#address-cells"), "root #address-cells");
        assertEquals(2, fdt.node("").u32("#size-cells"), "root #size-cells");
    }

    @Test
    void memoryNodeDescribesTheConfiguredRam() {
        Fdt fdt = Fdt.parse(buildDtb());

        Fdt.Node memoryNode = fdt.node("memory@" + Long.toHexString(RAM_BASE));
        assertNotNull(memoryNode, "the memory node is named after its base address");
        assertEquals("memory", memoryNode.string("device_type"));

        assertArrayEquals(new long[] { RAM_BASE, ADVERTISED_RAM_BYTES }, memoryNode.reg(),
                "reg is one address and size pair");
        assertEquals(RAM_BASE, memoryNode.reg()[0], "memory base");
        assertEquals(2048L * 1024 * 1024, memoryNode.reg()[1], "the tree advertises 2048 MiB of RAM");
        assertEquals(2 * 1024 * 1024 * 1024L, memoryNode.reg()[1], "which is 2048 MiB");
    }

    @Test
    void deviceRegPropertiesMatchTheQemuVirtMap() {
        Fdt fdt = Fdt.parse(buildDtb());

        Fdt.Node clint = fdt.node("soc/interrupt-controller@" + Long.toHexString(QEMU_VIRT_CLINT_BASE));
        assertNotNull(clint, "the CLINT node lives under /soc");
        assertEquals("sifive,clint1.0", clint.strings("compatible").get(1), "clint compatible list");
        assertEquals(QEMU_VIRT_CLINT_BASE, clint.reg()[0], "CLINT base");
        assertEquals(MemoryMap.CLINT_SIZE, clint.reg()[1], "CLINT size");
        assertEquals(MemoryMap.CLINT_BASE, clint.reg()[0],
                "the tree and the bus agree on the CLINT base");
        assertEquals(0, clint.raw("interrupt-controller").length,
                "interrupt-controller is a present zero length property");

        Fdt.Node plic = fdt.node("soc/interrupt-controller@" + Long.toHexString(QEMU_VIRT_PLIC_BASE));
        assertNotNull(plic, "the PLIC node lives under /soc");
        assertEquals("riscv,plic0", plic.strings("compatible").get(1), "plic compatible list");
        assertEquals(QEMU_VIRT_PLIC_BASE, plic.reg()[0], "PLIC base");
        assertEquals(MemoryMap.PLIC_BASE, plic.reg()[0],
                "the tree and the bus agree on the PLIC base");
        assertEquals(96, plic.u32("riscv,ndev"), "the PLIC exposes 96 sources");

        Fdt.Node uart = fdt.node("soc/serial@" + Long.toHexString(QEMU_VIRT_UART_BASE));
        assertNotNull(uart, "the console node lives under /soc");
        assertEquals("ns16550a", uart.string("compatible"), "uart compatible");
        assertEquals(QEMU_VIRT_UART_BASE, uart.reg()[0], "UART base");
        assertEquals(MemoryMap.UART_SIZE, uart.reg()[1], "UART size");
        assertEquals(MemoryMap.UART_BASE, uart.reg()[0],
                "the tree and the bus agree on the UART base");
        assertEquals(QEMU_VIRT_UART_IRQ, uart.u32("interrupts"), "UART interrupt");
        assertEquals(MemoryMap.UART_IRQ, uart.u32("interrupts"),
                "the tree and the bus agree on the UART interrupt");
        assertEquals(plic.u32("phandle"), uart.u32("interrupt-parent"),
                "the UART's interrupt parent is the PLIC");

        assertEquals("/soc/serial@" + Long.toHexString(QEMU_VIRT_UART_BASE),
                fdt.node("chosen").string("stdout-path"), "the kernel is pointed at the console");

        // Every virtio slot is on the map too, at the same 4 KiB stride virt uses.
        for (int slot = 0; slot < MemoryMap.VIRTIO_SLOT_COUNT; slot++) {
            long base = MemoryMap.VIRTIO_BASE + (long) slot * MemoryMap.VIRTIO_STRIDE;
            Fdt.Node node = fdt.node("soc/virtio_mmio@" + Long.toHexString(base));
            assertNotNull(node, "virtio slot " + slot + " is present in the tree");
            assertEquals(MemoryMap.VIRTIO_IRQ_BASE + slot, node.u32("interrupts"),
                    "virtio slot " + slot + " interrupt");
        }
    }

    // ---- ISA advertisement -----------------------------------------------------------------------

    /**
     * MISA extension bits use the specification's fixed positions, which are sparse and easy to get
     * wrong: {@code A} is bit 0 and {@code I} is bit 8, so the letters and the bit numbers are not
     * in alphabetical order.
     */
    private static final int MISA_A = 0;
    private static final int MISA_C = 2;
    private static final int MISA_D = 3;
    private static final int MISA_F = 5;
    private static final int MISA_I = 8;
    private static final int MISA_M = 12;
    private static final int MISA_S = 17;
    private static final int MISA_U = 19;
    private static final int MISA_V = 20;

    private static boolean misaBit(long misa, int bit) {
        return (misa >>> bit & 1) != 0;
    }

    @Test
    void misaAdvertisesOnlyTheExtensionsTheCoreDecodes() {
        long misa = CsrFile.MISA_VALUE;

        assertEquals(2, misa >>> 62 & 3, "MXL = 2 means 64 bit");
        assertTrue(misaBit(misa, MISA_I), "I");
        assertTrue(misaBit(misa, MISA_M), "M");
        assertTrue(misaBit(misa, MISA_A), "A, the AMO opcode is implemented");
        assertTrue(misaBit(misa, MISA_C), "C, compressed instructions are implemented");
        assertTrue(misaBit(misa, MISA_S), "S");
        assertTrue(misaBit(misa, MISA_U), "U");
        assertTrue(misaBit(misa, MISA_F), "F, the floating point opcodes are decoded");
        assertTrue(misaBit(misa, MISA_D), "D, single precision is implemented alongside it");
        // Vector is the one extension still advertised wrongly: claiming it makes Linux emit
        // instructions this core cannot decode.
        assertFalse(misaBit(misa, MISA_V), "V, no vector opcodes are implemented");
    }

    @Test
    void misaIsReadableFromTheGuest() {
        // Not just the constant: a guest actually reads the CSR, and a WARL mask could still clip it.
        assertEquals(CsrFile.MISA_VALUE, csr.rawRead(CsrFile.MISA), "raw read");
    }

    @Test
    void deviceTreeAdvertisesRv64imacu() {
        Fdt fdt = Fdt.parse(buildDtb());
        Fdt.Node cpu = fdt.node("cpus/cpu@0");
        assertNotNull(cpu, "the cpu node is present");

        assertEquals("rv64gc", cpu.string("riscv,isa"),
                "the kernel derives its ISA expectations from this string");
        assertEquals("rv64imafdc", cpu.string("riscv,isa-base"),
                "the base string omits s and u, which live in the extension list");

        List<String> extensions = cpu.strings("riscv,isa-extensions");
        assertTrue(extensions.contains("s"), "s is advertised as an extension");
        assertTrue(extensions.contains("u"), "u is advertised as an extension");
        for (String forbidden : List.of("v", "f", "d")) {
            assertFalse(extensions.contains(forbidden),
                    forbidden + " must not be advertised: " + extensions);
        }

        // The riscv,t property is a single cell, so it carries the extension bits without MXL; if
        // the two ever disagree the kernel has two contradicting sources of truth.
        assertEquals(CsrFile.MISA_VALUE & 0xFFFF_FFFFL, (long) cpu.u32("riscv,t"),
                "riscv,t agrees with the misa extension bits");
    }

    // ---- SBI base extension --------------------------------------------------------------------

    private long[] probe(long extension) {
        long[] regs = new long[32];
        regs[17] = SbiHandler.EXT_BASE; // a7 selects the base extension in the v2.0 interface
        regs[10] = 0;                    // a6 = function 0, probe
        regs[11] = extension;            // a1 = the extension being probed
        return regs;
    }

    @Test
    void sbiBaseProbeReportsTheExtensionsTheHandlerAnswers() {
        // Function numbering follows the specification: probe is 0, the spec version is 1, and the
        // implementation ID is 2. A kernel probes each extension it needs, so reporting only the
        // base extension would make it stop asking for a timer it is entitled to.
        for (int extension : List.of(SbiHandler.EXT_BASE, SbiHandler.EXT_TIME, SbiHandler.EXT_IPI,
                                     SbiHandler.EXT_HSM, SbiHandler.EXT_DEBUG_CONSOLE)) {
            long[] regs = probe(extension);
            assertEquals(SbiHandler.SBI_SUCCESS, sbi.call(regs, bus), "probe itself succeeds");
            assertEquals(SbiHandler.SBI_SUCCESS, regs[10],
                    "extension 0x" + Integer.toHexString(extension) + " is implemented");
        }
        long[] missing = probe(0x7FFF);
        assertEquals(SbiHandler.SBI_SUCCESS, sbi.call(missing, bus),
                "the probe call itself succeeds; the answer is the value in a0");
        assertEquals(SbiHandler.SBI_ERR_NOT_SUPPORTED, missing[10],
                "and reports it in a0, where the guest can actually see it");
    }

    @Test
    void sbiBaseDispatchesOnA6NotA0() {
        // The v0.2 calling convention puts the extension in a7 and the *function* in a6; a0..a5 are
        // arguments. Dispatching on a0 made every BASE call look like a probe, because the kernel
        // zeroes a0 before the ecall, so the kernel read -2 out of GET_SPEC_VERSION and gave up on
        // the firmware. Two tests here encoded that mistake by putting the function id in a0.
        long[] version = new long[32];
        version[16] = 1;        // a6 = BASE_GET_SPEC_VERSION
        version[17] = SbiHandler.EXT_BASE;
        version[10] = 0x7FFF;   // a0 is an argument here and must not select the function
        sbi.call(version, bus);
        assertEquals(0x02000000L, version[10], "a6 selects the function; a0 is ignored");

        // And through the real ecall path, where the hart actually loads a6 before trapping.
        long buffer = CODE + 0x3000;
        Asm.emit(memory, CODE, concat(
                loadPointer(11, buffer),
                new int[] { Asm.addi(A6, 0, BASE_GET_IMPL_NAME), Asm.addi(A7, 0, SbiHandler.EXT_BASE),
                            Asm.ecall() },
                new int[] { Asm.jal(0, 0) }));
        step(12);
        assertEquals("Theon", readCString(buffer, 64),
                "a real ecall to BASE_GET_IMPL_NAME dispatched on a6 and wrote the name");
    }

    @Test
    void sbiBaseReportsTheSpecificationVersionAndImplementationId() {
        long[] version = new long[32];
        version[16] = 1;        // a6 = function 1, get specification version
        version[17] = SbiHandler.EXT_BASE;
        sbi.call(version, bus);
        assertEquals(0x02000000L, version[10],
                "the specification encodes the version as (major << 24) | (minor << 16)");
        assertEquals(2, version[10] >>> 24, "so the major version is 2");

        long[] id = new long[32];
        id[16] = 2;            // a6 = function 2, get implementation ID
        id[17] = SbiHandler.EXT_BASE;
        sbi.call(id, bus);
        assertNotEquals(0L, id[10], "an implementation ID is reported, not zero");
    }

    // ---- SBI ------------------------------------------------------------------------------------

    @Test
    void sbiCallsFromSupervisorModeReturnToSupervisorMode() {
        // Three environment calls in a row, then park on a self loop so the run cannot wander off
        // the end of the program.
        int[] payload = concat(
                sbiCall(LEGACY_SET_TIMER, 1000),
                sbiCall(LEGACY_CONSOLE_PUTCHAR, 'H'),
                sbiCall(LEGACY_SHUTDOWN),
                new int[] { Asm.jal(0, 0) });
        Asm.emit(memory, CODE, payload);
        long park = CODE + (payload.length - 1) * 4L;

        long retired = step(24);

        assertEquals(3, sbi.callsHandled(), "all three environment calls reached the SBI handler");
        assertEquals(0, sbi.callsFailed(), "none of them reported an error");
        assertEquals(1000, sbi.pendingTimer(), "SET_TIMER recorded the deadline from a0");
        assertTrue(shutdownRequested, "SHUTDOWN ran the shutdown action");
        assertFalse(rebootRequested, "SHUTDOWN is not a reboot");
        assertEquals(PRIV_S, cpu.privilege(),
                "mret after each ecall put the hart back in S-mode, not M-mode");
        // mtvec is zero, so a trap that had escaped the supervisor hook would have left the pc
        // there. The payload instead parked on its own last instruction.
        assertEquals(park, cpu.pc(), "no trap escaped to mtvec");
        assertTrue(retired >= 12, "the whole payload ran, expected at least 12 instructions, got " + retired);
    }

    @Test
    void sbiSetTimerAndPutcharAdvancePastTheEcall() {
        Asm.emit(memory, CODE, concat(
                sbiCall(LEGACY_SET_TIMER, 42),
                sbiCall(LEGACY_CONSOLE_PUTCHAR, 'X')));

        step(4);
        assertEquals(CODE + 4 * 4, cpu.pc(), "the first ecall was consumed and execution resumed after it");

        step(4);
        assertEquals(CODE + 8 * 4, cpu.pc(), "so was the second");
        assertEquals(42, sbi.pendingTimer(), "the deadline survived the round trip");
    }

    @Test
    void sbiWritesTheImplementationNameThroughTheBus() {
        // BASE_GET_IMPL_NAME writes 64 bytes through Bus.dmaWrite, so the pointer in a1 has to
        // address a buffer that the hart can actually reach.
        long buffer = CODE + 0x2000;
        Asm.emit(memory, CODE, concat(
                loadPointer(11, buffer),
                new int[] { Asm.addi(A6, 0, BASE_GET_IMPL_NAME), Asm.addi(A7, 0, EXT_BASE), Asm.ecall() },
                new int[] { Asm.jal(0, 0) }));

        step(12);

        String name = readCString(buffer, 64);
        assertEquals("Theon", name, "the base extension wrote its name through the bus");
    }

    @Test
    void delegatedEcallIsTakenInSupervisorMode() {
        // With medeleg bit 9 set the ecall is a supervisor trap, so it must never reach the M-mode
        // SBI hook: the hart lands in S-mode with scause 9 and sepc pointing at the ecall.
        long handler = CODE + 0x1000;
        Asm.emit(memory, handler, new int[] { Asm.jal(0, 0) });
        Asm.emit(memory, CODE, sbiCall(LEGACY_CONSOLE_PUTCHAR, 'H'));

        csr.setMedeleg(1L << 9);
        csr.write(CsrFile.STVEC, handler);
        cpu.setPc(CODE);
        long handledBefore = sbi.callsHandled();

        step(4);

        assertEquals(handler, cpu.pc(), "a delegated ecall is taken in S-mode");
        assertEquals(PRIV_S, cpu.privilege());
        assertEquals(9, csr.scause(), "environment call from S-mode is cause 9");
        assertEquals(CODE + 12, csr.sepc(), "sepc points at the ecall");
        assertEquals(handledBefore, sbi.callsHandled(), "the M-mode SBI hook did not see it");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private byte[] buildDtb() {
        return new DeviceTreeBuilder(RAM_BASE, ADVERTISED_RAM_BYTES,
                RAM_BASE + ADVERTISED_RAM_BYTES - FRAMEBUFFER_BYTES, FRAMEBUFFER_BYTES,
                TIMEBASE_FREQUENCY, CLOCK_FREQUENCY, 1, "console=ttyS0,115200", true)
                .build();
    }

    /**
     * Retires {@code count} instructions one at a time.
     *
     * <p>{@code runBlock} stops at the first synchronous trap by design, which is what the engine
     * wants but not what a payload that issues {@code ecall} needs.
     */
    private long step(int count) {
        for (int i = 0; i < count; i++) {
            cpu.step();
        }
        return count;
    }

    /** One legacy environment call: {@code a0} argument, function in {@code a6}, {@code a7} zero. */
    private static int[] sbiCall(int function) {
        return new int[] { Asm.addi(A6, 0, function), Asm.addi(A7, 0, 0), Asm.ecall() };
    }

    private static int[] sbiCall(int function, int argument) {
        return new int[] { Asm.addi(A0, 0, argument), Asm.addi(A6, 0, function),
                Asm.addi(A7, 0, 0), Asm.ecall() };
    }

    /**
     * Materialises a 64 bit constant.
     *
     * <p>The four instruction form is needed rather than a bare {@code lui}, because {@code lui}
     * sign-extends its 32 bit result into the full register: a pointer at {@code 0x80003000} has
     * bit 31 set, so {@code lui} alone would leave {@code x} holding a negative address and the
     * SBI's write would fault. Shifting the value left and back right by 32 forces zero extension.
     */
    private static int[] loadPointer(int rd, long value) {
        if (value <= 0x7FFF_FFFFL) {
            return new int[] { Asm.addi(rd, 0, (int) value) };
        }
        return new int[] { Asm.lui(rd, (int) (value >>> 12)), Asm.addi(rd, rd, (int) value),
                Asm.slli(rd, rd, 32), Asm.srli(rd, rd, 32) };
    }

    private static int[] concat(int[]... parts) {
        int total = 0;
        for (int[] part : parts) {
            total += part.length;
        }
        int[] out = new int[total];
        int at = 0;
        for (int[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private String readCString(long address, int limit) {
        int length = 0;
        while (length < limit && bus.read8Phy(address + length) != 0) {
            length++;
        }
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = bus.read8Phy(address + i);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /**
     * A minimal Flattened Device Tree reader.
     *
     * <p>Only what the spec calls mandatory is implemented: the forty byte header, the token
     * stream, and property values as raw bytes that the node then decodes according to the cell
     * counts it inherited. Parsing rather than scanning means an assertion about a property is an
     * assertion about that property.
     */
    private static final class Fdt {

        private static final int MAGIC = 0xD00DFEED;
        private static final int BEGIN_NODE = 1;
        private static final int END_NODE = 2;
        private static final int PROP = 3;
        private static final int END = 9;

        final byte[] blob;
        final long magic;
        final int totalSize;
        final int offStruct;
        final int offStrings;
        final int offMemRsvmap;
        final int version;
        final int lastCompVersion;
        final int sizeStrings;
        final int sizeStruct;

        private Node root;

        private Fdt(byte[] blob) {
            this.blob = blob;
            this.magic = u32(blob, 0) & 0xFFFF_FFFFL;
            this.totalSize = u32(blob, 4);
            this.offStruct = u32(blob, 8);
            this.offStrings = u32(blob, 12);
            this.offMemRsvmap = u32(blob, 16);
            this.version = u32(blob, 20);
            this.lastCompVersion = u32(blob, 24);
            this.sizeStrings = u32(blob, 32);
            this.sizeStruct = u32(blob, 36);
        }

        static Fdt parse(byte[] blob) {
            Fdt fdt = new Fdt(blob);
            fdt.walk();
            return fdt;
        }

        /** Walks the structure block, which is a flat token stream rather than a real tree. */
        private void walk() {
            Deque<Node> stack = new ArrayDeque<>();
            // The outermost BEGIN_NODE in the block is the root, so it cannot be pushed onto a
            // pre-made parent or every lookup would have to start one level too deep.
            Node parent = null;
            int at = offStruct;
            int limit = offStruct + sizeStruct;
            while (at < limit) {
                int token = u32(blob, at);
                at += 4;
                switch (token) {
                    case BEGIN_NODE -> {
                        String name = cstring(blob, at);
                        at = align4(at + name.length() + 1);
                        Node node = new Node(name);
                        node.parent = parent;
                        if (parent == null) {
                            root = node;
                        } else {
                            parent.add(node);
                        }
                        stack.push(node);
                        parent = node;
                    }
                    case END_NODE -> {
                        stack.pop();
                        parent = stack.isEmpty() ? null : stack.peek();
                    }
                    case PROP -> {
                        int length = u32(blob, at);
                        int nameOffset = u32(blob, at + 4);
                        at += 8;
                        byte[] value = new byte[length];
                        System.arraycopy(blob, at, value, 0, length);
                        at = align4(at + length);
                        parent.properties.put(cstring(blob, offStrings + nameOffset), value);
                    }
                    case END -> {
                        return;
                    }
                    default -> throw new IllegalStateException("unexpected FDT token " + token);
                }
            }
        }

        /** Looks up a node by slash separated path; an empty string is the root. */
        Node node(String path) {
            Node current = root;
            for (String segment : path.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                Node next = current.children.get(segment);
                if (next == null) {
                    return null;
                }
                current = next;
            }
            return current;
        }

        private static int u32(byte[] data, int offset) {
            return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16
                    | (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
        }

        private static int align4(int value) {
            return (value + 3) & ~3;
        }

        private static String cstring(byte[] data, int offset) {
            int end = offset;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            return new String(data, offset, end - offset, StandardCharsets.UTF_8);
        }

        /** One node, with its properties kept as the raw bytes the tree stored. */
        static final class Node {
            final String name;
            final Map<String, byte[]> properties = new LinkedHashMap<>();
            final Map<String, Node> children = new LinkedHashMap<>();
            Node parent;

            Node(String name) {
                this.name = name;
            }

            void add(Node child) {
                children.put(child.name, child);
            }

            byte[] raw(String property) {
                byte[] value = properties.get(property);
                if (value == null) {
                    throw new AssertionError("node " + name + " has no property " + property
                            + ", it has " + properties.keySet());
                }
                return value;
            }

            /** Decodes a single big-endian cell, the form used for counts and small integers. */
            int u32(String property) {
                byte[] value = raw(property);
                if (value.length != 4) {
                    throw new AssertionError("property " + property + " is " + value.length
                            + " bytes, expected a single cell");
                }
                return (value[0] & 0xFF) << 24 | (value[1] & 0xFF) << 16
                        | (value[2] & 0xFF) << 8 | (value[3] & 0xFF);
            }

            /** Decodes a NUL terminated string property. */
            String string(String property) {
                return Fdt.cstring(raw(property), 0);
            }

            /**
             * Decodes a list of NUL separated strings, as {@code compatible} uses.
             *
             * <p>The raw bytes are walked directly because {@link #string(String)} stops at the
             * first NUL, so it would silently return only the first entry of the list.
             */
            List<String> strings(String property) {
                List<String> values = new ArrayList<>();
                byte[] value = raw(property);
                int start = 0;
                for (int i = 0; i <= value.length; i++) {
                    if (i == value.length || value[i] == 0) {
                        if (i > start) {
                            values.add(new String(value, start, i - start, StandardCharsets.UTF_8));
                        }
                        start = i + 1;
                    }
                }
                return values;
            }

            /** Decodes {@code reg} into 64 bit cells, high half first, per the spec's cell order. */
            long[] reg() {
                byte[] value = raw("reg");
                long[] cells = new long[value.length / 8];
                for (int i = 0; i < cells.length; i++) {
                    long high = Fdt.u32(value, i * 8) & 0xFFFF_FFFFL;
                    long low = Fdt.u32(value, i * 8 + 4) & 0xFFFF_FFFFL;
                    cells[i] = high << 32 | low;
                }
                return cells;
            }
        }
    }
}