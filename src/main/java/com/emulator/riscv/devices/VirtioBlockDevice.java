package com.emulator.riscv.devices;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.emulator.riscv.bus.Bus;

/**
 * virtio-blk, the root filesystem transport.
 *
 * <p>Request layout is the standard header / payload / status chain: a 16-byte
 * {@code virtio_blk_outhdr}, then a payload whose direction is given by the descriptor's write
 * flag, then a single status byte. Requests are served synchronously on the machine thread, which
 * is correct here because a single hart cannot make progress while blocked on I/O anyway.
 */
public final class VirtioBlockDevice extends AbstractVirtioDevice {

    private static final int SECTOR_SIZE = 512;
    private static final int HEADER_SIZE = 16;

    private static final int VIRTIO_BLK_T_IN = 0;
    private static final int VIRTIO_BLK_T_OUT = 1;
    private static final int VIRTIO_BLK_T_FLUSH = 4;
    private static final int VIRTIO_BLK_T_GET_ID = 11;

    private static final int VIRTIO_BLK_S_OK = 0;
    private static final int VIRTIO_BLK_S_IOERR = 1;
    private static final int VIRTIO_BLK_S_UNSUPP = 2;

    private static final long F_BLK_SIZE = 1L << 6;
    private static final long F_FLUSH = 1L << 9;
    private static final long F_SIZE_MAX = 1L << 1;
    private static final long F_SEG_MAX = 1L << 2;
    private static final long F_TOPOLOGY = 1L << 10;
    private static final long F_CONFIG_WCE = 1L << 11;

    private final Bus bus;
    private final DiskImage disk;
    private final int queueMax;
    private final boolean present;

    private Virtqueue queue;
    private int lastAvailable;
    private long features;

    private byte[] scratch = new byte[1 << 16];

    private static final byte[] SERIAL_ID =
            "riscv-emulator".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private long ioRequests;
    private long ioErrors;

    public VirtioBlockDevice(Bus bus, DiskImage disk, boolean present) {
        this.bus = bus;
        this.disk = disk;
        this.present = present;
        this.queueMax = present ? 128 : 0;
    }

    public boolean present() {
        return present;
    }

    public DiskImage disk() {
        return disk;
    }

    public long requestCount() {
        return ioRequests;
    }

    public long errorCount() {
        return ioErrors;
    }

    @Override
    public int vendorId() {
        return 0x1AF4;
    }

    @Override
    public int deviceId() {
        return ID_BLOCK;
    }

    @Override
    public long deviceFeatures() {
        if (!present) {
            return 0;
        }
        return FEATURE_VERSION_1 | FEATURE_ACCESS_PLATFORM
                | F_SIZE_MAX | F_SEG_MAX | F_BLK_SIZE | F_FLUSH;
    }

    @Override
    public void writeFeatures(long negotiated) {
        this.features = negotiated;
    }

    @Override
    public int queueCount() {
        return 1;
    }

    @Override
    public int queueMaxSize() {
        return queueMax;
    }

    @Override
    public void queueReady(int queueIndex, Virtqueue queue) {
        if (queueIndex == 0) {
            this.queue = queue;
            this.lastAvailable = 0;
        }
    }

    @Override
    public void notify(int queueIndex) {
        if (queueIndex != 0 || queue == null || !present) {
            return;
        }
        int size = queue.size();
        int available = VirtioMmioTransport.availIndex(queue, bus);
        int used = VirtioMmioTransport.usedIndex(queue, bus);
        boolean served = false;

        while (lastAvailable != available) {
            int head = VirtioMmioTransport.availEntry(queue, bus, lastAvailable & (size - 1));
            serveRequest(head);
            lastAvailable = (lastAvailable + 1) & 0xFFFF;
            used++;
            served = true;
        }
        if (served) {
            VirtioMmioTransport.publishUsed(queue, bus, used);
            raiseInterrupt();
        }
    }

    private int serveRequest(int head) {
        List<Segment> segments = new ArrayList<>(4);
        int index = head;
        for (int guard = 0; guard < 64; guard++) {
            long descriptor = VirtioMmioTransport.descriptorBuffer(queue, bus, index);
            int flags = VirtioMmioTransport.descriptorFlags(bus, descriptor);
            Segment segment = new Segment(
                    VirtioMmioTransport.descriptorAddress(bus, descriptor),
                    VirtioMmioTransport.descriptorLength(bus, descriptor),
                    (flags & VirtioMmioTransport.DESC_F_WRITE) != 0);
            segments.add(segment);
            if ((flags & VirtioMmioTransport.DESC_F_NEXT) == 0) {
                break;
            }
            index = VirtioMmioTransport.descriptorNext(bus, descriptor);
        }

        ioRequests++;
        int status = perform(segments);
        if (status != VIRTIO_BLK_S_OK) {
            ioErrors++;
        }
        Segment statusSegment = segments.get(segments.size() - 1);
        if (statusSegment.write && statusSegment.length > 0) {
            bus.write8Phy(statusSegment.address, (byte) status);
        }
        return head;
    }

    private int perform(List<Segment> segments) {
        if (segments.size() < 2) {
            return VIRTIO_BLK_S_IOERR;
        }
        Segment header = segments.get(0);
        if (header.write || header.length < HEADER_SIZE) {
            return VIRTIO_BLK_S_IOERR;
        }
        long type = bus.read32Phy(header.address) & 0xFFFF_FFFFL;
        long sector = bus.read64Phy(header.address + 8);
        long byteOffset = sector * SECTOR_SIZE;
        if (byteOffset < 0) {
            return VIRTIO_BLK_S_IOERR;
        }

        try {
            return switch ((int) type) {
                case VIRTIO_BLK_T_IN, VIRTIO_BLK_T_OUT -> {
                    // A read descriptor is marked writable because the device fills it; a write
                    // descriptor is marked read-only because the device drains it. The direction
                    // the *device* moves data is therefore the opposite of the guest's, and
                    // selecting on the guest's own direction skipped every payload buffer and
                    // made every read fail with I/O error.
                    boolean deviceWrites = type == VIRTIO_BLK_T_IN;
                    long moved = 0;
                    for (int i = 1; i < segments.size() - 1; i++) {
                        Segment segment = segments.get(i);
                        if (segment.write != deviceWrites || segment.length == 0) {
                            continue;
                        }
                        moved += transfer(segment, byteOffset + moved, deviceWrites);
                    }
                    yield moved > 0 ? VIRTIO_BLK_S_OK : VIRTIO_BLK_S_IOERR;
                }
                case VIRTIO_BLK_T_FLUSH -> {
                    disk.flush();
                    yield VIRTIO_BLK_S_OK;
                }
                case VIRTIO_BLK_T_GET_ID -> {
                    Segment payload = segments.get(1);
                    byte[] serial = SERIAL_ID;
                    bus.dmaWrite(serial, 0, Math.min(serial.length, payload.length),
                            payload.address);
                    yield VIRTIO_BLK_S_OK;
                }
                default -> VIRTIO_BLK_S_UNSUPP;
            };
        } catch (IOException ex) {
            return VIRTIO_BLK_S_IOERR;
        }
    }

    /** Moves one payload segment in or out of the image through a reusable staging array. */
    private int transfer(Segment segment, long byteOffset, boolean writing) throws IOException {
        if (segment.length > scratch.length) {
            scratch = new byte[Math.max(segment.length, scratch.length * 2)];
        }
        int length = Math.min(segment.length, scratch.length);
        if (writing) {
            bus.dmaRead(segment.address, scratch, 0, length);
            return disk.write(byteOffset, ByteBuffer.wrap(scratch, 0, length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(scratch, 0, length);
        int read = disk.read(byteOffset, buffer);
        if (read <= 0) {
            return 0;
        }
        bus.dmaWrite(scratch, 0, read, segment.address);
        return read;
    }

    @Override
    public long readConfig(int offset) {
        return switch (offset) {
            case 0 -> present ? disk.capacitySectors() : 0L;
            case 8 -> 1L << 30;                 // size_max: a single segment of up to 1 GiB
            case 12 -> present ? disk.capacitySectors() : 0L;
            case 24 -> SECTOR_SIZE;             // blk_size
            default -> 0L;
        };
    }

    @Override
    public String name() {
        return "virtio-blk";
    }

    private record Segment(long address, int length, boolean write) {
    }
}