package com.techmonkey.autocraftingtable;

import com.mojang.serialization.Codec;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.NetworkInventory;
import com.tom.storagemod.platform.PlatformBlockEntity;
import com.tom.storagemod.util.TickerUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Tom's Simple Storage terminal that crafts one chosen recipe over and over, straight out of the network it is
 * attached to (a connector, or a cable leading to one), and puts the results back. It holds no items itself.
 */
public final class AutoCraftingTerminalBlockEntity extends PlatformBlockEntity
        implements TickerUtil.TickableServer, ExtendedMenuProvider<MenuOpeningData> {
    public static final int TICKS_PER_CRAFT = 10;
    private static final double MAX_USE_DISTANCE_SQUARED = 64.0;

    // Status codes shown on the screen. The menu syncs this number to the client.
    public static final int STATUS_NO_RECIPE = 0;
    public static final int STATUS_PAUSED_BY_SIGNAL = 1;
    public static final int STATUS_NO_FUEL = 2;
    public static final int STATUS_MISSING_INGREDIENTS = 3;
    public static final int STATUS_NOT_CONNECTED = 4;
    public static final int STATUS_NETWORK_FULL = 5;
    public static final int STATUS_CANNOT_CRAFT = 6;
    public static final int STATUS_CRAFTING = 7;

    private final List<ItemStack> chosenTypes = new ArrayList<>(Collections.nCopies(RecipeLayout.GRID_SIZE, ItemStack.EMPTY));
    private final NetworkInventory network = new NetworkInventory();
    private boolean networkAttached = false;
    private String selectedRecipeId = "";
    private int charge = 0;
    private int cooldown = 0;
    private int status = STATUS_NO_RECIPE;
    private int missingSlots = 0;

    // Recipe lookups are cached until recipes reload.
    private CraftingRecipe cachedRecipe;
    private List<Optional<Ingredient>> cachedLayout = List.of();
    private ItemStack cachedPreview = ItemStack.EMPTY;
    private int cachedGeneration = -1;

    public AutoCraftingTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(AutoCraftingTable.BLOCK_ENTITY, pos, state);
    }

    // ----- Network connection (the same way Tom's terminals connect) -----

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            attachToNetwork();
        }
    }

    /** The terminal faces the block it is placed on: a connector, a cable, or any storage block. */
    private void attachToNetwork() {
        BlockState state = getBlockState();
        Direction facing = state.getValue(AbstractStorageTerminalBlock.FACING);
        AbstractStorageTerminalBlock.TerminalPos placement = state.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
        if (placement == AbstractStorageTerminalBlock.TerminalPos.UP) {
            facing = Direction.UP;
        } else if (placement == AbstractStorageTerminalBlock.TerminalPos.DOWN) {
            facing = Direction.DOWN;
        }
        network.onLoad(level, getBlockPos().relative(facing), facing.getOpposite(), this);
        networkAttached = true;
    }

    /** The whole network as one storage, or null when the terminal is not attached to anything. */
    private Storage<ItemVariant> networkStorage() {
        if (!networkAttached) {
            attachToNetwork();
        }
        IInventoryAccess access = network.getAccess(level, getBlockPos());
        // A connector hands back its merged network; a single block hands back its own storage. Either way,
        // get() is the Fabric storage on Tom's Fabric build.
        Object handler = access == null ? null : access.get();
        if (handler instanceof Storage<?> storage) {
            @SuppressWarnings("unchecked")
            Storage<ItemVariant> itemStorage = (Storage<ItemVariant>) storage;
            return itemStorage;
        }
        return null;
    }

    // ----- Ticking -----

    @Override
    public void updateServer() {
        if (cooldown > 0) {
            cooldown--;
        }
        if (recipe() == null) {
            status = STATUS_NO_RECIPE;
            missingSlots = 0;
        } else if (AutoCraftingConfig.get().redstoneControl && level.hasNeighborSignal(getBlockPos())) {
            status = STATUS_PAUSED_BY_SIGNAL;
        } else if (cooldown == 0) {
            cooldown = TICKS_PER_CRAFT;
            craftOnce();
        }
    }

    private void craftOnce() {
        Storage<ItemVariant> storage = networkStorage();
        if (storage == null) {
            status = STATUS_NOT_CONNECTED;
            missingSlots = 0;
            return;
        }
        NetworkCrafter.Result result;
        try (Transaction transaction = Transaction.openOuter()) {
            result = NetworkCrafter.craft(recipe(), level, new FabricItemNetwork(storage, transaction), chosenTypes,
                    allowMixedTypes(), charge, AutoCraftingConfig.get().requireFuel);
            if (result.problem() == NetworkCrafter.Problem.NONE) {
                transaction.commit();
            }
        }
        status = switch (result.problem()) {
            case NONE -> STATUS_CRAFTING;
            case MISSING_INGREDIENTS -> STATUS_MISSING_INGREDIENTS;
            case NO_FUEL -> STATUS_NO_FUEL;
            case NETWORK_FULL -> STATUS_NETWORK_FULL;
            case CANNOT_CRAFT -> STATUS_CANNOT_CRAFT;
        };
        missingSlots = result.missingSlots();
        if (result.remainingCharge() != charge) {
            charge = result.remainingCharge();
            setChanged();
        }
    }

    /**
     * How many of each grid position's item type the network holds, then (last entry) how much redstone dust.
     * Shown in the screen's tooltips.
     */
    public int[] countsInNetwork() {
        int[] counts = new int[RecipeLayout.GRID_SIZE + 1];
        Storage<ItemVariant> storage = networkStorage();
        if (storage == null) {
            return counts;
        }
        boolean hasRecipe = recipe() != null;
        for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
            ItemStack stored = view.getResource().toStack();
            if (stored.is(Items.REDSTONE)) {
                counts[RecipeLayout.GRID_SIZE] = (int) Math.min(Integer.MAX_VALUE, counts[RecipeLayout.GRID_SIZE] + view.getAmount());
            }
            if (!hasRecipe) {
                continue;
            }
            for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
                ItemStack chosen = chosenTypes.get(gridSlot);
                boolean matches = allowMixedTypes()
                        ? NetworkCrafter.accepts(cachedLayout.get(gridSlot), chosen, true, stored)
                        : !chosen.isEmpty() && stored.is(chosen.getItem());
                if (matches) {
                    counts[gridSlot] = (int) Math.min(Integer.MAX_VALUE, counts[gridSlot] + view.getAmount());
                }
            }
        }
        return counts;
    }

    // ----- Menu -----

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.auto_crafting_table.auto_crafting_terminal");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        List<RecipeBookEntry> entries = RecipeBookCache.entries((ServerLevel) level);
        return new AutoCraftingTerminalMenu(containerId, playerInventory, this, entries);
    }

    @Override
    public MenuOpeningData getScreenOpeningData(ServerPlayer player) {
        return MenuOpeningData.fromEntries(RecipeBookCache.entries((ServerLevel) level));
    }

    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(getBlockPos()) == this
                && player.distanceToSqr(getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 0.5)
                <= MAX_USE_DISTANCE_SQUARED;
    }

    // ----- State accessors used by the menu -----

    public String selectedRecipeId() {
        return selectedRecipeId;
    }

    public int charge() {
        return charge;
    }

    public int status() {
        return status;
    }

    public int missingSlots() {
        return missingSlots;
    }

    /** Server setting: any item the recipe accepts works, instead of only the type shown in each position. */
    public static boolean allowMixedTypes() {
        return AutoCraftingConfig.get().mixedTypes;
    }

    public ItemStack chosenType(int gridSlot) {
        return chosenTypes.get(gridSlot);
    }

    public ItemStack previewResult() {
        refreshRecipeCache();
        return cachedPreview;
    }

    public boolean hasInterchangeableIngredients() {
        return layout().stream()
                .flatMap(Optional::stream)
                .anyMatch(ingredient -> ingredient.items().limit(2).count() > 1);
    }

    // ----- Player controls (always called on the server) -----

    public void selectRecipe(String recipeId) {
        selectedRecipeId = recipeId;
        cachedGeneration = -1;
        List<Optional<Ingredient>> layout = layout();
        for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
            Optional<Ingredient> ingredient = layout.isEmpty() ? Optional.empty() : layout.get(gridSlot);
            chosenTypes.set(gridSlot, ingredient.flatMap(this::firstEnabledItem).map(ItemStack::new).orElse(ItemStack.EMPTY));
        }
        status = STATUS_NO_RECIPE;
        missingSlots = 0;
        cooldown = 0;
        setChanged();
    }

    /** Cycles the item type of a grid position. Positions with the same ingredient change together. */
    public void cycleChosenType(int gridSlot) {
        List<Optional<Ingredient>> layout = layout();
        if (gridSlot < 0 || gridSlot >= RecipeLayout.GRID_SIZE || layout.isEmpty() || layout.get(gridSlot).isEmpty()) {
            return;
        }
        Optional<Ingredient> ingredient = layout.get(gridSlot);
        List<Item> options = ingredient.get().items()
                .map(Holder::value)
                .filter(item -> item.isEnabled(level.enabledFeatures()))
                .toList();
        if (options.size() < 2) {
            return;
        }
        int nextIndex = 0;
        for (int index = 0; index < options.size(); index++) {
            if (chosenTypes.get(gridSlot).is(options.get(index))) {
                nextIndex = (index + 1) % options.size();
                break;
            }
        }
        Item nextItem = options.get(nextIndex);
        for (int slot = 0; slot < RecipeLayout.GRID_SIZE; slot++) {
            if (layout.get(slot).equals(ingredient)) {
                chosenTypes.set(slot, new ItemStack(nextItem));
            }
        }
        setChanged();
    }

    // ----- Recipe lookup -----

    private Optional<Item> firstEnabledItem(Ingredient ingredient) {
        return ingredient.items().map(Holder::value).filter(item -> item.isEnabled(level.enabledFeatures())).findFirst();
    }

    private List<Optional<Ingredient>> layout() {
        refreshRecipeCache();
        return cachedLayout;
    }

    private CraftingRecipe recipe() {
        refreshRecipeCache();
        return cachedRecipe;
    }

    private void refreshRecipeCache() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        RecipeBookCache.entries(serverLevel);
        int currentGeneration = RecipeBookCache.generation();
        if (cachedGeneration == currentGeneration) {
            return;
        }
        cachedGeneration = currentGeneration;
        cachedRecipe = null;
        cachedLayout = List.of();
        cachedPreview = ItemStack.EMPTY;
        Identifier recipeKey = selectedRecipeId.isEmpty() ? null : Identifier.tryParse(selectedRecipeId);
        if (recipeKey == null) {
            return;
        }
        RecipeManager recipeManager = serverLevel.recipeAccess();
        recipeManager.byKey(ResourceKey.create(Registries.RECIPE, recipeKey)).ifPresent(holder -> {
            if (holder.value() instanceof CraftingRecipe craftingRecipe) {
                cachedRecipe = craftingRecipe;
                cachedLayout = RecipeLayout.of(craftingRecipe);
                cachedPreview = RecipeLayout.sampleResult(craftingRecipe);
            }
        });
    }

    // ----- Saving -----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("Recipe", selectedRecipeId);
        output.putInt("Charge", charge);
        output.store("ChosenTypes", Codec.STRING.listOf(), chosenTypes.stream()
                .map(stack -> stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .toList());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        selectedRecipeId = input.getStringOr("Recipe", "");
        charge = Math.clamp(input.getIntOr("Charge", 0), 0, NetworkCrafter.CRAFTS_PER_DUST);
        List<String> savedTypes = input.read("ChosenTypes", Codec.STRING.listOf()).orElse(List.of());
        for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
            Identifier itemKey = gridSlot < savedTypes.size() ? Identifier.tryParse(savedTypes.get(gridSlot)) : null;
            chosenTypes.set(gridSlot, itemKey == null
                    ? ItemStack.EMPTY
                    : BuiltInRegistries.ITEM.getOptional(itemKey).map(ItemStack::new).orElse(ItemStack.EMPTY));
        }
        cachedGeneration = -1;
        cooldown = 0;
    }
}
