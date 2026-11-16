package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Works out which recipes in the book the network has the ingredients for right now, for the recipe list's
 * "Can make" filter. Each recipe is boiled down once to "N items from this set" groups, then checked against a
 * count of what storage holds. Fuel is not considered: this is about ingredients only.
 */
public final class CraftableRecipes {
    /** One kind of ingredient in a recipe: how many grid positions use it, and which items it accepts. */
    private record Requirement(Set<Item> acceptedItems, int amountNeeded) {
    }

    private static List<List<Requirement>> cachedRequirements = List.of();
    private static int cachedGeneration = -1;

    private CraftableRecipes() {
    }

    /** One bit per entry in the recipe book, set when the stored item counts cover that recipe once. */
    public static BitSet find(ServerLevel level, List<RecipeBookEntry> entries, Map<Item, Long> storedCounts) {
        List<List<Requirement>> requirements = requirementsFor(level, entries);
        BitSet craftable = new BitSet(entries.size());
        for (int index = 0; index < requirements.size(); index++) {
            if (isCovered(requirements.get(index), storedCounts)) {
                craftable.set(index);
            }
        }
        return craftable;
    }

    private static boolean isCovered(List<Requirement> requirements, Map<Item, Long> storedCounts) {
        if (requirements.isEmpty()) {
            return false;
        }
        for (Requirement requirement : requirements) {
            long available = 0;
            for (Item item : requirement.acceptedItems()) {
                available += storedCounts.getOrDefault(item, 0L);
                if (available >= requirement.amountNeeded()) {
                    break;
                }
            }
            if (available < requirement.amountNeeded()) {
                return false;
            }
        }
        return true;
    }

    private static synchronized List<List<Requirement>> requirementsFor(ServerLevel level, List<RecipeBookEntry> entries) {
        int generation = RecipeBookCache.generation();
        if (generation == cachedGeneration && cachedRequirements.size() == entries.size()) {
            return cachedRequirements;
        }
        List<List<Requirement>> built = new ArrayList<>(entries.size());
        for (RecipeBookEntry entry : entries) {
            built.add(requirementsOf(level, entry.recipeId()));
        }
        cachedRequirements = List.copyOf(built);
        cachedGeneration = generation;
        return cachedRequirements;
    }

    private static List<Requirement> requirementsOf(ServerLevel level, String recipeId) {
        Identifier recipeKey = Identifier.tryParse(recipeId);
        if (recipeKey == null) {
            return List.of();
        }
        Optional<CraftingRecipe> recipe = level.recipeAccess()
                .byKey(ResourceKey.create(Registries.RECIPE, recipeKey))
                .map(holder -> holder.value() instanceof CraftingRecipe craftingRecipe ? craftingRecipe : null);
        if (recipe.isEmpty()) {
            return List.of();
        }
        // Positions that use the same ingredient are counted together (rails need 6 of the same iron).
        Map<Ingredient, Integer> amountPerIngredient = new LinkedHashMap<>();
        for (Optional<Ingredient> position : RecipeLayout.of(recipe.get())) {
            position.ifPresent(ingredient -> amountPerIngredient.merge(ingredient, 1, Integer::sum));
        }
        List<Requirement> requirements = new ArrayList<>();
        amountPerIngredient.forEach((ingredient, amount) -> {
            Set<Item> accepted = ingredient.items().map(Holder::value)
                    .filter(item -> item.isEnabled(level.enabledFeatures()))
                    .collect(Collectors.toSet());
            requirements.add(new Requirement(accepted, amount));
        });
        return List.copyOf(requirements);
    }

    /** Total stored amount per item type. */
    public static Map<Item, Long> countByItem(Iterable<StorageView<ItemVariant>> views) {
        Map<Item, Long> counts = new HashMap<>();
        for (StorageView<ItemVariant> view : views) {
            counts.merge(view.getResource().getItem(), view.getAmount(), Long::sum);
        }
        return counts;
    }
}
