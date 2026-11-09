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

public final class MarketMenu extends ChestMenu {
   private static final int PER_PAGE = 45;
   private static final int SLOT_PREV = 45;
   private static final int SLOT_TOGGLE = 47;
   private static final int SLOT_INFO = 49;
   private static final int SLOT_NEXT = 53;
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final MarketManager market;
   private final EconomyManager eco;
   private final long[] slotListing = new long[45];
   private boolean mine;
   private int page;
   private long pending = -1L;

   private MarketMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, MarketManager market, EconomyManager eco) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.view = view;
      this.viewer = viewer;
      this.market = market;
      this.eco = eco;
   }

   public static void open(ServerPlayer player, MarketManager market, EconomyManager eco) {
      SimpleContainer view = new SimpleContainer(54);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
         MarketMenu menu = new MarketMenu(id, inv, view, player, market, eco);
         menu.refresh();
         return menu;
      }, Component.literal("Player Market")));
   }

   private void refresh() {
      List<MarketManager.Listing> all = this.market.view(this.mine, this.viewer.getUUID());
      int pages = Math.max(1, (all.size() + 45 - 1) / 45);
      this.page = Math.max(0, Math.min(this.page, pages - 1));

      for (int i = 0; i < 45; i++) {
         int idx = this.page * 45 + i;
         if (idx < all.size()) {
            MarketManager.Listing l = all.get(idx);
            this.view.setItem(i, this.display(l));
            this.slotListing[i] = l.id();
         } else {
            this.view.setItem(i, ItemStack.EMPTY);
            this.slotListing[i] = -1L;
         }
      }

      ItemStack filler = TradeManager.named(Items.PAPER, " ", ChatFormatting.GRAY, null);

      for (int c = 45; c < 54; c++) {
         this.view.setItem(c, filler.copy());
      }

      if (this.page > 0) {
         this.view.setItem(45, TradeManager.named(Items.ARROW, "Previous page", ChatFormatting.YELLOW, "Page " + (this.page + 1) + "/" + pages));
      }

      if (this.page < pages - 1) {
         this.view.setItem(53, TradeManager.named(Items.ARROW, "Next page", ChatFormatting.YELLOW, "Page " + (this.page + 1) + "/" + pages));
      }

      this.view
         .setItem(
            47,
            this.mine
               ? TradeManager.named(
                  Items.ENDER_CHEST, "Showing: My listings", ChatFormatting.GOLD, "Click an item to take it back. Click here for all listings."
               )
               : TradeManager.named(Items.CHEST, "Showing: All listings", ChatFormatting.GOLD, "Click here to see only your listings.")
         );
      this.view
         .setItem(
            49,
            TradeManager.named(
               Items.GOLD_INGOT,
               "Balance: " + this.eco.fmt(this.eco.get(this.viewer.getUUID())),
               ChatFormatting.GOLD,
               "Sell: hold an item, then /market sell <price>"
            )
         );
   }

   private ItemStack display(MarketManager.Listing l) {
      ItemStack copy = l.stack().copy();
      List<Component> lines = new ArrayList<>();
      ItemLore old = (ItemLore)copy.get(DataComponents.LORE);
      if (old != null) {
         lines.addAll(old.lines());
      }

      lines.add(Component.literal(" "));
      lines.add(Component.literal("Price: " + this.eco.fmt(l.price())).withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
      lines.add(Component.literal("Seller: " + l.sellerName()).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)));
      lines.add(
         Component.literal(this.mine ? "Click to take it back" : "Click twice to buy").withStyle(ChatFormatting.GREEN).withStyle(st -> st.withItalic(false))
      );
      copy.set(DataComponents.LORE, new ItemLore(lines));
      return copy;
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (clickType == ContainerInput.PICKUP && slotId >= 0 && slotId < 54) {
         if (slotId < 45) {
            long id = this.slotListing[slotId];
            if (id < 0L) {
               return;
            }

            this.onListingClicked(id);
         } else if (slotId == 45) {
            this.page--;
            this.pending = -1L;
            this.refresh();
         } else if (slotId == 53) {
            this.page++;
            this.pending = -1L;
            this.refresh();
         } else if (slotId == 47) {
            this.mine = !this.mine;
            this.page = 0;
            this.pending = -1L;
            this.refresh();
         }
      }
   }

   private void onListingClicked(long id) {
      MarketManager.Listing l = this.market.find(id);
      if (l == null) {
         Msg.err(this.viewer, "That listing is no longer available.");
         this.refresh();
      } else if (this.mine) {
         this.market.cancel(this.viewer, id);
         this.refresh();
      } else if (this.pending != id) {
         this.pending = id;
         Msg.info(
            this.viewer, "Click again to buy " + l.stack().getCount() + "x " + l.stack().getHoverName().getString() + " for " + this.eco.fmt(l.price()) + "."
         );
      } else {
         this.pending = -1L;
         this.market.buy(this.viewer, id);
         this.refresh();
      }
   }
}
