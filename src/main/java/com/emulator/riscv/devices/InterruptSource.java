package com.emulator.riscv.devices;

/** A device that can assert and deassert one of the PLIC's interrupt sources. */
public interface InterruptSource {

    /** Sources are numbered from one; zero is reserved as the "no interrupt" sentinel. */
    int SOURCE_UART = 10;
    int SOURCE_RTC = 11;
    int SOURCE_VIRTIO_BASE = 1;

    void assertSource(int source);

    void deassertSource(int source);
}