package com.techmonkey.autocraftingtable.client;

import com.techmonkey.autocraftingtable.AutoCraftingTable;
import com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu;
import com.techmonkey.autocraftingtable.CraftableRecipesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

public final class AutoCraftingTableClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(AutoCraftingTable.MENU, AutoCraftingTerminalScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(CraftableRecipesPayload.TYPE, (payload, context) -> {
            if (context.player().containerMenu instanceof AutoCraftingTerminalMenu menu && menu.containerId == payload.containerId()) {
                menu.receiveCraftableRecipes(payload.craftable());
            }
        });
    }
}
