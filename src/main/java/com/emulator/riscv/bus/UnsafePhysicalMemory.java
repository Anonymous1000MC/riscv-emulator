package com.emulator.riscv.bus;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

/**
 * Flat off-heap arena backed by {@code sun.misc.Unsafe}.
 *
 * <p>This is the fast path: every guest load and store becomes a single native memory operation
 * at an absolute offset, with no bounds check and no Java object in between, which is what lets
 * HotSpot inline the interpreter's memory helpers. It also has no 2 GiB ceiling, so machines
 * configured above {@link #MAX_DIRECT_BUFFER_BYTES} still get a contiguous address space.
 *
 * <p>If a JVM ever stops exposing {@code Unsafe}, {@link #isAvailable()} returns {@code false}
 * and the caller falls back to {@link ChunkedPhysicalMemory}.
 */
public final class UnsafePhysicalMemory extends PhysicalMemory {

    private static final Unsafe UNSAFE = resolveUnsafe();
    private static final boolean AVAILABLE = UNSAFE != null;

    private long base;
    private final long length;

    private UnsafePhysicalMemory(long bytes) {
        this.length = bytes;
        this.base = UNSAFE.allocateMemory(bytes);
        UNSAFE.setMemory(base, bytes, (byte) 0);
    }

    private static Unsafe resolveUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether this JVM exposes a usable {@code Unsafe} instance. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * @throws IllegalStateException when {@code Unsafe} is not available on this JVM
     */
    public static PhysicalMemory allocate(long bytes) {
        if (!AVAILABLE) {
            throw new IllegalStateException("sun.misc.Unsafe is unavailable on this JVM");
        }
        return new UnsafePhysicalMemory(bytes);
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public long getLong(long offset) {
        return UNSAFE.getLong(base + offset);
    }

    @Override
    public void putLong(long offset, long value) {
        UNSAFE.putLong(base + offset, value);
    }

    @Override
    public int getInt(long offset) {
        return UNSAFE.getInt(base + offset);
    }

    @Override
    public void putInt(long offset, int value) {
        UNSAFE.putInt(base + offset, value);
    }

    @Override
    public short getShort(long offset) {
        return UNSAFE.getShort(base + offset);
    }

    @Override
    public void putShort(long offset, short value) {
        UNSAFE.putShort(base + offset, value);
    }

    @Override
    public byte getByte(long offset) {
        return UNSAFE.getByte(base + offset);
    }

    @Override
    public void putByte(long offset, byte value) {
        UNSAFE.putByte(base + offset, value);
    }

    @Override
    public void zero() {
        UNSAFE.setMemory(base, length, (byte) 0);
    }

    @Override
    public void close() {
        if (base != 0) {
            UNSAFE.freeMemory(base);
            base = 0;
        }
    }

    @Override
    public byte[] read(long offset, int count) {
        byte[] out = new byte[count];
        long at = base + offset;
        for (int i = 0; i < count; i++) {
            out[i] = UNSAFE.getByte(at + i);
        }
        return out;
    }

    @Override
    public void write(long offset, byte[] data, int srcIndex, int length) {
        long at = base + offset;
        for (int i = 0; i < length; i++) {
            UNSAFE.putByte(at + i, data[srcIndex + i]);
        }
    }

    @Override
    public void read(long offset, byte[] dst, int dstIndex, int count) {
        long at = base + offset;
        for (int i = 0; i < count; i++) {
            dst[dstIndex + i] = UNSAFE.getByte(at + i);
        }
    }

    @Override
    public String describe() {
        return "UnsafePhysicalMemory[" + (length >> 20) + " MiB]";
    }
}