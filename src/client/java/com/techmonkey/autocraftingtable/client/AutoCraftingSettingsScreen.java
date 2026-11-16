package com.techmonkey.autocraftingtable.client;

import com.techmonkey.autocraftingtable.AutoCraftingConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Mod Menu settings. Edits config/auto_crafting_table.json on this computer. */
public final class AutoCraftingSettingsScreen extends Screen {
    private final Screen parent;

    public AutoCraftingSettingsScreen(Screen parent) {
        super(Component.translatable("gui.auto_crafting_table.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AutoCraftingConfig config = AutoCraftingConfig.get();
        int left = width / 2 - 120;
        int y = Math.max(20, height / 2 - 100);

        addRenderableWidget(new StringWidget(left, y, 240, 20, title, font));
        y += 26;

        addRenderableWidget(Button.builder(onOff("gui.auto_crafting_table.setting.redstone", config.redstoneControl), button -> {
            config.redstoneControl = !config.redstoneControl;
            config.save();
            button.setMessage(onOff("gui.auto_crafting_table.setting.redstone", config.redstoneControl));
        }).bounds(left, y, 240, 20).build());
        y += 21;
        addRenderableWidget(new StringWidget(left, y, 240, 12, Component.translatable("gui.auto_crafting_table.setting.redstone.tip"), font));
        y += 18;

        addRenderableWidget(Button.builder(onOff("gui.auto_crafting_table.setting.fuel", config.requireFuel), button -> {
            config.requireFuel = !config.requireFuel;
            config.save();
            button.setMessage(onOff("gui.auto_crafting_table.setting.fuel", config.requireFuel));
        }).bounds(left, y, 240, 20).build());
        y += 21;
        addRenderableWidget(new StringWidget(left, y, 240, 12, Component.translatable("gui.auto_crafting_table.setting.fuel.tip"), font));
        y += 18;

        addRenderableWidget(Button.builder(onOff("gui.auto_crafting_table.setting.mixed", config.mixedTypes), button -> {
            config.mixedTypes = !config.mixedTypes;
            config.save();
            button.setMessage(onOff("gui.auto_crafting_table.setting.mixed", config.mixedTypes));
        }).bounds(left, y, 240, 20).build());
        y += 21;
        addRenderableWidget(new StringWidget(left, y, 240, 12, Component.translatable("gui.auto_crafting_table.setting.mixed.tip"), font));
        y += 26;

        addRenderableWidget(new StringWidget(left, y, 240, 12, Component.translatable("gui.auto_crafting_table.setting.server_note"), font));
        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(left, y, 240, 20).build());
    }

    private static Component onOff(String key, boolean value) {
        return Component.translatable(key, Component.translatable(value ? "options.on" : "options.off"));
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
