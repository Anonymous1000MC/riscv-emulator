package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;

/**
 * SiFive test finisher.
 *
 * <p>Boards map a tiny device here so user space can shut the machine down without a full
 * power-off path. Keeping it means {@code poweroff} and {@code reboot} work in a guest that
 * expects them instead of trapping on an unmapped access.
 */
public final class SiFiveTestDevice implements Device {

    public static final int FINISHER_PASS = 0x5555;
    public static final int FINISHER_FAIL = 0x7777;
    public static final int FINISHER_RESET = 0x3333;

    private Runnable shutdownHandler = () -> { };

    public void setShutdownHandler(Runnable handler) {
        this.shutdownHandler = handler == null ? () -> { } : handler;
    }

    @Override
    public int read(int size, long pa) {
        // There is no readable register; returning zero keeps speculative load probes harmless.
        return 0;
    }

    @Override
    public void write(int size, long pa, int value) {
        int operation = value & 0xFFFF;
        if (operation == FINISHER_PASS || operation == FINISHER_FAIL) {
            return;
        }
        if (operation == FINISHER_RESET) {
            shutdownHandler.run();
        }
    }

    /** Requests a machine shutdown from the host side. */
    public void requestShutdown() {
        shutdownHandler.run();
    }

    @Override
    public String name() {
        return "sifive-test";
    }

    public static long base() {
        return MemoryMap.SIFIVE_TEST;
    }
}