package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Bus;
import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;

/**
 * virtio-mmio transport, version 2 (virtio 1.0 modern).
 *
 * <p>Register map, matching {@code hw/virtio/virtio-mmio.c}:
 *
 * <pre>
 *   0x000 MagicValue ("virt")   0x004 Version    0x008 DeviceID   0x00C VendorID
 *   0x010 DeviceFeatures        0x014 DeviceFeaturesSel
 *   0x020 DriverFeatures        0x024 DriverFeaturesSel
 *   0x030 QueueSel              0x034 QueueNumMax 0x038 QueueNum   0x03C QueueAlign
 *   0x040 (reserved at v2)
 *   0x050 QueueNotify           0x060 InterruptStatus 0x064 InterruptACK
 *   0x070 Status
 *   0x080 QueueDescLo  0x084 QueueDescHi
 *   0x090 QueueAvailLo 0x094 QueueAvailHi
 *   0x0A0 QueueUsedLo  0x0A4 QueueUsedHi
 *   0x100+ device configuration space
 * </pre>
 *
 * <p>Only the split-queue layout is implemented, which is what Linux programs for virtio-mmio.
 * The interrupt line is level triggered through the PLIC: a device raises it when it appends to a
 * used ring, and the driver lowers it by writing {@link #INTERRUPT_ACK}.
 */
public final class VirtioMmioTransport implements Device {

    private static final int MAGIC_VALUE = 0x000;
    private static final int VERSION = 0x004;
    private static final int DEVICE_ID = 0x008;
    private static final int VENDOR_ID = 0x00C;
    private static final int DEVICE_FEATURES = 0x010;
    private static final int DEVICE_FEATURES_SEL = 0x014;
    private static final int DRIVER_FEATURES = 0x020;
    private static final int DRIVER_FEATURES_SEL = 0x024;
    private static final int QUEUE_SEL = 0x030;
    private static final int QUEUE_NUM_MAX = 0x034;
    private static final int QUEUE_NUM = 0x038;
    private static final int QUEUE_ALIGN = 0x03C;
    private static final int QUEUE_NOTIFY = 0x050;
    private static final int INTERRUPT_STATUS = 0x060;
    private static final int INTERRUPT_ACK = 0x064;
    private static final int STATUS = 0x070;
    private static final int QUEUE_DESC_LO = 0x080;
    private static final int QUEUE_DESC_HI = 0x084;
    private static final int QUEUE_AVAIL_LO = 0x090;
    private static final int QUEUE_AVAIL_HI = 0x094;
    private static final int QUEUE_USED_LO = 0x0A0;
    private static final int QUEUE_USED_HI = 0x0A4;
    private static final int CONFIG_BASE = 0x100;

    public static final int STATUS_ACKNOWLEDGE = 1;
    public static final int STATUS_DRIVER = 2;
    public static final int STATUS_DRIVER_OK = 4;
    public static final int STATUS_FEATURES_OK = 8;
    public static final int STATUS_FAILED = 128;

    public static final int MAGIC = 0x74726976;

    /** Virtqueue layout constants from the specification. */
    public static final int DESC_F_NEXT = 1;
    public static final int DESC_F_WRITE = 2;
    public static final int DESC_F_INDIRECT = 4;
    public static final int DESC_SIZE = 16;
    public static final int AVAIL_SIZE = 6;
    public static final int USED_SIZE = 6;

    private final Bus bus;
    private final AbstractVirtioDevice device;
    private final long base;
    private final InterruptSource interruptController;
    private final int irq;

    private int deviceFeatureSel;
    private long driverFeatures;
    private int driverFeatureSel;
    private int queueSel;
    private final int[] queueNum = new int[16];
    private final long[] queueDesc = new long[16];
    private final long[] queueAvail = new long[16];
    private final long[] queueUsed = new long[16];
    private final boolean[] queueReady = new boolean[16];

    private int interruptStatus;
    private int status;

    private final Thread owner;

    public VirtioMmioTransport(Bus bus, AbstractVirtioDevice device, long base,
                                InterruptSource interruptController, int irq) {
        this.bus = bus;
        this.device = device;
        this.base = base;
        this.interruptController = interruptController;
        this.irq = irq;
        this.owner = Thread.currentThread();
        device.attach(this);
    }

    public long base() {
        return base;
    }

    public AbstractVirtioDevice device() {
        return device;
    }

    public int status() {
        return status;
    }

    /** Raises the device's interrupt line; called by device models when they complete work. */
    public void raiseInterrupt() {
        interruptStatus |= 1;
        interruptController.assertSource(irq);
    }

    public void clearInterrupt() {
        interruptStatus &= ~1;
        interruptController.deassertSource(irq);
    }

    @Override
    public int read(int size, long pa) {
        return readRaw(size, pa);
    }


    private int readRaw(int size, long pa) {
        long offset = pa - base;
        if (offset >= CONFIG_BASE) {
            int at = (int) (offset - CONFIG_BASE);
            long word = device.readConfig(at & ~7);
            if (size == 8) {
                return (int) word;
            }
            if (size == 4) {
                return (int) (word >>> (((at & 4)) * 8));
            }
            if (size == 2) {
                return (int) ((word >>> ((at & 6) * 8)) & 0xFFFF);
            }
            return (int) ((word >>> ((at & 7) * 8)) & 0xFF);
        }
        return switch ((int) offset) {
            case MAGIC_VALUE -> MAGIC;
            case VERSION -> 2;
            case DEVICE_ID -> device.deviceId();
            case VENDOR_ID -> device.vendorId();
            case DEVICE_FEATURES -> (int) (device.deviceFeatures() >>> (deviceFeatureSel * 32));
            case QUEUE_SEL -> queueSel;
            case QUEUE_NUM_MAX -> device.queueMaxSize();
            case QUEUE_NUM -> queueNum[queueSel];
            case QUEUE_ALIGN -> (int) VirtioDevice.FEATURE_ANY_LAYOUT;
            case INTERRUPT_STATUS -> interruptStatus;
            case STATUS -> status;
            case QUEUE_DESC_LO -> (int) queueDesc[queueSel];
            case QUEUE_DESC_HI -> (int) (queueDesc[queueSel] >>> 32);
            case QUEUE_AVAIL_LO -> (int) queueAvail[queueSel];
            case QUEUE_AVAIL_HI -> (int) (queueAvail[queueSel] >>> 32);
            case QUEUE_USED_LO -> (int) queueUsed[queueSel];
            case QUEUE_USED_HI -> (int) (queueUsed[queueSel] >>> 32);
            default -> 0;
        };
    }

    @Override
    public void write(int size, long pa, int value) {
        writeRaw(size, pa, value);
    }

    private void writeRaw(int size, long pa, int value) {
        long offset = pa - base;
        if (offset >= CONFIG_BASE) {
            int at = (int) (offset - CONFIG_BASE);
            int aligned = at & ~7;
            long current = device.readConfig(aligned);
            if (size == 8) {
                device.writeConfig(aligned, value & 0xFFFF_FFFF_FFFF_FFFFL);
            } else if (size == 4) {
                long shift = (at & 4) * 8;
                device.writeConfig(aligned, (current & ~(0xFFFF_FFFFL << shift))
                        | ((long) value & 0xFFFF_FFFFL) << shift);
            } else if (size == 2) {
                long shift = (at & 6) * 8;
                device.writeConfig(aligned, (current & ~(0xFFFFL << shift))
                        | ((long) value & 0xFFFFL) << shift);
            } else {
                long shift = (at & 7) * 8;
                device.writeConfig(aligned, (current & ~(0xFFL << shift))
                        | ((long) value & 0xFFL) << shift);
            }
            return;
        }
        switch ((int) offset) {
            case DEVICE_FEATURES_SEL -> deviceFeatureSel = value & 3;
            case DRIVER_FEATURES -> driverFeatures = (driverFeatureSel & 1) == 0
                    ? (driverFeatures & 0xFFFF_FFFF_0000_0000L) | (value & 0xFFFF_FFFFL)
                    : (driverFeatures & 0xFFFF_FFFFL) | ((long) value << 32);
            case DRIVER_FEATURES_SEL -> {
                driverFeatureSel = value & 3;
                if (driverFeatureSel == 3) {
                    long negotiated = driverFeatures;
                    // The driver must acknowledge VIRTIO_F_VERSION_1; anything it cannot have
                    // (because we did not offer it) is cleared here so feature-dependent
                    // behaviour in the model matches the final negotiation.
                    negotiated &= device.deviceFeatures();
                    device.writeFeatures(negotiated);
                }
            }
            case QUEUE_SEL -> queueSel = value & 0xF;
            case QUEUE_NUM -> queueNum[queueSel] = value;
            case QUEUE_DESC_LO -> queueDesc[queueSel] = (queueDesc[queueSel] & 0xFFFF_FFFF_0000_0000L)
                    | (value & 0xFFFF_FFFFL);
            case QUEUE_DESC_HI -> queueDesc[queueSel] = (queueDesc[queueSel] & 0xFFFF_FFFFL)
                    | ((long) value << 32);
            case QUEUE_AVAIL_LO -> queueAvail[queueSel] = (queueAvail[queueSel] & 0xFFFF_FFFF_0000_0000L)
                    | (value & 0xFFFF_FFFFL);
            case QUEUE_AVAIL_HI -> queueAvail[queueSel] = (queueAvail[queueSel] & 0xFFFF_FFFFL)
                    | ((long) value << 32);
            case QUEUE_USED_LO -> queueUsed[queueSel] = (queueUsed[queueSel] & 0xFFFF_FFFF_0000_0000L)
                    | (value & 0xFFFF_FFFFL);
            case QUEUE_USED_HI -> queueUsed[queueSel] = (queueUsed[queueSel] & 0xFFFF_FFFFL)
                    | ((long) value << 32);
            case QUEUE_NOTIFY -> notifyQueue(value);
            case INTERRUPT_ACK -> {
                interruptStatus &= ~value;
                if (interruptStatus == 0) {
                    clearInterrupt();
                }
            }
            case STATUS -> {
                status = value & 0xFF;
                if ((status & STATUS_FAILED) != 0) {
                    onFailed();
                }
            }
            default -> {
            }
        }
    }

    private void onFailed() {
        for (int i = 0; i < queueNum.length; i++) {
            queueReady[i] = false;
        }
    }

    private void notifyQueue(int index) {
        if (index < 0 || index >= 16) {
            return;
        }
        int size = queueNum[index];
        if (size == 0) {
            return;
        }
        if (!queueReady[index]) {
            // The driver has now published all three ring addresses; freeze the layout so the
            // device model reads a consistent view.
            queueReady[index] = true;
            device.queueReady(index, new QueueView(index, size));
        }
        if ((status & STATUS_DRIVER_OK) != 0) {
            device.notify(index);
        }
    }

    @Override
    public void tick() {
        device.tick();
    }

    @Override
    public String name() {
        return "virtio-mmio/" + device.name();
    }

    /** Immutable view of one virtqueue as programmed by the driver. */
    private final class QueueView implements VirtioDevice.Virtqueue {
        private final int index;
        private final int size;

        QueueView(int index, int size) {
            this.index = index;
            this.size = size;
        }

        @Override
        public long descriptorAddress() {
            return queueDesc[index];
        }

        @Override
        public long availAddress() {
            return queueAvail[index];
        }

        @Override
        public long usedAddress() {
            return queueUsed[index];
        }

        @Override
        public int size() {
            return size;
        }
    }

    // ---- shared ring helpers -------------------------------------------------------------------

    /** Reads the descriptor at {@code index} from {@code queue}. */
    public static long descriptorBuffer(VirtioDevice.Virtqueue queue, Bus bus, int index) {
        return queue.descriptorAddress() + (long) index * DESC_SIZE;
    }

    public static int descriptorFlags(Bus bus, long descriptor) {
        return bus.read16Phy(descriptor + 12) & 0xFFFF;
    }

    public static long descriptorAddress(Bus bus, long descriptor) {
        return bus.read64Phy(descriptor);
    }

    public static int descriptorLength(Bus bus, long descriptor) {
        return bus.read32Phy(descriptor + 8);
    }

    public static int descriptorNext(Bus bus, long descriptor) {
        return bus.read16Phy(descriptor + 14) & 0xFFFF;
    }

    public static int availIndex(VirtioDevice.Virtqueue queue, Bus bus) {
        return bus.read16Phy(queue.availAddress() + 2) & 0xFFFF;
    }

    public static int availEntry(VirtioDevice.Virtqueue queue, Bus bus, int slot) {
        return bus.read16Phy(queue.availAddress() + 4 + (long) (slot & 0x7FFF) * 2) & 0xFFFF;
    }

    public static int usedIndex(VirtioDevice.Virtqueue queue, Bus bus) {
        return bus.read16Phy(queue.usedAddress() + 2) & 0xFFFF;
    }

    /**
     * Records one completed descriptor and publishes the new index.
     *
     * <p>Both halves are required. Advancing the index without writing the element leaves the
     * driver reading a zeroed entry: it sees a completion for descriptor zero that never
     * happened, with a length of zero, and the request it was waiting on never completes. The
     * input device wrote the element and published separately; the block device only published.
     */
    public static void writeUsed(VirtioDevice.Virtqueue queue, Bus bus, int slot, int id, int length) {
        long at = queue.usedAddress() + 4 + (long) (slot & 0x7FFF) * 8;
        bus.write32Phy(at, id);
        bus.write32Phy(at + 4, length);
    }

    /** Publishes a completion: the ring element plus the index that makes it visible. */
    public static void completeUsed(VirtioDevice.Virtqueue queue, Bus bus, int slot, int id, int length) {
        writeUsed(queue, bus, slot, id, length);
        bus.write16Phy(queue.usedAddress() + 2, (short) (slot & 0xFFFF));
    }

    public static void publishUsed(VirtioDevice.Virtqueue queue, Bus bus, int index) {
        bus.write16Phy(queue.usedAddress() + 2, (short) (index & 0xFFFF));
    }

    /** True when this transport was created on the machine's execution thread. */
    public boolean isOwnedByCurrentThread() {
        return Thread.currentThread() == owner;
    }

    public static long transportBase(int slot) {
        return MemoryMap.VIRTIO_BASE + (long) slot * MemoryMap.VIRTIO_STRIDE;
    }
}