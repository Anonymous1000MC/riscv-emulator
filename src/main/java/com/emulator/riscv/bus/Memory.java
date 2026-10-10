package com.emulator.riscv.bus;

import com.emulator.riscv.cpu.TrapException;

import java.util.ArrayList;
import java.util.List;

/**
 * The physical address space: an off-heap RAM arena plus a table of MMIO device windows.
 *
 * <p>Loads and stores are split into {@code Phy} entry points, which take an already translated
 * physical address, and the {@link Bus} virtual entry points, which run the MMU first. Keeping
 * them separate means the physical helpers stay a single range check plus one arena access,
 * which is what makes them inlinable into the interpreter loop.
 *
 * <p>The framebuffer is carved out of the top of RAM and routed through
 * {@link com.emulator.riscv.devices.FramebufferDevice} rather than served directly from the
 * arena. That keeps dirty-tile bookkeeping completely off the RAM path instead of costing every
 * guest store a range check.
 */
public final class Memory {

    private final PhysicalMemory arena;
    private final long ramBase;
    private final long ramSize;
    private final long arenaSize;

    // Framebuffer carve-out, expressed as arena offsets.
    private long fbLo = -1;
    private long fbHi = -1;
    private PixelWindow framebuffer;

    private Device[] devices = new Device[0];
    private long[] deviceBase = new long[0];
    private long[] deviceSize = new long[0];
    private boolean tableFrozen;

    private final List<Device> pending = new ArrayList<>(8);

    public Memory(PhysicalMemory arena, long ramBase, long ramSize) {
        if (ramSize > arena.size()) {
            throw new IllegalArgumentException("RAM size " + ramSize + " exceeds arena "
                    + arena.size());
        }
        this.arena = arena;
        this.ramBase = ramBase;
        this.ramSize = ramSize;
        this.arenaSize = arena.size();
    }

    /** Allocates a RAM arena of {@code bytes}, preferring the flat Unsafe arena. */
    public static Memory allocateRam(long ramBase, long ramBytes, boolean preferUnsafe) {
        PhysicalMemory arena;
        if (preferUnsafe && UnsafePhysicalMemory.isAvailable()) {
            arena = UnsafePhysicalMemory.allocate(ramBytes);
        } else {
            arena = ChunkedPhysicalMemory.allocate(ramBytes);
        }
        return new Memory(arena, ramBase, ramBytes);
    }

    public PhysicalMemory arena() {
        return arena;
    }

    public long ramBase() {
        return ramBase;
    }

    public long ramSize() {
        return ramSize;
    }

    // ---- construction ----------------------------------------------------------------------

    /** Registers a device window. Must be called before the machine starts executing. */
    public void map(long base, long size, Device device) {
        if (tableFrozen) {
            throw new IllegalStateException("device table already frozen");
        }
        if (base < ramBase && base + size > ramBase) {
            throw new IllegalArgumentException("device window " + Long.toHexString(base)
                    + " overlaps RAM");
        }
        // An overlapping window is not a silent no-op: deviceAt returns the first match, so the
        // shadowed device simply becomes unreachable and every access reads back zero.
        for (int i = 0; i < pendingBase.size(); i++) {
            long otherBase = pendingBase.get(i);
            long otherSize = pendingSize.get(i);
            if (base < otherBase + otherSize && otherBase < base + size) {
                throw new IllegalArgumentException("device window " + Long.toHexString(base)
                        + "+" + Long.toHexString(size) + " overlaps the window already mapped at "
                        + Long.toHexString(otherBase) + "+" + Long.toHexString(otherSize));
            }
        }
        pending.add(device);
        pendingBase.add(base);
        pendingSize.add(size);
    }

    private final List<Long> pendingBase = new ArrayList<>(8);
    private final List<Long> pendingSize = new ArrayList<>(8);

    /**
     * Routes the framebuffer's physical window through {@code device} so that writes mark dirty
     * tiles. The window is carved from the top of the RAM arena.
     */
    public void mapFramebuffer(PixelWindow device, long physicalBase, long size) {
        this.framebuffer = device;
        this.fbLo = physicalBase - ramBase;
        this.fbHi = fbLo + size;
    }

    public long framebufferPhysicalBase() {
        return ramBase + Math.max(fbLo, 0);
    }

    public long framebufferPhysicalSize() {
        return fbHi > fbLo ? fbHi - fbLo : 0;
    }

    /** Freezes the device table into flat arrays for fast lookup. */
    public void freeze() {
        if (tableFrozen) {
            return;
        }
        int n = pending.size();
        Device[] d = new Device[n];
        long[] base = new long[n];
        long[] size = new long[n];
        for (int i = 0; i < n; i++) {
            d[i] = pending.get(i);
            base[i] = pendingBase.get(i);
            size[i] = pendingSize.get(i);
        }
        this.devices = d;
        this.deviceBase = base;
        this.deviceSize = size;
        this.tableFrozen = true;
    }

    // ---- physical access --------------------------------------------------------------------

    public long read64(long pa) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                return framebuffer.read(8, pa) & 0xFFFF_FFFF_FFFF_FFFFL;
            }
            return arena.getLong(off);
        }
        Device device = deviceAt(pa);
        if (device != null) {
            return device.read(8, pa) & 0xFFFF_FFFF_FFFF_FFFFL;
        }
        throw accessFault(TrapException.LOAD_ACCESS_FAULT, pa);
    }

    public int read32(long pa) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                return framebuffer.read(4, pa);
            }
            return arena.getInt(off);
        }
        Device device = deviceAt(pa);
        if (device != null) {
            return device.read(4, pa);
        }
        throw accessFault(TrapException.LOAD_ACCESS_FAULT, pa);
    }

    public short read16(long pa) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                return (short) framebuffer.read(2, pa);
            }
            return arena.getShort(off);
        }
        Device device = deviceAt(pa);
        if (device != null) {
            return (short) device.read(2, pa);
        }
        throw accessFault(TrapException.LOAD_ACCESS_FAULT, pa);
    }

    public byte read8(long pa) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                return (byte) framebuffer.read(1, pa);
            }
            return arena.getByte(off);
        }
        Device device = deviceAt(pa);
        if (device != null) {
            return (byte) device.read(1, pa);
        }
        throw accessFault(TrapException.LOAD_ACCESS_FAULT, pa);
    }

    public void write64(long pa, long value) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                framebuffer.write(8, pa, (int) value);
                return;
            }
            arena.putLong(off, value);
            return;
        }
        Device device = deviceAt(pa);
        if (device != null) {
            device.write(8, pa, (int) value);
            return;
        }
        throw accessFault(TrapException.STORE_ACCESS_FAULT, pa);
    }

    public void write32(long pa, int value) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                framebuffer.write(4, pa, value);
                return;
            }
            arena.putInt(off, value);
            return;
        }
        Device device = deviceAt(pa);
        if (device != null) {
            device.write(4, pa, value);
            return;
        }
        throw accessFault(TrapException.STORE_ACCESS_FAULT, pa);
    }

    public void write16(long pa, short value) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                framebuffer.write(2, pa, value & 0xFFFF);
                return;
            }
            arena.putShort(off, value);
            return;
        }
        Device device = deviceAt(pa);
        if (device != null) {
            device.write(2, pa, value & 0xFFFF);
            return;
        }
        throw accessFault(TrapException.STORE_ACCESS_FAULT, pa);
    }

    /**
     * Whether a physical address can be stored to, without performing the store.
 *
     * <p>Used by callers that service guest-supplied pointers and need to report failure instead of
     * raising an access fault.
     */
    public boolean isWritable(long pa) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            return true;
        }
        return deviceAt(pa) != null;
    }

    public void write8(long pa, byte value) {
        long off = pa - ramBase;
        if (off >= 0 && off < ramSize) {
            if (off >= fbLo && off < fbHi) {
                framebuffer.write(1, pa, value & 0xFF);
                return;
            }
            arena.putByte(off, value);
            return;
        }
        Device device = deviceAt(pa);
        if (device != null) {
            device.write(1, pa, value & 0xFF);
            return;
        }
        throw accessFault(TrapException.STORE_ACCESS_FAULT, pa);
    }

    /**
     * Sign-extending eight bit load. Byte loads go through the generic path because the sign
     * extension is a one-instruction operation at the call site in {@link Bus}.
     */
    public byte loadByteRaw(long pa) {
        return read8(pa);
    }

    /** Misaligned physical load; Linux never issues these, but DMA paths and firmware might. */
    public long readMisaligned(long pa, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value |= (read8(pa + i) & 0xFFL) << (i * 8);
        }
        return value;
    }

    public void writeMisaligned(long pa, int size, long value) {
        for (int i = 0; i < size; i++) {
            write8(pa + i, (byte) (value >>> (i * 8)));
        }
    }

    private Device deviceAt(long pa) {
        Device[] table = devices;
        for (int i = 0; i < table.length; i++) {
            long rel = pa - deviceBase[i];
            if (rel >= 0 && rel < deviceSize[i]) {
                return table[i];
            }
        }
        return null;
    }

    private TrapException accessFault(int cause, long pa) {
        return new TrapException(cause, pa);
    }

    // ---- direct arena helpers used by the loader ---------------------------------------------

    /** Copies a little-endian byte array into guest RAM, ignoring the device windows. */
    public void loadImage(byte[] data, int srcIndex, int length, long pa) {
        long off = pa - ramBase;
        if (off < 0 || off + length > ramSize) {
            throw new IllegalArgumentException("image does not fit at " + Long.toHexString(pa));
        }
        arena.write(off, data, srcIndex, length);
    }

    public void zeroRam() {
        arena.zero();
    }

    public void tickDevices() {
        Device[] table = devices;
        for (Device device : table) {
            device.tick();
        }
    }

    public void close() {
        arena.close();
    }

    public String describe() {
        return "Memory[" + arena.describe() + ", ram@" + Long.toHexString(ramBase) + "+"
                + (ramSize >> 20) + "MiB, devices=" + devices.length + "]";
    }
}