package com.simsmp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public final class DailyShopMenu extends ChestMenu {
   private static final int SLOT_INFO = 49;
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final DailyShop shop;
   private final EconomyManager eco;
   private final String[] slotOffer = new String[54];
   private String pending;

   private DailyShopMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, DailyShop shop, EconomyManager eco) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.view = view;
      this.viewer = viewer;
      this.shop = shop;
      this.eco = eco;
   }

   public static void open(ServerPlayer player, DailyShop shop, EconomyManager eco) {
      SimpleContainer view = new SimpleContainer(54);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
         DailyShopMenu menu = new DailyShopMenu(id, inv, view, player, shop, eco);
         menu.refresh();
         return menu;
      }, Component.literal("Daily Shop")));
   }

   private static int slotFor(int index) {
      return index / 5 * 9 + 2 + index % 5;
   }

   static String duration(long millis) {
      long totalMinutes = Math.max(0L, millis) / 60000L;
      long h = totalMinutes / 60L;
      long m = totalMinutes % 60L;
      return h > 0L ? h + "h " + m + "m" : m + "m";
   }

   private void refresh() {
      for (int s = 0; s < 54; s++) {
         this.view.setItem(s, ItemStack.EMPTY);
         this.slotOffer[s] = null;
      }

      ItemStack filler = TradeManager.named(Items.PAPER, " ", ChatFormatting.GRAY, null);

      for (int c = 45; c < 54; c++) {
         this.view.setItem(c, filler.copy());
      }

      List<DailyShop.Offer> offers = this.shop.offers();

      for (int i = 0; i < offers.size(); i++) {
         DailyShop.Offer o = offers.get(i);
         int slot = slotFor(i);
         this.slotOffer[slot] = o.id();
         if (this.shop.isSold(o.id())) {
            this.view.setItem(slot, TradeManager.named(Items.BARRIER, "Sold out", ChatFormatting.RED, o.stack().getHoverName().getString()));
         } else {
            this.view.setItem(slot, this.display(o));
         }
      }

      this.view
         .setItem(
            49,
            TradeManager.named(
               Items.CLOCK,
               "New items in " + duration(this.shop.millisUntilRefresh()),
               ChatFormatting.AQUA,
               "Balance: " + this.eco.fmt(this.eco.get(this.viewer.getUUID())) + "  |  Same shop for everyone - first to buy wins"
            )
         );
   }

   private ItemStack display(DailyShop.Offer o) {
      ItemStack copy = o.stack().copy();
      List<Component> lines = new ArrayList<>();
      ItemLore old = (ItemLore)copy.get(DataComponents.LORE);
      if (old != null) {
         lines.addAll(old.lines());
      }

      lines.add(Component.literal(" "));
      lines.add(Component.literal("Price: " + this.eco.fmt(o.price())).withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
      lines.add(Component.literal("Click twice to buy").withStyle(ChatFormatting.GREEN).withStyle(st -> st.withItalic(false)));
      copy.set(DataComponents.LORE, new ItemLore(lines));
      return copy;
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (clickType == ContainerInput.PICKUP && slotId >= 0 && slotId < 54) {
         String id = this.slotOffer[slotId];
         if (id != null) {
            if (this.shop.isSold(id)) {
               Msg.err(this.viewer, "Too late - someone else already bought that.");
               this.pending = null;
               this.refresh();
            } else if (id.equals(this.pending)) {
               this.pending = null;
               this.shop.buy(this.viewer, id);
               this.refresh();
            } else {
               this.pending = id;

               for (DailyShop.Offer o : this.shop.offers()) {
                  if (o.id().equals(id)) {
                     Msg.info(
                        this.viewer,
                        "Click again to buy " + o.stack().getCount() + "x " + o.stack().getHoverName().getString() + " for " + this.eco.fmt(o.price()) + "."
                     );
                     break;
                  }
               }
            }
         }
      }
   }
}
