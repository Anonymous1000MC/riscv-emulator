package com.emulator.riscv.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistent emulator configuration backed by {@code config/riscv_emulator.json}.
 *
 * <p>Missing keys are filled from the field initialisers, so an old or partially written
 * file is always upgraded in place on the next write rather than failing to load.
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "riscv_emulator.json";

    /** Root directory the mod reads disk images and initrds from. */
    public String machine_directory = "riscv_machines";

    /** Guest RAM allocated per computer block, in mebibytes. Clamped to {@link #max_ram_mb}. */
    public int default_ram_mb = 2048;

    /** Upper bound the server operator allows for {@link #default_ram_mb}. */
    public int max_ram_mb = 8192;

    /** Framebuffer width in pixels. */
    public int screen_width = 1920;

    /** Framebuffer height in pixels. */
    public int screen_height = 1080;

    /** Retired-instructions-per-second ceiling used by the throttle loop. */
    public int cpu_frequency_target_mhz = 200;

    /** Maximum rate at which framebuffer contents are pushed to clients, in frames per second. */
    public int framebuffer_fps_limit = 30;

    /** Number of retired instructions executed between timer/interrupt polls. */
    public int execute_block_size = 10000;

    /** Translation cache associativity and set count; both are exposed for tuning. */
    public int tlb_sets = 512;
    public int tlb_ways = 4;

    /** Enable Sv48 (4-level) page tables. When false the MMU only advertises Bare/Sv39. */
    public boolean enable_sv48 = true;

    /** Guest kernel path relative to {@link #machine_directory}. */
    public String kernel_image = "Image";

    /** Root filesystem/disk image path relative to {@link #machine_directory}. */
    public String rootfs_image = "rootfs.img";

    /** Optional initramfs handed to the kernel via the {@code initrd} device tree property. */
    public String initrd_image = "";

    /** {@code root=...} string handed to the guest through {@code /chosen/bootargs}. */
    public String bootargs = "console=ttyS0,115200 earlycon root=/dev/vda rw";

    /**
     * Use {@code sun.misc.Unsafe} for the flat physical-address arena. Disabling this falls
     * back to chunked direct {@link java.nio.ByteBuffer} storage, which is safe but slower
     * and cannot hold the very large allocations Unsafe permits.
     */
    public boolean unsafe_memory = true;

    /** Run guest machines on a dedicated thread instead of the server tick thread. */
    public boolean dedicated_executor = true;

    /** Compress framebuffer sync payloads with Deflate before putting them on the wire. */
    public boolean compress_framebuffer_packets = true;

    /** Only stream framebuffer updates to players that currently have the screen open. */
    public boolean sync_only_when_open = true;

    private static ModConfig instance;

    private ModConfig() {
    }

    public static synchronized ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Discards the cached instance so the next {@link #get()} re-reads from disk. */
    public static synchronized void invalidate() {
        instance = null;
    }

    private static Path configPath() {
        return Path.of(CONFIG_DIR, CONFIG_FILE);
    }

    private static ModConfig load() {
        ModConfig cfg = new ModConfig();
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            cfg.sanitize();
            cfg.save();
            return cfg;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject()) {
                cfg.merge(root.getAsJsonObject());
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[riscv-emulator] Could not read " + path + ", using defaults: " + ex);
        }
        cfg.sanitize();
        cfg.save();
        return cfg;
    }

    private void merge(JsonObject json) {
        // Defaults first so a truncated file cannot zero out unset keys.
        JsonObject merged = GSON.toJsonTree(this).getAsJsonObject();
        for (String key : json.keySet()) {
            if (merged.has(key) && json.get(key).isJsonPrimitive()) {
                merged.add(key, json.get(key));
            }
        }
        Gson quiet = new Gson();
        try {
            ModConfig parsed = quiet.fromJson(merged, ModConfig.class);
            if (parsed != null) {
                this.default_ram_mb = parsed.default_ram_mb;
                this.max_ram_mb = parsed.max_ram_mb;
                this.screen_width = parsed.screen_width;
                this.screen_height = parsed.screen_height;
                this.cpu_frequency_target_mhz = parsed.cpu_frequency_target_mhz;
                this.framebuffer_fps_limit = parsed.framebuffer_fps_limit;
                this.execute_block_size = parsed.execute_block_size;
                this.tlb_sets = parsed.tlb_sets;
                this.tlb_ways = parsed.tlb_ways;
                this.enable_sv48 = parsed.enable_sv48;
                this.machine_directory = parsed.machine_directory;
                this.kernel_image = parsed.kernel_image;
                this.rootfs_image = parsed.rootfs_image;
                this.initrd_image = parsed.initrd_image;
                this.bootargs = parsed.bootargs;
                this.unsafe_memory = parsed.unsafe_memory;
                this.dedicated_executor = parsed.dedicated_executor;
                this.compress_framebuffer_packets = parsed.compress_framebuffer_packets;
                this.sync_only_when_open = parsed.sync_only_when_open;
            }
        } catch (RuntimeException ignored) {
            // Fall through with whatever defaults are already in place.
        }
    }

    /** Clamps every field into a range the rest of the emulator is prepared to handle. */
    public void sanitize() {
        if (max_ram_mb < 64) max_ram_mb = 64;
        if (max_ram_mb > 65536) max_ram_mb = 65536;
        if (default_ram_mb < 64) default_ram_mb = 64;
        if (default_ram_mb > max_ram_mb) default_ram_mb = max_ram_mb;

        if (screen_width < 320) screen_width = 320;
        if (screen_width > 4096) screen_width = 4096;
        if (screen_height < 240) screen_height = 240;
        if (screen_height > 4096) screen_height = 4096;

        if (cpu_frequency_target_mhz < 1) cpu_frequency_target_mhz = 1;
        if (cpu_frequency_target_mhz > 100000) cpu_frequency_target_mhz = 100000;

        if (framebuffer_fps_limit < 1) framebuffer_fps_limit = 1;
        if (framebuffer_fps_limit > 120) framebuffer_fps_limit = 120;

        if (execute_block_size < 64) execute_block_size = 64;
        if (execute_block_size > 1_000_000) execute_block_size = 1_000_000;

        if (tlb_sets < 64) tlb_sets = 64;
        if (tlb_sets > 65536) tlb_sets = 65536;
        if (tlb_ways < 1) tlb_ways = 1;
        if (tlb_ways > 16) tlb_ways = 16;

        if (machine_directory == null || machine_directory.isBlank()) {
            machine_directory = "riscv_machines";
        }
        if (kernel_image == null || kernel_image.isBlank()) kernel_image = "Image";
        if (rootfs_image == null || rootfs_image.isBlank()) rootfs_image = "rootfs.img";
        if (bootargs == null) bootargs = "";
    }

    /** Bytes of guest RAM described by {@link #default_ram_mb}. */
    public long defaultRamBytes() {
        return (long) default_ram_mb * 1024L * 1024L;
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(CONFIG_FILE + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
                writer.write('\n');
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            System.err.println("[riscv-emulator] Could not write " + path + ": " + ex);
        }
    }
}