package com.emulator;

import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emulator.block.ModBlockEntities;
import com.emulator.block.RiscVComputerBlock;
import com.emulator.network.RiscVNetworking;
import com.emulator.riscv.config.ModConfig;

/**
 * Mod entrypoint.
 *
 * <p>Registration is deliberately tiny: one block, one block entity type and the network payloads.
 * Everything interesting lives in {@code com.emulator.riscv}, which has no Minecraft types at all
 * and can be exercised headlessly.
 */
public class RiscVEmulatorMod implements ModInitializer {

    public static final String MOD_ID = "riscv-emulator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final RiscVComputerBlock RISCV_COMPUTER_BLOCK =
            new RiscVComputerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                    .strength(3.0f)
                    .requiresTool()
                    .nonOpaque()
                    .sounds(net.minecraft.sound.BlockSoundGroup.METAL));

    @Override
    public void onInitialize() {
        ModConfig config = ModConfig.get();
        LOGGER.info("Loading RISC-V emulator configuration ({} MiB RAM, {}x{})",
                config.default_ram_mb, config.screen_width, config.screen_height);

        Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "computer"), RISCV_COMPUTER_BLOCK);
        Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "computer"),
                new net.minecraft.item.BlockItem(RISCV_COMPUTER_BLOCK, new net.minecraft.item.Item.Settings()));

        ModBlockEntities.register(MOD_ID);

        // Registering the codecs is what makes the payload types usable; the receivers are wired
        // up on the client entrypoint and in the block entity respectively.
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(RiscVNetworking.FRAMEBUFFER, RiscVNetworking.FramebufferSyncPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(RiscVNetworking.SCREEN_OPEN, RiscVNetworking.ScreenOpenPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(RiscVNetworking.SCREEN_CLOSE, RiscVNetworking.ScreenClosePayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(RiscVNetworking.INPUT, RiscVNetworking.InputPayload.CODEC);

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                RiscVNetworking.INPUT,
                (payload, context) -> context.server().execute(() ->
                        RiscVNetworking.applyInput(
                                com.emulator.network.ScreenSessions.computerAt(context.player()),
                                payload)));

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> com.emulator.network.ScreenSessions.close(handler.player));
    }

    /** Identifier helper. */
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    /** Unused reference kept so the registry key class is loaded on both sides. */
    static Class<?> keyClass() {
        return RegistryKey.class;
    }
}