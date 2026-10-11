package com.emulator.riscv.devices;

import com.emulator.riscv.bus.Device;
import com.emulator.riscv.bus.MemoryMap;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * NS16550A-compatible console.
 *
 * <p>Only what Linux's 8250 driver actually programs is implemented: eight registers with the
 * DLAB-aware divisor latch, a receive FIFO, the four interrupt causes, and line-status reporting
 * for the "no interrupt pending" paths. There is no modem-control hardware, so MSR reads as
 * "carrier detect and data set ready asserted", which is what a null-modem console looks like.
 */
public final class Uart16550Device implements Device {

    private static final int REG_RBR = 0;
    private static final int REG_IER = 1;
    private static final int REG_IIR = 2;
    private static final int REG_LCR = 3;
    private static final int REG_MCR = 4;
    private static final int REG_LSR = 5;
    private static final int REG_MSR = 6;
    private static final int REG_SCR = 7;

    private static final int IER_RECEIVED_DATA = 1;
    private static final int IER_TRANSMITTER_EMPTY = 2;
    private static final int IER_RECEIVER_STATUS = 4;
    private static final int IER_MODERATOR_STATUS = 8;

    private static final int IIR_MODEM_STATUS = 0x00;
    private static final int IIR_NO_INTERRUPT = 0x01;
    private static final int IIR_TRANSMITTER_EMPTY = 0x02;
    private static final int IIR_RECEIVED_DATA = 0x04;
    private static final int IIR_RECEIVER_STATUS = 0x06;

    private static final int LSR_DATA_READY = 0x01;
    private static final int LSR_TRANSMITTER_EMPTY = 0x20;
    private static final int LSR_TRANSMITTER_IDLE = 0x40;

    private static final int LSR_FRAME = 0x08;
    private static final int LSR_PARITY = 0x04;
    private static final int LSR_OVERRUN = 0x02;

    private static final int FIFO_DEPTH = 1024;

    private final InterruptSource interruptController;
    private final int irq;

    private final ArrayDeque<Integer> receive = new ArrayDeque<>(64);
    private final StringBuilder transmit = new StringBuilder(256);

    private int ier;
    private int iir = IIR_NO_INTERRUPT;

    /**
     * Latched interrupt conditions.
     *
     * <p>The transmitter-empty, receiver-status and modem-status interrupts are edge triggered on
     * real hardware: the line is raised when the condition changes and the guest clears it by
     * reading the IIR. Deriving them from IER alone instead leaves them permanently asserted
     * once the guest enables console interrupts, and the controller then re-enters the serial
     * handler for ever without ever letting the boot make progress.
     */
    private boolean transmitterEmptyLatched;
    private boolean receiverStatusLatched;
    private boolean modemStatusLatched;
    private int lcr;
    private int mcr;
    private int scr;
    private int divisorLow = 1;
    private int divisorHigh;

    private Consumer<Character> outputSink = c -> { };
    private Runnable transmitDrain = () -> { };

    public Uart16550Device(InterruptSource interruptController, int irq) {
        this.interruptController = interruptController;
        this.irq = irq;
    }

    /** Redirects transmitted characters; the block entity uses this to feed the in-game console. */
    public void setOutputSink(Consumer<Character> sink) {
        this.outputSink = sink == null ? b -> { } : sink;
    }

    /** Invoked once per drain so the owner can flush the pending text somewhere. */
    public void setTransmitDrain(Runnable drain) {
        this.transmitDrain = drain == null ? () -> { } : drain;
    }

    /** Queues bytes for the guest to read from the receive data register. */
    public void receiveBytes(byte[] data, int offset, int length) {
        synchronized (receive) {
            for (int i = 0; i < length && receive.size() < FIFO_DEPTH; i++) {
                receive.addLast(data[offset + i] & 0xFF);
            }
        }
        refreshInterrupt();
    }

    public void receiveByte(int value) {
        synchronized (receive) {
            if (receive.size() < FIFO_DEPTH) {
                receive.addLast(value & 0xFF);
            }
        }
        refreshInterrupt();
    }

    public boolean hasTransmitted() {
        return !transmit.isEmpty();
    }

    /** Returns and clears the characters written by the guest since the last call. */
    public String drainTransmitted() {
        if (transmit.isEmpty()) {
            return "";
        }
        String text = transmit.toString();
        transmit.setLength(0);
        raiseTransmitterEmpty();
        return text;
    }

    /**
     * Raises the transmitter-empty interrupt, but only on the transition out of busy.
     *
     * <p>A 16550 does not re-raise this every time the holding register happens to be found
     * empty. It raises it when the transmitter empties and stays quiet until the guest writes
     * another character, so latching it on every poll would leave the line permanently up.
     */
    private void raiseTransmitterEmpty() {
        if (!transmitterEmptyLatched) {
            transmitterEmptyLatched = true;
            refreshInterrupt();
        }
    }

    private boolean divisorLatched() {
        return (lcr & 0x80) != 0;
    }

    @Override
    public int read(int size, long pa) {
        int offset = (int) (pa - MemoryMap.UART_BASE);
        switch (offset) {
            case REG_RBR:
                if (divisorLatched()) {
                    return divisorLow & 0xFF;
                }
                synchronized (receive) {
                    Integer next = receive.pollFirst();
                    return next == null ? 0 : next;
                }
            case REG_IER:
                return divisorLatched() ? divisorHigh & 0xFF : ier & 0xF;
            case REG_IIR: {
                if (divisorLatched()) {
                    return 0;
                }
                int status = interruptPending() ? currentIir() : IIR_NO_INTERRUPT;
                // Reading the IIR acknowledges the interrupt it reports, exactly as a 16550
                // does. Without this the latch would never clear and the line would stay up.
                if (status == IIR_TRANSMITTER_EMPTY) {
                    transmitterEmptyLatched = false;
                } else if (status == IIR_RECEIVER_STATUS) {
                    receiverStatusLatched = false;
                } else if (status == IIR_MODEM_STATUS) {
                    modemStatusLatched = false;
                }
                refreshInterrupt();
                return (status & 0x0F) | 0xC0;
            }
            case REG_LCR:
                return lcr & 0xFF;
            case REG_MCR:
                return mcr & 0x1F;
            case REG_LSR: {
                boolean dataReady;
                synchronized (receive) {
                    dataReady = !receive.isEmpty();
                }
                int status = LSR_TRANSMITTER_EMPTY | LSR_TRANSMITTER_IDLE;
                if (dataReady) {
                    status |= LSR_DATA_READY;
                }
                return status;
            }
            case REG_MSR:
                // Null modem: delta and complement status bits, with DCD and DSR asserted.
                return 0x30;
            case REG_SCR:
                return scr & 0xFF;
            default:
                return 0;
        }
    }

    @Override
    public void write(int size, long pa, int value) {
        int offset = (int) (pa - MemoryMap.UART_BASE);
        switch (offset) {
            case REG_RBR:
                if (divisorLatched()) {
                    divisorLow = value & 0xFF;
                } else {
                    emit(value & 0xFF);
                }
                return;
            case REG_IER:
                if (divisorLatched()) {
                    divisorHigh = value & 0xFF;
                } else {
                    ier = value & 0x0F;
                    refreshInterrupt();
                }
                return;
            case REG_IIR:
                // Write to FCR; the receive trigger level is the only bit that matters here.
                return;
            case REG_LCR:
                lcr = value & 0xFF;
                return;
            case REG_MCR:
                mcr = value & 0xFF;
                return;
            case REG_LSR:
                return;
            case REG_MSR:
                return;
            case REG_SCR:
                scr = value & 0xFF;
                return;
            default:
        }
    }

    private void emit(int value) {
        transmit.append((char) value);
        // The holding register is now busy, so the line drops; it is re-raised by the drain.
        transmitterEmptyLatched = false;
        refreshInterrupt();
        if (transmit.length() >= 256) {
            flush();
        }
        refreshInterrupt();
    }

    /** Pushes buffered output to the sink; called from the machine's periodic tick. */
    public void flush() {
        if (transmit.isEmpty()) {
            return;
        }
        StringBuilder batch = new StringBuilder(transmit.length());
        batch.append(transmit);
        transmit.setLength(0);
        for (int i = 0; i < batch.length(); i++) {
            outputSink.accept(batch.charAt(i));
        }
        transmitDrain.run();
        raiseTransmitterEmpty();
    }

    private boolean dataReady() {
        synchronized (receive) {
            return !receive.isEmpty();
        }
    }

    private boolean interruptPending() {
        return ((ier & IER_RECEIVED_DATA) != 0 && dataReady())
                || ((ier & IER_TRANSMITTER_EMPTY) != 0 && transmitterEmptyLatched)
                || ((ier & IER_RECEIVER_STATUS) != 0 && receiverStatusLatched)
                || ((ier & IER_MODERATOR_STATUS) != 0 && modemStatusLatched);
    }

    private int currentIir() {
        if ((ier & IER_RECEIVED_DATA) != 0 && dataReady()) {
            return IIR_RECEIVED_DATA;
        }
        if ((ier & IER_TRANSMITTER_EMPTY) != 0 && transmitterEmptyLatched) {
            return IIR_TRANSMITTER_EMPTY;
        }
        if ((ier & IER_RECEIVER_STATUS) != 0 && receiverStatusLatched) {
            return IIR_RECEIVER_STATUS;
        }
        return IIR_MODEM_STATUS;
    }

    private void refreshInterrupt() {
        iir = interruptPending() ? currentIir() : IIR_NO_INTERRUPT;
        if (iir == IIR_NO_INTERRUPT) {
            interruptController.deassertSource(irq);
        } else {
            interruptController.assertSource(irq);
        }
    }

    /** Baud rate implied by the divisor latch, for diagnostics. */
    public int baudRate() {
        int divisor = Math.max(1, (divisorHigh << 8) | divisorLow);
        return 115200 / divisor;
    }

    /** Convenience for tests and the console screen: pushes a whole string into the guest. */
    public void type(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        receiveBytes(bytes, 0, bytes.length);
    }

    @Override
    public String name() {
        return "ns16550a";
    }

    /** Unused status bits kept for completeness of the 8250 model. */
    static int unusedStatusBits() {
        return LSR_OVERRUN | LSR_PARITY | LSR_FRAME;
    }
}