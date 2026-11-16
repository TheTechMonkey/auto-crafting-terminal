package com.techmonkey.autocraftingtable;

import net.minecraft.util.StringRepresentable;

/** The red light on the terminal's front: off when idle or paused, blinking while crafting, solid when stuck. */
public enum TerminalLight implements StringRepresentable {
    OFF("off"),
    BLINK("blink"),
    SOLID("solid");

    private final String serializedName;

    TerminalLight(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /** Which light a status code shows. */
    public static TerminalLight forStatus(int status) {
        return switch (status) {
            case AutoCraftingTerminalBlockEntity.STATUS_CRAFTING -> BLINK;
            case AutoCraftingTerminalBlockEntity.STATUS_NO_RECIPE, AutoCraftingTerminalBlockEntity.STATUS_PAUSED_BY_SIGNAL,
                 AutoCraftingTerminalBlockEntity.STATUS_TURNED_OFF -> OFF;
            default -> SOLID;
        };
    }
}
