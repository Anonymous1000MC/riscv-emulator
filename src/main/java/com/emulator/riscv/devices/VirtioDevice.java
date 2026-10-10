package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Device;

/**
 * Model of a virtio 1.0 device sitting behind the virtio-mmio transport.
 *
 * <p>Configuration space is byte-addressed through {@link #readConfig(int)} and
 * {@link #writeConfig(int, long)} so that multi-byte fields do not have to be reassembled by the
 * transport: drivers always write the full 32- or 64-bit field, and the model can take it directly.
 */
public interface VirtioDevice {

    int ID_NET = 1;
    int ID_BLOCK = 2;
    int ID_CONSOLE = 3;
    int ID_RANDOM = 4;
    int ID_GPU = 16;
    int ID_FRAMEBUFFER = 16;
    int ID_INPUT = 18;

    /** {@code VIRTIO_F_VERSION_1}, bit 32. Modern transports only negotiate this transport. */
    long FEATURE_VERSION_1 = 1L << 32;
    long FEATURE_ACCESS_PLATFORM = 1L << 33;
    /** {@code VIRTIO_RING_F_EVENT_IDX}, bit 29. */
    long FEATURE_EVENT_IDX = 1L << 29;
    /** {@code VIRTIO_RING_F_INDIRECT_DESC}, bit 28. */
    long FEATURE_INDIRECT_DESC = 1L << 28;
    /** {@code VIRTIO_F_ANY_LAYOUT}, bit 27. */
    long FEATURE_ANY_LAYOUT = 1L << 27;

    int vendorId();

    int deviceId();

    long deviceFeatures();

    /** Called once the driver has written the final negotiated feature set. */
    void writeFeatures(long features);

    /** Number of virtqueues the driver should create. */
    int queueCount();

    /** Maximum number of descriptors per virtqueue; zero for devices without data queues. */
    int queueMaxSize();

    /** Invoked after the driver has programmed the queue's descriptor/avail/used addresses. */
    default void queueReady(int queueIndex, Virtqueue queue) {
    }

    /** Invoked when the driver kicks the queue, i.e. adds a descriptor to the available ring. */
    void notify(int queueIndex);

    int configSize();

    long readConfig(int offset);

    void writeConfig(int offset, long value);

    /** Periodic housekeeping; called from the machine's tick. */
    default void tick() {
    }

    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * A split virtqueue as programmed by the driver.
     *
     * <p>Layouts are 64-bit physical addresses because {@code VIRTIO_F_ACCESS_PLATFORM} is offered
     * and virtio-mmio has no I/O address space to translate through.
     */
    interface Virtqueue {
        long descriptorAddress();

        long availAddress();

        long usedAddress();

        int size();
    }
}