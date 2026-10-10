package com.emulator.riscv.bus;

/**
 * Off-heap storage backing the guest's physical address space.
 *
 * <p>Two implementations exist: an {@code sun.misc.Unsafe} arena that provides a single flat
 * 64-bit addressable block (the fast path), and a chunked direct {@link java.nio.ByteBuffer}
 * arena used when {@code Unsafe} is unavailable or explicitly disabled. Both expose the same
 * primitive accessors so {@link Memory} can be written once and remain monomorphic at the call
 * site for a given machine instance.
 */
public abstract class PhysicalMemory {

    /** Number of bytes addressable through this arena. */
    public abstract long size();

    public abstract long getLong(long offset);

    public abstract void putLong(long offset, long value);

    public abstract int getInt(long offset);

    public abstract void putInt(long offset, int value);

    public abstract short getShort(long offset);

    public abstract void putShort(long offset, short value);

    public abstract byte getByte(long offset);

    public abstract void putByte(long offset, byte value);

    public abstract void zero();

    /** Releases the native allocation. */
    public abstract void close();

    /** Human readable description used in crash logs. */
    public abstract String describe();

    /** Copies a block of guest memory out of the arena, for debugging and disk image building. */
    public byte[] read(long offset, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = getByte(offset + i);
        }
        return out;
    }

    /** Writes a block of bytes into the arena. */
    public void write(long offset, byte[] data, int srcIndex, int length) {
        for (int i = 0; i < length; i++) {
            putByte(offset + i, data[srcIndex + i]);
        }
    }

    /** Reads a block of bytes straight into {@code dst} without allocating an intermediate array. */
    public void read(long offset, byte[] dst, int dstIndex, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstIndex + i] = getByte(offset + i);
        }
    }
}