package com.techmonkey.autocraftingtable;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * The storage a terminal crafts from. In the game this is Tom's network inside a Fabric transaction, so a failed
 * craft is rolled back; tests use a plain in-memory version.
 */
public interface ItemNetwork {
    /** Removes one item matching this type (same item and components). Returns false if there is none. */
    boolean takeOne(ItemStack type);

    /** One stack per distinct stored item type, used to find alternatives when mixed types are on. */
    List<ItemStack> storedTypes();

    /** Stores the whole stack. Returns false, storing nothing, if it doesn't all fit. */
    boolean put(ItemStack stack);
}
