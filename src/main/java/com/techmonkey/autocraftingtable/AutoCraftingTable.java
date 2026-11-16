package com.techmonkey.autocraftingtable;

import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Auto Crafting Terminal: a Tom's Simple Storage add-on that crafts a chosen recipe out of the network. */
public final class AutoCraftingTable implements ModInitializer {
    public static final String MOD_ID = "auto_crafting_table";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier BLOCK_ID = id("auto_crafting_terminal");

    // Same feel as Tom's terminals: wooden, a little light, see-through around the thin panel.
    public static final AutoCraftingTerminalBlock BLOCK = new AutoCraftingTerminalBlock(BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, BLOCK_ID))
            .mapColor(MapColor.WOOD)
            .sound(SoundType.WOOD)
            .strength(3.0f)
            .lightLevel(state -> 6)
            .noOcclusion());

    public static final BlockItem ITEM = new BlockItem(BLOCK, new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, BLOCK_ID))
            .useBlockDescriptionPrefix());

    public static final BlockEntityType<AutoCraftingTerminalBlockEntity> BLOCK_ENTITY =
            new BlockEntityType<>(AutoCraftingTerminalBlockEntity::new, Set.of(BLOCK));

    public static final ExtendedMenuType<AutoCraftingTerminalMenu, MenuOpeningData> MENU =
            new ExtendedMenuType<>(AutoCraftingTerminalMenu::new, MenuOpeningData.STREAM_CODEC);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        AutoCraftingConfig.load();
        Registry.register(BuiltInRegistries.BLOCK, BLOCK_ID, BLOCK);
        Registry.register(BuiltInRegistries.ITEM, BLOCK_ID, ITEM);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, BLOCK_ID, BLOCK_ENTITY);
        Registry.register(BuiltInRegistries.MENU, BLOCK_ID, MENU);

        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("functional_blocks"))).register(output -> output.accept(ITEM));

        PayloadTypeRegistry.serverboundPlay().register(MenuActionPayload.TYPE, MenuActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CraftableRecipesPayload.TYPE, CraftableRecipesPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MenuActionPayload.TYPE, (payload, context) -> {
            if (context.player().containerMenu instanceof AutoCraftingTerminalMenu menu && menu.containerId == payload.containerId()) {
                menu.handleAction(context.player(), payload.action(), payload.value());
            }
        });

        // Recipe lists are rebuilt after /reload and whenever a different world starts.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> RecipeBookCache.invalidate());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RecipeBookCache.invalidate());

        LOGGER.info("Auto Crafting Terminal registered");
    }
}
