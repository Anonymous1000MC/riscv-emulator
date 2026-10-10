package com.emulator.riscv.devices;

import java.security.SecureRandom;
import java.util.Random;

import com.emulator.riscv.bus.Bus;

/**
 * virtio-rng, an entropy source.
 *
 * <p>Linux's CRNG will block the boot early if it cannot get enough entropy, so this device is
 * what keeps a freshly created machine from hanging in {@code random: crng init done}. The pool is
 * seeded from {@link java.security.SecureRandom} and then advanced locally, so no system call is
 * made per request.
 */
public final class VirtioRngDevice extends AbstractVirtioDevice {

    private static final int QUEUE_SIZE = 8;

    private final Bus bus;
    private final Random pool;
    private Virtqueue queue;
    private int lastAvailable;
    private int usedIndex;

    public VirtioRngDevice(Bus bus) {
        this.bus = bus;
        this.pool = new Random(new SecureRandom().nextLong());
    }

    @Override
    public int deviceId() {
        return ID_RANDOM;
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
        return 1;
    }

    @Override
    public int queueMaxSize() {
        return QUEUE_SIZE;
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
        if (queueIndex != 0 || queue == null) {
            return;
        }
        int size = queue.size();
        int available = VirtioMmioTransport.availIndex(queue, bus);
        boolean served = false;
        while (lastAvailable != available) {
            int head = VirtioMmioTransport.availEntry(queue, bus, lastAvailable & (size - 1));
            serve(head);
            lastAvailable = (lastAvailable + 1) & 0xFFFF;
            usedIndex++;
            served = true;
        }
        if (served) {
            VirtioMmioTransport.publishUsed(queue, bus, usedIndex);
            raiseInterrupt();
        }
    }

    private void serve(int head) {
        int index = head;
        for (int guard = 0; guard < 64; guard++) {
            long descriptor = VirtioMmioTransport.descriptorBuffer(queue, bus, index);
            int flags = VirtioMmioTransport.descriptorFlags(bus, descriptor);
            if ((flags & VirtioMmioTransport.DESC_F_WRITE) != 0) {
                long address = VirtioMmioTransport.descriptorAddress(bus, descriptor);
                int length = VirtioMmioTransport.descriptorLength(bus, descriptor);
                for (int offset = 0; offset < length; offset += 8) {
                    bus.write64Phy(address + offset, pool.nextLong());
                }
            }
            if ((flags & VirtioMmioTransport.DESC_F_NEXT) == 0) {
                break;
            }
            index = VirtioMmioTransport.descriptorNext(bus, descriptor);
        }
    }

    @Override
    public String name() {
        return "virtio-rng";
    }
}