package com.emulator.block;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.Identifier;

/** Holder for the mod's block entity types, built during mod initialisation. */
public final class ModBlockEntities {

    private ModBlockEntities() {
    }

    public static BlockEntityType<RiscVComputerBlockEntity> riscvComputer;

    /** Builds the block entity types; called once from the mod entrypoint. */
    public static void register(String modId) {
        if (riscvComputer == null) {
            riscvComputer = FabricBlockEntityTypeBuilder
                    .create(RiscVComputerBlockEntity::new, com.emulator.RiscVEmulatorMod.RISCV_COMPUTER_BLOCK)
                    .build();
        }
    }

    /** Identifier helper kept for the networking payloads. */
    public static Identifier id(String modId, String path) {
        return Identifier.of(modId, path);
    }
}