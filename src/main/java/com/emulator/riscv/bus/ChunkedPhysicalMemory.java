package com.emulator.riscv.bus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Portable off-heap arena built from little-endian direct {@link ByteBuffer}s.
 *
 * <p>Used when {@code Unsafe} is unavailable or switched off in the mod configuration. Because
 * a single direct buffer cannot address more than {@link Integer#MAX_VALUE} bytes, the arena is
 * split into equally sized chunks; a machine of 8 GiB becomes sixteen 512 MiB chunks. Chunk
 * selection is a shift and a mask, so the fast path stays a couple of extra ALU operations.
 */
public final class ChunkedPhysicalMemory extends PhysicalMemory {

    /** A direct buffer must stay comfortably below {@link Integer#MAX_VALUE} to leave room for
     *  {@code ByteBuffer.allocateDirect} headers on some collectors. */
    public static final int MAX_DIRECT_BUFFER_BYTES = 1 << 30;

    private static final int CHUNK_SHIFT = 29; // 512 MiB
    private static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private final ByteBuffer[] chunks;
    private final long length;
    private final int chunkShift;
    private final long chunkMask;

    private ChunkedPhysicalMemory(long bytes) {
        this.length = bytes;
        int chunkSize = CHUNK_SIZE;
        if (bytes < CHUNK_SIZE) {
            chunkSize = (int) Math.max(1, roundUpPow2(bytes));
        }
        int shift = Integer.numberOfTrailingZeros(chunkSize);
        this.chunkShift = shift;
        this.chunkMask = chunkSize - 1L;
        int count = (int) ((bytes + chunkSize - 1) >>> shift);
        this.chunks = new ByteBuffer[count];
        for (int i = 0; i < count; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(chunkSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            chunks[i] = buffer;
        }
    }

    private static long roundUpPow2(long value) {
        long v = 1;
        while (v < value && v < (1L << 30)) {
            v <<= 1;
        }
        return v;
    }

    public static PhysicalMemory allocate(long bytes) {
        return new ChunkedPhysicalMemory(bytes);
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public long getLong(long offset) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        return c.getLong((int) (offset & chunkMask));
    }

    @Override
    public void putLong(long offset, long value) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        c.putLong((int) (offset & chunkMask), value);
    }

    @Override
    public int getInt(long offset) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        return c.getInt((int) (offset & chunkMask));
    }

    @Override
    public void putInt(long offset, int value) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        c.putInt((int) (offset & chunkMask), value);
    }

    @Override
    public short getShort(long offset) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        return c.getShort((int) (offset & chunkMask));
    }

    @Override
    public void putShort(long offset, short value) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        c.putShort((int) (offset & chunkMask), value);
    }

    @Override
    public byte getByte(long offset) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        return c.get((int) (offset & chunkMask));
    }

    @Override
    public void putByte(long offset, byte value) {
        ByteBuffer c = chunks[(int) (offset >>> chunkShift)];
        c.put((int) (offset & chunkMask), value);
    }

    @Override
    public void zero() {
        for (ByteBuffer chunk : chunks) {
            for (int i = 0; i < chunk.capacity(); i++) {
                chunk.put(i, (byte) 0);
            }
        }
    }

    @Override
    public void close() {
        // Direct buffers are reclaimed by the cleaner; nothing deterministic to do here.
    }

    @Override
    public byte[] read(long offset, int count) {
        byte[] out = new byte[count];
        int done = 0;
        while (done < count) {
            ByteBuffer c = chunks[(int) ((offset + done) >>> chunkShift)];
            int index = (int) ((offset + done) & chunkMask);
            int n = Math.min(count - done, c.capacity() - index);
            c.position(index);
            c.get(out, done, n);
            done += n;
        }
        return out;
    }

    @Override
    public void write(long offset, byte[] data, int srcIndex, int length) {
        int done = 0;
        while (done < length) {
            ByteBuffer c = chunks[(int) ((offset + done) >>> chunkShift)];
            int index = (int) ((offset + done) & chunkMask);
            int n = Math.min(length - done, c.capacity() - index);
            c.position(index);
            c.put(data, srcIndex + done, n);
            done += n;
        }
    }

    @Override
    public String describe() {
        return "ChunkedPhysicalMemory[" + (length >> 20) + " MiB, " + chunks.length + " chunks]";
    }
}