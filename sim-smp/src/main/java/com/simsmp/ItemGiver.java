package com.simsmp;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

final class ItemGiver {
   private static final int MAIN_SLOTS = 36;

   private ItemGiver() {
   }

   static void give(ServerPlayer player, ItemStack stack) {
      if (!stack.isEmpty()) {
         Inventory inv = player.getInventory();
         ItemStack rest = stack.copy();

         for (int i = 0; i < 36 && !rest.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, rest) && cur.getCount() < cur.getMaxStackSize()) {
               int move = Math.min(rest.getCount(), cur.getMaxStackSize() - cur.getCount());
               cur.grow(move);
               rest.shrink(move);
            }
         }

         for (int ix = 0; ix < 36 && !rest.isEmpty(); ix++) {
            if (inv.getItem(ix).isEmpty()) {
               inv.setItem(ix, rest);
               rest = ItemStack.EMPTY;
            }
         }

         inv.setChanged();
         if (!rest.isEmpty()) {
            ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), rest);
            player.level().addFreshEntity(entity);
         }
      }
   }
}
