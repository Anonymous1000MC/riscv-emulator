package com.emulator.riscv.devices;

import java.util.ArrayDeque;
import java.util.Deque;

import com.emulator.riscv.bus.Bus;

/**
 * virtio-input, the keyboard and mouse transport.
 *
 * <p>The device exposes one virtio device whose {@code subsel} selects between the three event
 * families: {@code KEY}, {@code REL} and {@code ABS}. Events travel from the device to the driver
 * on virtqueue 0; virtqueue 1 is the direction the driver uses to send events back and is
 * implemented as a sink that drops them.
 *
 * <p>Event payloads are the packed {@code struct input_event} layout, which is a
 * timeval followed by a type, code and value. Linux's evdev driver consumes these directly, so no
 * translation layer is needed on the guest side.
 */
public final class VirtioInputDevice extends AbstractVirtioDevice {

    private static final int VIRTIO_INPUT_ID_UNPACK = 1;

    private static final int SUBCLASS_KEY = 1;
    private static final int SUBCLASS_REL = 2;
    private static final int SUBCLASS_ABS = 3;

    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_REL = 2;
    private static final int EV_ABS = 3;

    /** {@code struct timeval} with a 64-bit tv_sec and 64-bit tv_usec on RV64. */
    private static final int EVENT_SIZE = 24;

    private static final int QUEUE_DEVICE = 0;
    private static final int QUEUE_DRIVER = 1;
    private static final int QUEUE_SIZE = 64;

    private final Bus bus;

    private Virtqueue deviceQueue;
    private Virtqueue driverQueue;
    private int lastAvailable;
    private int usedIndex;

    private int select;
    private int subsel;
    private final int[][] bitmap = new int[SUBCLASS_ABS + 1][8];
    private final Deque<int[]> pending = new ArrayDeque<>(256);

    private byte[] scratch = new byte[EVENT_SIZE * 64];

    public VirtioInputDevice(Bus bus) {
        this.bus = bus;
        for (int family = 1; family <= SUBCLASS_ABS; family++) {
            for (int code = 0; code < 64; code++) {
                if ((code & 63) < 8 || code < 64) {
                    bitmap[family][code >>> 6] |= 1 << (code & 63);
                }
            }
        }
        // A keyboard reports every key code; a pointer reports a standard button set.
        for (int code = 64; code < 128; code++) {
            bitmap[SUBCLASS_KEY][code >>> 6] |= 1 << (code & 63);
        }
        for (int code = 128; code < 192; code++) {
            bitmap[SUBCLASS_KEY][code >>> 6] |= 1 << (code & 63);
        }
        for (int code = 272; code < 320; code++) {
            bitmap[SUBCLASS_KEY][code >>> 6] |= 1 << (code & 63);
        }
        // Relative axes: REL_X 0, REL_Y 1, REL_HWHEEL 6, REL_DIAL 7, REL_WHEEL 8.
        for (int code = 0; code <= 8; code++) {
            bitmap[SUBCLASS_REL][code >>> 6] |= 1 << (code & 63);
        }
        // Absolute axes: ABS_X 0, ABS_Y 1.
        for (int code = 0; code <= 1; code++) {
            bitmap[SUBCLASS_ABS][code >>> 6] |= 1 << (code & 63);
        }
        // Clear anything above the families we model.
        for (int family = 1; family <= SUBCLASS_ABS; family++) {
            bitmap[family][0] &= 0x0F;
        }
    }

    @Override
    public int deviceId() {
        return ID_INPUT;
    }

    @Override
    public long deviceFeatures() {
        return FEATURE_VERSION_1 | FEATURE_ACCESS_PLATFORM;
    }

    @Override
    public void writeFeatures(long negotiated) {
    }

    @Override
    public int queueCount() {
        return 2;
    }

    @Override
    public int queueMaxSize() {
        return QUEUE_SIZE;
    }

    @Override
    public void queueReady(int queueIndex, Virtqueue queue) {
        switch (queueIndex) {
            case QUEUE_DEVICE -> {
                deviceQueue = queue;
                lastAvailable = 0;
            }
            case QUEUE_DRIVER -> driverQueue = queue;
            default -> {
            }
        }
    }

    @Override
    public void notify(int queueIndex) {
        if (queueIndex == QUEUE_DRIVER) {
            drainDriverQueue();
            return;
        }
        if (deviceQueue == null) {
            return;
        }
        int size = deviceQueue.size();
        int available = VirtioMmioTransport.availIndex(deviceQueue, bus);
        boolean progressed = false;
        while (lastAvailable != available) {
            int head = VirtioMmioTransport.availEntry(deviceQueue, bus,
                    lastAvailable & (size - 1));
            progressed |= fillDescriptor(head);
            lastAvailable = (lastAvailable + 1) & 0xFFFF;
            usedIndex++;
            VirtioMmioTransport.publishUsed(deviceQueue, bus, usedIndex);
        }
        if (progressed) {
            raiseInterrupt();
        }
    }

    /** Consumes and discards anything the driver sent us; the guest never originates input. */
    private void drainDriverQueue() {
        if (driverQueue == null) {
            return;
        }
        int size = driverQueue.size();
        int available = VirtioMmioTransport.availIndex(driverQueue, bus);
        int consumed = 0;
        while (consumed != available) {
            int head = VirtioMmioTransport.availEntry(driverQueue, bus, consumed & (size - 1));
            long descriptor = VirtioMmioTransport.descriptorBuffer(driverQueue, bus, head);
            int flags = VirtioMmioTransport.descriptorFlags(bus, descriptor);
            VirtioMmioTransport.writeUsed(driverQueue, bus, consumed & (size - 1), head,
                    VirtioMmioTransport.descriptorLength(bus, descriptor));
            consumed++;
            if ((flags & VirtioMmioTransport.DESC_F_NEXT) == 0) {
                break;
            }
        }
        if (consumed != 0) {
            VirtioMmioTransport.publishUsed(driverQueue, bus, consumed);
        }
    }

    /** Writes one buffered event into the descriptor chain at {@code head}. */
    private boolean fillDescriptor(int head) {
        if (pending.isEmpty()) {
            return false;
        }
        long descriptor = VirtioMmioTransport.descriptorBuffer(deviceQueue, bus, head);
        int flags = VirtioMmioTransport.descriptorFlags(bus, descriptor);
        long address = VirtioMmioTransport.descriptorAddress(bus, descriptor);
        int length = VirtioMmioTransport.descriptorLength(bus, descriptor);

        int written = 0;
        while (length >= EVENT_SIZE && !pending.isEmpty()) {
            int[] event = pending.poll();
            writeEvent(address + written, event);
            written += EVENT_SIZE;
            length -= EVENT_SIZE;
            if ((flags & VirtioMmioTransport.DESC_F_NEXT) == 0) {
                break;
            }
            descriptor = VirtioMmioTransport.descriptorBuffer(deviceQueue, bus,
                    VirtioMmioTransport.descriptorNext(bus, descriptor));
            address = VirtioMmioTransport.descriptorAddress(bus, descriptor);
            length = VirtioMmioTransport.descriptorLength(bus, descriptor);
        }
        return written > 0;
    }

    private void writeEvent(long address, int[] event) {
        long now = System.currentTimeMillis() * 1_000_000L;
        bus.write64Phy(address, now);
        bus.write64Phy(address + 8, 0L);
        bus.write16Phy(address + 16, (short) event[0]);
        bus.write16Phy(address + 18, (short) event[1]);
        bus.write32Phy(address + 20, event[2]);
    }

    // ---- event sources ---------------------------------------------------------------------

    /** Queues a key press or release; {@code code} is a Linux {@code KEY_*} constant. */
    public void pushKey(int code, int value) {
        pending.addLast(new int[] { EV_KEY, code, value });
        enqueueSyn();
    }

    /** Queues a relative axis movement such as a mouse delta. */
    public void pushRelative(int axis, int delta) {
        pending.addLast(new int[] { EV_REL, axis, delta });
        enqueueSyn();
    }

    /** Queues an absolute axis position, for a touchscreen or an absolute pointing device. */
    public void pushAbsolute(int axis, int value) {
        pending.addLast(new int[] { EV_ABS, axis, value });
        enqueueSyn();
    }

    /** Ends the current report so the guest sees a coherent snapshot. */
    public void endFrame() {
        enqueueSyn();
    }

    private void enqueueSyn() {
        synchronized (pending) {
            pending.addLast(new int[] { EV_SYN, 0, 0 });
        }
    }

    public int pendingEventCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    public boolean bitmapBit(int family, int code) {
        if (family < 1 || family > SUBCLASS_ABS || code < 0 || code >= 512) {
            return false;
        }
        return (bitmap[family][code >>> 6] & (1 << (code & 63))) != 0;
    }

    @Override
    public long readConfig(int offset) {
        return switch (offset) {
            case 0 -> select;
            case 2 -> subsel;
            case 4 -> 64;      // size: the model advertises up to 64 codes per family
            default -> 0L;
        };
    }

    @Override
    public void writeConfig(int offset, long value) {
        switch (offset) {
            case 0 -> select = (int) value & 0xFFFF;
            case 2 -> subsel = (int) value & 0xFFFF;
            default -> {
            }
        }
    }

    @Override
    public String name() {
        return "virtio-input";
    }
}