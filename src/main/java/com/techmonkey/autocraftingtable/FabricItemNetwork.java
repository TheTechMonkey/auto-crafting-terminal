package com.techmonkey.autocraftingtable;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

/** A Fabric item storage (such as Tom's network) seen through one transaction. Nothing sticks until it is committed. */
public final class FabricItemNetwork implements ItemNetwork {
    private final Storage<ItemVariant> storage;
    private final TransactionContext transaction;

    public FabricItemNetwork(Storage<ItemVariant> storage, TransactionContext transaction) {
        this.storage = storage;
        this.transaction = transaction;
    }

    @Override
    public boolean takeOne(ItemStack type) {
        return storage.extract(ItemVariant.of(type), 1, transaction) == 1;
    }

    @Override
    public List<ItemStack> storedTypes() {
        List<ItemStack> types = new ArrayList<>();
        for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
            types.add(view.getResource().toStack());
        }
        return types;
    }

    @Override
    public boolean put(ItemStack stack) {
        return storage.insert(ItemVariant.of(stack), stack.getCount(), transaction) == stack.getCount();
    }
}
