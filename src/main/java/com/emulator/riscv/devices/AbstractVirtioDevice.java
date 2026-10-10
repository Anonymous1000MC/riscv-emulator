package com.emulator.riscv.devices;

/**
 * Shared plumbing for virtio device models: transport attachment, vendor identity and the
 * interrupt callback that the transport registers on construction.
 */
public abstract class AbstractVirtioDevice implements VirtioDevice {

    protected VirtioMmioTransport transport;

    /** Called by {@link VirtioMmioTransport} once the model is bound to a register window. */
    public void attach(VirtioMmioTransport transport) {
        this.transport = transport;
    }

    @Override
    public int vendorId() {
        return 0x1AF4;
    }

    /** Signals the driver that the used ring has advanced. */
    protected void raiseInterrupt() {
        if (transport != null) {
            transport.raiseInterrupt();
        }
    }

    @Override
    public int configSize() {
        return 0;
    }

    @Override
    public long readConfig(int offset) {
        return 0L;
    }

    @Override
    public void writeConfig(int offset, long value) {
    }
}