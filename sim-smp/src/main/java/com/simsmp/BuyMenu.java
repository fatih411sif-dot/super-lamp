package com.simsmp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** /buy: ores and ingots that players sold to the system, for sale at the current system price. */
public final class BuyMenu extends ChestMenu {
   private final ServerPlayer viewer;
   private final PriceEngine engine;
   private final EconomyManager eco;
   private final String[] ids = new String[45];

   private BuyMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, PriceEngine engine, EconomyManager eco) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.viewer = viewer;
      this.engine = engine;
      this.eco = eco;
   }

   private static Map<String, Item> cache;

   private static synchronized Item lookup(String id) {
      if (cache == null) {
         cache = new HashMap<>();
         for (Item it : BuiltInRegistries.ITEM) {
            cache.put(BuiltInRegistries.ITEM.getKey(it).toString(), it);
         }
      }

      return cache.getOrDefault(id, Items.AIR);
   }

   public static void open(ServerPlayer player, PriceEngine engine, EconomyManager eco) {
      SimpleContainer view = new SimpleContainer(54);
      List<String> items = engine.stockedItems();
      String[] slotIds = new String[45];
      for (int i = 0; i < items.size() && i < 45; i++) {
         String id = items.get(i);
         Item item = lookup(id);
         if (item == Items.AIR) {
            continue;
         }

         slotIds[i] = id;
         view.setItem(
            i,
            Ui.named(
               item,
               Msg.pretty(id),
               ChatFormatting.YELLOW,
               "Price: " + eco.fmt(engine.buyPrice(id)) + " each",
               "In stock: " + engine.stock(id),
               "Left click: buy 1",
               "Right click: buy 16",
               "Shift click: buy 64"
            )
         );
      }

      view.setItem(
         49,
         Ui.named(
            Items.GOLD_INGOT,
            "Your balance: " + eco.fmt(eco.get(player.getUUID())),
            ChatFormatting.GOLD,
            "Stock is what players sold with /sell.",
            "Few sellers = high prices, many sellers = low prices.",
            engine.describe()
         )
      );
      Component title = Component.literal("Ore Shop (/buy)");
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
         BuyMenu m = new BuyMenu(id, inv, view, player, engine, eco);
         System.arraycopy(slotIds, 0, m.ids, 0, 45);
         return m;
      }, title));
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (slotId < 0) {
         return;
      }

      String kind = clickType.name();
      if (slotId < 45 && this.ids[slotId] != null && (kind.equals("PICKUP") || kind.equals("QUICK_MOVE"))) {
         int want = kind.equals("QUICK_MOVE") ? 64 : (button == 1 ? 16 : 1);
         String id = this.ids[slotId];
         int n = this.engine.buy(this.viewer, id, want);
         if (n > 0) {
            Item item = lookup(id);
            int left = n;
            while (left > 0) {
               int c = Math.min(left, Math.max(1, new ItemStack(item).getMaxStackSize()));
               ItemGiver.give(this.viewer, new ItemStack(item, c));
               left -= c;
            }

            Msg.ok(this.viewer, "Bought " + n + "x " + Msg.pretty(id) + ".");
         }
      }

      open(this.viewer, this.engine, this.eco);
   }
}
