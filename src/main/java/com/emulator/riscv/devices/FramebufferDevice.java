package com.emulator.riscv.devices;

import java.nio.ByteBuffer;

import com.emulator.riscv.bus.PixelWindow;
import com.emulator.riscv.bus.PhysicalMemory;

/**
 * The machine's display.
 *
 * <h2>Where the pixels live</h2>
 * The buffer is carved out of the top of guest RAM rather than kept in a separate allocation,
 * because virtio-framebuffer guests reach it by DMA: the driver is handed a physical address in
 * {@code framebuffer_start} and then the page allocator, the driver model and the compositor all
 * write to that address directly. A buffer outside the RAM arena would be invisible to those
 * stores.
 *
 * <p>That window is routed through this class instead of being served straight from the arena, so
 * that every guest write updates the damage map. Keeping it out of the RAM hot path costs nothing
 * beyond the dispatch: ordinary RAM accesses still take the single range check in {@code Memory},
 * and only accesses landing in this window take the extra hop.
 *
 * <h2>Damage tracking</h2>
 * Writes are bucketed into 64x64 pixel tiles. Sequential writes - what a compositor scanline
 * actually produces - are absorbed by a one-entry cache of the last touched tile, so the common
 * case costs a compare. The tile map is coalesced into one rectangle per upload so the renderer
 * can call a single {@code glTexSubImage2D}.
 */
public final class FramebufferDevice extends AbstractVirtioDevice implements PixelWindow {

    /** Tile granularity in both axes. */
    private static final int TILE_SHIFT = 6;
    private static final int TILE_SIZE = 1 << TILE_SHIFT;

    /** virtio pixel formats: {@code VIRTIO_FB_RGB} then {@code VIRTIO_FB_RGBA}. */
    public static final long PIXFMT_RGB = 0;
    public static final long PIXFMT_RGBA = 1;

    private final PhysicalMemory arena;
    private final long physicalBase;
    private final long pixelOffset;

    private final int width;
    private final int height;
    private final int bytesPerRow;
    private final int tilesX;
    private final int tilesY;

    private final long[] dirtyTiles;
    private int dirtyX0 = -1;
    private int dirtyY0 = -1;
    private int dirtyX1 = -1;
    private int dirtyY1 = -1;

    /** One-entry cache of the last touched tile so scanline-ordered writes stay division-free. */
    private long cachedTileStart = Long.MIN_VALUE;
    private long cachedTileEnd = Long.MIN_VALUE;
    private int cachedTileIndex = -1;

    private long configWidth;
    private long configHeight;
    private long configPixelFormat = PIXFMT_RGBA;
    private long configFramebufferStart;
    private long configRefreshRate = 60;
    private long configLineLength;
    private long configEnabled;

    private final InterruptSource interruptController;
    private final int irq;
    private long lastVblankNanos;
    private long frameSerial;

    public FramebufferDevice(PhysicalMemory arena, long physicalBase, long ramBase,
                             int width, int height, InterruptSource interruptController, int irq) {
        this.arena = arena;
        this.physicalBase = physicalBase;
        this.pixelOffset = physicalBase - ramBase;
        this.width = width;
        this.height = height;
        this.bytesPerRow = width * 4;
        this.tilesX = (width + TILE_SIZE - 1) >> TILE_SHIFT;
        this.tilesY = (height + TILE_SIZE - 1) >> TILE_SHIFT;
        this.dirtyTiles = new long[(tilesX * tilesY + 63) >>> 6];
        this.configWidth = width;
        this.configFramebufferStart = physicalBase;
        this.configLineLength = bytesPerRow;
        this.interruptController = interruptController;
        this.irq = irq;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long physicalBase() {
        return physicalBase;
    }

    public long sizeBytes() {
        return (long) width * height * 4;
    }

    public long frameSerial() {
        return frameSerial;
    }

    public boolean enabled() {
        return configEnabled != 0;
    }

    public long refreshRate() {
        return configRefreshRate;
    }

    // ---- damage tracking --------------------------------------------------------------------

    public boolean hasDamage() {
        return dirtyX0 >= 0;
    }

    /**
     * Returns the bounding rectangle of everything written since the previous call and clears the
     * damage state.
     *
     * @return {@code null} when nothing changed, otherwise {@code x | y<<12 | w<<24 | h<<36}
     */
    public Long consumeDamageRect() {
        if (dirtyX0 < 0) {
            return null;
        }
        int x0 = dirtyX0;
        int y0 = dirtyY0;
        int w = dirtyX1 - x0 + 1;
        int h = dirtyY1 - y0 + 1;
        clearDamage();
        return (long) x0 | ((long) y0 << 12) | ((long) w << 24) | ((long) h << 36);
    }

    private void clearDamage() {
        dirtyX0 = -1;
        dirtyY0 = -1;
        dirtyX1 = -1;
        dirtyY1 = -1;
        java.util.Arrays.fill(dirtyTiles, 0L);
        cachedTileStart = Long.MIN_VALUE;
        cachedTileEnd = Long.MIN_VALUE;
        cachedTileIndex = -1;
    }

    private void markDirtyRange(long start, int size) {
        if (cachedTileIndex >= 0 && start >= cachedTileStart && start + size <= cachedTileEnd) {
            setDirtyTile(cachedTileIndex);
            return;
        }
        long end = start + size;
        long at = start;
        while (at < end) {
            int y = (int) (at / bytesPerRow);
            int x = (int) ((at % bytesPerRow) >> 2);
            int tileX = x >> TILE_SHIFT;
            int tileY = y >> TILE_SHIFT;
            markTile(tileX, tileY);
            long tileEnd = tileStart(tileX, tileY) + tileByteSize();
            at = Math.max(at + 1, tileEnd);
        }
    }

    private long tileStart(int tileX, int tileY) {
        return (long) tileY * TILE_SIZE * bytesPerRow + (long) tileX * TILE_SIZE * 4;
    }

    private long tileByteSize() {
        return (long) TILE_SIZE * TILE_SIZE * 4;
    }

    private void markTile(int tileX, int tileY) {
        if (tileX < 0 || tileY < 0 || tileX >= tilesX || tileY >= tilesY) {
            return;
        }
        int index = tileY * tilesX + tileX;
        long start = tileStart(tileX, tileY);
        cachedTileStart = start;
        cachedTileEnd = start + tileByteSize();
        cachedTileIndex = index;
        setDirtyTile(index);
    }

    private void setDirtyTile(int index) {
        dirtyTiles[index >>> 6] |= 1L << (index & 63);
        int tileX = index % tilesX;
        int tileY = index / tilesX;
        int x0 = tileX << TILE_SHIFT;
        int y0 = tileY << TILE_SHIFT;
        int x1 = Math.min(x0 + TILE_SIZE - 1, width - 1);
        int y1 = Math.min(y0 + TILE_SIZE - 1, height - 1);
        if (dirtyX0 < 0) {
            dirtyX0 = x0;
            dirtyY0 = y0;
            dirtyX1 = x1;
            dirtyY1 = y1;
        } else {
            if (x0 < dirtyX0) dirtyX0 = x0;
            if (y0 < dirtyY0) dirtyY0 = y0;
            if (x1 > dirtyX1) dirtyX1 = x1;
            if (y1 > dirtyY1) dirtyY1 = y1;
        }
    }

    /** Marks the entire buffer as damaged; used when the machine is created. */
    public void markAllDamaged() {
        for (int y = 0; y < tilesY; y++) {
            for (int x = 0; x < tilesX; x++) {
                markTile(x, y);
            }
        }
    }

    // ---- pixel access ------------------------------------------------------------------------

    /** Copies a rectangle into {@code dst} as tightly packed RGBA rows. */
    public void copyRegion(int x, int y, int w, int h, ByteBuffer dst) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int clampedX = Math.max(0, Math.min(x, width));
        int clampedY = Math.max(0, Math.min(y, height));
        int clampedW = Math.min(w, width - clampedX);
        int clampedH = Math.min(h, height - clampedY);
        if (clampedW <= 0 || clampedH <= 0) {
            return;
        }
        dst.clear();
        for (int row = 0; row < clampedH; row++) {
            long offset = pixelOffset
                    + (long) (clampedY + row) * bytesPerRow + (long) clampedX * 4L;
            for (int col = 0; col < clampedW; col++) {
                dst.putInt(arena.getInt(offset + (long) col * 4L));
            }
        }
        dst.flip();
    }

    /** Copies the whole buffer; the fallback path when the renderer wants a single upload. */
    public void copyWholeFrame(ByteBuffer dst) {
        copyRegion(0, 0, width, height, dst);
    }

    /** Fills the buffer with opaque black and marks everything damaged. */
    public void clear() {
        for (int row = 0; row < height; row++) {
            long offset = pixelOffset + (long) row * bytesPerRow;
            for (int col = 0; col < width; col++) {
                arena.putInt(offset + (long) col * 4L, 0xFF00_0000);
            }
        }
        markAllDamaged();
    }

    /** True when {@code pa} falls inside this device's memory-mapped pixel window. */
    public boolean covers(long pa) {
        return pa >= physicalBase && pa < physicalBase + sizeBytes();
    }

    @Override
    public int read(int size, long pa) {
        long offset = pixelOffset + (pa - physicalBase);
        return switch (size) {
            case 1 -> arena.getByte(offset) & 0xFF;
            case 2 -> arena.getShort(offset) & 0xFFFF;
            case 4 -> arena.getInt(offset);
            default -> (int) arena.getLong(offset);
        };
    }

    @Override
    public void write(int size, long pa, int value) {
        long offset = pixelOffset + (pa - physicalBase);
        switch (size) {
            case 1 -> arena.putByte(offset, (byte) value);
            case 2 -> arena.putShort(offset, (short) value);
            case 4 -> arena.putInt(offset, value);
            default -> arena.putLong(offset, value & 0xFFFF_FFFF_FFFF_FFFFL);
        }
        markDirtyRange(offset, size);
    }

    // ---- VirtioDevice -------------------------------------------------------------------------

    @Override
    public int deviceId() {
        return VirtioDevice.ID_FRAMEBUFFER;
    }

    @Override
    public long deviceFeatures() {
        return 0L;
    }

    @Override
    public void writeFeatures(long features) {
    }

    @Override
    public int queueCount() {
        return 1;
    }

    @Override
    public int queueMaxSize() {
        return 0;
    }

    @Override
    public void notify(int queueIndex) {
        // The framebuffer device has no data queue; the driver only ever touches configuration.
    }

    @Override
    public void writeConfig(int offset, long value) {
        switch (offset) {
            case 0 -> configWidth = value & 0xFFFF_FFFFL;
            case 8 -> configHeight = value & 0xFFFF_FFFFL;
            case 16 -> configPixelFormat = value & 0xFFFF_FFFFL;
            case 24 -> configFramebufferStart = value & 0xFFFF_FFFFL;
            case 32 -> configRefreshRate = value & 0xFFFF_FFFFL;
            case 40 -> configLineLength = value & 0xFFFF_FFFFL;
            case 48 -> {
                configEnabled = value & 0xFFFF_FFFFL;
                if (configEnabled != 0) {
                    interruptController.assertSource(irq);
                    lastVblankNanos = System.nanoTime();
                    frameSerial++;
                }
            }
            default -> {
            }
        }
    }

    @Override
    public long readConfig(int offset) {
        return switch (offset) {
            case 0 -> configWidth;
            case 8 -> configHeight;
            case 16 -> configPixelFormat;
            case 24 -> configFramebufferStart;
            case 32 -> configRefreshRate;
            case 40 -> configLineLength;
            case 48 -> configEnabled;
            default -> 0L;
        };
    }

    @Override
    public void tick() {
        if (configEnabled == 0) {
            return;
        }
        long periodNanos = 1_000_000_000L / Math.max(1L, configRefreshRate);
        long now = System.nanoTime();
        if (now - lastVblankNanos >= periodNanos) {
            lastVblankNanos = now;
            frameSerial++;
            interruptController.assertSource(irq);
        }
    }

    @Override
    public String name() {
        return "virtio-fb";
    }
}