package com.emulator.riscv.bus;

/**
 * A memory-mapped window whose writes need to be observed by the device that owns it.
 *
 * <p>The framebuffer uses this instead of being served straight from the RAM arena, so that guest
 * stores can update the damage map without adding a range check to every ordinary RAM access.
 */
public interface PixelWindow {

    int read(int size, long pa);

    void write(int size, long pa, int value);
}
