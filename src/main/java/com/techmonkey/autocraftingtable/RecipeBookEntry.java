package com.techmonkey.autocraftingtable;

import net.minecraft.world.item.ItemStack;

/** One selectable crafting recipe. Only the server keeps the recipe id; clients receive the result item. */
public record RecipeBookEntry(String recipeId, ItemStack result) {
}
