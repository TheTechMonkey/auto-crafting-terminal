package com.techmonkey.autocraftingtable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server message for every control in the table window.
 * Vanilla's menu button message cannot carry large numbers, so recipe positions in big
 * recipe books (thousands of entries) need this dedicated message.
 */
public record MenuActionPayload(int containerId, int action, int value) implements CustomPacketPayload {
    // Codes 0 to 2 belonged to the removed on/off, redstone mode and mixed types buttons.
    public static final int ACTION_CYCLE_TYPE = 3;
    public static final int ACTION_SELECT_RECIPE = 4;
    /** Asks the server which recipes storage can make right now (the "Can make" filter). */
    public static final int ACTION_REQUEST_CRAFTABLE = 5;
    /** The On/Off button. */
    public static final int ACTION_TOGGLE_ON = 6;

    public static final Type<MenuActionPayload> TYPE = new Type<>(AutoCraftingTable.id("menu_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MenuActionPayload::containerId,
            ByteBufCodecs.VAR_INT, MenuActionPayload::action,
            ByteBufCodecs.VAR_INT, MenuActionPayload::value,
            MenuActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
