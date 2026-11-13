package com.simsmp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public final class SellMenu extends ChestMenu {
   private final SimpleContainer box;
   private final SimConfig cfg;
   private final EconomyManager eco;
   private final ClassManager classes;
   private boolean settled;

   private SellMenu(int id, Inventory inv, SimpleContainer box, SimConfig cfg, EconomyManager eco, ClassManager classes) {
      super(MenuType.GENERIC_9x6, id, inv, box, 6);
      this.box = box;
      this.cfg = cfg;
      this.eco = eco;
      this.classes = classes;
   }

   public static void open(ServerPlayer player, SimConfig cfg, EconomyManager eco, ClassManager classes) {
      SimpleContainer box = new SimpleContainer(54);
      // Java with the resource pack: drawn sell screen (glyph U+E700); Bedrock keeps the plain title
      Component title = player.getUUID().getMostSignificantBits() == 0L
         ? Component.literal("Sell Items (close to sell)")
         : Component.literal("\ue001\ue700").withStyle(net.minecraft.ChatFormatting.WHITE);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> new SellMenu(id, inv, box, cfg, eco, classes), title));
   }

   public void removed(Player player) {
      super.removed(player);
      if (!this.settled && player instanceof ServerPlayer sp) {
         this.settled = true;
         long total = 0L;
         LinkedHashMap<String, Integer> sold = new LinkedHashMap<>();
         ArrayList<ItemStack> unsellable = new ArrayList<>();

         for (int sb = 0; sb < this.box.getContainerSize(); sb++) {
            ItemStack stack = this.box.getItem(sb);
            if (!stack.isEmpty()) {
               String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
               Long price = this.cfg.prices.get(key);
               PriceEngine pe = PriceEngine.get();
               BankManager bank = BankManager.get();
               long gold = bank == null ? -1L : bank.goldValue(key, stack.getCount());
               DrugManager.Drug drug = DrugManager.of(stack);
               if (drug != null) {
                  String dk = DrugManager.keyOf(drug);
                  long base = DrugManager.basePrice(drug);
                  long unit = pe == null ? base : pe.effective(dk, base);
                  long value = this.classes.sellValue(sp.getUUID(), stack, unit);
                  total += value;
                  sold.merge(dk, stack.getCount(), Integer::sum);
                  if (pe != null) {
                     pe.recordSale(dk, stack.getCount(), value);
                  }
               } else if (gold > 0L) {
                  // gold: priced by the bank's gold price, and the gold goes to the bank owner
                  total += gold;
                  sold.merge(key, stack.getCount(), Integer::sum);
                  bank.redirectToOwner(key, stack.getCount());
                  if (pe != null) {
                     pe.recordSale(key, stack.getCount(), gold);
                  }
               } else if (price != null && price > 0L) {
                  long unit = pe == null ? price : pe.effective(key, price);
                  long value = this.classes.sellValue(sp.getUUID(), stack, unit);
                  total += value;
                  sold.merge(key, stack.getCount(), Integer::sum);
                  if (bank != null && PriceEngine.diamonds(key) > 0L) {
                     bank.redirectToOwner(key, stack.getCount());
                  }

                  if (pe != null) {
                     pe.recordSale(key, stack.getCount(), value);
                  }
               } else {
                  unsellable.add(stack.copy());
               }

               this.box.setItem(sb, ItemStack.EMPTY);
            }
         }

         for (ItemStack s : unsellable) {
            ItemGiver.give(sp, s);
         }

         if (total > 0L) {
            this.eco.add(sp.getUUID(), total);
            StringBuilder sbx = new StringBuilder();

            for (Entry<String, Integer> e : sold.entrySet()) {
               if (sbx.length() > 0) {
                  sbx.append(", ");
               }

               sbx.append(e.getValue()).append("x ").append(Msg.pretty(e.getKey()));
            }

            Msg.ok(sp, "Sold " + sbx + ".");
            if (PriceEngine.get() != null) {
               Msg.info(sp, PriceEngine.get().describe());
            }

            Msg.ok(sp, "You earned " + this.eco.fmt(total) + ". Balance: " + this.eco.fmt(this.eco.get(sp.getUUID())));
         } else if (!unsellable.isEmpty()) {
            Msg.err(sp, "None of those items can be sold. They were returned to you.");
         }

         if (total > 0L && !unsellable.isEmpty()) {
            Msg.info(sp, "Items that can't be sold were returned to you.");
         }
      }
   }
}
