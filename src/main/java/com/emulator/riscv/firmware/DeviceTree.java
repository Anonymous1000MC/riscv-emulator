package com.emulator.riscv.firmware;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A flattened device tree (DTB version 17) generator.
 *
 * <p>The tree mirrors the layout QEMU's {@code virt} machine produces, because that is the
 * description RISC-V Linux is tested against. Writing it by hand in Java rather than shipping a
 * static blob means the node addresses follow this emulator's own memory map instead of drifting
 * away from it when the map changes.
 *
 * <p>All fields are big endian, as the specification requires, and every token stream is padded to
 * a four byte boundary.
 */
public final class DeviceTree {

    private static final int FDT_MAGIC = 0xD00DFEED;
    private static final int FDT_VERSION = 17;
    private static final int FDT_LAST_COMP_VERSION = 16;

    private static final int FDT_BEGIN_NODE = 1;
    private static final int FDT_END_NODE = 2;
    private static final int FDT_PROP = 3;
    private static final int FDT_NOP = 4;
    private static final int FDT_END = 9;

    private final ByteArrayOutputStream structStream = new ByteArrayOutputStream(4096);
    private final Map<String, Integer> stringOffsets = new HashMap<>();
    private final ByteArrayOutputStream strings = new ByteArrayOutputStream(1024);
    private final List<long[]> reservations = new ArrayList<>();

    private int phandleCounter = 1;
    private int depth;

    public DeviceTree() {
    }

    // ---- token stream ------------------------------------------------------------------------

    public DeviceTree beginNode(String name) {
        writeInt(FDT_BEGIN_NODE);
        writeString(name);
        depth++;
        return this;
    }

    public DeviceTree endNode() {
        depth--;
        writeInt(FDT_END_NODE);
        return this;
    }

    /** Reserves a phandle value; returns it so the caller can wire up references. */
    public int newPhandle() {
        return phandleCounter++;
    }

    public DeviceTree property(String name, int value) {
        return property(name, new int[] { value });
    }

    public DeviceTree propertyU32(String name, long value) {
        return property(name, new int[] { (int) value });
    }

    public DeviceTree propertyU64(String name, long value) {
        byte[] data = new byte[8];
        writeBigEndian(data, 0, (int) (value >>> 32));
        writeBigEndian(data, 4, (int) value);
        return rawProperty(name, data);
    }

    public DeviceTree property(String name, int[] cells) {
        byte[] data = new byte[cells.length * 4];
        for (int i = 0; i < cells.length; i++) {
            writeBigEndian(data, i * 4, cells[i]);
        }
        return rawProperty(name, data);
    }

    public DeviceTree property(String name, long[] cells) {
        byte[] data = new byte[cells.length * 4];
        for (int i = 0; i < cells.length; i++) {
            writeBigEndian(data, i * 4, (int) cells[i]);
        }
        return rawProperty(name, data);
    }

    /** A {@code reg} property written as address/size cell pairs. */
    public DeviceTree propertyReg(long base, long size, int addressCells, int sizeCells) {
        List<Long> cells = new ArrayList<>();
        appendCells(cells, base, addressCells);
        appendCells(cells, size, sizeCells);
        long[] values = new long[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            values[i] = cells.get(i);
        }
        return property("reg", values);
    }

    private static void appendCells(List<Long> out, long value, int cellCount) {
        if (cellCount == 1) {
            out.add(value);
            return;
        }
        out.add(value >>> 32);
        out.add(value & 0xFFFF_FFFFL);
    }

    public DeviceTree propertyString(String name, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, data, 0, bytes.length);
        return rawProperty(name, data);
    }

    public DeviceTree propertyStrings(String name, String... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(bytes);
            out.write(0);
        }
        return rawProperty(name, out.toByteArray());
    }

    /** An empty (zero length) property, which is how {@code interrupt-controller} is expressed. */
    public DeviceTree propertyEmpty(String name) {
        return rawProperty(name, new byte[0]);
    }

    /** A boolean property; in device tree a present zero-length property means true. */
    public DeviceTree propertyBoolean(String name, boolean value) {
        return value ? propertyEmpty(name) : this;
    }

    private DeviceTree rawProperty(String name, byte[] data) {
        writeInt(FDT_PROP);
        writeInt(data.length);
        writeInt(stringOffset(name));
        writeBytes(data);
        int pad = (4 - (data.length & 3)) & 3;
        for (int i = 0; i < pad; i++) {
            structStream.write(0);
        }
        return this;
    }

    private int stringOffset(String name) {
        return stringOffsets.computeIfAbsent(name, key -> {
            int offset = strings.size();
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            strings.writeBytes(bytes);
            strings.write(0);
            return offset;
        });
    }

    // ---- memory reservations ------------------------------------------------------------------

    /** Reserves {@code [address, address + size)} in the kernel's physical memory map. */
    public DeviceTree reserve(long address, long size) {
        if (address != 0 && size != 0) {
            reservations.add(new long[] { address, size });
        }
        return this;
    }

    // ---- serialisation -------------------------------------------------------------------------

    private void writeInt(int value) {
        byte[] data = new byte[4];
        writeBigEndian(data, 0, value);
        structStream.writeBytes(data);
    }

    private void writeBytes(byte[] data) {
        structStream.writeBytes(data);
    }

    private void writeString(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        structStream.writeBytes(bytes);
        structStream.write(0);
        int pad = (4 - ((bytes.length + 1) & 3)) & 3;
        for (int i = 0; i < pad; i++) {
            structStream.write(0);
        }
    }

    /** Terminates the structure block and returns the complete DTB image. */
    public byte[] build() {
        writeInt(FDT_END);
        // The stream object mutates, so take a snapshot of its contents.
        byte[] structBlock = structStream.toByteArray();
        byte[] stringsBlock = strings.toByteArray();

        int headerSize = 40;
        int reserveBlockSize = (reservations.size() + 1) * 16;
        int offMemRsvMap = headerSize;
        int offStruct = offMemRsvMap + reserveBlockSize;
        int offStrings = offStruct + structBlock.length;
        int totalSize = offStrings + stringsBlock.length;

        byte[] dtb = new byte[totalSize];

        ByteArrayOutputStream reserve = new ByteArrayOutputStream(reserveBlockSize);
        for (long[] entry : reservations) {
            long address = entry[0];
            long size = entry[1];
            byte[] data = new byte[16];
            writeBigEndian(data, 0, (int) (address >>> 32));
            writeBigEndian(data, 4, (int) address);
            writeBigEndian(data, 8, (int) (size >>> 32));
            writeBigEndian(data, 12, (int) size);
            reserve.writeBytes(data);
        }
        // Terminator.
        reserve.writeBytes(new byte[16]);

        System.arraycopy(reserve.toByteArray(), 0, dtb, offMemRsvMap, reserveBlockSize);
        System.arraycopy(structBlock, 0, dtb, offStruct, structBlock.length);
        System.arraycopy(stringsBlock, 0, dtb, offStrings, stringsBlock.length);

        writeBigEndian(dtb, 0, FDT_MAGIC);
        writeBigEndian(dtb, 4, totalSize);
        writeBigEndian(dtb, 8, offStruct);
        writeBigEndian(dtb, 12, offStrings);
        writeBigEndian(dtb, 16, offMemRsvMap);
        writeBigEndian(dtb, 20, FDT_VERSION);
        writeBigEndian(dtb, 24, FDT_LAST_COMP_VERSION);
        writeBigEndian(dtb, 28, 0);
        writeBigEndian(dtb, 32, stringsBlock.length);
        writeBigEndian(dtb, 36, structBlock.length);
        return dtb;
    }

    private static void writeBigEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /** Node depth, exposed for diagnostics. */
    public int depth() {
        return depth;
    }

    /** Convenience for callers that just want to serialise without inspecting the tree. */
    public byte[] toBytes() throws IOException {
        try {
            return build();
        } catch (RuntimeException ex) {
            throw new IOException("failed to serialise device tree", ex);
        }
    }
}