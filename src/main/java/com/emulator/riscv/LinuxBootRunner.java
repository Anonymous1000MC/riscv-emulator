package com.emulator.riscv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.emulator.riscv.bus.MemoryMap;
import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.RiscVCpu;
import com.emulator.riscv.cpu.TrapException;

/**
 * Boots a Linux guest on {@link RiscVEngine} without Minecraft, printing the serial console live.
 *
 * <p>This is the debugging tool for "the machine starts but the kernel never gets anywhere". It
 * exists because that class of problem is invisible in the block entity: a silent stall and a slow
 * boot look identical through a texture upload. Here the guest's UART goes straight to
 * {@code System.out}, and every fault that escapes to M-mode is reported with its address decoded.
 *
 * <p>Run it from the command line:
 * <pre>{@code
 * java -cp build/classes/java/main com.emulator.riscv.LinuxBootRunner \
 *      --kernel=/path/to/Image --rootfs=/path/to/rootfs.img
 * }</pre>
 *
 * <p>The emulator core has no logging framework and no error callbacks, so faults are discovered by
 * sampling the trap CSRs once per execution slice rather than by being pushed to a listener. That
 * is sufficient because the interpreter handles every trap internally and leaves the cause where a
 * sampler can find it.
 *
 * <p><strong>Delegated traps are not faults.</strong> An S-mode page fault is the kernel doing
 * normal work — probing, demand paging, {@code handle_mm_fault} — so it is counted and reported but
 * never treated as an error. Only traps that reach M-mode, which a healthy Linux never does, are
 * flagged as failures.
 */
public final class LinuxBootRunner implements AutoCloseable {

    /** Default RAM size, matching the machine the block is meant to serve. */
    public static final int DEFAULT_RAM_MB = 2048;

    /** Default wall-clock budget for a boot that is expected to reach a shell prompt. */
    public static final long DEFAULT_TIMEOUT_NANOS = 120_000_000_000L;

    /**
     * Strings that mean "the boot got far enough". {@code Linux version} is printed by the kernel
     * itself; {@code # } is a root shell prompt and only appears once userspace is up.
     */
    public static final List<String> DEFAULT_HALT_MARKERS = List.of("Linux version", "# ");

    /**
     * Sampling granularity.
     *
     * <p>The interpreter converts traps internally and leaves no callback to observe, so a fault is
     * only visible if a sample lands before the next one overwrites {@code mcause}. A coarse slice
     * therefore hides everything but the last trap of its window, so slices start fine and are
     * relaxed once the guest has been clean for a while.
     */
    private static final long FINE_SLICE_NANOS = 20_000L;
    private static final long COARSE_SLICE_NANOS = 2_000_000L;
    private static final int CLEAN_SAMPLES_BEFORE_RELAX = 64;

    /**
     * Where {@code mtvec} points: a single infinite-loop instruction written into RAM.
     *
     * <p>Any trap that reaches M-mode is fatal to a Linux boot, so the useful thing is to stop the
     * guest on the <em>first</em> such trap and inspect it. Pointing {@code mtvec} at a valid
     * instruction does exactly that: the hart parks on the stub instead of jumping to address zero
     * and trapping again, so {@code mcause} and {@code mtval} still hold the original fault rather
     * than being overwritten by the secondary one. Left at zero the runner only ever sees the
     * follow-on instruction access fault and reports the wrong address.
     *
     * <p>It sits between the device tree and the kernel load address, so nothing else can collide
     * with it.
     */
    /** How long a running guest may go without printing before it is declared wedged. */
    private static final long WEDGE_SILENCE_NANOS = 3_000_000_000L;

    private static final long TRAP_STUB = MemoryMap.RAM_BASE + 0x0010_0000L;

    /** {@code j .} — an infinite loop, the only instruction the stub needs. */
    private static final int SELF_LOOP = 0x0000006F;

    /** How often the main thread wakes to drain the console. */
    private static final long POLL_INTERVAL_MILLIS = 20L;

    /**
     * Identical faults repeat once per instruction in a trap loop, so a signature is only reported
     * once and afterwards counted.
     */
    private static final int FAULT_REPORT_LIMIT = 25;

    /** Configuration for one boot attempt. */
    public record Options(Path kernel, Path rootfs, int ramMb, long timeoutNanos,
                          List<String> haltMarkers, boolean forwardStdin, String bootargs) {

        public Options {
            if (kernel == null) {
                throw new IllegalArgumentException("a kernel image is required");
            }
            if (ramMb <= 0) {
                throw new IllegalArgumentException("ramMb must be positive, got " + ramMb);
            }
            haltMarkers = haltMarkers == null || haltMarkers.isEmpty()
                    ? DEFAULT_HALT_MARKERS : List.copyOf(haltMarkers);
            if (rootfs == null) {
                bootargs = bootargs == null ? "" : bootargs;
            }
        }

        public static Options of(Path kernel, Path rootfs) {
            return new Options(kernel, rootfs, DEFAULT_RAM_MB, DEFAULT_TIMEOUT_NANOS,
                    DEFAULT_HALT_MARKERS, false, null);
        }
    }

    /** One trap that escaped to the machine level, or a virtual queue that reported failure. */
    public record Fault(long atNanos, int cause, long value, int privilege, long pc,
                        int repeatCount, String detail) {
    }

    /** Everything a boot attempt produced. */
    public record Result(boolean reachedMarker, String marker, String haltReason,
                         long retiredInstructions, double instructionsPerSecond,
                         long consoleBytes, long consoleLines, List<Fault> faults,
                         long machineFaults, long supervisorPageFaults, List<String> deviceProbe) {

        /** A boot is a success when the kernel announced itself and nothing trapped to M-mode. */
        public boolean succeeded() {
            return reachedMarker && machineFaults == 0;
        }
    }

    // ---- state ----------------------------------------------------------------------------------

    private final Options options;
    private final RiscVEngine engine;
    private final StringBuilder console = new StringBuilder();
    private final Object consoleLock = new Object();
    private final List<Fault> faults = new ArrayList<>();
    /** Signature to index into {@link #faults}, so repeats update in place. */
    private final Map<String, Integer> faultIndex = new LinkedHashMap<>();
    private final Object faultLock = new Object();
    private final AtomicLong consoleBytesReceived = new AtomicLong();
    private final AtomicLong supervisorPageFaults = new AtomicLong();
    private final AtomicBoolean stdinClosed = new AtomicBoolean();
    private final List<String> deviceProbe;
    private volatile boolean markerHit;
    private volatile String matchedMarker;
    private long consoleBytes;
    private long consoleLines;

    private LinuxBootRunner(Options options, RiscVEngine engine, List<String> deviceProbe) {
        this.options = options;
        this.engine = engine;
        this.deviceProbe = deviceProbe;
    }

    /**
     * Builds the machine and wires the console.
     *
     * @throws IOException if the kernel is missing or RAM cannot be allocated
     */
    public static LinuxBootRunner create(Options options) throws IOException {
        if (!Files.isRegularFile(options.kernel())) {
            throw new IOException("no kernel image at " + options.kernel()
                    + "; pass --kernel=/path/to/Image (a raw binary, not an ELF)");
        }
        RiscVEngine.MachineConfig config = new RiscVEngine.MachineConfig(
                options.ramMb(),
                64, 64,                     // headless: a tiny framebuffer nobody looks at
                1000,                       // cpuFrequencyMhz, only used to size a time slice
                60,                         // framebufferFpsLimit, irrelevant without a renderer
                4096,                       // executeBlockSize
                64, 4,                      // TLB sets and ways
                false,                      // Sv39; Sv48 needs a test that exercises it
                true,                       // prefer the Unsafe arena
                options.bootargs(),
                options.kernel(), options.rootfs(), null);

        RiscVEngine engine = new RiscVEngine(config);
        engine.memory().write32(TRAP_STUB, SELF_LOOP);
        engine.csr().rawWrite(CsrFile.MTVEC, TRAP_STUB);
        List<String> probe = verifyDeviceMap(engine);

        LinuxBootRunner runner = new LinuxBootRunner(options, engine, probe);
        engine.uart().setOutputSink(runner::onConsoleByte);
        return runner;
    }

    // ---- boot state verification ----------------------------------------------------------------

    /**
     * Reads an identifying register from every device the guest needs during boot.
     *
     * <p>This is not ceremony. Device lookup returns the first window that contains an address, so
     * an oversized window silently makes the devices behind it unreachable and every access reads
     * back zero — the UART simply never prints anything. Checking the addresses up front turns
     * that failure into one line at startup instead of a mysterious hang.
     */
    private static List<String> verifyDeviceMap(RiscVEngine engine) {
        List<String> report = new ArrayList<>();
        report.add(String.format(Locale.ROOT, "RAM        %d MiB at %s",
                engine.config().ramMb(), hex(MemoryMap.RAM_BASE)));
        report.add(String.format(Locale.ROOT, "kernel     %s at %s",
                engine.config().kernelImage().getFileName(), hex(RiscVEngine.KERNEL_LOAD_ADDRESS)));
        report.add(String.format(Locale.ROOT, "dtb        a1 = %s, a0 = %d",
                hex(engine.cpu().reg(11)), engine.cpu().reg(10)));
        report.add(String.format(Locale.ROOT, "uart       %s LSR = 0x%02x",
                hex(MemoryMap.UART_BASE),
                engine.bus().read8Phy(MemoryMap.UART_BASE + 5) & 0xFF));

        long magic = engine.bus().read32Phy(MemoryMap.VIRTIO_BLK_BASE) & 0xFFFF_FFFFL;
        report.add(String.format(Locale.ROOT, "virtio-blk %s magic = 0x%08x%s",
                hex(MemoryMap.VIRTIO_BLK_BASE), magic,
                magic == 0x74726976L ? " (virtio-mmio)" : " UNREACHABLE"));
        report.add(String.format(Locale.ROOT, "mtvec      %s, parks a faulting guest there", hex(TRAP_STUB)));

        if (magic != 0x74726976L) {
            throw new IllegalStateException("the virtio block transport at "
                    + hex(MemoryMap.VIRTIO_BLK_BASE) + " did not answer; a device window is "
                    + "probably shadowing it");
        }
        return report;
    }

    // ---- execution ------------------------------------------------------------------------------

    /** Boots the guest and returns once it hits a marker, faults irrecoverably, or times out. */
    public Result run() throws IOException, InterruptedException {
        Thread inputPump = options.forwardStdin() ? startStdinPump() : null;

        for (String line : deviceProbe) {
            System.out.println("  " + line);
        }

        AtomicLong retired = new AtomicLong();
        AtomicReference<Throwable> crash = new AtomicReference<>();
        CsrFile csr = engine.csr();
        RiscVCpu cpu = engine.cpu();

        Thread worker = new Thread(() -> {
            long slice = FINE_SLICE_NANOS;
            int cleanSamples = 0;
            long lastOutputNanos = System.nanoTime();
            long lastBytes = 0;
            try {
                while (!engine.halted() && !markerHit) {
                    long executed = engine.runForNanos(slice);
                    if (executed == 0) {
                        break;
                    }
                    retired.addAndGet(executed);

                    long bytes = consoleBytesReceived.get();
                    if (bytes != lastBytes) {
                        lastBytes = bytes;
                        lastOutputNanos = System.nanoTime();
                    }
                    if (sampleDiagnostics(csr, cpu) > 0) {
                        // A trap that reached M-mode is unrecoverable for a Linux boot, so stop here
                        // with the state intact rather than let a trap storm bury it.
                        System.out.println("  ** the guest trapped to machine mode; stopping so the"
                                + " first fault survives");
                        engine.stop("trapped to machine mode");
                        break;
                    }
                    if (++cleanSamples >= CLEAN_SAMPLES_BEFORE_RELAX) {
                        slice = COARSE_SLICE_NANOS;
                    }
                    if (lastBytes > 0 && System.nanoTime() - lastOutputNanos > WEDGE_SILENCE_NANOS) {
                        System.out.println("  ** the guest has printed nothing for "
                                + (WEDGE_SILENCE_NANOS / 1_000_000L) + " ms; it is wedged");
                        engine.stop("wedged: no console progress");
                        break;
                    }
                }
            } catch (Throwable t) {
                crash.set(t);
                engine.stop("host error: " + t);
            }
        }, "riscv-boot");
        worker.setDaemon(true);
        worker.start();

        long deadline = System.nanoTime() + options.timeoutNanos();
        boolean timedOut = true;
        while (worker.isAlive() && System.nanoTime() < deadline) {
            drainConsole();
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        drainConsole();
        timedOut = worker.isAlive();
        if (timedOut) {
            engine.stop("timed out after " + (options.timeoutNanos() / 1_000_000L) + " ms");
            worker.join(5_000);
        }
        drainConsole();

        stdinClosed.set(true);
        if (inputPump != null) {
            inputPump.join(1_000);
        }

        Throwable failure = crash.get();
        if (failure != null) {
            System.out.println("  boot aborted: " + failure);
            failure.printStackTrace(System.out);
        }

        return new Result(markerHit, matchedMarker, engine.haltReason(),
                retired.get(), engine.instructionsPerSecond(),
                consoleBytes, consoleLines, snapshotFaults(),
                machineFaultCount(), supervisorPageFaults.get(), deviceProbe);
    }

    /**
     * Looks at the trap CSRs once per slice and records anything unusual.
     *
     * <p>{@code mcause} and {@code scause} are the same storage in this CSR file, so a single read
     * covers both levels; the hart's current privilege says which one it was.
     */
    private int sampleDiagnostics(CsrFile csr, RiscVCpu cpu) {
        int recorded = 0;
        long cause = csr.mcause();
        int code = (int) (cause & ~TrapException.INTERRUPT_BIT);
        boolean interrupt = (cause & TrapException.INTERRUPT_BIT) != 0;
        if (interrupt || code == 0) {
            return 0;
        }
        int privilege = cpu.privilege();
        if (privilege != 3) {
            // A supervisor page fault is the kernel paging on demand; ordinary, expected work.
            if (code == TrapException.LOAD_PAGE_FAULT
                    || code == TrapException.STORE_PAGE_FAULT
                    || code == TrapException.INSTRUCTION_PAGE_FAULT) {
                supervisorPageFaults.incrementAndGet();
            }
            return 0;
        }
        // mepc, not pc: the trap has already been taken, so pc is the handler and mepc is the
        // instruction that actually faulted.
        boolean fresh = recordFault(code, csr.mtval(), privilege, csr.mepc(),
                describe(code, csr.mtval(), csr));
        return fresh ? 1 : 0;
    }

    /** @return {@code true} when this is a fault signature not seen before */
    private boolean recordFault(int cause, long value, int privilege, long pc, String detail) {
        boolean fresh;
        synchronized (faultLock) {
            String signature = cause + ":" + hex(value) + ":" + privilege;
            Integer at = faultIndex.get(signature);
            if (at != null) {
                Fault previous = faults.get(at);
                faults.set(at, new Fault(previous.atNanos(), cause, value, privilege, pc,
                        previous.repeatCount() + 1, detail));
                return false;
            }
            if (faultIndex.size() >= FAULT_REPORT_LIMIT) {
                return false;
            }
            faultIndex.put(signature, faults.size());
            faults.add(new Fault(System.nanoTime(), cause, value, privilege, pc, 1, detail));
            fresh = true;
        }
        System.out.printf("  [fault] %s, trapped from pc=%s%n", detail, hex(pc));
        return fresh;
    }

    private long machineFaultCount() {
        long count = 0;
        synchronized (faultLock) {
            for (Fault fault : faults) {
                count += fault.repeatCount();
            }
        }
        return count;
    }

    private static String describe(int cause, long value, CsrFile csr) {
        long satp = csr.satp();
        int mode = (int) (satp >>> 60) & 0xF;
        String translation = switch (mode) {
            case 0 -> "bare, the address is physical";
            case 8 -> "Sv39, the address is virtual";
            case 9 -> "Sv48, the address is virtual";
            default -> "unknown paging mode " + mode;
        };
        String kind = switch (cause) {
            case TrapException.INSTRUCTION_ACCESS_FAULT -> "instruction access fault";
            case TrapException.LOAD_ACCESS_FAULT -> "load access fault, usually an unmapped MMIO read";
            case TrapException.STORE_ACCESS_FAULT -> "store access fault, usually an unmapped MMIO write";
            case TrapException.INSTRUCTION_PAGE_FAULT -> "instruction page fault";
            case TrapException.LOAD_PAGE_FAULT -> "load page fault";
            case TrapException.STORE_PAGE_FAULT -> "store page fault";
            case TrapException.ILLEGAL_INSTRUCTION -> "illegal instruction";
            case TrapException.BREAKPOINT -> "breakpoint";
            case TrapException.INSTRUCTION_ADDR_MISALIGNED -> "instruction address misaligned";
            case TrapException.LOAD_ADDR_MISALIGNED -> "load address misaligned";
            case TrapException.STORE_ADDR_MISALIGNED -> "store address misaligned";
            default -> "cause " + cause;
        };
        return String.format(Locale.ROOT, "%s at %s (%s)", kind, hex(value), translation);
    }

    // ---- console --------------------------------------------------------------------------------

    /**
     * Receives one character from the guest.
     *
     * <p>Runs on the execution thread inside the guest's own store instruction, so it only ever
     * touches the console buffer.
     */
    private List<Fault> snapshotFaults() {
        synchronized (faultLock) {
            return List.copyOf(faults);
        }
    }

    private void onConsoleByte(char c) {
        consoleBytesReceived.incrementAndGet();
        synchronized (consoleLock) {
            console.append(c);
        }
    }

    /**
     * Prints whatever the guest has produced since the last call and watches for halt markers.
     *
     * <p>Only the main thread calls this, so the byte and line totals need no synchronisation.
     */
    private void drainConsole() {
        String text;
        synchronized (consoleLock) {
            text = console.toString();
        }
        // Markers are matched against the full text before any of it is consumed. A prompt like
        // "# " arrives on a completed line, so checking after the trim would never see it.
        checkMarkers(text);

        int start = 0;
        int end;
        while ((end = text.indexOf('\n', start)) >= 0) {
            String line = strip(text.substring(start, end));
            System.out.println("  | " + line);
            consoleBytes += line.length() + 1;
            consoleLines++;
            start = end + 1;
        }
        if (start > 0) {
            // Retain only the partial trailing line; complete lines have already been reported.
            synchronized (consoleLock) {
                console.delete(0, start);
            }
        }
    }

    /**
     * Halts the machine as soon as a marker shows up in the output.
     *
     * <p>Matching runs over the trailing text left after the last newline, which is where a partially
     * received line lives, so {@code Linux version} is caught even if it has not been followed by a
     * newline yet.
     */
    private void checkMarkers(String text) {
        if (markerHit) {
            return;
        }
        for (String marker : options.haltMarkers()) {
            if (text.contains(marker)) {
                markerHit = true;
                matchedMarker = marker;
                System.out.println("  ** marker seen: \"" + marker + "\" — stopping the machine");
                engine.stop("halt marker: " + marker);
                return;
            }
        }
    }

    private static String strip(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\r' || line.charAt(end - 1) == 0)) {
            end--;
        }
        return line.substring(0, end);
    }

    // ---- host console input ---------------------------------------------------------------------

    /**
     * Forwards {@code System.in} into the guest's receive FIFO.
     *
     * <p>Daemon and interruption-driven so a closed or absent stdin cannot keep the JVM alive or
     * wedge the runner waiting for input that will never arrive.
     */
    private Thread startStdinPump() {
        Thread pump = new Thread(() -> {
            try (InputStream in = System.in) {
                int b;
                while (!stdinClosed.get() && (b = in.read()) >= 0) {
                    if (b == '\n') {
                        b = '\r';
                    }
                    engine.uart().receiveByte(b);
                }
            } catch (IOException ignored) {
                // A closed host console simply means the guest gets no input.
            }
        }, "riscv-stdin");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    @Override
    public void close() {
        stdinClosed.set(true);
        engine.stop("runner closed");
        engine.close();
    }

    // ---- entry point ----------------------------------------------------------------------------

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    public static void main(String[] args) throws Exception {
        Path kernel = null;
        Path rootfs = null;
        int ramMb = DEFAULT_RAM_MB;
        long timeout = DEFAULT_TIMEOUT_NANOS;
        boolean stdin = false;
        List<String> markers = null;
        String bootargs = null;

        for (String arg : args) {
            int eq = arg.indexOf('=');
            String key = eq < 0 ? arg : arg.substring(0, eq);
            String value = eq < 0 ? "" : arg.substring(eq + 1);
            switch (key) {
                case "--kernel" -> kernel = Path.of(value);
                case "--rootfs" -> rootfs = value.isEmpty() ? null : Path.of(value);
                case "--ram" -> ramMb = Integer.parseInt(value);
                case "--timeout-ms" -> timeout = Long.parseLong(value) * 1_000_000L;
                case "--stdin" -> stdin = true;
                case "--halt-on" -> markers = List.of(value.split(","));
                case "--bootargs" -> bootargs = value;
                case "--help" -> {
                    System.out.println("""
                            usage: LinuxBootRunner --kernel=Image [--rootfs=rootfs.img] [options]

                              --kernel=PATH     raw kernel binary, loaded at 0x80200000 (required)
                              --rootfs=PATH     disk image attached to virtio-blk at 0x10001000
                              --ram=MB          RAM size, default 2048
                              --timeout-ms=N    wall clock budget, default 120000
                              --halt-on=A,B     stop on these strings, default 'Linux version', '# '
                              --bootargs=S      kernel command line
                              --stdin           forward System.in into the guest console""");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        if (kernel == null) {
            System.err.println("error: --kernel is required (try --help)");
            System.exit(2);
        }

        Options options = new Options(kernel, rootfs, ramMb, timeout, markers, stdin, bootargs);
        try (LinuxBootRunner runner = LinuxBootRunner.create(options)) {
            System.out.println("booting " + kernel + " with " + ramMb + " MiB of RAM");
            Result result = runner.run();
            System.out.println();
            System.out.println("---- summary ----");
            System.out.println("  reached marker : " + result.reachedMarker()
                    + (result.marker() != null ? " (" + result.marker() + ")" : ""));
            System.out.println("  halt reason    : " + result.haltReason());
            System.out.println("  retired        : " + result.retiredInstructions()
                    + " instructions");
            System.out.println("  throughput     : "
                    + String.format(Locale.ROOT, "%.1f", result.instructionsPerSecond() / 1e6)
                    + " MIPS");
            System.out.println("  console        : " + result.consoleLines() + " lines, "
                    + result.consoleBytes() + " bytes");
            System.out.println("  machine faults : " + result.machineFaults());
            System.out.println("  S-mode pgfaults: " + result.supervisorPageFaults()
                    + " (normal for a booting kernel)");
            System.out.println("  verdict        : " + (result.succeeded() ? "BOOTED" : "NOT BOOTED"));
            for (Fault fault : result.faults()) {
                System.out.println("    " + fault.detail() + " x" + fault.repeatCount()
                        + " pc=" + hex(fault.pc()));
            }
        }
    }
}