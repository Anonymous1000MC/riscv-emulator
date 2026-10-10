package com.emulator.riscv.cpu;

/** Raised when a CSR address is unimplemented, read-only, or the encoding is not legal. */
public final class IllegalInstructionException extends TrapException {

    private static final long serialVersionUID = 1L;

    private final int csrAddress;

    public IllegalInstructionException(int csrAddress, String message) {
        super(ILLEGAL_INSTRUCTION, 0L);
        this.csrAddress = csrAddress;
    }

    public int csrAddress() {
        return csrAddress;
    }
}