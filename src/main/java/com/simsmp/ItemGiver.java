package com.simsmp;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Gives items to a player: fills stacks, then empty slots, then drops what's left at their feet. */
final class ItemGiver {
    private static final int MAIN_SLOTS = 36; // hotbar + main inventory

    private ItemGiver() {}

    static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        Inventory inv = player.getInventory();
        ItemStack rest = stack.copy();

        // 1) top up existing stacks
        for (int i = 0; i < MAIN_SLOTS && !rest.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty()
                    && ItemStack.isSameItemSameComponents(cur, rest)
                    && cur.getCount() < cur.getMaxStackSize()) {
                int move = Math.min(rest.getCount(), cur.getMaxStackSize() - cur.getCount());
                cur.grow(move);
                rest.shrink(move);
            }
        }

        // 2) empty slots
        for (int i = 0; i < MAIN_SLOTS && !rest.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, rest);
                rest = ItemStack.EMPTY;
            }
        }

        inv.setChanged();

        // 3) inventory full: drop at the player's feet
        if (!rest.isEmpty()) {
            ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), rest);
            player.level().addFreshEntity(entity);
        }
    }
}
