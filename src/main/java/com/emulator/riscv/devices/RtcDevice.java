package com.emulator.riscv.devices;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;

/**
 * Goldfish-style real time clock.
 *
 * <p>The RTC is optional for a Linux guest - the CLINT's {@code mtime} is what the kernel actually
 * uses for its clock - but the {@code virt} board exposes one and some guests probe for it during
 * early boot. All calendar registers read as binary coded decimal, matching the hardware.
 */
public final class RtcDevice implements Device {

    private static final int RTC_SECONDS = 0x00;
    private static final int RTC_MINUTES = 0x04;
    private static final int RTC_HOURS = 0x08;
    private static final int RTC_DAY = 0x0C;
    private static final int RTC_MONTH = 0x10;
    private static final int RTC_YEAR = 0x14;
    private static final int RTC_DAYS = 0x18;
    private static final int RTC_CTRL = 0x20;
    private static final int RTC_IRQ_ENABLE = 0x2C;
    private static final int RTC_IRQ_STATUS = 0x30;
    private static final int RTC_EOI = 0x34;

    private final InterruptSource interruptController;
    private final int irq;

    private int control;
    private int irqEnable;

    public RtcDevice(InterruptSource interruptController, int irq) {
        this.interruptController = interruptController;
        this.irq = irq;
    }

    private static int bcd(int value) {
        return ((value / 10) << 4) | (value % 10);
    }

    @Override
    public int read(int size, long pa) {
        int offset = (int) (pa - MemoryMap.RTC_BASE);
        LocalDateTime now = LocalDateTime.now();
        return switch (offset) {
            case RTC_SECONDS -> bcd(now.getSecond());
            case RTC_MINUTES -> bcd(now.getMinute());
            case RTC_HOURS -> bcd(now.getHour());
            case RTC_DAY -> now.getDayOfWeek().getValue();
            case RTC_MONTH -> bcd(now.getMonthValue());
            case RTC_YEAR -> bcd(now.getYear() - 2000);
            case RTC_DAYS -> bcd(now.getDayOfMonth());
            case RTC_CTRL -> control;
            case RTC_IRQ_ENABLE -> irqEnable;
            case RTC_IRQ_STATUS -> 0;
            default -> 0;
        };
    }

    @Override
    public void write(int size, long pa, int value) {
        int offset = (int) (pa - MemoryMap.RTC_BASE);
        switch (offset) {
            case RTC_CTRL -> control = value;
            case RTC_IRQ_ENABLE -> {
                irqEnable = value;
                if (value == 0) {
                    interruptController.deassertSource(irq);
                }
            }
            case RTC_EOI -> interruptController.deassertSource(irq);
            default -> {
            }
        }
    }

    @Override
    public String name() {
        return "rtc";
    }

    /** Unused today, kept so a future alarm path can convert epoch time consistently. */
    public long epochSeconds() {
        return LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}