package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/** Crafts real Minecraft recipes out of an in-memory storage standing in for a Tom's network. */
public final class NetworkCrafterTest {
    private static int checks = 0;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    /** A small chest-like storage with a slot limit. */
    private static final class MemoryNetwork implements ItemNetwork {
        private final List<ItemStack> stacks = new ArrayList<>();
        private final int slotLimit;

        MemoryNetwork(int slotLimit, ItemStack... contents) {
            this.slotLimit = slotLimit;
            for (ItemStack stack : contents) {
                stacks.add(stack.copy());
            }
        }

        MemoryNetwork copy() {
            return new MemoryNetwork(slotLimit, stacks.toArray(ItemStack[]::new));
        }

        void replaceWith(MemoryNetwork other) {
            stacks.clear();
            other.stacks.forEach(stack -> stacks.add(stack.copy()));
        }

        int count(Item item) {
            return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
        }

        @Override
        public boolean takeOne(ItemStack type) {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, type)) {
                    stack.shrink(1);
                    stacks.removeIf(ItemStack::isEmpty);
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<ItemStack> storedTypes() {
            return stacks.stream().map(stack -> stack.copyWithCount(1)).toList();
        }

        @Override
        public boolean put(ItemStack stack) {
            int remaining = stack.getCount();
            for (ItemStack stored : stacks) {
                if (ItemStack.isSameItemSameComponents(stored, stack)) {
                    remaining -= Math.min(remaining, stored.getMaxStackSize() - stored.getCount());
                }
            }
            int freeSlots = slotLimit - stacks.size();
            if (remaining > (long) freeSlots * stack.getMaxStackSize()) {
                return false;
            }
            int toPlace = stack.getCount();
            for (ItemStack stored : stacks) {
                if (ItemStack.isSameItemSameComponents(stored, stack)) {
                    int moved = Math.min(toPlace, stored.getMaxStackSize() - stored.getCount());
                    stored.grow(moved);
                    toPlace -= moved;
                }
            }
            while (toPlace > 0) {
                int moved = Math.min(toPlace, stack.getMaxStackSize());
                stacks.add(stack.copyWithCount(moved));
                toPlace -= moved;
            }
            return true;
        }
    }

    /** Runs a craft the way the terminal does: changes stick only when the craft succeeds. */
    private static NetworkCrafter.Result craft(CraftingRecipe recipe, MemoryNetwork network, boolean mixed, int charge, boolean requireFuel) {
        MemoryNetwork working = network.copy();
        NetworkCrafter.Result result = NetworkCrafter.craft(recipe, null, working, chosen(recipe), mixed, charge, requireFuel);
        if (result.problem() == NetworkCrafter.Problem.NONE) {
            network.replaceWith(working);
        }
        return result;
    }

    private static ShapedRecipe shaped(ItemStack result, Map<Character, Ingredient> keys, String... pattern) {
        return new ShapedRecipe(new Recipe.CommonInfo(false),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                ShapedRecipePattern.of(keys, pattern), ItemStackTemplate.fromNonEmptyStack(result));
    }

    private static List<ItemStack> chosen(CraftingRecipe recipe) {
        return RecipeLayout.of(recipe).stream()
                .map(ingredient -> ingredient.flatMap(value -> value.items().findFirst()).map(holder -> new ItemStack(holder.value())).orElse(ItemStack.EMPTY))
                .toList();
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Item components are normally bound when a world loads; do it by hand here.
        var lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(pending -> pending.apply());

        ShapedRecipe planks = shaped(new ItemStack(Items.OAK_PLANKS, 4), Map.of('L', Ingredient.of(Items.OAK_LOG)), "L");

        MemoryNetwork storage = new MemoryNetwork(27, new ItemStack(Items.OAK_LOG, 2));
        var result = craft(planks, storage, false, 0, true);
        check(result.problem() == NetworkCrafter.Problem.NO_FUEL, "needs dust when the charge is empty");
        check(storage.count(Items.OAK_LOG) == 2, "a failed craft gives everything back");

        storage.put(new ItemStack(Items.REDSTONE));
        result = craft(planks, storage, false, 0, true);
        check(result.problem() == NetworkCrafter.Problem.NONE, "crafts with a log and a dust");
        check(storage.count(Items.OAK_PLANKS) == 4, "planks go back into storage");
        check(storage.count(Items.OAK_LOG) == 1, "exactly one log was used");
        check(storage.count(Items.REDSTONE) == 0, "one dust was used");
        check(result.remainingCharge() == NetworkCrafter.CRAFTS_PER_DUST - 1, "one dust gives 64 crafts");

        result = craft(planks, storage, false, 63, true);
        check(result.problem() == NetworkCrafter.Problem.NONE && result.remainingCharge() == 62, "stored charge is used before dust");
        check(storage.count(Items.OAK_LOG) == 0, "second log used");

        result = craft(planks, storage, false, 62, true);
        check(result.problem() == NetworkCrafter.Problem.MISSING_INGREDIENTS, "reports missing ingredients");
        check(result.missingSlots() == 1, "marks the missing grid position");

        storage.put(new ItemStack(Items.OAK_LOG));
        result = craft(planks, storage, false, 0, false);
        check(result.problem() == NetworkCrafter.Problem.NONE && result.remainingCharge() == 0, "fuel setting off crafts without dust");

        MemoryNetwork fullStorage = new MemoryNetwork(2, new ItemStack(Items.OAK_LOG, 2), new ItemStack(Items.STONE, 64));
        result = craft(planks, fullStorage, false, 5, true);
        check(result.problem() == NetworkCrafter.Problem.NETWORK_FULL, "reports full storage");
        check(fullStorage.count(Items.OAK_LOG) == 2, "full storage loses nothing");

        ShapedRecipe chest = shaped(new ItemStack(Items.CHEST), Map.of('P', Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS)), "PPP", "P P", "PPP");
        MemoryNetwork woods = new MemoryNetwork(27, new ItemStack(Items.BIRCH_PLANKS, 8));
        result = craft(chest, woods, false, 5, true);
        check(result.problem() == NetworkCrafter.Problem.MISSING_INGREDIENTS, "mixed types off needs the shown wood");
        check(woods.count(Items.BIRCH_PLANKS) == 8, "nothing taken when ingredients are missing");
        result = craft(chest, woods, true, 5, true);
        check(result.problem() == NetworkCrafter.Problem.NONE, "mixed types on uses any wood");
        check(woods.count(Items.CHEST) == 1 && woods.count(Items.BIRCH_PLANKS) == 0, "chest made from birch");

        ShapedRecipe cake = shaped(new ItemStack(Items.CAKE), Map.of('M', Ingredient.of(Items.MILK_BUCKET), 'S', Ingredient.of(Items.SUGAR),
                'E', Ingredient.of(Items.EGG), 'W', Ingredient.of(Items.WHEAT)), "MMM", "SES", "WWW");
        MemoryNetwork pantry = new MemoryNetwork(27, new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.SUGAR, 2), new ItemStack(Items.EGG), new ItemStack(Items.WHEAT, 3));
        result = craft(cake, pantry, false, 5, true);
        check(result.problem() == NetworkCrafter.Problem.NONE, "cake crafts");
        check(pantry.count(Items.CAKE) == 1, "cake goes back");
        check(pantry.count(Items.BUCKET) == 3, "empty buckets go back");

        System.out.println("PASS: " + checks + " network crafting checks");
    }
}
