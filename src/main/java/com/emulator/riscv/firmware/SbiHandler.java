package com.emulator.riscv.firmware;

import com.emulator.riscv.Theon;
import com.emulator.riscv.bus.Bus;

/**
 * The Supervisor Binary Interface.
 *
 * <p>This machine does not ship an OpenSBI binary; the M-mode half of the platform lives here in
 * Java and is invoked directly by {@link com.emulator.riscv.cpu.RiscVCpu} whenever a trap would
 * otherwise land in M-mode. That covers every SBI call the kernel can make, which on a single-hart
 * machine is a small and closed set: timers, inter-hart interrupts, fences and reset.
 *
 * <p>Both the legacy extension (a seven register {@code ecall} with {@code a7 == 0} and the
 * function in {@code a6}) and the version 1.0/2.0 extension identifiers in {@code a7} are
 * implemented, because kernels older than 5.7 probe only the legacy form.
 */
public final class SbiHandler {

    public static final int SBI_SUCCESS = 0;
    public static final int SBI_ERR_FAILED = -1;
    public static final int SBI_ERR_NOT_SUPPORTED = -2;
    public static final int SBI_ERR_INVALID_PARAM = -3;
    public static final int SBI_ERR_DENIED = -4;
    /**
     * Returned when a guest-supplied pointer is not addressable. Real firmware reports this rather
     * than trapping: a guest asking the firmware to write somewhere impossible is the guest's
     * error, and killing the machine for it is how a probe of a half-initialised buffer turns into
     * a dead hart.
     */
    public static final int SBI_ERR_INVALID_ADDRESS = -5;

    // Legacy extension: a7 = 0, a6 selects the call.
    private static final int LEGACY_SET_SCRATCH = 0;
    private static final int LEGACY_BASE_EXT = 3;
    private static final int LEGACY_CONSOLE_PUTCHAR = 1;
    private static final int LEGACY_CONSOLE_GETCHAR = 2;
    private static final int LEGACY_SHUTDOWN = 8;
    private static final int LEGACY_SET_TIMER = 5;
    private static final int LEGACY_TIME = 6;

    // Version 1.0 extension identifiers.
    public static final int EXT_BASE = 0x10;
    public static final int EXT_TIME = 0x11;
    public static final int EXT_IPI = 0x12;
    public static final int EXT_RFENCE = 0x13;
    public static final int EXT_HSM = 0x14;
    public static final int EXT_HFENCE = 0x15;
    public static final int EXT_SYSTEM_RESET = 0x17;
    public static final int EXT_DEBUG_CONSOLE = 0x444D;

    // BASE extension functions.
    private static final int BASE_PROBE = 0;
    private static final int BASE_GET_SPEC = 1;
    private static final int BASE_GET_IMPL_ID = 2;
    private static final int BASE_GET_IMPL_VERSION = 3;
    private static final int BASE_GET_IMPL_NAME = 4;
    private static final int BASE_GET_MACHINE = 5;
    private static final int BASE_GET_MACHINE_VERSION = 6;
    private static final int BASE_GET_SBI_IMPL_ID = 7;

    // TIME extension functions.
    private static final int TIME_TIME = 0;
    private static final int TIME_CSR_FREQ = 1;
    private static final int TIME_CSR_READ = 2;
    private static final int TIME_CSR_WRITE = 3;

    private static final long SBI_IMPL_ID = 0x4D525356L; // "MRSV"
    private static final String IMPL_NAME = Theon.NAME;
    private static final long SBI_SPEC_MAJOR = 2;
    private static final long SBI_SPEC_MINOR = 0;

    private final long timeFrequency;
    private final Runnable shutdownAction;
    private final Runnable rebootAction;

    private long scratch0;
    private long scratch1;
    private long pendingTimer;
    private long callsHandled;
    private long callsFailed;

    public SbiHandler(long timeFrequency, Runnable shutdownAction, Runnable rebootAction) {
        this.timeFrequency = timeFrequency;
        this.shutdownAction = shutdownAction;
        this.rebootAction = rebootAction;
    }

    public long callsHandled() {
        return callsHandled;
    }

    public long callsFailed() {
        return callsFailed;
    }

    /**
     * Services one SBI call.
     *
     * @param regs the hart's register file; results are written back into {@code a0} and
     *             {@code a1} exactly as the specification prescribes
     * @return the SBI return value for {@code a0}, or {@code a1} for the two-register calls
     */
    public long call(long[] regs, Bus bus) {
        long function = regs[17]; // a7
        callsHandled++;
        long result = function == 0 ? legacy(regs) : extended(function, regs, bus);
        if (result < 0) {
            callsFailed++;
            // Errors have to be reported in a0. The success paths already write their result
            // there themselves, and overwriting here would discard it; the negative paths leave
            // a0 holding whatever the caller passed in, which the guest would then read as a
            // successful result. A kernel probing for an extension it does not get would carry
            // on as if the call worked.
            regs[10] = result;
        }
        return result;
    }

    private long legacy(long[] regs) {
        long function = regs[16]; // a6
        switch ((int) function) {
            case LEGACY_SET_SCRATCH:
                scratch0 = regs[10];
                scratch1 = regs[11];
                return SBI_SUCCESS;
            case LEGACY_CONSOLE_PUTCHAR:
                return SBI_SUCCESS;
            case LEGACY_CONSOLE_GETCHAR:
                regs[10] = -1L;
                return SBI_SUCCESS;
            case LEGACY_BASE_EXT:
                regs[10] = 1L; // one SBI extension is implemented
                return SBI_SUCCESS;
            case LEGACY_TIME:
                regs[10] = now();
                return SBI_SUCCESS;
            case LEGACY_SET_TIMER:
                pendingTimer = regs[10];
                return SBI_SUCCESS;
            case LEGACY_SHUTDOWN:
                shutdownAction.run();
                return SBI_SUCCESS;
            default:
                return SBI_ERR_NOT_SUPPORTED;
        }
    }

    private long extended(long function, long[] regs, Bus bus) {
        switch ((int) function) {
            case EXT_BASE:
                return base(regs, bus);
            case EXT_TIME:
                return time(regs);
            case EXT_IPI:
                // A single hart never needs to interrupt another hart.
                return SBI_SUCCESS;
            case EXT_RFENCE, EXT_HFENCE:
                return SBI_SUCCESS;
            case EXT_HSM:
                // Only hart zero exists, so start/stop/suspend/resume are either no-ops or
                // genuinely unsupported.
                return regs[10] == 0 ? SBI_SUCCESS : SBI_ERR_NOT_SUPPORTED;
            case EXT_SYSTEM_RESET:
                return systemReset(regs);
            case EXT_DEBUG_CONSOLE:
                return debugConsole(regs, bus);
            default:
                return SBI_ERR_NOT_SUPPORTED;
        }
    }

    /** The v2.0 extension identifiers this handler answers for, probed by ID and not by name. */
    private static boolean isImplementedExtension(long extension) {
        return switch ((int) extension) {
            case EXT_BASE, EXT_TIME, EXT_IPI, EXT_RFENCE, EXT_HSM, EXT_HFENCE,
                 EXT_SYSTEM_RESET, EXT_DEBUG_CONSOLE -> true;
            default -> false;
        };
    }

    private long base(long[] regs, Bus bus) {
        switch ((int) regs[16]) {   // a6 holds the function id
            case BASE_PROBE: {
                long extension = regs[11];
                // Probe has to name every extension this handler actually services. Reporting only
                // the base extension makes a kernel conclude there is no timer, no IPI and no HSM,
                // and it then stops asking for them - which is why the specification's function
                // numbering matters: probe is 0, the spec version is 1, and the implementation ID
                // is 2.
                regs[10] = isImplementedExtension(extension) ? SBI_SUCCESS : SBI_ERR_NOT_SUPPORTED;
                return SBI_SUCCESS;
            }
            case BASE_GET_SPEC:
                regs[10] = (SBI_SPEC_MAJOR << 24) | (SBI_SPEC_MINOR << 16);
                return SBI_SUCCESS;
            case BASE_GET_IMPL_ID:
                regs[10] = SBI_IMPL_ID;
                return SBI_SUCCESS;
            case BASE_GET_IMPL_VERSION:
                regs[10] = 0x00010000L;
                return SBI_SUCCESS;
            case BASE_GET_IMPL_NAME: {
                long err = writeName(bus, regs[11], IMPL_NAME);
                if (err != SBI_SUCCESS) {
                    return err;
                }
                regs[10] = SBI_SUCCESS;
                return SBI_SUCCESS;
            }
            case BASE_GET_MACHINE:
                regs[10] = 0x000D_0000L;
                return SBI_SUCCESS;
            case BASE_GET_MACHINE_VERSION:
                regs[10] = 0x00010000L;
                return SBI_SUCCESS;
            case BASE_GET_SBI_IMPL_ID:
                regs[10] = SBI_SUCCESS;
                regs[11] = SBI_IMPL_ID;
                regs[12] = 0x00010000L;
                return SBI_SUCCESS;
            default:
                return SBI_ERR_NOT_SUPPORTED;
        }
    }

    /** Writes a NUL padded 64 byte identifier into the scratch area the caller supplied in a1. */
    private static long writeName(Bus bus, long address, String text) {
        byte[] buffer = new byte[64];
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buffer, 0, Math.min(bytes.length, 63));
        return bus.dmaWrite(buffer, 0, buffer.length, address) ? SBI_SUCCESS
                : SBI_ERR_INVALID_ADDRESS;
    }

    private long time(long[] regs) {
        switch ((int) regs[16]) {   // a6 holds the function id
            case TIME_TIME:
                regs[10] = now();
                regs[11] = SBI_SUCCESS;
                return SBI_SUCCESS;
            case TIME_CSR_FREQ:
                regs[10] = timeFrequency;
                regs[11] = SBI_SUCCESS;
                return SBI_SUCCESS;
            case TIME_CSR_READ:
                regs[10] = timeFrequency;
                regs[11] = now();
                return SBI_SUCCESS;
            case TIME_CSR_WRITE:
                pendingTimer = regs[11];
                regs[10] = SBI_SUCCESS;
                return SBI_SUCCESS;
            default:
                return SBI_ERR_NOT_SUPPORTED;
        }
    }

    private long systemReset(long[] regs) {
        long type = regs[10];
        long reason = regs[11];
        switch ((int) type) {
            case 0 -> shutdownAction.run();          // shutdown
            case 1 -> rebootAction.run();            // cold reboot
            case 2 -> rebootAction.run();            // warm reboot
            default -> {
                return SBI_ERR_INVALID_PARAM;
            }
        }
        return SBI_SUCCESS;
    }

    private long debugConsole(long[] regs, Bus bus) {
        switch ((int) regs[16]) {   // a6 holds the function id
            case 0 -> regs[10] = SBI_SUCCESS;                 // probe extension
            case 1, 3, 4 -> {                                 // console writes
                return SBI_SUCCESS;
            }
            case 2 -> regs[10] = 0L;                          // console read: no data
            default -> {
                return SBI_ERR_NOT_SUPPORTED;
            }
        }
        return SBI_SUCCESS;
    }

    private long now() {
        return TimeSource.get();
    }

    public long pendingTimer() {
        return pendingTimer;
    }

    public void setTimeSource(java.util.function.LongSupplier source) {
        TimeSource.SUPPLIER = source == null ? () -> 0L : source;
    }

    public long scratch0() {
        return scratch0;
    }

    public long scratch1() {
        return scratch1;
    }

    private static final class TimeSource {
        static java.util.function.LongSupplier SUPPLIER = () -> 0L;

        static long get() {
            return SUPPLIER.getAsLong();
        }
    }
}