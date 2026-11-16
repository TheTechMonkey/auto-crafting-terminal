package com.techmonkey.autocraftingtable.client;

import com.techmonkey.autocraftingtable.AutoCraftingTable;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public final class AutoCraftingTableClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(AutoCraftingTable.MENU, AutoCraftingTerminalScreen::new);
    }
}
