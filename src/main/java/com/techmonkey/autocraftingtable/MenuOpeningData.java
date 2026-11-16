package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Data sent to a client when it opens the table: the result item of every recipe in book order.
 * Each entry is two small numbers, so even very large modpacks stay far below packet size limits.
 * Recipe ids never leave the server; the client selects a recipe by its position in this list.
 */
public record MenuOpeningData(List<ItemStack> recipeResults) {
    public static final int MAX_RECIPES = 200_000;

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuOpeningData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MenuOpeningData decode(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_RECIPES) {
                throw new IllegalArgumentException("Invalid Auto Crafting Table recipe count: " + size);
            }
            List<ItemStack> results = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                Item item = BuiltInRegistries.ITEM.byId(buffer.readVarInt());
                int count = buffer.readVarInt();
                results.add(new ItemStack(item, Math.max(1, count)));
            }
            return new MenuOpeningData(List.copyOf(results));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, MenuOpeningData data) {
            buffer.writeVarInt(data.recipeResults().size());
            for (ItemStack result : data.recipeResults()) {
                buffer.writeVarInt(BuiltInRegistries.ITEM.getId(result.getItem()));
                buffer.writeVarInt(result.getCount());
            }
        }
    };

    public static MenuOpeningData fromEntries(List<RecipeBookEntry> entries) {
        return new MenuOpeningData(entries.stream().map(RecipeBookEntry::result).toList());
    }
}
