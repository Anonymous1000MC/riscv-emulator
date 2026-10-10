package com.emulator.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.util.ActionResult;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * The in-world computer.
 *
 * <p>The block is directional so the screen faces the player who placed it, and carries three
 * logical flags: {@code POWERED} drives the machine, {@code BOOTING} covers the window between
 * power-on and the guest producing its first frame, and {@code CRASHED} records that the machine
 * stopped with an error so the renderer can show that rather than a frozen display.
 *
 * <p>Ticking is deliberately delegated to the block entity, which owns the emulator thread. The
 * block itself only maps state changes onto that thread's lifecycle.
 */
public class RiscVComputerBlock extends Block implements BlockEntityProvider {

    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final BooleanProperty BOOTING = BooleanProperty.of("booting");
    public static final BooleanProperty CRASHED = BooleanProperty.of("crashed");
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    private static final VoxelShape BODY = Block.createCuboidShape(1, 0, 1, 15, 14, 15);
    private static final VoxelShape SCREEN_NORTH = Block.createCuboidShape(2, 3, 0, 14, 13, 2);
    private static final VoxelShape SCREEN_SOUTH = Block.createCuboidShape(2, 3, 14, 14, 13, 16);
    private static final VoxelShape SCREEN_WEST = Block.createCuboidShape(0, 3, 2, 2, 13, 14);
    private static final VoxelShape SCREEN_EAST = Block.createCuboidShape(14, 3, 2, 16, 13, 14);

    /*
     * This block extends Block rather than BlockWithEntity. The latter exists to supply a data-driven
     * codec and a screen-handler factory, neither of which applies here: the block carries no
     * configurable state, and the screen is opened explicitly from onUse. Skipping it also means
     * there is no codec to keep in sync with the block state properties.
     */

    public RiscVComputerBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState()
                .with(POWERED, false)
                .with(BOOTING, false)
                .with(CRASHED, false)
                .with(FACING, Direction.NORTH));
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RiscVComputerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
            BlockEntityType<T> type) {
        if (world.isClient()) {
            return null;
        }
        if (type != ModBlockEntities.riscvComputer) {
            return null;
        }
        return (BlockEntityTicker<T>) (tickWorld, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof RiscVComputerBlockEntity computer) {
                RiscVComputerBlockEntity.serverTick(tickWorld, tickPos, tickState, computer);
            }
        };
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED, BOOTING, CRASHED, FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING, context.getPlayerLookDirection().getOpposite());
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
                                         ShapeContext context) {
        return switch (state.get(FACING)) {
            case NORTH -> VoxelShapes.union(BODY, SCREEN_NORTH);
            case SOUTH -> VoxelShapes.union(BODY, SCREEN_SOUTH);
            case WEST -> VoxelShapes.union(BODY, SCREEN_WEST);
            default -> VoxelShapes.union(BODY, SCREEN_EAST);
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos,
                                        ShapeContext context) {
        return BODY;
    }

    /** A powered computer that has not crashed can be opened. */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        if (!state.get(POWERED)) {
            return ActionResult.PASS;
        }
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof RiscVComputerBlockEntity computer && player instanceof ServerPlayerEntity server) {
            computer.openScreen(server);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, net.minecraft.server.world.ServerWorld world,
                                   BlockPos pos, boolean moved) {
        if (world.getBlockEntity(pos) instanceof RiscVComputerBlockEntity computer) {
            computer.shutdown("block removed");
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}