package com.techmonkey.autocraftingtable.client;

import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.DUST_X;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.DUST_Y;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.GRID_X;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.GRID_Y;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.HOTBAR_Y;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.PLAYER_X;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.PLAYER_Y;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.RESULT_X;
import static com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu.RESULT_Y;

import com.techmonkey.autocraftingtable.AutoCraftingTerminalBlockEntity;
import com.techmonkey.autocraftingtable.AutoCraftingTerminalMenu;
import com.techmonkey.autocraftingtable.MenuActionPayload;
import com.techmonkey.autocraftingtable.NetworkCrafter;
import com.techmonkey.autocraftingtable.RecipeLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Left: a searchable list of recipes by name. Right: the recipe laid out like the vanilla crafting table, showing
 * how much of each ingredient the network holds, and a status line that says why the terminal is or is not crafting.
 */
public final class AutoCraftingTerminalScreen extends AbstractContainerScreen<AutoCraftingTerminalMenu> {
    // Minecraft 26.3 reports mouse buttons with SDL numbering: left is 1, middle 2, right 3.
    private static final int SDL_BUTTON_LEFT = 1;
    private static final int SDL_BUTTON_RIGHT = 3;

    private static final int MACHINE_WIDTH = 176;
    private static final int PANEL_HEIGHT = 180;
    private static final int BOOK_WIDTH = 147;
    private static final int BOOK_GAP = 4;
    private static final int BOOK_OFFSET = -(BOOK_WIDTH + BOOK_GAP);
    private static final int TOTAL_WIDTH = BOOK_WIDTH + BOOK_GAP + MACHINE_WIDTH;

    // Recipe list, relative to the machine panel's left edge.
    private static final int LIST_X = BOOK_OFFSET + 6;
    private static final int LIST_Y = 37;
    private static final int LIST_WIDTH = BOOK_WIDTH - 12;
    private static final int ROW_HEIGHT = 18;
    private static final int ROWS_PER_PAGE = 7;
    private static final int PAGE_BUTTON_Y = 164;
    private static final int PAGE_BUTTON_WIDTH = 20;
    private static final int PAGE_BUTTON_HEIGHT = 12;

    // "Can make" filter button, top right of the recipe list.
    private static final int FILTER_BUTTON_WIDTH = 52;
    private static final int FILTER_BUTTON_HEIGHT = 12;
    private static final int FILTER_BUTTON_X = LIST_X + LIST_WIDTH - FILTER_BUTTON_WIDTH;
    private static final int FILTER_BUTTON_Y = 4;
    /** How often the "Can make" list is rechecked while it is turned on: every 2 seconds. */
    private static final int CRAFTABLE_REFRESH_TICKS = 40;

    // On/Off button, top right of the machine panel.
    private static final int POWER_BUTTON_WIDTH = 30;
    private static final int POWER_BUTTON_HEIGHT = 12;
    private static final int POWER_BUTTON_X = MACHINE_WIDTH - 8 - POWER_BUTTON_WIDTH;
    private static final int POWER_BUTTON_Y = 4;

    private static final int STATUS_Y = 74;

    private static final int COLOR_PANEL_EDGE = 0xff373737;
    private static final int COLOR_PANEL_LIGHT = 0xffffffff;
    private static final int COLOR_PANEL_FILL = 0xffc6c6c6;
    private static final int COLOR_SLOT_FILL = 0xff8b8b8b;
    private static final int COLOR_LABEL = 0xff404040;
    private static final int COLOR_HINT = 0xff6a6a6a;
    private static final int COLOR_GHOST_MISSING = 0x99e08080;
    private static final int COLOR_GHOST_FADE = 0x99c6c6c6;
    private static final int COLOR_ROW_SELECTED = 0xff4a73b5;
    private static final int COLOR_ROW_HOVER = 0xffd8d8d8;
    private static final int COLOR_BUTTON = 0xff7a7a7a;
    private static final int COLOR_GOOD = 0xff2e6b1f;
    private static final int COLOR_WARNING = 0xff9a4a00;
    private static final int COLOR_POWER_ON = 0xff3c8a2a;

    private static final ItemStack REDSTONE_PICTURE = new ItemStack(Items.REDSTONE);

    /** Remembered between openings for as long as the game runs. */
    private static boolean showOnlyCraftable = false;

    private final List<Integer> filteredRecipeIndexes = new ArrayList<>();
    private final List<String> searchableNames = new ArrayList<>();
    private EditBox searchBox;
    private int currentPage = 0;
    private int ticksUntilCraftableRefresh = 0;
    private int lastCraftableUpdate = -1;

    public AutoCraftingTerminalScreen(AutoCraftingTerminalMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, MACHINE_WIDTH, PANEL_HEIGHT);
        titleLabelX = 8;
        titleLabelY = 6;
        inventoryLabelX = PLAYER_X;
        inventoryLabelY = PLAYER_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - TOTAL_WIDTH) / 2 - BOOK_OFFSET;
        searchBox = new EditBox(font, leftPos + LIST_X, topPos + 19, LIST_WIDTH, 14, Component.translatable("gui.auto_crafting_table.search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.translatable("gui.auto_crafting_table.search"));
        searchBox.setResponder(text -> {
            currentPage = 0;
            applySearch();
        });
        addRenderableWidget(searchBox);
        buildSearchNames();
        applySearch();
        jumpToSelectedRecipe();
    }

    // ----- Recipe list -----

    private void buildSearchNames() {
        searchableNames.clear();
        for (ItemStack result : menu.recipeResults) {
            String itemName = result.getHoverName().getString().toLowerCase(Locale.ROOT);
            String itemId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            searchableNames.add(itemName + " " + itemId);
        }
    }

    private void applySearch() {
        filteredRecipeIndexes.clear();
        String query = searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        for (int index = 0; index < searchableNames.size(); index++) {
            boolean matchesSearch = query.isEmpty() || searchableNames.get(index).contains(query);
            // Until the server's first answer arrives, the filter shows everything rather than an empty list.
            boolean passesFilter = !showOnlyCraftable || !menu.hasCraftableAnswer() || menu.isCraftable(index);
            if (matchesSearch && passesFilter) {
                filteredRecipeIndexes.add(index);
            }
        }
        currentPage = Math.min(currentPage, lastPage());
    }

    /** Opens the list on the page that holds the table's current recipe. */
    private void jumpToSelectedRecipe() {
        int position = filteredRecipeIndexes.indexOf(menu.selectedRecipeIndex());
        if (position >= 0) {
            currentPage = position / ROWS_PER_PAGE;
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!showOnlyCraftable) {
            return;
        }
        if (--ticksUntilCraftableRefresh <= 0) {
            ticksUntilCraftableRefresh = CRAFTABLE_REFRESH_TICKS;
            sendAction(MenuActionPayload.ACTION_REQUEST_CRAFTABLE, 0);
        }
        if (menu.craftableUpdates() != lastCraftableUpdate) {
            lastCraftableUpdate = menu.craftableUpdates();
            applySearch();
        }
    }

    private void toggleCraftableFilter() {
        showOnlyCraftable = !showOnlyCraftable;
        ticksUntilCraftableRefresh = 0;
        currentPage = 0;
        applySearch();
    }

    private int lastPage() {
        return Math.max(0, (filteredRecipeIndexes.size() - 1) / ROWS_PER_PAGE);
    }

    // ----- Drawing -----

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics, leftPos + BOOK_OFFSET, topPos, BOOK_WIDTH, PANEL_HEIGHT);
        drawPanel(graphics, leftPos, topPos, MACHINE_WIDTH, PANEL_HEIGHT);
        drawRecipeList(graphics, mouseX - leftPos, mouseY - topPos);
        drawMachine(graphics);
    }

    private void drawRecipeList(GuiGraphicsExtractor graphics, int relativeX, int relativeY) {
        label(graphics, Component.translatable("gui.auto_crafting_table.recipes"), LIST_X, 7, COLOR_LABEL);
        int selectedIndex = menu.selectedRecipeIndex();
        int hoveredRecipe = recipeUnderMouse(relativeX, relativeY);
        int firstOnPage = currentPage * ROWS_PER_PAGE;
        for (int row = 0; row < ROWS_PER_PAGE && firstOnPage + row < filteredRecipeIndexes.size(); row++) {
            int recipeIndex = filteredRecipeIndexes.get(firstOnPage + row);
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y + row * ROW_HEIGHT;
            boolean selected = recipeIndex == selectedIndex;
            if (selected) {
                graphics.fill(x, y, x + LIST_WIDTH, y + ROW_HEIGHT - 1, COLOR_ROW_SELECTED);
            } else if (recipeIndex == hoveredRecipe) {
                graphics.fill(x, y, x + LIST_WIDTH, y + ROW_HEIGHT - 1, COLOR_ROW_HOVER);
            }
            ItemStack result = menu.recipeResults.get(recipeIndex);
            drawItemWithCount(graphics, result, x + 1, y);
            String name = fitToWidth(result.getHoverName().getString(), LIST_WIDTH - 22);
            graphics.text(font, name, x + 20, y + 5, selected ? 0xffffffff : COLOR_LABEL, false);
        }
        drawTextButton(graphics, FILTER_BUTTON_X, FILTER_BUTTON_Y, FILTER_BUTTON_WIDTH, FILTER_BUTTON_HEIGHT,
                Component.translatable(showOnlyCraftable ? "gui.auto_crafting_table.filter.craftable" : "gui.auto_crafting_table.filter.all").getString(),
                showOnlyCraftable ? COLOR_ROW_SELECTED : COLOR_BUTTON);
        if (filteredRecipeIndexes.isEmpty()) {
            String emptyKey = showOnlyCraftable && searchBox.getValue().isBlank()
                    ? "gui.auto_crafting_table.nothing_craftable" : "gui.auto_crafting_table.no_matches";
            label(graphics, Component.translatable(emptyKey), LIST_X + 2, LIST_Y + 5, COLOR_HINT);
        }
        drawTextButton(graphics, LIST_X, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT, "<");
        drawTextButton(graphics, LIST_X + LIST_WIDTH - PAGE_BUTTON_WIDTH, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT, ">");
        String pageText = Component.translatable("gui.auto_crafting_table.page", currentPage + 1, lastPage() + 1).getString();
        graphics.text(font, pageText, leftPos + LIST_X + LIST_WIDTH / 2 - font.width(pageText) / 2, topPos + PAGE_BUTTON_Y + 2, COLOR_LABEL, false);
    }

    private void drawMachine(GuiGraphicsExtractor graphics) {
        for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
            drawSlot(graphics, GRID_X - 1 + gridSlot % 3 * 18, GRID_Y - 1 + gridSlot / 3 * 18);
        }
        drawArrow(graphics, leftPos + 90, topPos + 35);
        drawResultSlot(graphics, RESULT_X - 5, RESULT_Y - 5);
        if (menu.fuelRequired()) {
            drawSlot(graphics, DUST_X - 1, DUST_Y - 1);
            int x = leftPos + DUST_X;
            int y = topPos + DUST_Y;
            graphics.item(REDSTONE_PICTURE, x, y);
            if (menu.status() == AutoCraftingTerminalBlockEntity.STATUS_NO_FUEL) {
                // Red when running out of dust is what stopped crafting.
                graphics.fill(x, y, x + 16, y + 16, COLOR_GHOST_MISSING);
            } else if (menu.charge() > 0) {
                // The dust drains from the top down as its charge is used: the used part is faded out.
                int usedHeight = 16 * (NetworkCrafter.CRAFTS_PER_DUST - menu.charge()) / NetworkCrafter.CRAFTS_PER_DUST;
                graphics.fill(x, y, x + 16, y + usedHeight, COLOR_GHOST_FADE);
            } else if (menu.dustInNetwork() == 0) {
                // No charge and nothing in storage to load next.
                graphics.fill(x, y, x + 16, y + 16, COLOR_GHOST_FADE);
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlot(graphics, PLAYER_X - 1 + column * 18, PLAYER_Y - 1 + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            drawSlot(graphics, PLAYER_X - 1 + column * 18, HOTBAR_Y - 1);
        }
        drawRecipePattern(graphics);
        drawStatus(graphics);
        boolean switchedOn = menu.isSwitchedOn();
        drawTextButton(graphics, POWER_BUTTON_X, POWER_BUTTON_Y, POWER_BUTTON_WIDTH, POWER_BUTTON_HEIGHT,
                Component.translatable(switchedOn ? "gui.auto_crafting_table.power.on" : "gui.auto_crafting_table.power.off").getString(),
                switchedOn ? COLOR_POWER_ON : COLOR_BUTTON);
    }

    /**
     * The recipe as pictures, one item per position like a crafting grid. Each craft uses exactly one item from
     * each position. Positions the network ran out of turn red; hover a position to see how many are in storage.
     * The terminal holds no items; these are only pictures.
     */
    private void drawRecipePattern(GuiGraphicsExtractor graphics) {
        boolean showMissing = menu.status() == AutoCraftingTerminalBlockEntity.STATUS_MISSING_INGREDIENTS;
        for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
            ItemStack expected = menu.chosenType(gridSlot);
            if (expected.isEmpty()) {
                continue;
            }
            int x = leftPos + GRID_X + gridSlot % 3 * 18;
            int y = topPos + GRID_Y + gridSlot / 3 * 18;
            graphics.item(expected, x, y);
            if (showMissing && menu.isMissing(gridSlot)) {
                graphics.fill(x, y, x + 16, y + 16, COLOR_GHOST_MISSING);
            }
        }
        ItemStack result = menu.previewResult();
        if (!result.isEmpty()) {
            drawItemWithCount(graphics, result, leftPos + RESULT_X, topPos + RESULT_Y);
        }
    }

    private void drawStatus(GuiGraphicsExtractor graphics) {
        int status = menu.status();
        int color = switch (status) {
            case AutoCraftingTerminalBlockEntity.STATUS_CRAFTING -> COLOR_GOOD;
            case AutoCraftingTerminalBlockEntity.STATUS_NO_RECIPE, AutoCraftingTerminalBlockEntity.STATUS_TURNED_OFF -> COLOR_HINT;
            default -> COLOR_WARNING;
        };
        Component text = Component.translatable("gui.auto_crafting_table.status." + status);
        graphics.fill(leftPos + 8, topPos + STATUS_Y + 1, leftPos + 13, topPos + STATUS_Y + 6, color);
        graphics.text(font, fitToWidth(text.getString(), MACHINE_WIDTH - 24), leftPos + 16, topPos + STATUS_Y, color, false);
    }

    /** Draws an item with its stack count in the corner, the way Minecraft shows counts in slots. */
    private void drawItemWithCount(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        graphics.item(stack, x, y);
        if (stack.getCount() > 1) {
            drawCornerText(graphics, String.valueOf(stack.getCount()), x, y);
        }
    }

    private void drawCornerText(GuiGraphicsExtractor graphics, String text, int x, int y) {
        graphics.text(font, text, x + 17 - font.width(text), y + 9, 0xffffffff, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int relativeX = mouseX - leftPos;
        int relativeY = mouseY - topPos;
        int hoveredPosition = gridSlotUnderMouse(relativeX, relativeY);
        if (hoveredPosition >= 0 && !menu.chosenType(hoveredPosition).isEmpty()) {
            Component name = menu.chosenType(hoveredPosition).getHoverName();
            String stored = storedCount(menu.countInNetwork(hoveredPosition));
            graphics.setTooltipForNextFrame(canPickType()
                    ? Component.translatable("gui.auto_crafting_table.tip.ingredient_choose", name, stored)
                    : Component.translatable("gui.auto_crafting_table.tip.ingredient", name, stored), mouseX, mouseY);
        }
        if (menu.fuelRequired() && isInside(relativeX, relativeY, DUST_X, DUST_Y, 16, 16)) {
            graphics.setTooltipForNextFrame(Component.translatable("gui.auto_crafting_table.tip.dust",
                    menu.charge(), storedCount(menu.dustInNetwork()), NetworkCrafter.CRAFTS_PER_DUST), mouseX, mouseY);
        }
        if (!menu.previewResult().isEmpty() && isInside(relativeX, relativeY, RESULT_X, RESULT_Y, 16, 16)) {
            graphics.setTooltipForNextFrame(menu.previewResult().getHoverName(), mouseX, mouseY);
        }
        if (isInside(relativeX, relativeY, POWER_BUTTON_X, POWER_BUTTON_Y, POWER_BUTTON_WIDTH, POWER_BUTTON_HEIGHT)) {
            graphics.setTooltipForNextFrame(Component.translatable("gui.auto_crafting_table.power.tip"), mouseX, mouseY);
        }
        if (isInside(relativeX, relativeY, FILTER_BUTTON_X, FILTER_BUTTON_Y, FILTER_BUTTON_WIDTH, FILTER_BUTTON_HEIGHT)) {
            graphics.setTooltipForNextFrame(Component.translatable("gui.auto_crafting_table.filter.tip"), mouseX, mouseY);
        }
        int hoveredRecipe = recipeUnderMouse(relativeX, relativeY);
        if (hoveredRecipe >= 0) {
            graphics.setTooltipForNextFrame(menu.recipeResults.get(hoveredRecipe).getHoverName(), mouseX, mouseY);
        }
    }

    private static String storedCount(int count) {
        return String.format(Locale.ROOT, "%,d", count) + (count >= AutoCraftingTerminalMenu.MAX_SHOWN_COUNT ? "+" : "");
    }

    /** With mixed types turned off in the settings, each slot takes one type, picked by right-clicking. */
    private boolean canPickType() {
        return !menu.allowMixedTypes() && menu.hasInterchangeableIngredients();
    }

    // ----- Input -----

    private void sendAction(int action, int value) {
        ClientPlayNetworking.send(new MenuActionPayload(menu.containerId, action, value));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double relativeX = event.x() - leftPos;
        double relativeY = event.y() - topPos;
        boolean leftButton = event.button() == SDL_BUTTON_LEFT;
        boolean rightButton = event.button() == SDL_BUTTON_RIGHT;

        if (leftButton) {
            if (isInside(relativeX, relativeY, POWER_BUTTON_X, POWER_BUTTON_Y, POWER_BUTTON_WIDTH, POWER_BUTTON_HEIGHT)) {
                sendAction(MenuActionPayload.ACTION_TOGGLE_ON, 0);
                return true;
            }
            if (isInside(relativeX, relativeY, FILTER_BUTTON_X, FILTER_BUTTON_Y, FILTER_BUTTON_WIDTH, FILTER_BUTTON_HEIGHT)) {
                toggleCraftableFilter();
                return true;
            }
            if (isInside(relativeX, relativeY, LIST_X, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)) {
                currentPage = Math.max(0, currentPage - 1);
                return true;
            }
            if (isInside(relativeX, relativeY, LIST_X + LIST_WIDTH - PAGE_BUTTON_WIDTH, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)) {
                currentPage = Math.min(lastPage(), currentPage + 1);
                return true;
            }
            int clickedRecipe = recipeUnderMouse(relativeX, relativeY);
            if (clickedRecipe >= 0 && menu.getCarried().isEmpty()) {
                sendAction(MenuActionPayload.ACTION_SELECT_RECIPE, clickedRecipe);
                return true;
            }
        }
        if (rightButton && menu.getCarried().isEmpty() && canPickType()) {
            int position = gridSlotUnderMouse(relativeX, relativeY);
            if (position >= 0) {
                sendAction(MenuActionPayload.ACTION_CYCLE_TYPE, position);
                return true;
            }
        }
        // Clicks on the recipe list never count as dropping a carried stack outside the window.
        if (isInside(relativeX, relativeY, BOOK_OFFSET, 0, BOOK_WIDTH, PANEL_HEIGHT)) {
            if (searchBox.isMouseOver(event.x(), event.y())) {
                setFocused(searchBox);
                searchBox.setFocused(true);
                return searchBox.mouseClicked(event, doubleClick);
            }
            searchBox.setFocused(false);
            setFocused(null);
            return true;
        }
        searchBox.setFocused(false);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // While typing in the search box, keys like E (inventory) or 1-9 (hotbar swap) must not act on the window.
        if (searchBox != null && searchBox.isFocused() && !event.isEscape()) {
            searchBox.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInside(mouseX - leftPos, mouseY - topPos, BOOK_OFFSET, 0, BOOK_WIDTH, PANEL_HEIGHT)) {
            currentPage = Math.clamp(currentPage - (int) Math.signum(scrollY), 0, lastPage());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return !isInside(mouseX - leftPos, mouseY - topPos, BOOK_OFFSET, 0, TOTAL_WIDTH, PANEL_HEIGHT);
    }

    private int recipeUnderMouse(double relativeX, double relativeY) {
        if (!isInside(relativeX, relativeY, LIST_X, LIST_Y, LIST_WIDTH, ROWS_PER_PAGE * ROW_HEIGHT)) {
            return -1;
        }
        int row = (int) (relativeY - LIST_Y) / ROW_HEIGHT;
        int filteredPosition = currentPage * ROWS_PER_PAGE + row;
        return filteredPosition < filteredRecipeIndexes.size() ? filteredRecipeIndexes.get(filteredPosition) : -1;
    }

    private int gridSlotUnderMouse(double relativeX, double relativeY) {
        for (int gridSlot = 0; gridSlot < RecipeLayout.GRID_SIZE; gridSlot++) {
            if (isInside(relativeX, relativeY, GRID_X + gridSlot % 3 * 18, GRID_Y + gridSlot / 3 * 18, 16, 16)) {
                return gridSlot;
            }
        }
        return -1;
    }

    // ----- Drawing helpers -----

    private void label(GuiGraphicsExtractor graphics, Component text, int relativeX, int relativeY, int color) {
        graphics.text(font, text, leftPos + relativeX, topPos + relativeY, color, false);
    }

    /** Shortens text with "..." so it fits in the given pixel width. */
    private String fitToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String shortened = text;
        while (!shortened.isEmpty() && font.width(shortened + "...") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "...";
    }

    private static boolean isInside(double x, double y, int left, int top, int width, int height) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, COLOR_PANEL_LIGHT);
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, COLOR_PANEL_FILL);
    }

    private void drawSlot(GuiGraphicsExtractor graphics, int relativeX, int relativeY) {
        int x = leftPos + relativeX;
        int y = topPos + relativeY;
        graphics.fill(x, y, x + 18, y + 18, COLOR_PANEL_LIGHT);
        graphics.fill(x, y, x + 17, y + 17, COLOR_PANEL_EDGE);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, COLOR_SLOT_FILL);
    }

    private void drawTextButton(GuiGraphicsExtractor graphics, int relativeX, int relativeY, int width, int height, String text) {
        drawTextButton(graphics, relativeX, relativeY, width, height, text, COLOR_BUTTON);
    }

    private void drawTextButton(GuiGraphicsExtractor graphics, int relativeX, int relativeY, int width, int height, String text, int fillColor) {
        int x = leftPos + relativeX;
        int y = topPos + relativeY;
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fillColor);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x40ffffff);
        graphics.text(font, text, x + width / 2 - font.width(text) / 2, y + (height - 8) / 2, 0xffffffff, true);
    }

    /** The big 26x26 result slot from the vanilla crafting table. */
    private void drawResultSlot(GuiGraphicsExtractor graphics, int relativeX, int relativeY) {
        int x = leftPos + relativeX;
        int y = topPos + relativeY;
        graphics.fill(x, y, x + 26, y + 26, COLOR_PANEL_LIGHT);
        graphics.fill(x, y, x + 25, y + 25, COLOR_PANEL_EDGE);
        graphics.fill(x + 1, y + 1, x + 25, y + 25, COLOR_SLOT_FILL);
    }

    /** The same arrow shape as the vanilla crafting table, in the panel's shadow gray. */
    private static void drawArrow(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y + 6, x + 15, y + 10, COLOR_HINT);
        for (int step = 0; step < 8; step++) {
            graphics.fill(x + 14 + step, y + step, x + 15 + step, y + 16 - step, COLOR_HINT);
        }
    }
}
