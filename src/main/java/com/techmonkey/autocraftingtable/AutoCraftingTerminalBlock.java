package com.techmonkey.autocraftingtable;

import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Built on Tom's terminal block, so it has the same thin shape, places on walls, floors and ceilings,
 * and joins inventory cables the same way.
 */
public final class AutoCraftingTerminalBlock extends AbstractStorageTerminalBlock {
    public static final EnumProperty<TerminalLight> LIGHT = EnumProperty.create("light", TerminalLight.class);
    private static final int COMPARATOR_SIGNAL_CRAFTING = 15;

    public AutoCraftingTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIGHT, TerminalLight.OFF));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LIGHT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutoCraftingTerminalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AutoCraftingTerminalBlockEntity terminal) {
            player.openMenu(terminal);
        }
        return InteractionResult.SUCCESS;
    }

    // ----- Comparator: full signal while crafting, none otherwise -----

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return state.getValue(LIGHT) == TerminalLight.BLINK ? COMPARATOR_SIGNAL_CRAFTING : 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.auto_crafting_table.auto_crafting_terminal").withStyle(ChatFormatting.GRAY));
    }
}
