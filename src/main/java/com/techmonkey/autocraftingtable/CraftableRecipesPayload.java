package com.techmonkey.autocraftingtable;

import java.util.BitSet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client answer for the "Can make" filter: one bit per recipe in the book, set when storage holds
 * its ingredients. A few hundred bytes even for very large recipe books.
 */
public record CraftableRecipesPayload(int containerId, BitSet craftable) implements CustomPacketPayload {
    private static final int MAX_WORDS = MenuOpeningData.MAX_RECIPES / Long.SIZE + 1;

    public static final Type<CraftableRecipesPayload> TYPE = new Type<>(AutoCraftingTable.id("craftable_recipes"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftableRecipesPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CraftableRecipesPayload decode(RegistryFriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            int wordCount = buffer.readVarInt();
            if (wordCount < 0 || wordCount > MAX_WORDS) {
                throw new IllegalArgumentException("Invalid Auto Crafting Terminal craftable list size: " + wordCount);
            }
            long[] words = new long[wordCount];
            for (int index = 0; index < wordCount; index++) {
                words[index] = buffer.readLong();
            }
            return new CraftableRecipesPayload(containerId, BitSet.valueOf(words));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, CraftableRecipesPayload payload) {
            long[] words = payload.craftable().toLongArray();
            buffer.writeVarInt(payload.containerId());
            buffer.writeVarInt(words.length);
            for (long word : words) {
                buffer.writeLong(word);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
