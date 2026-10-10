package com.emulator.riscv.devices;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A raw block device backed by a disk image file.
 *
 * <p>Transfer uses positional {@code FileChannel} calls so a request costs one syscall for the
 * whole buffer rather than one per sector. The file is opened read/write, and when it has to be
 * created it is allocated sparse so an 8 GiB image costs nothing until it is actually written.
 *
 * <p>This is deliberately a sector-level device with no filesystem layer: virtio-blk hands the
 * guest raw sectors and nothing else. Boot from an {@code initramfs}, or place a filesystem in the
 * image yourself and point {@code root=} at it.
 */
public final class DiskImage implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final long sizeBytes;
    private final boolean readOnly;

    private DiskImage(Path path, FileChannel channel, long sizeBytes, boolean readOnly) {
        this.path = path;
        this.channel = channel;
        this.sizeBytes = sizeBytes;
        this.readOnly = readOnly;
    }

    /** Opens an existing image read/write. */
    public static DiskImage open(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        return new DiskImage(path, channel, channel.size(), false);
    }

    /** Opens an existing image read-only, which is what a {@code root=...} mount should use. */
    public static DiskImage openReadOnly(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        return new DiskImage(path, channel, channel.size(), true);
    }

    /**
     * Opens {@code path}, creating a sparse image of {@code sizeBytes} when it does not exist.
     *
     * @param sizeBytes size applied only when the file has to be created
     */
    public static DiskImage openOrCreate(Path path, long sizeBytes) throws IOException {
        if (Files.isRegularFile(path)) {
            return open(path);
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(Math.max(0, sizeBytes - 1));
            channel.write(ByteBuffer.wrap(new byte[1]));
        }
        return open(path);
    }

    public Path path() {
        return path;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /** Size in 512-byte sectors, which is the unit virtio-blk reports. */
    public long capacitySectors() {
        return sizeBytes / 512;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /**
     * Reads into {@code dst} from {@code position}, advancing the buffer's position.
     *
     * @return the number of bytes transferred
     */
    public int read(long position, ByteBuffer dst) throws IOException {
        if (position < 0 || position >= sizeBytes) {
            return 0;
        }
        int want = (int) Math.min(dst.remaining(), sizeBytes - position);
        int done = 0;
        while (done < want && dst.hasRemaining()) {
            int n = channel.read(dst, position + done);
            if (n <= 0) {
                break;
            }
            done += n;
        }
        return done;
    }

    /**
     * Writes from {@code src} to {@code position}, advancing the buffer's position.
     *
     * @return the number of bytes transferred
     */
    public int write(long position, ByteBuffer src) throws IOException {
        if (readOnly) {
            throw new IOException("disk image is read-only: " + path);
        }
        if (position < 0 || position >= sizeBytes) {
            return 0;
        }
        int want = (int) Math.min(src.remaining(), sizeBytes - position);
        int done = 0;
        while (done < want && src.hasRemaining()) {
            int n = channel.write(src, position + done);
            if (n <= 0) {
                break;
            }
            done += n;
        }
        return done;
    }

    public void flush() throws IOException {
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        if (channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }
}