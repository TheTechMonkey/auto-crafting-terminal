package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Server-side list of every usable crafting recipe, built once and shared by all tables.
 * Rebuilt after /reload or when another world loads.
 */
public final class RecipeBookCache {
    private static RecipeManager cachedManager;
    private static List<RecipeBookEntry> cachedEntries = List.of();
    private static int generation;

    private RecipeBookCache() {
    }

    public static synchronized void invalidate() {
        cachedManager = null;
        cachedEntries = List.of();
        generation++;
    }

    /** Increases whenever recipes may have changed, so block entities know to look their recipe up again. */
    public static synchronized int generation() {
        return generation;
    }

    public static synchronized List<RecipeBookEntry> entries(ServerLevel level) {
        RecipeManager manager = level.recipeAccess();
        if (manager != cachedManager) {
            cachedManager = manager;
            cachedEntries = buildEntries(manager);
            generation++;
        }
        return cachedEntries;
    }

    private static List<RecipeBookEntry> buildEntries(RecipeManager manager) {
        List<RecipeBookEntry> entries = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (holder.value() instanceof CraftingRecipe craftingRecipe && !RecipeLayout.of(craftingRecipe).isEmpty()) {
                ItemStack result = RecipeLayout.sampleResult(craftingRecipe);
                if (!result.isEmpty()) {
                    entries.add(new RecipeBookEntry(holder.id().identifier().toString(), result));
                }
            }
        }
        entries.sort(Comparator.comparing(RecipeBookEntry::recipeId));
        AutoCraftingTable.LOGGER.info("Auto Crafting Table recipe book built with {} recipes", entries.size());
        return List.copyOf(entries);
    }
}
