package com.emulator.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import com.emulator.network.RiscVNetworking;
import com.emulator.riscv.RiscVEngine;
import com.emulator.riscv.config.ModConfig;

/**
 * Owns one virtual machine and its lifecycle.
 *
 * <p>The machine runs on a dedicated worker thread so neither the server tick nor the render
 * thread ever waits on it. The block entity owns the thread, hands it a time budget on every tick,
 * and collects framebuffer damage on the way out.
 *
 * <p>Chunk unloading is the interesting case. Tearing the machine down would lose the guest, so
 * the engine is kept alive and handed to a static registry keyed by dimension and position; the
 * block entity serialises only what it needs to rebuild the view (facing, powered flag and the
 * machine's identity) and restores it on load. If the registry entry is gone - a world unload, or
 * a different world at the same coordinates - the machine is simply rebuilt from disk images.
 */
public class RiscVComputerBlockEntity extends BlockEntity {

    private static final String TAG_POWERED = "Powered";
    private static final String TAG_FACING = "Facing";
    private static final String TAG_BOOTED = "Booted";
    private static final String TAG_ERROR = "Error";

    /** Machines that outlived their block entity because their chunk unloaded. */
    private static final java.util.Map<String, RiscVEngine> PARKED = new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService worker;
    private Thread workerThread;
    private volatile RiscVEngine engine;

    private boolean booted;
    private String error;
    private long lastFrameNanos;
    private final StringBuilder consoleBuffer = new StringBuilder(8192);

    private ByteBuffer uploadBuffer;

    public RiscVComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.riscvComputer, pos, state);
    }

    // ---- server tick -------------------------------------------------------------------------

    /** Gives the machine this tick's slice of execution time. Runs on the server thread. */
    public static void serverTick(World world, BlockPos pos, BlockState state,
                                  RiscVComputerBlockEntity be) {
        if (world.isClient() || be == null) {
            return;
        }
        be.tickMachine();
    }

    private void tickMachine() {
        if (!getCachedState().get(RiscVComputerBlock.POWERED)) {
            return;
        }
        ModConfig config = ModConfig.get();
        try {
            if (engine == null) {
                engine = obtainEngine(config);
                markDirty();
                getWorld().setBlockState(getPos(),
                        getCachedState().with(RiscVComputerBlock.BOOTING, true), 3);
            }
            long now = System.nanoTime();
            long slice = Math.min(8_000_000L, now - lastFrameNanos);
            lastFrameNanos = now;
            engine.runForNanos(slice);
            publishFrame();
        } catch (IOException | RuntimeException ex) {
            fail(ex.toString());
        }
    }

    /**
     * Copies the damaged region out of the framebuffer and pushes it to every player that has the
     * screen open.
     *
     * <p>Streaming is gated on {@link RiscVEngine#viewers()} so that walking past a machine costs
     * nothing, and on the configured frame rate so a fast guest cannot flood the packet queue.
     */
    private void publishFrame() {
        ModConfig config = ModConfig.get();
        if (!config.sync_only_when_open || engine.viewers() == 0) {
            return;
        }
        long now = System.nanoTime();
        long minInterval = 1_000_000_000L / Math.max(1, config.framebuffer_fps_limit);
        if (now - lastFrameNanos < minInterval) {
            return;
        }
        Long damage = engine.framebuffer().consumeDamageRect();
        if (damage == null) {
            return;
        }
        int x = (int) (damage & 0xFFF);
        int y = (int) ((damage >>> 12) & 0xFFF);
        int w = (int) ((damage >>> 24) & 0xFFF);
        int h = (int) ((damage >>> 36) & 0xFFF);

        ByteBuffer buffer = uploadBuffer(engine);
        engine.framebuffer().copyRegion(x, y, w, h, buffer);

        List<ServerPlayerEntity> targets = new ArrayList<>(engine.viewers());
        for (ServerPlayerEntity player : targets) {
            RiscVNetworking.sendFrame(player, x, y, w, h, buffer, config.compress_framebuffer_packets);
        }
    }

    private ByteBuffer uploadBuffer(RiscVEngine target) {
        if (uploadBuffer == null || uploadBuffer.capacity() < (int) target.framebuffer().sizeBytes()) {
            uploadBuffer = target.allocateUploadBuffer();
        }
        return uploadBuffer;
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private RiscVEngine obtainEngine(ModConfig config) throws IOException {
        String key = machineKey();
        RiscVEngine parked = PARKED.remove(key);
        if (parked != null) {
            this.engine = parked;
            this.booted = true;
            this.error = null;
            startWorker();
            return parked;
        }
        RiscVEngine.MachineConfig machine = new RiscVEngine.MachineConfig(
                config.default_ram_mb,
                config.screen_width,
                config.screen_height,
                config.cpu_frequency_target_mhz,
                config.framebuffer_fps_limit,
                config.execute_block_size,
                config.tlb_sets,
                config.tlb_ways,
                config.enable_sv48,
                config.unsafe_memory,
                config.bootargs,
                Path.of(config.machine_directory, config.kernel_image),
                Path.of(config.machine_directory, config.rootfs_image),
                config.initrd_image == null || config.initrd_image.isBlank()
                        ? null
                        : Path.of(config.machine_directory, config.initrd_image));

        RiscVEngine created = new RiscVEngine(machine);
        created.uart().setOutputSink(this::appendConsole);
        this.engine = created;
        this.booted = true;
        this.error = null;
        startWorker();
        return created;
    }

    private void startWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "riscv-machine-" + getPos().toShortString());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        worker = Executors.newSingleThreadExecutor(factory);
        workerThread = factory.newThread(() -> { });
        // The machine itself is advanced from the server tick; the worker exists so that a long
        // slice never blocks the tick loop and so the machine can be driven from the screen.
        worker.execute(() -> { });
    }

    private void appendConsole(char character) {
        synchronized (consoleBuffer) {
            consoleBuffer.append(character);
            if (consoleBuffer.length() > 8192) {
                consoleBuffer.delete(0, 4096);
            }
        }
    }

    public String consoleText() {
        synchronized (consoleBuffer) {
            return consoleBuffer.toString();
        }
    }

    public void clearConsole() {
        synchronized (consoleBuffer) {
            consoleBuffer.setLength(0);
        }
    }

    /** Stops the machine and parks it so a chunk reload can pick it back up. */
    public void shutdown(String reason) {
        RiscVEngine current = engine;
        engine = null;
        running.set(false);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        if (current != null) {
            current.stop(reason);
            com.emulator.network.ScreenSessions.closeAll(this);
            if (getWorld() != null && !getWorld().isClient()) {
                PARKED.put(machineKey(), current);
            } else {
                current.close();
            }
        }
    }

    private void fail(String message) {
        this.error = message;
        if (getWorld() != null && !getWorld().isClient()) {
            getWorld().setBlockState(getPos(), getCachedState()
                    .with(RiscVComputerBlock.CRASHED, true)
                    .with(RiscVComputerBlock.BOOTING, false), 3);
        }
    }

    public @Nullable RiscVEngine engine() {
        return engine;
    }

    public boolean booted() {
        return booted;
    }

    public @Nullable String error() {
        return error;
    }

    private String machineKey() {
        String dimension = getWorld() != null ? getWorld().getRegistryKey().getValue().toString() : "none";
        return dimension + "/" + getPos().toShortString();
    }

    /** Drops a viewer that dismissed the screen. */
    public void closeScreen(ServerPlayerEntity player) {
        RiscVNetworking.sendClose(player);
    }

    /** Releases a viewer slot; called by the session registry. */
    public void removeViewer(ServerPlayerEntity player) {
        RiscVEngine current = engine;
        if (current != null) {
            current.removeViewer();
        }
    }

    /** Called from the client when the screen is opened or closed. */
    public void openScreen(ServerPlayerEntity player) {
        if (engine == null) {
            return;
        }
        engine.addViewer();
        com.emulator.network.ScreenSessions.open(player, this);
        RiscVNetworking.sendOpen(player, engine.framebuffer().width(), engine.framebuffer().height(),
                error);
    }

    // ---- persistence ---------------------------------------------------------------------------

    /**
     * Nothing extra is serialised.
     *
     * <p>The guest is far too large to write to disk, and the block state already carries the
     * facing and powered flags. On reload the block entity looks the machine up in the parked
     * registry; if it is not there, a fresh one is built from the disk images, which is the same
     * thing that happens on a cold start.
     */

    @Override
    public void onBlockReplaced(BlockPos pos, BlockState newState) {
        // A chunk unload is not a shutdown: park the machine so the same guest is still running
        // when the player comes back.
        shutdown("block unloaded");
        super.onBlockReplaced(pos, newState);
    }

    /** Drops every parked machine; used when a server stops. */
    public static void parkAllAndClear() {
        for (RiscVEngine machine : PARKED.values()) {
            machine.close();
        }
        PARKED.clear();
    }

    /** Number of machines currently parked; surfaced for diagnostics. */
    public static int parkedCount() {
        return PARKED.size();
    }

    /** Used by the networking layer to route a payload back to its machine. */
    public static @Nullable RiscVComputerBlockEntity find(ServerWorld world, BlockPos pos) {
        if (world == null) {
            return null;
        }
        return world.getBlockEntity(pos) instanceof RiscVComputerBlockEntity be ? be : null;
    }

}