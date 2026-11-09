package com.jpc.block;

import java.util.function.Consumer;


import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A desktop-computer style block: a beige case with a screen on the front.
 * It faces the player when placed. Right-click opens the full-screen VM,
 * sneak + right-click switches the screen light on/off.
 */
public class ComputerBlock extends Block {

	/** Whether the screen is powered on. */
	public static final BooleanProperty ON = BooleanProperty.create("on");

	/**
	 * Set by the client initializer: opens the VM screen. Stays null on a dedicated
	 * server, where there is no screen. (The main source set cannot see client classes.)
	 */
	public static volatile Consumer<BlockPos> clientOpenHook;

	public ComputerBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH)
				.setValue(ON, false));
	}


	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockStateProperties.HORIZONTAL_FACING, ON);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// Screen faces the player.
		return defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		// Sneak + right-click: power the screen light on/off.
		if (player.isShiftKeyDown()) {
			if (!level.isClientSide()) {
				boolean nowOn = !state.getValue(ON);
				level.setBlockAndUpdate(pos, state.setValue(ON, nowOn));
				level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.4F, nowOn ? 0.7F : 0.5F);
			}
			return InteractionResult.SUCCESS;
		}

		// Plain right-click: open the VM screen. Only the client has a screen to open.
		if (level.isClientSide()) {
			Consumer<BlockPos> hook = clientOpenHook;
			if (hook != null) {
				hook.accept(pos);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
