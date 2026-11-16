package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Maps shaped and shapeless crafting recipes onto the fixed 3x3 grid of the table. */
public final class RecipeLayout {
    public static final int GRID_SIZE = 9;

    private RecipeLayout() {
    }

    /** Returns nine grid positions, or an empty list when the recipe cannot be used by the table. */
    public static List<Optional<Ingredient>> of(CraftingRecipe recipe) {
        List<Optional<Ingredient>> grid = new ArrayList<>(Collections.nCopies(GRID_SIZE, Optional.empty()));
        if (recipe instanceof ShapedRecipe shaped && shaped.getWidth() <= 3 && shaped.getHeight() <= 3) {
            for (int row = 0; row < shaped.getHeight(); row++) {
                for (int column = 0; column < shaped.getWidth(); column++) {
                    grid.set(row * 3 + column, shaped.getIngredients().get(row * shaped.getWidth() + column));
                }
            }
            return grid;
        }
        if (recipe instanceof ShapelessRecipe) {
            List<Ingredient> ingredients = recipe.placementInfo().ingredients();
            if (ingredients.size() > GRID_SIZE) {
                return List.of();
            }
            for (int index = 0; index < ingredients.size(); index++) {
                grid.set(index, Optional.of(ingredients.get(index)));
            }
            return grid;
        }
        return List.of();
    }

    /** Crafts the recipe once with the first accepted item of every ingredient, for display only. */
    public static ItemStack sampleResult(CraftingRecipe recipe) {
        List<Optional<Ingredient>> layout = of(recipe);
        if (layout.isEmpty()) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> sampleItems = layout.stream()
                .map(ingredient -> ingredient.flatMap(value -> value.items().findFirst())
                        .map(holder -> new ItemStack(holder.value()))
                        .orElse(ItemStack.EMPTY))
                .toList();
        try {
            return recipe.assemble(CraftingInput.of(3, 3, sampleItems));
        } catch (RuntimeException exception) {
            // A mod recipe that cannot assemble from sample items is skipped instead of breaking the book.
            return ItemStack.EMPTY;
        }
    }
}
