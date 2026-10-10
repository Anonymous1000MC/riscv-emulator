package com.emulator.riscv.bus;

/**
 * A memory-mapped device window.
 *
 * <p>Devices are addressed by absolute physical address; the {@link Memory} table has already
 * verified that {@code pa} falls inside the window before dispatching here.
 */
public interface Device {

    /** Size of the access in bytes: 1, 2, 4 or 8. */
    int read(int size, long pa);

    void write(int size, long pa, int value);

    /** Short identifier used in access-fault messages and crash dumps. */
    default String name() {
        return getClass().getSimpleName();
    }

    /** Called once per emulated block so devices can service timeouts and deferred work. */
    default void tick() {
    }
}