package com.emulator.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.network.ServerPlayerEntity;

import com.emulator.block.RiscVComputerBlockEntity;

/**
 * Tracks which machine each player currently has open.
 *
 * <p>Client input packets carry no address, so the server has to remember the mapping. Sessions are
 * established when the server tells a client to open a screen and torn down on disconnect or when
 * the block entity shuts down, which is what stops a stale session from injecting keystrokes into a
 * machine the player has walked away from.
 */
public final class ScreenSessions {

    private static final Map<UUID, RiscVComputerBlockEntity> BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<Long, RiscVComputerBlockEntity> BY_BLOCK = new ConcurrentHashMap<>();

    private ScreenSessions() {
    }

    /** Records that {@code player} is now looking at {@code computer}. */
    public static void open(ServerPlayerEntity player, RiscVComputerBlockEntity computer) {
        BY_PLAYER.put(player.getUuid(), computer);
        BY_BLOCK.put(computer.getPos().asLong(), computer);
    }

    public static void close(ServerPlayerEntity player) {
        RiscVComputerBlockEntity computer = BY_PLAYER.remove(player.getUuid());
        if (computer != null) {
            computer.removeViewer(player);
            BY_BLOCK.remove(computer.getPos().asLong());
        }
    }

    /** Drops every viewer of a machine, used when it shuts down. */
    public static void closeAll(RiscVComputerBlockEntity computer) {
        BY_BLOCK.remove(computer.getPos().asLong());
        BY_PLAYER.entrySet().removeIf(entry -> entry.getValue() == computer);
    }

    public static RiscVComputerBlockEntity computerAt(ServerPlayerEntity player) {
        return BY_PLAYER.get(player.getUuid());
    }

    public static int openSessions() {
        return BY_PLAYER.size();
    }
}