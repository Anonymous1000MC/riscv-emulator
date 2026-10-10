package com.emulator.riscv.cpu;

/**
 * Base class for synchronous traps raised by the execution pipeline.
 *
 * <p>Stack traces are suppressed: trap handling runs on the emulator hot path (every
 * {@code ecall} from S-mode produces one) and materialising a trace would dominate the cost of
 * the exception itself.
 */
public class TrapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Trap cause as defined by the privileged specification. */
    public final long cause;

    /** Faulting value written to {@code mtval}/{@code stval}. */
    public final long value;

    public TrapException(long cause, long value) {
        super(null, null, false, false);
        this.cause = cause;
        this.value = value;
    }

    // Synchronous exception causes.
    public static final int INSTRUCTION_ADDR_MISALIGNED = 0;
    public static final int INSTRUCTION_ACCESS_FAULT = 1;
    public static final int ILLEGAL_INSTRUCTION = 2;
    public static final int BREAKPOINT = 3;
    public static final int LOAD_ADDR_MISALIGNED = 4;
    public static final int LOAD_ACCESS_FAULT = 5;
    public static final int STORE_ADDR_MISALIGNED = 6;
    public static final int STORE_ACCESS_FAULT = 7;
    public static final int USER_ECALL = 8;
    public static final int SUPERVISOR_ECALL = 9;
    public static final int VIRTUAL_SUPERVISOR_ECALL = 10;
    public static final int MACHINE_ECALL = 11;
    public static final int INSTRUCTION_PAGE_FAULT = 12;
    public static final int LOAD_PAGE_FAULT = 13;
    public static final int STORE_PAGE_FAULT = 15;

    /** Asynchronous interrupt causes occupy the top bit of the cause field. */
    public static final long INTERRUPT_BIT = 0x8000_0000_0000_0000L;

    public boolean isInterrupt() {
        return (cause & INTERRUPT_BIT) != 0;
    }
}