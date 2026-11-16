package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

/**
 * Crafts one recipe straight out of a storage network: takes one craft's ingredients (and a redstone dust when
 * the charge has run out), crafts, and puts the result and any returned containers back. All or nothing: the
 * caller rolls the network back on any failure, so a failed craft leaves storage exactly as it was.
 */
public final class NetworkCrafter {
    public static final int CRAFTS_PER_DUST = 64;

    public enum Problem {
        NONE, MISSING_INGREDIENTS, NO_FUEL, CANNOT_CRAFT, NETWORK_FULL
    }

    /** missingSlots has one bit per grid position whose ingredient the network did not have. */
    public record Result(Problem problem, int remainingCharge, int missingSlots) {
    }

    private NetworkCrafter() {
    }

    /** True if stack fits the ingredient, and is the chosen type unless mixed types are allowed. */
    public static boolean accepts(Optional<Ingredient> ingredient, ItemStack chosenType, boolean allowMixedTypes, ItemStack stack) {
        boolean fitsIngredient = ingredient.map(value -> value.test(stack)).orElse(false);
        boolean fitsChosenType = allowMixedTypes || (!chosenType.isEmpty() && stack.is(chosenType.getItem()));
        return fitsIngredient && fitsChosenType;
    }

    /**
     * Crafts once against the network. The caller must roll back the network's changes unless the result is NONE
     * (in the game, by only committing the transaction on success).
     */
    public static Result craft(CraftingRecipe recipe, Level level, ItemNetwork network, List<ItemStack> chosenTypes,
                               boolean allowMixedTypes, int charge, boolean requireFuel) {
        List<Optional<Ingredient>> layout = RecipeLayout.of(recipe);
        if (layout.isEmpty()) {
            return new Result(Problem.CANNOT_CRAFT, charge, 0);
        }
        List<ItemStack> craftingItems = new ArrayList<>(RecipeLayout.GRID_SIZE);
        int missingSlots = 0;
        for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
            Optional<Ingredient> ingredient = layout.get(gridSlot);
            if (ingredient.isEmpty()) {
                craftingItems.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack taken = takeOne(network, ingredient, chosenTypes.get(gridSlot), allowMixedTypes);
            if (taken.isEmpty()) {
                missingSlots |= 1 << gridSlot;
            }
            craftingItems.add(taken);
        }
        if (missingSlots != 0) {
            return new Result(Problem.MISSING_INGREDIENTS, charge, missingSlots);
        }

        if (requireFuel && charge == 0 && !network.takeOne(new ItemStack(Items.REDSTONE))) {
            return new Result(Problem.NO_FUEL, charge, 0);
        }

        CraftingInput input = CraftingInput.of(3, 3, craftingItems);
        if (!recipe.matches(input, level)) {
            return new Result(Problem.CANNOT_CRAFT, charge, 0);
        }
        ItemStack output = recipe.assemble(input);
        if (output.isEmpty() || (level != null && !output.isItemEnabled(level.enabledFeatures()))) {
            return new Result(Problem.CANNOT_CRAFT, charge, 0);
        }
        if (!network.put(output)) {
            return new Result(Problem.NETWORK_FULL, charge, 0);
        }
        for (ItemStack remainder : recipe.getRemainingItems(input)) {
            if (!remainder.isEmpty() && !network.put(remainder)) {
                return new Result(Problem.NETWORK_FULL, charge, 0);
            }
        }

        int chargeAfterCraft = charge;
        if (requireFuel) {
            chargeAfterCraft = (charge == 0 ? CRAFTS_PER_DUST : charge) - 1;
        }
        return new Result(Problem.NONE, chargeAfterCraft, 0);
    }

    /** Takes the chosen type first; with mixed types on, falls back to any stored item the recipe accepts there. */
    private static ItemStack takeOne(ItemNetwork network, Optional<Ingredient> ingredient, ItemStack chosenType, boolean allowMixedTypes) {
        if (!chosenType.isEmpty() && network.takeOne(chosenType)) {
            return chosenType.copyWithCount(1);
        }
        if (!allowMixedTypes) {
            return ItemStack.EMPTY;
        }
        for (ItemStack stored : network.storedTypes()) {
            if (accepts(ingredient, chosenType, true, stored) && network.takeOne(stored)) {
                return stored.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }
}
