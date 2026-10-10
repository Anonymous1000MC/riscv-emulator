package com.emulator.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.emulator.riscv.cpu.TrapException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link LinuxBootRunner} end to end against purpose-built RISC-V payloads.
 *
 * <p>There is no RISC-V Linux kernel available to this repository, so the payloads under
 * {@code src/test/resources/riscvboot} stand in for one. They are real RV64 machine code assembled
 * with the cross toolchain and linked flat at {@code 0x80200000}, which is exactly what the runner
 * expects, so everything the runner actually exercises — the device map, the UART, the trap path,
 * marker detection, thread coordination — is covered. What is <em>not</em> covered is a Linux boot
 * itself: see {@code STATUS.md}.
 *
 * <p>The assembly sources are checked in next to the binaries so the payloads can be rebuilt:
 * <pre>{@code riscv64-linux-gnu-gcc -march=rv64imac -mabi=lp64 -nostdlib -nostartfiles \
 *       -static -no-pie -Wl,-T,link.ld -o smoke.elf smoke.S
 * riscv64-linux-gnu-objcopy -O binary smoke.elf smoke.bin}</pre>
 */
class LinuxBootRunnerTest {

    /**
     * RAM for the test machines.
     *
     * <p>The runner defaults to 2048 MiB because that is the machine the mod serves, but a
     * multi-gigabyte arena per test is not something to impose on a build agent. The size does not
     * affect anything under test: the payloads touch a few kilobytes at {@code 0x80200000}.
     */
    private static final int TEST_RAM_MB = 128;

    private static final long SHORT_TIMEOUT_NANOS = 60_000_000_000L;

    @TempDir
    Path temp;

    private Path rootfs;

    @BeforeAll
    static void requirePayloads() {
        // A missing resource would otherwise show up as an opaque IOException in every test.
        for (String name : List.of("riscvboot/smoke.bin", "riscvboot/fault.bin")) {
            assertNotNull(LinuxBootRunnerTest.class.getClassLoader().getResource(name),
                    "test payload " + name + " is missing from the resources");
        }
    }

    /** Copies a checked-in payload out of the jar so the engine can load it by path. */
    private Path payload(String name) throws IOException {
        Path target = temp.resolve(name.substring(name.lastIndexOf('/') + 1));
        try (InputStream in = LinuxBootRunnerTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            Files.copy(in, target);
        }
        return target;
    }

    /**
     * An empty regular file is enough: the engine only opens the image, and passing a real file
     * keeps it from creating a gigabyte-sized sparse one for every test.
     */
    private Path emptyRootfs() throws IOException {
        if (rootfs == null) {
            rootfs = Files.createFile(temp.resolve("rootfs.img"));
        }
        return rootfs;
    }

    private LinuxBootRunner.Options options(Path kernel, List<String> markers) throws IOException {
        return new LinuxBootRunner.Options(kernel, emptyRootfs(), TEST_RAM_MB,
                SHORT_TIMEOUT_NANOS, markers, false, null);
    }

    // ---- happy path -----------------------------------------------------------------------------

    @Test
    void smokePayloadBootsAndStopsOnItsPrompt() throws Exception {
        try (LinuxBootRunner runner = LinuxBootRunner.create(
                options(payload("riscvboot/smoke.bin"), List.of("# ")))) {
            LinuxBootRunner.Result result = runner.run();

            assertTrue(result.reachedMarker(), "the runner saw the shell prompt and halted");
            assertEquals("# ", result.marker());
            assertTrue(result.succeeded(), "verdict was BOOTED: " + result.haltReason());
            assertEquals(0, result.machineFaults(), "a clean guest traps to nothing");
            assertTrue(result.retiredInstructions() > 0, "the guest actually executed");
            assertTrue(result.consoleLines() > 0, "console output was counted");
        }
    }

    @Test
    void deviceMapMatchesTheQemuVirtAddresses() throws Exception {
        try (LinuxBootRunner runner = LinuxBootRunner.create(
                options(payload("riscvboot/smoke.bin"), List.of("# ")))) {
            List<String> probe = runner.run().deviceProbe();
            String text = String.join("\n", probe);

            assertTrue(text.contains("0x10000000"), "the UART is reported: " + text);
            assertTrue(text.contains("0x10001000"), "virtio-blk is reported: " + text);
            assertTrue(text.contains("0x74726976"),
                    "virtio-blk answered its MagicValue register: " + text);
            assertTrue(text.contains("UNREACHABLE") == false,
                    "no device window is shadowed: " + text);
        }
    }

    @Test
    void guestSeesTheHandoffTheSpecificationDescribes() throws Exception {
        try (LinuxBootRunner runner = LinuxBootRunner.create(
                options(payload("riscvboot/smoke.bin"), List.of("# ")))) {
            LinuxBootRunner.Result result = runner.run();

            // a0 is the hart id and a1 the device tree pointer, set by the engine before the first
            // instruction retires; the payload prints both back out.
            assertTrue(result.consoleLines() > 0);
            assertTrue(result.succeeded());
            // The payload only prints its banner once it can read the device tree pointer, so a
            // successful run already proves the handoff; this pins the address it printed.
String handoff = String.join("\n", result.deviceProbe());
            // a1 carries the device tree, which is placed below the kernel and above RAM base; the
            // exact address depends on how much RAM the machine has, so assert the shape of the
            // handoff rather than one hard coded value.
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("a1 = 0x([0-9a-f]{8,16})").matcher(handoff);
            assertTrue(m.find(), "a1 carries a device tree address: " + handoff);
            long dtb = Long.parseLong(m.group(1), 16);
            assertTrue(dtb >= TestMachine.RAM_BASE,
                    "the device tree is inside RAM: " + Long.toHexString(dtb));
            assertTrue(dtb < RiscVEngine.KERNEL_LOAD_ADDRESS,
                    "the device tree does not collide with the kernel: " + Long.toHexString(dtb));
            assertTrue(result.deviceProbe().stream().anyMatch(l -> l.contains("a0 = 0")),
                    "the hart id was handed over in a0: " + result.deviceProbe());
        }
    }

    // ---- diagnostics -----------------------------------------------------------------------------

    @Test
    void aFaultingGuestIsReportedWithItsOriginalAddress() throws Exception {
        try (LinuxBootRunner runner = LinuxBootRunner.create(
                options(payload("riscvboot/fault.bin"), List.of("NEVER-PRINTED")))) {
            LinuxBootRunner.Result result = runner.run();

            assertFalse(result.reachedMarker(), "the payload never reaches a prompt");
            assertFalse(result.succeeded(), "trapping to machine mode is not a boot");
            assertEquals(1, result.faults().size(), "exactly one distinct fault signature");

            LinuxBootRunner.Fault fault = result.faults().get(0);
            assertEquals(TrapException.LOAD_ACCESS_FAULT, fault.cause(),
                    "reading an unmapped address is a load access fault");
            assertEquals(0x4000_0000L, fault.value(),
                    "the address is the one the payload asked for, not the follow-on trap");
            assertEquals(3, fault.privilege(), "the trap reached machine mode");
            assertTrue(fault.detail().contains("unmapped"), "the report explains itself: "
                    + fault.detail());
        }
    }

    @Test
    void aMissingKernelIsRejectedBeforeAnythingIsAllocated() {
        LinuxBootRunner.Options options = new LinuxBootRunner.Options(
                Path.of("/nonexistent/Image"), null, TEST_RAM_MB, SHORT_TIMEOUT_NANOS,
                null, false, null);
        IOException error = assertThrows(IOException.class, () -> LinuxBootRunner.create(options));
        assertTrue(error.getMessage().contains("no kernel image"),
                "the message says what is wrong: " + error.getMessage());
    }

    @Test
    void optionsRejectAnAbsentKernel() {
        assertThrows(IllegalArgumentException.class, () -> new LinuxBootRunner.Options(
                null, null, TEST_RAM_MB, SHORT_TIMEOUT_NANOS, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new LinuxBootRunner.Options(
                Path.of("/x"), null, 0, SHORT_TIMEOUT_NANOS, null, false, null));
    }
}