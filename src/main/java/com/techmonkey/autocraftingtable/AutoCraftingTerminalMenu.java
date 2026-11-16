package com.techmonkey.autocraftingtable;

import java.util.BitSet;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The terminal holds no items, so this menu only carries the recipe preview, status and counts, plus the player's inventory. */
public final class AutoCraftingTerminalMenu extends AbstractContainerMenu {
    private static final int PREVIEW_SLOT_COUNT = RecipeLayout.GRID_SIZE + 1;

    // Screen layout, shared with the client screen. Positions are where items draw (frames sit 1px up and left).
    public static final int GRID_X = 30;
    public static final int GRID_Y = 17;
    public static final int RESULT_X = 124;
    public static final int RESULT_Y = 35;
    public static final int DUST_X = 8;
    public static final int DUST_Y = 53;
    public static final int PLAYER_X = 8;
    public static final int PLAYER_Y = 98;
    public static final int HOTBAR_Y = 156;

    private static final int COUNT_REFRESH_TICKS = 20;
    /** Counts travel as 16-bit values; anything above this shows as this. */
    public static final int MAX_SHOWN_COUNT = 32767;

    private static final int DATA_CHARGE = 0;
    private static final int DATA_MIXED = 1;
    private static final int DATA_HAS_ALTERNATIVES = 2;
    // The selected index is split into two 16-bit halves.
    private static final int DATA_SELECTED_HIGH = 3;
    private static final int DATA_SELECTED_LOW = 4;
    private static final int DATA_STATUS = 5;
    private static final int DATA_FUEL_REQUIRED = 6;
    private static final int DATA_MISSING_SLOTS = 7;
    private static final int DATA_SWITCHED_ON = 8;
    private static final int DATA_FIRST_COUNT = 9;
    /** One count per grid position, then the redstone dust count. */
    private static final int DATA_COUNT = DATA_FIRST_COUNT + RecipeLayout.GRID_SIZE + 1;

    /** Result items shown in the recipe list, in the same order as the server's recipe list. */
    public final List<ItemStack> recipeResults;
    /** Server only: the recipe list this menu was opened with. Empty on the client. */
    private final List<RecipeBookEntry> serverEntries;
    private final AutoCraftingTerminalBlockEntity terminal;
    private final SimpleContainer previewItems = new SimpleContainer(PREVIEW_SLOT_COUNT);
    private final ContainerData syncedData;
    private final int[] networkCounts = new int[RecipeLayout.GRID_SIZE + 1];
    private int ticksUntilCountRefresh = 0;
    private String lastSelectedRecipeId;
    private int lastSelectedIndex = -1;

    /** Client constructor, created from the opening data the server sends. */
    public AutoCraftingTerminalMenu(int containerId, Inventory playerInventory, MenuOpeningData openingData) {
        this(containerId, playerInventory, null, List.of(), openingData.recipeResults());
    }

    /** Server constructor. */
    public AutoCraftingTerminalMenu(int containerId, Inventory playerInventory, AutoCraftingTerminalBlockEntity terminal,
                                    List<RecipeBookEntry> entries) {
        this(containerId, playerInventory, terminal, entries, entries.stream().map(RecipeBookEntry::result).toList());
    }

    private AutoCraftingTerminalMenu(int containerId, Inventory playerInventory, AutoCraftingTerminalBlockEntity terminal,
                                     List<RecipeBookEntry> serverEntries, List<ItemStack> recipeResults) {
        super(AutoCraftingTable.MENU, containerId);
        this.terminal = terminal;
        this.serverEntries = serverEntries;
        this.recipeResults = recipeResults;
        this.syncedData = terminal == null ? new SimpleContainerData(DATA_COUNT) : new ServerData();

        // Hidden slots that carry the recipe preview (item types and result) to the client.
        for (int previewSlot = 0; previewSlot < PREVIEW_SLOT_COUNT; previewSlot++) {
            addSlot(new Slot(previewItems, previewSlot, -10000, -10000) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }

                @Override
                public boolean isActive() {
                    return false;
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, PLAYER_X + column * 18, PLAYER_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, PLAYER_X + column * 18, HOTBAR_Y));
        }
        addDataSlots(syncedData);
        refreshPreview();
    }

    /** Server-side values that are synced to the client. */
    private final class ServerData implements ContainerData {
        @Override
        public int get(int index) {
            if (index >= DATA_FIRST_COUNT && index < DATA_COUNT) {
                return Math.min(MAX_SHOWN_COUNT, networkCounts[index - DATA_FIRST_COUNT]);
            }
            return switch (index) {
                case DATA_CHARGE -> terminal.charge();
                case DATA_MIXED -> AutoCraftingTerminalBlockEntity.allowMixedTypes() ? 1 : 0;
                case DATA_HAS_ALTERNATIVES -> terminal.hasInterchangeableIngredients() ? 1 : 0;
                case DATA_SELECTED_HIGH -> (selectedIndexOnServer() >>> 16) & 0xFFFF;
                case DATA_SELECTED_LOW -> selectedIndexOnServer() & 0xFFFF;
                case DATA_STATUS -> terminal.status();
                case DATA_FUEL_REQUIRED -> AutoCraftingConfig.get().requireFuel ? 1 : 0;
                case DATA_MISSING_SLOTS -> terminal.missingSlots();
                case DATA_SWITCHED_ON -> terminal.isSwitchedOn() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }

    private int selectedIndexOnServer() {
        String selectedId = terminal.selectedRecipeId();
        if (!selectedId.equals(lastSelectedRecipeId)) {
            lastSelectedRecipeId = selectedId;
            lastSelectedIndex = -1;
            for (int index = 0; index < serverEntries.size(); index++) {
                if (serverEntries.get(index).recipeId().equals(selectedId)) {
                    lastSelectedIndex = index;
                    break;
                }
            }
        }
        return lastSelectedIndex;
    }

    private void refreshPreview() {
        if (terminal != null) {
            for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
                previewItems.setItem(gridSlot, terminal.chosenType(gridSlot));
            }
            previewItems.setItem(RecipeLayout.GRID_SIZE, terminal.previewResult());
        }
    }

    @Override
    public void broadcastChanges() {
        refreshPreview();
        if (terminal != null && --ticksUntilCountRefresh <= 0) {
            ticksUntilCountRefresh = COUNT_REFRESH_TICKS;
            int[] counts = terminal.countsInNetwork();
            System.arraycopy(counts, 0, networkCounts, 0, counts.length);
        }
        super.broadcastChanges();
    }

    // ----- Client-side readers -----

    public ItemStack chosenType(int gridSlot) {
        return previewItems.getItem(gridSlot);
    }

    public ItemStack previewResult() {
        return previewItems.getItem(RecipeLayout.GRID_SIZE);
    }

    public int charge() {
        return syncedData.get(DATA_CHARGE);
    }

    public boolean allowMixedTypes() {
        return syncedData.get(DATA_MIXED) != 0;
    }

    public boolean hasInterchangeableIngredients() {
        return syncedData.get(DATA_HAS_ALTERNATIVES) != 0;
    }

    public boolean fuelRequired() {
        return syncedData.get(DATA_FUEL_REQUIRED) != 0;
    }

    public int status() {
        return syncedData.get(DATA_STATUS);
    }

    public boolean isSwitchedOn() {
        return syncedData.get(DATA_SWITCHED_ON) != 0;
    }

    public boolean isMissing(int gridSlot) {
        return (syncedData.get(DATA_MISSING_SLOTS) & (1 << gridSlot)) != 0;
    }

    /** How many of this position's item the network holds, capped at MAX_SHOWN_COUNT. */
    public int countInNetwork(int gridSlot) {
        return syncedData.get(DATA_FIRST_COUNT + gridSlot) & 0xFFFF;
    }

    /** How much redstone dust the network holds, capped at MAX_SHOWN_COUNT. */
    public int dustInNetwork() {
        return syncedData.get(DATA_FIRST_COUNT + RecipeLayout.GRID_SIZE) & 0xFFFF;
    }

    /** Client only: the last "Can make" answer from the server, or null before the first one arrives. */
    private BitSet craftableRecipes;
    private int craftableUpdates = 0;

    public void receiveCraftableRecipes(BitSet craftable) {
        craftableRecipes = craftable;
        craftableUpdates++;
    }

    public boolean isCraftable(int recipeIndex) {
        return craftableRecipes != null && craftableRecipes.get(recipeIndex);
    }

    public boolean hasCraftableAnswer() {
        return craftableRecipes != null;
    }

    /** Goes up each time a new answer arrives, so the screen knows to refilter. */
    public int craftableUpdates() {
        return craftableUpdates;
    }

    public int selectedRecipeIndex() {
        int high = syncedData.get(DATA_SELECTED_HIGH) & 0xFFFF;
        int low = syncedData.get(DATA_SELECTED_LOW) & 0xFFFF;
        return (high << 16) | low;
    }

    // ----- Control handling (server) -----

    /** Applies a control from the client. Called on the server thread for the player's open menu. */
    public void handleAction(Player player, int action, int value) {
        if (terminal == null || !stillValid(player) || player.isSpectator()) {
            return;
        }
        if (action == MenuActionPayload.ACTION_REQUEST_CRAFTABLE) {
            if (player instanceof ServerPlayer serverPlayer) {
                ServerPlayNetworking.send(serverPlayer, new CraftableRecipesPayload(containerId, terminal.craftableRecipes(serverEntries)));
            }
            return;
        }
        switch (action) {
            case MenuActionPayload.ACTION_CYCLE_TYPE -> terminal.cycleChosenType(value);
            case MenuActionPayload.ACTION_TOGGLE_ON -> terminal.toggleSwitchedOn();
            case MenuActionPayload.ACTION_SELECT_RECIPE -> {
                if (value >= 0 && value < serverEntries.size()) {
                    terminal.selectRecipe(serverEntries.get(value).recipeId());
                }
            }
            default -> {
                return;
            }
        }
        ticksUntilCountRefresh = 0;
        broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return terminal == null || terminal.stillValid(player);
    }

    /** Nothing to shift-click into: the terminal holds no items. */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }
}
