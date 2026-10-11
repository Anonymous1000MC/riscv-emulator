package com.emulator.riscv.devices;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.emulator.riscv.bus.Bus;
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
    private final Bus bus;
    private final long physicalBase;
    private final long pixelOffset;
    private final long ramBase;
    private final byte[] publishScratch = new byte[1 << 16];

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

    public FramebufferDevice(Bus bus, PhysicalMemory arena, long physicalBase, long ramBase,
                             int width, int height, InterruptSource interruptController, int irq) {
        this.bus = bus;
        this.arena = arena;
        this.physicalBase = physicalBase;
        this.pixelOffset = physicalBase - ramBase;
        this.ramBase = ramBase;
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
        // virtio-mmio version 2 only speaks the modern protocol, and the guest's driver refuses to
        // probe a device that does not offer it. Without this bit the kernel logged
        // "must provide VIRTIO_F_VERSION_1 feature!" and left the scanout unbound.
        return FEATURE_VERSION_1 | FEATURE_ACCESS_PLATFORM;
    }

    @Override
    public void writeFeatures(long features) {
    }

    // ---- virtio-gpu ------------------------------------------------------------------------
    // Linux has never shipped a driver for VIRTIO_ID_FRAMEBUFFER (device id 11), so a device that
    // answers with 11 is never bound by anything. virtio-gpu is the only display device a mainline
    // guest can actually drive, and this class speaks it.

    /** {@code VIRTIO_GPU_CMD_RESOURCE_CREATE_2D}. */
    private static final int CMD_RESOURCE_CREATE_2D = 0x0101;
    /** {@code VIRTIO_GPU_CMD_SET_SCANOUT}. */
    private static final int CMD_SET_SCANOUT = 0x0103;
    /** {@code VIRTIO_GPU_CMD_RESOURCE_FLUSH}. */
    private static final int CMD_RESOURCE_FLUSH = 0x0104;
    /** {@code VIRTIO_GPU_CMD_GET_DISPLAY_INFO}. */
    private static final int CMD_GET_DISPLAY_INFO = 0x0100;
    private static final int CMD_RESOURCE_UNREF = 0x0102;
    private static final int CMD_TRANSFER_TO_HOST_2D = 0x0105;
    private static final int CMD_RESOURCE_ATTACH_BACKING = 0x0106;
    private static final int CMD_UPDATE_CURSOR = 0x0300;
    private static final int CMD_MOVE_CURSOR = 0x0301;

    /** {@code VIRTIO_GPU_RESP_OK_NODATA}. */
    private static final int RESP_OK_NODATA = 0x1100;
    /** {@code VIRTIO_GPU_RESP_OK_DISPLAY_INFO}. */
    private static final int RESP_OK_DISPLAY_INFO = 0x1101;
    /** {@code VIRTIO_GPU_RESP_ERR_UNSUPPORTED}. */
    private static final int RESP_ERR_UNSUPPORTED = 0x1104;

    /** {@code VIRTIO_GPU_FLAG_FENCE}: the reply must carry the fence back. */
    private static final int FLAG_FENCE = 0x1;

    /** Set on a command's type when the device must not answer it. */
    private static final int CMD_FLAG_NO_RESPONSE = 0x1;

    /** {@code VIRTIO_GPU_EVENT_CHANGE}, reported through {@code events_read}. */
    private static final int EVENT_CHANGE = 0x1;

    /** The one scanout this device owns. The guest creates resource 1 to mean the display. */
    private static final long SCANOUT_RESOURCE = 1;

    private static final int HDR_SIZE = 24;

    private long eventsRead;
    private long ioRequests;
    private int controlQueueSize;
    private int controlAvail;
    private int controlUsed;
    private final java.util.Map<Long, long[]> resources = new java.util.HashMap<>();
    /** Resource currently scanned out, and whether the guest asked for one. */
    private long scanoutResource = -1;
    private boolean scanoutActive;
    /**
     * Guest buffers backing each resource. The guest allocates its own pages, draws into them and
     * flushes; it never writes into the window this device advertises. Without tracking the
     * backing here the scanout resolves to real guest memory that nothing ever looks at, which is
     * why a correctly driven protocol still produced a black screen.
     */
    private final java.util.Map<Long, List<long[]>> resourceBacking = new java.util.HashMap<>();
    private long scanoutBackingAddress = -1;
    private long scanoutBackingLength;

    @Override
    public int queueCount() {
        // controlq and cursorq. The cursor queue is advertised because the driver always programs
        // both, but this device draws no cursor.
        return 2;
    }

    @Override
    public int queueMaxSize() {
        return 64;
    }

    @Override
    public void queueReady(int queueIndex, Virtqueue queue) {
        if (queueIndex == 0) {
            controlQueue = queue;
        }
    }

    private Virtqueue controlQueue;

    @Override
    public void notify(int queueIndex) {
        if (queueIndex != 0 || controlQueue == null) {
            return;
        }
        Bus bus = this.bus;
        int size = controlQueue.size();
        int available = VirtioMmioTransport.availIndex(controlQueue, bus);
        while (controlAvail != available) {
            int head = VirtioMmioTransport.availEntry(controlQueue, bus,
                    controlAvail & (size - 1));
            int written = serveControlRequest(head);
            controlAvail = (controlAvail + 1) & 0xFFFF;
            controlUsed++;
            // virtio-gpu's driver does not block on completions, but it reclaims buffers from the
            // used ring, so the element still has to be published or it will run out of them.
            VirtioMmioTransport.completeUsed(controlQueue, bus, (controlUsed - 1) & (size - 1),
                    controlUsed, head, written);
            raiseInterrupt();
        }
    }

    /**
     * Serves one controlq chain and returns the number of bytes written back into guest memory.
     *
     * <p>virtio-gpu is a command protocol rather than a request/response one: the driver builds a
     * header plus its payload, kicks the queue, and reads the answer from the buffers it supplied.
     * That is why this is a switch over the header rather than a loop over a descriptor table in
     * the usual sense.
     */
    private int serveControlRequest(int head) {
        Bus bus = this.bus;
        // The chain is: header (device-readable), payload (mixed), optional response (writable).
        List<Segment> segments = new ArrayList<>(4);
        int index = head;
        for (int guard = 0; guard < 64; guard++) {
            long descriptor = VirtioMmioTransport.descriptorBuffer(controlQueue, bus, index);
            int flags = VirtioMmioTransport.descriptorFlags(bus, descriptor);
            segments.add(new Segment(
                    VirtioMmioTransport.descriptorAddress(bus, descriptor),
                    VirtioMmioTransport.descriptorLength(bus, descriptor),
                    (flags & VirtioMmioTransport.DESC_F_WRITE) != 0));
            if ((flags & VirtioMmioTransport.DESC_F_NEXT) == 0) {
                break;
            }
            index = VirtioMmioTransport.descriptorNext(bus, descriptor);
        }

        if (segments.isEmpty() || segments.get(0).length() < HDR_SIZE) {
            return 0;
        }
        Segment header = segments.get(0);
        int type = readLe32(bus, header.address());
        int flagsField = readLe32(bus, header.address() + 4);
        long ctxId = readLe32(bus, header.address() + 12) & 0xFFFF_FFFFL;

        // Payload sits immediately after the header in the same descriptor for every command the
        // driver sends, which keeps the common case to one read.
        long payload = header.address() + HDR_SIZE;
        int payloadLen = (int) Math.max(0, header.length() - HDR_SIZE);
        Segment response = segments.size() > 1 ? segments.get(segments.size() - 1) : null;
        // A chain of [header][response] leaves the payload at the header's tail with nothing left;
        // the driver instead supplies [header][payload][response].
        if (segments.size() >= 3) {
            Segment mid = segments.get(1);
            payload = mid.address();
            payloadLen = (int) mid.length();
            response = segments.get(2);
        }

        long fenceId = 0;
        if (header.length() >= 16) {
            fenceId = bus.read64Phy(header.address() + 8);
        }
        switch (type) {
            case CMD_GET_DISPLAY_INFO -> {
                // The driver hands over a writable response buffer and expects the whole
                // virtio_gpu_resp_display_info to appear in it. This kernel does not pass an
                // output pointer in the request, so the reply has to be built here or the
                // driver keeps seeing zero scanouts and disables every crtc.
                Segment out = segments.size() > 1 ? segments.get(segments.size() - 1) : null;
                // struct virtio_gpu_resp_display_info is a request-style header followed by an
                // array of pmodes, each one a rect plus enabled/flags. The driver does not read a
                // count: it scans the array for the first enabled entry and takes its size as the
                // display resolution. Writing a count where it expects a rect is why it kept
                // reporting no crtc at all.
                //
                // The pmode stride is 24 bytes and there are VIRTIO_GPU_MAX_SCANOUTS of them,
                // which is the 408-byte buffer the driver hands over.
                if (out != null && out.length() >= 48) {
                    long at = out.address();
                    bus.write32Phy(at, RESP_OK_DISPLAY_INFO);
                    bus.write32Phy(at + 4, flagsField & FLAG_FENCE);
                    bus.write64Phy(at + 8, (flagsField & FLAG_FENCE) != 0 ? fenceId : 0L);
                    bus.write32Phy(at + 16, 0);   // ctx_id
                    bus.write32Phy(at + 20, 0);   // padding
                    bus.write32Phy(at + 24, 0);   // pmodes[0].r.x
                    bus.write32Phy(at + 28, 0);   // pmodes[0].r.y
                    bus.write32Phy(at + 32, width);
                    bus.write32Phy(at + 36, height);
                    bus.write32Phy(at + 40, 1);   // pmodes[0].enabled
                    bus.write32Phy(at + 44, 0);   // pmodes[0].flags
                }
                break;
            }
            case CMD_RESOURCE_ATTACH_BACKING -> {
                handleAttachBacking(bus, header, segments, flagsField, fenceId, response);
            }
            case CMD_TRANSFER_TO_HOST_2D -> {
                handleTransferToHost2D(bus, payload, payloadLen, segments, flagsField, fenceId, response);
            }
            case CMD_SET_SCANOUT -> {
                handleSetScanout(bus, payload, payloadLen);
                writeResponse(bus, response, RESP_OK_NODATA, flagsField, fenceId);
            }
            case CMD_RESOURCE_FLUSH -> {
                handleFlush(bus, payload, payloadLen);
                writeResponse(bus, response, RESP_OK_NODATA, flagsField, fenceId);
            }
            // The remaining commands are all requests this device has no work to do for: the
            // backing store is fixed, there is one plane, and no cursor is drawn. They still have
            // to be answered, because the driver blocks on every reply.
            case CMD_RESOURCE_CREATE_2D, CMD_RESOURCE_UNREF,
                 CMD_UPDATE_CURSOR, CMD_MOVE_CURSOR ->
                writeResponse(bus, response, RESP_OK_NODATA, flagsField, fenceId);
            default ->
                writeResponse(bus, response, RESP_ERR_UNSUPPORTED, flagsField, fenceId);
        }
        return 0;
    }

    /**
     * Records the guest pages that back a resource.
     *
     * <p>{@code struct virtio_gpu_resource_attach_backing} keeps the id and entry count in the
     * tail of the header descriptor and puts the entry array in the next one, so both have to be
     * read from where they actually are rather than from one contiguous buffer.
     */
    private void handleAttachBacking(Bus bus, Segment header, List<Segment> segments,
                                     int flagsField, long fenceId, Segment response) {
        long resource = readLe32(bus, header.address() + 24) & 0xFFFF_FFFFL;
        int count = readLe32(bus, header.address() + 28);
        List<long[]> entries = new ArrayList<>(Math.max(0, count));
        Segment array = segments.size() > 2 ? segments.get(1) : null;
        if (array != null) {
            for (int i = 0; i < count && (i + 1) * 16 <= array.length(); i++) {
                long at = array.address() + (long) i * 16;
                long address = bus.read64Phy(at);
                long length = readLe32(bus, at + 8) & 0xFFFF_FFFFL;
                entries.add(new long[]{address, length});
            }
        }
        resourceBacking.put(resource, entries);
        writeResponse(bus, response, RESP_OK_NODATA, flagsField, fenceId);
    }

    /** Copies the scanned-out resource's backing into the window the renderer reads from. */
    private void publishScanout() {
        List<long[]> entries = resourceBacking.get(scanoutResource);
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int copied = 0;
        int total = (int) Math.min(sizeBytes(),
                entries.stream().mapToLong(e -> e[1]).sum());
        for (long[] entry : entries) {
            if (copied >= total) {
                break;
            }
            long srcOffset = entry[0] - ramBase;
            if (srcOffset < 0) {
                continue;
            }
            int chunk = (int) Math.min(entry[1], total - copied);
            while (chunk > 0) {
                int step = Math.min(chunk, publishScratch.length);
                arena.read(srcOffset, publishScratch, 0, step);
                arena.write(pixelOffset + copied, publishScratch, 0, step);
                srcOffset += step;
                copied += step;
                chunk -= step;
            }
        }
        scanoutBackingLength = copied;
        if (copied > 0) {
            for (int row = 0; row < height; row++) {
                markDirtyRange(rowOffset(row), bytesPerRow);
            }
        }
    }

    /**
     * Moves guest pages into a resource's backing.
     *
     * <p>The guest renders into shmem and then asks the device to transfer the rectangle in. It
     * never writes the resource directly, so ignoring this command leaves every resource backed
     * by untouched zero pages and the scanout copies from a blank buffer.
     */
    private void handleTransferToHost2D(Bus bus, long payload, int length, List<Segment> segments,
                                        int flagsField, long fenceId, Segment response) {
        if (length < 28) {
            writeResponse(bus, response, RESP_OK_NODATA, flagsField, fenceId);
            return;
        }
        int x = readLe32(bus, payload);
        int y = readLe32(bus, payload + 4);
        int w = readLe32(bus, payload + 8);
        int h = readLe32(bus, payload + 12);
        long offset = bus.read64Phy(payload + 16);
        long resource = readLe32(bus, payload + 24) & 0xFFFF_FFFFL;

        List<long[]> entries = resourceBacking.get(resource);
        // The source is the one readable segment the driver appends after the command.
        Segment source = null;
        for (int i = 1; i < segments.size(); i++) {
            if (!segments.get(i).write()) {
                source = segments.get(i);
                break;
            }
        }
        if (entries != null && source != null && w > 0 && h > 0) {
            long flatOffset = offset + (long) y * bytesPerRow + (long) x * 4;
            int rowBytes = w * 4;
            int done = 0;
            for (int row = 0; row < h; row++) {
                int want = Math.min(rowBytes, Math.max(0, source.length() - done));
                if (want <= 0) {
                    break;
                }
                copyIntoBacking(bus, entries, source.address() + done, want, flatOffset);
                flatOffset += rowBytes;
                done += want;
            }
        }
        writeResponse(bus, response, RESP_OK_NODATA, flagsField, fenceId);
    }

    /** Writes {@code length} bytes into a resource's backing pages starting at a flat offset. */
    private void copyIntoBacking(Bus bus, List<long[]> entries, long source, int length,
                                 long flatOffset) {
        int remaining = length;
        long written = flatOffset;
        int cursor = 0;
        for (long[] entry : entries) {
            int entryLength = (int) Math.min(Integer.MAX_VALUE, entry[1]);
            if (written >= entryLength) {
                written -= entryLength;
                continue;
            }
            int within = (int) written;
            int chunk = (int) Math.min(entryLength - within, remaining);
            int moved = 0;
            while (moved < chunk) {
                int step = Math.min(chunk - moved, publishScratch.length);
                for (int i = 0; i < step; i++) {
                    publishScratch[i] = (byte) bus.read8Phy(source + cursor + moved + i);
                }
                arena.write(entry[0] - ramBase + within + moved, publishScratch, 0, step);
                moved += step;
            }
            remaining -= chunk;
            cursor += chunk;
            if (remaining <= 0) {
                return;
            }
        }
    }

    private void handleSetScanout(Bus bus, long payload, int length) {
        // struct virtio_gpu_set_scanout is header, binding, rect, then the resource it displays.
        // The field offsets around the rect have shifted between kernel versions, so rather than
        // hard-coding one the resource is taken from whichever candidate the guest actually
        // attached backing to. Reading the wrong field left the scanout pointing at nothing and
        // every flush copied from a null resource.
        long resource = -1;
        for (int at = 20; at <= 24; at += 4) {
            if (length < at + 4) {
                continue;
            }
            long candidate = readLe32(bus, payload + at) & 0xFFFF_FFFFL;
            if (resourceBacking.containsKey(candidate)) {
                resource = candidate;
                break;
            }
        }
        if (resource < 0) {
            // Fall back to the only resource that exists at all.
            resource = resourceBacking.keySet().stream()
                    .mapToLong(Long::longValue).min().orElse(-1L);
        }
        scanoutResource = resource;
        scanoutActive = true;
        publishScanout();
        interruptController.assertSource(irq);
    }

    private void handleFlush(Bus bus, long payload, int length) {
        if (length < 24) {
            return;
        }
        long resource = readLe32(bus, payload) & 0xFFFF_FFFFL;
        int x = readLe32(bus, payload + 8);
        int y = readLe32(bus, payload + 12);
        int w = readLe32(bus, payload + 16);
        int h = readLe32(bus, payload + 20);
        if (w <= 0 || h <= 0) {
            w = width;
            h = height;
        }
        x = Math.max(0, Math.min(x, width));
        y = Math.max(0, Math.min(y, height));
        w = Math.min(w, width - x);
        h = Math.min(h, height - y);
        if (w > 0 && h > 0) {
            // The guest has told us these pixels are final. Record them as damaged so the renderer
            // uploads them, then let it know the update landed.
            for (int row = y; row < y + h; row++) {
                markDirtyRange(rowOffset(row), w * 4);
            }
            frameSerial++;
        }
        publishScanout();
        eventsRead |= EVENT_CHANGE;
        interruptController.assertSource(irq);
    }

    /** Writes {@code struct virtio_gpu_resp_hdr_nodered} carrying a status the driver accepts. */
    private void writeResponse(Bus bus, Segment response, int type,
                               int requestFlags, long fenceId) {
        if (response == null || response.length() < 24) {
            return;
        }
        bus.write32Phy(response.address(), type);
        bus.write32Phy(response.address() + 4, requestFlags & FLAG_FENCE);
        bus.write64Phy(response.address() + 8, (requestFlags & FLAG_FENCE) != 0 ? fenceId : 0L);
        bus.write32Phy(response.address() + 16, 0);  // ctx_id
        bus.write32Phy(response.address() + 20, 0);  // padding
    }

    private static int readLe32(Bus bus, long address) {
        return bus.read32Phy(address);
    }

    private long rowOffset(int row) {
        return pixelOffset + (long) row * bytesPerRow;
    }

    @Override
    public void writeConfig(int offset, long value) {
        // virtio_gpu_config exposes only events_read, and it is read-only from the guest.
    }

    @Override
    public long readConfig(int offset) {
        // struct virtio_gpu_config is events_read, events_clear, num_scanouts, num_capsets.
        // Getting the order wrong is silent and fatal: the driver read num_scanouts where
        // events_clear lives, saw zero scanouts and switched KMS off entirely.
        return switch (offset) {
            case 0 -> eventsRead & 0xFFFF_FFFFL;
            case 4 -> 0L;   // events_clear: writing it acknowledges; reading it is always zero
            case 8 -> 1L;   // num_scanouts: this device has exactly one
            case 12 -> 0L;  // num_capsets: this device advertises no capabilities
            default -> 0L;
        };
    }

    @Override
    public int configSize() {
        return 16;
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

    private record Segment(long address, int length, boolean write) {
    }
}
