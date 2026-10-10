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

    // Extension identifiers are the ASCII fourcc, not small ordinals. Probing is done by comparing
    // the id a guest passes against these, which is why the kernel sends 0x48534d for HSM.
    public static final int EXT_BASE = 0x10;
    public static final int EXT_TIME = 0x54494D45;   // "TIME"
    public static final int EXT_IPI = 0x735049;      // "IPI"
    public static final int EXT_RFENCE = 0x52464E43; // "RFNC"
    public static final int EXT_HSM = 0x48534D;      // "HSM"
    public static final int EXT_SYSTEM_RESET = 0x53525354; // "SRST"
    public static final int EXT_DEBUG_CONSOLE = 0x444D4443; // "DBCN"

    // BASE extension (EID 0x10) function ids, as the specification fixes them. The machine
    // identification group sits at 4, 5 and 6, and there is no implementation-name function at
    // all: this handler previously had the whole table shifted by one, so a kernel asking for the
    // specification version (FID 0) was answered by the probe logic instead, which reported
    // "extension 0 is not implemented" and made Linux fall back to SBI v0.
    private static final int BASE_GET_SPEC_VERSION = 0;
    private static final int BASE_GET_IMPL_ID = 1;
    private static final int BASE_GET_IMPL_VERSION = 2;
    private static final int BASE_PROBE_EXT = 3;
    private static final int BASE_GET_MVENDORID = 4;
    private static final int BASE_GET_MARCHID = 5;
    private static final int BASE_GET_MIMPID = 6;

    // TIME extension functions.
    private static final int TIME_SET_TIMER = 0;

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
            case EXT_RFENCE:
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
            case EXT_BASE, EXT_TIME, EXT_IPI, EXT_RFENCE, EXT_HSM,
                 EXT_SYSTEM_RESET, EXT_DEBUG_CONSOLE -> true;
            default -> false;
        };
    }

    private long base(long[] regs, Bus bus) {
        // The SBI return convention is: the error code in a0, the value in a1. Writing a value
        // into a0 instead made a kernel read "success" out of a version number and vice versa.
        switch ((int) regs[16]) {   // a6 holds the function id
            case BASE_GET_SPEC_VERSION -> {
                regs[10] = SBI_SUCCESS;
                regs[11] = (SBI_SPEC_MAJOR << 24) | SBI_SPEC_MINOR;
                return SBI_SUCCESS;
            }
            case BASE_GET_IMPL_ID -> {
                regs[10] = SBI_SUCCESS;
                regs[11] = SBI_IMPL_ID;
                return SBI_SUCCESS;
            }
            case BASE_GET_IMPL_VERSION -> {
                regs[10] = SBI_SUCCESS;
                regs[11] = 0x00010000L;
                return SBI_SUCCESS;
            }
            case BASE_PROBE_EXT -> {
                // FID 3, and the extension being probed arrives in a0. Reporting "not supported"
                // for an extension this handler services is how the kernel lost its timer.
                long extension = regs[10];
                regs[10] = SBI_SUCCESS;
                regs[11] = isImplementedExtension(extension) ? 1 : 0;
                return SBI_SUCCESS;
            }
            case BASE_GET_MVENDORID, BASE_GET_MARCHID, BASE_GET_MIMPID -> {
                // Plain value registers with no pointer, so nothing is ever written back here.
                regs[10] = SBI_SUCCESS;
                regs[11] = 0;
                return SBI_SUCCESS;
            }
            default -> {
                return SBI_ERR_NOT_SUPPORTED;
            }
        }
    }

    private long time(long[] regs) {
        switch ((int) regs[16]) {   // a6 holds the function id
            case TIME_SET_TIMER -> {
                // FID 0. The compare value arrives in a0; Linux arms the timer through this and
                // then waits for the CLINT comparison to raise the interrupt.
                pendingTimer = regs[10];
                return SBI_SUCCESS;
            }
            default -> {
                return SBI_ERR_NOT_SUPPORTED;
            }
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